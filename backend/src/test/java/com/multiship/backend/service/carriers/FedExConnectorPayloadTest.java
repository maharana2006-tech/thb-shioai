package com.multiship.backend.service.carriers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.dto.CustomsCommodityDTO;
import com.multiship.backend.dto.IntlShipmentBlockDTO;
import com.multiship.backend.dto.ShipmentRequestDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden-value tests for {@link FedExConnector#buildShipmentPayload}. Mirrors
 * the UPS payload test — the shape assertions here are what FedEx's Ship API
 * v1 validates against.
 */
class FedExConnectorPayloadTest {

    private FedExConnector connector;
    private Method buildShipmentPayload;

    @BeforeEach
    void setUp() throws Exception {
        // Populate the label-response option so the payload builder doesn't NPE
        // reading it — every other config value is unused by the SUT.
        CarrierProperties props = new CarrierProperties();
        props.getFedEx().setLabelResponseOption("URL_ONLY");

        // FxRateService stub that always returns empty — forces the IOSS
        // threshold check to use the fixed local table, which the pre-Sprint
        // 5.5 assertions were written against. Sprint 5.5 tests explicitly
        // wire the live path via FedExConnectorFxIossTest.
        com.multiship.backend.service.fx.FxRateService noFx =
                new com.multiship.backend.service.fx.FxRateService() {
                    @Override
                    public java.util.Optional<java.math.BigDecimal> rate(String from, String to) {
                        return java.util.Optional.empty();
                    }
                    @Override
                    public java.util.Optional<java.math.BigDecimal> convert(java.math.BigDecimal amount, String from, String to) {
                        return java.util.Optional.empty();
                    }
                    @Override
                    public boolean supports(String currency) {
                        return false;
                    }
                };
        connector = new FedExConnector(props, new ObjectMapper(), noFx);
        buildShipmentPayload = FedExConnector.class.getDeclaredMethod("buildShipmentPayload", ShipmentRequestDTO.class);
        buildShipmentPayload.setAccessible(true);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> build(ShipmentRequestDTO request) throws Exception {
        return (Map<String, Object>) buildShipmentPayload.invoke(connector, request);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> requestedShipment(ShipmentRequestDTO request) throws Exception {
        return (Map<String, Object>) build(request).get("requestedShipment");
    }

    private ShipmentRequestDTO baseRequest() {
        return ShipmentRequestDTO.builder()
                .carrierCode("FEDEX")
                .accountNumber("A99999")
                .serviceType("INTERNATIONAL_PRIORITY")
                .packageType("YOUR_PACKAGING")
                .weight(new BigDecimal("2.5"))
                .weightUnit("LB")
                .shipperName("Acme Warehouse")
                .shipperPhone("5551234567")
                .shipperAddressLine1("1 Warehouse Way")
                .shipperCity("Louisville")
                .shipperState("KY")
                .shipperPostalCode("40209")
                .shipperCountryCode("US")
                .recipientName("Jane Doe")
                .recipientPhone("5559876543")
                .recipientAddressLine1("42 High Street")
                .recipientCity("London")
                .recipientState("")
                .recipientPostalCode("W1A 1AA")
                .recipientCountryCode("GB")
                .referenceNumber("PO-1001")
                .declaredValue(new BigDecimal("500.00"))
                .declaredValueCurrency("EUR")
                .build();
    }

    private IntlShipmentBlockDTO baseIntl() {
        return IntlShipmentBlockDTO.builder()
                .international(true)
                .incoterms("DDP")
                .customsCurrency("EUR")
                .customsTotalValue(new BigDecimal("500.00"))
                .reasonForExport("SALE")
                .weightUnit("KG")
                .commodities(List.of(CustomsCommodityDTO.builder()
                        .description("Widget")
                        .hsCode("6104.62.20")
                        .countryOfOrigin("US")
                        .quantity(10)
                        .unitValue(new BigDecimal("50.00"))
                        .unitWeight(new BigDecimal("0.5"))
                        .sku("SKU-1")
                        .build()))
                .build();
    }

    @SuppressWarnings("unchecked")
    @Test
    void domesticPayloadOmitsCustomsClearanceDetail() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setRecipientCountryCode("US");
        Map<String, Object> rs = requestedShipment(r);
        assertNull(rs.get("customsClearanceDetail"));
        assertNull(rs.get("shipmentSpecialServicesRequested"));
    }

    // ===== FDX-2 — boundary guard on blank / placeholder accountNumber =====

    /** Unwrap the InvocationTargetException from the reflection-based
     *  build helper so callers can assertThrows the real cause. */
    private static IllegalArgumentException expectBlankAccountThrow(
            org.junit.jupiter.api.function.Executable action) {
        try {
            action.execute();
        } catch (java.lang.reflect.InvocationTargetException ite) {
            if (ite.getCause() instanceof IllegalArgumentException iae) return iae;
            throw new AssertionError("expected IllegalArgumentException, got: " + ite.getCause(), ite);
        } catch (Throwable t) {
            throw new AssertionError("expected IllegalArgumentException (wrapped), got: " + t, t);
        }
        throw new AssertionError("expected IllegalArgumentException, but nothing was thrown");
    }

    @Test
    void accountNumber_blank_throws_at_boundary() {
        // Pre-FDX-2, a blank accountNumber silently became "ACCOUNT" which
        // FedEx rejects with a cryptic validation error. Now the connector
        // throws IllegalArgumentException at the boundary so the operator
        // sees an actionable message instead of chasing a FedEx 400.
        ShipmentRequestDTO r = baseRequest();
        r.setAccountNumber("");
        IllegalArgumentException ex = expectBlankAccountThrow(() -> requestedShipment(r));
        assertTrue(ex.getMessage().contains("account number"),
                "must name the missing field; got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("CarrierAccountRef"),
                "should point the operator at the fix; got: " + ex.getMessage());
    }

    @Test
    void accountNumber_null_throws_at_boundary() {
        ShipmentRequestDTO r = baseRequest();
        r.setAccountNumber(null);
        expectBlankAccountThrow(() -> requestedShipment(r));
    }

    @Test
    void accountNumber_literalACCOUNT_throws_at_boundary() {
        // CarrierServiceImpl.buildShipmentRequest still plants "ACCOUNT" as
        // a defensive fallback (line 2048). Post-FDX-2, if that placeholder
        // reaches the connector we fail fast instead of forwarding it to
        // FedEx (which rejects). Surfaces the upstream cleanup opportunity.
        ShipmentRequestDTO r = baseRequest();
        r.setAccountNumber("ACCOUNT");
        IllegalArgumentException ex = expectBlankAccountThrow(() -> requestedShipment(r));
        assertTrue(ex.getMessage().contains("ACCOUNT"),
                "message should call out the placeholder; got: " + ex.getMessage());
    }

    // ===== FDX-H2 — pickupType wired from ShipmentRequestDTO =====

    @SuppressWarnings("unchecked")
    @Test
    void pickupType_defaultsToUseScheduledPickup_whenDtoLeavesItNull() throws Exception {
        // Pre-FDX-H2 behavior preserved for callers that don't populate
        // the new DTO field. Matches the pre-FDX-H hardcode exactly.
        ShipmentRequestDTO r = baseRequest();
        r.setPickupType(null);
        Map<String, Object> rs = requestedShipment(r);
        assertEquals("USE_SCHEDULED_PICKUP", rs.get("pickupType"),
                "null pickupType must fall to the pre-FDX-H hardcode for back-compat");
    }

    @SuppressWarnings("unchecked")
    @Test
    void pickupType_dropBoxOnDtoLandsOnFedExWire() throws Exception {
        // Operator-set DROP_BOX (drop-off shipper without a standing pickup)
        // must reach the wire so FedEx dispatches the drop-box driver
        // instead of rejecting the label. Fixes the pre-FDX-H bug where
        // drop-off shippers couldn't buy labels at all.
        ShipmentRequestDTO r = baseRequest();
        r.setPickupType("DROP_BOX");
        Map<String, Object> rs = requestedShipment(r);
        assertEquals("DROP_BOX", rs.get("pickupType"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void pickupType_returnLabelForcesContactFedexToSchedule_overridingDto() throws Exception {
        // Return labels have a hard requirement: the customer isn't in the
        // shipper's book so no standing pickup applies. Connector's return-
        // label branch must WIN over the pickupType field (which came from
        // the shipper's account default, not the customer's context).
        ShipmentRequestDTO r = baseRequest();
        r.setPickupType("DROP_BOX");       // shipper's account default
        r.setIsReturn(true);
        Map<String, Object> rs = requestedShipment(r);
        assertEquals("CONTACT_FEDEX_TO_SCHEDULE", rs.get("pickupType"),
                "return-label override must win over the DTO pickupType");
    }

    @SuppressWarnings("unchecked")
    @Test
    void kgOnDtoLandsAsKgOnFedExWire() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setWeightUnit("KG");
        r.setWeight(new BigDecimal("1.5"));
        Map<String, Object> rs = requestedShipment(r);
        // Sprint 28 — requestedPackageLineItems is now a List<Map> for multi-package iteration.
        java.util.List<Map<String, Object>> pkgs =
                (java.util.List<Map<String, Object>>) rs.get("requestedPackageLineItems");
        Map<String, Object> pkg = pkgs.get(0);
        Map<String, Object> weight = (Map<String, Object>) pkg.get("weight");
        assertEquals("KG", weight.get("units"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void internationalPayloadEmitsCustomsClearanceDetail() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setIntl(baseIntl());

        Map<String, Object> rs = requestedShipment(r);
        Map<String, Object> ccd = (Map<String, Object>) rs.get("customsClearanceDetail");
        assertNotNull(ccd);
        Map<String, Object> ci = (Map<String, Object>) ccd.get("commercialInvoice");
        assertEquals("DDP", ci.get("termsOfSale"));
        assertEquals("SOLD", ci.get("purpose"));

        Map<String, Object> cv = (Map<String, Object>) ccd.get("customsValue");
        assertEquals(new BigDecimal("500.00"), cv.get("amount"));
        assertEquals("EUR", cv.get("currency"));

        List<Map<String, Object>> commodities = (List<Map<String, Object>>) ccd.get("commodities");
        assertEquals(1, commodities.size());
        Map<String, Object> line = commodities.get(0);
        assertEquals("Widget", line.get("description"));
        assertEquals("6104.62.20", line.get("harmonizedCode"));
        assertEquals("US", line.get("countryOfManufacture"));
        assertEquals("SKU-1", line.get("partNumber"));
        Map<String, Object> unitPrice = (Map<String, Object>) line.get("unitPrice");
        assertEquals("EUR", unitPrice.get("currency"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void ddpMapsToSenderDutiesPayment() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setIntl(baseIntl()); // DDP
        Map<String, Object> duties = (Map<String, Object>) ((Map<String, Object>)
                requestedShipment(r).get("customsClearanceDetail")).get("dutiesPayment");
        assertEquals("SENDER", duties.get("paymentType"));
        assertNotNull(duties.get("payor"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void dapMapsToRecipientAndOmitsPayor() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        IntlShipmentBlockDTO intl = baseIntl();
        intl.setIncoterms("DAP");
        r.setIntl(intl);
        Map<String, Object> duties = (Map<String, Object>) ((Map<String, Object>)
                requestedShipment(r).get("customsClearanceDetail")).get("dutiesPayment");
        assertEquals("RECIPIENT", duties.get("paymentType"));
        assertNull(duties.get("payor"), "DAP should leave duty payor blank — FedEx bills consignee");
    }

    @SuppressWarnings("unchecked")
    @Test
    void thirdPartyDutyIncludesPayorAccount() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        IntlShipmentBlockDTO intl = baseIntl();
        intl.setIncoterms("DAP");
        intl.setDutyBillTo("THIRD_PARTY");
        intl.setDutyAccount("PAYER-999");
        r.setIntl(intl);
        Map<String, Object> duties = (Map<String, Object>) ((Map<String, Object>)
                requestedShipment(r).get("customsClearanceDetail")).get("dutiesPayment");
        assertEquals("THIRD_PARTY", duties.get("paymentType"));
        Map<String, Object> payor = (Map<String, Object>) duties.get("payor");
        Map<String, Object> account = (Map<String, Object>) ((Map<String, Object>) payor.get("responsibleParty"))
                .get("accountNumber");
        assertEquals("PAYER-999", account.get("value"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void iossEmittedForEuLowValueShipment() throws Exception {
        ShipmentRequestDTO r = baseRequest(); // GB destination — no longer EU but treat as intl
        r.setRecipientCountryCode("DE"); // EU
        IntlShipmentBlockDTO intl = baseIntl();
        intl.setCustomsTotalValue(new BigDecimal("120.00")); // ≤ €150
        intl.setImporterIoss("IM3702000001");
        r.setIntl(intl);

        Map<String, Object> shipper = (Map<String, Object>) requestedShipment(r).get("shipper");
        List<Map<String, Object>> tins = (List<Map<String, Object>>) shipper.get("tins");
        assertNotNull(tins);
        assertTrue(tins.stream().anyMatch(t -> "IOSS".equals(t.get("tinType"))),
                "IOSS TIN should be present on EU B2C ≤€150");
    }

    @SuppressWarnings("unchecked")
    @Test
    void iossSuppressedAboveThreshold() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setRecipientCountryCode("DE");
        IntlShipmentBlockDTO intl = baseIntl();
        intl.setCustomsTotalValue(new BigDecimal("300.00")); // > €150
        intl.setImporterIoss("IM3702000001");
        r.setIntl(intl);

        Map<String, Object> shipper = (Map<String, Object>) requestedShipment(r).get("shipper");
        List<Map<String, Object>> tins = (List<Map<String, Object>>) shipper.get("tins");
        assertFalse(tins != null && tins.stream().anyMatch(t -> "IOSS".equals(t.get("tinType"))),
                "IOSS TIN should be omitted above €150");
    }

    @SuppressWarnings("unchecked")
    @Test
    void iossSuppressedOutsideEu() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setRecipientCountryCode("GB"); // non-EU post-Brexit
        IntlShipmentBlockDTO intl = baseIntl();
        intl.setCustomsTotalValue(new BigDecimal("120.00"));
        intl.setImporterIoss("IM3702000001");
        r.setIntl(intl);

        Map<String, Object> shipper = (Map<String, Object>) requestedShipment(r).get("shipper");
        List<Map<String, Object>> tins = (List<Map<String, Object>>) shipper.get("tins");
        assertFalse(tins != null && tins.stream().anyMatch(t -> "IOSS".equals(t.get("tinType"))),
                "IOSS TIN should be omitted for non-EU destinations");
    }

    @SuppressWarnings("unchecked")
    @Test
    void eoriEmittedRegardlessOfDestination() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        IntlShipmentBlockDTO intl = baseIntl();
        intl.setImporterEori("GB123456789012");
        r.setIntl(intl);

        Map<String, Object> shipper = (Map<String, Object>) requestedShipment(r).get("shipper");
        List<Map<String, Object>> tins = (List<Map<String, Object>>) shipper.get("tins");
        assertTrue(tins.stream().anyMatch(t ->
                "GB123456789012".equals(t.get("number")) && "BUSINESS_UNION".equals(t.get("tinType"))));
    }

    @SuppressWarnings("unchecked")
    @Test
    void reasonForExportMapsToFedExPurpose() throws Exception {
        // FDX-D — mapping expanded to cover all 8
        // ShipmentDefaultsResolver.SHIPPING_PURPOSE_ENUM values.
        // Pre-FDX-D, MERCHANDISE/PERSONAL_USE/REPAIR_AND_RETURN silently
        // fell to SOLD; RETURN mapped to the literal "RETURN" which isn't
        // a valid FedEx purpose enum. Now every value hits an explicit
        // branch and RETURN/REPAIR share REPAIR_AND_RETURN (FedEx's
        // repair-flow enum value).
        Map<String, String> mapping = new java.util.LinkedHashMap<>();
        mapping.put("SALE", "SOLD");
        mapping.put("MERCHANDISE", "SOLD");                 // FDX-D — was silently defaulting to SOLD; now explicit
        mapping.put("GIFT", "GIFT");
        mapping.put("SAMPLE", "SAMPLE");
        mapping.put("RETURN", "REPAIR_AND_RETURN");         // FDX-D — was "RETURN" (invalid FedEx enum)
        mapping.put("REPAIR", "REPAIR_AND_RETURN");
        mapping.put("REPAIR_AND_RETURN", "REPAIR_AND_RETURN"); // FDX-D — was falling to SOLD default
        mapping.put("PERSONAL_USE", "PERSONAL_EFFECTS");    // FDX-D — was falling to SOLD default
        mapping.put("DOCUMENTS", "NOT_SOLD");
        for (Map.Entry<String, String> entry : mapping.entrySet()) {
            ShipmentRequestDTO r = baseRequest();
            IntlShipmentBlockDTO intl = baseIntl();
            intl.setReasonForExport(entry.getKey());
            r.setIntl(intl);
            Map<String, Object> ci = (Map<String, Object>) ((Map<String, Object>)
                    requestedShipment(r).get("customsClearanceDetail")).get("commercialInvoice");
            assertEquals(entry.getValue(), ci.get("purpose"), "Reason " + entry.getKey());
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void etdSpecialServiceRequestedForIntlShipments() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setIntl(baseIntl());
        Map<String, Object> ssr = (Map<String, Object>) requestedShipment(r).get("shipmentSpecialServicesRequested");
        List<String> types = (List<String>) ssr.get("specialServiceTypes");
        assertTrue(types.contains("ELECTRONIC_TRADE_DOCUMENTS"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void importerOfRecordSuppressedWhenBlank() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setIntl(baseIntl()); // no importer name/address
        Map<String, Object> ccd = (Map<String, Object>) requestedShipment(r).get("customsClearanceDetail");
        assertNull(ccd.get("importerOfRecord"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void brokerBlockOnlyWhenBrokerConfigured() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        IntlShipmentBlockDTO intl = baseIntl();
        intl.setBrokerName("Broker Inc");
        intl.setBrokerCompany("Broker Ltd");
        intl.setBrokerAddressLine1("1 Broker St");
        intl.setBrokerCity("Dover");
        intl.setBrokerCountry("GB");
        r.setIntl(intl);

        Map<String, Object> ccd = (Map<String, Object>) requestedShipment(r).get("customsClearanceDetail");
        List<Map<String, Object>> brokers = (List<Map<String, Object>>) ccd.get("brokers");
        assertNotNull(brokers);
        assertEquals(1, brokers.size());
        assertEquals("IMPORT", brokers.get(0).get("type"));
    }

    // ===================================================================
    // US Export EEI — exportDetail.exportComplianceStatement wiring
    // ===================================================================

    @SuppressWarnings("unchecked")
    @Test
    void exportDetailOmittedWhenNoFtrOrAes() throws Exception {
        // Absent FTR/AES on the intl block, we leave exportDetail off the
        // wire and let FedEx apply its server-side default (§30.37(a),
        // safe only under $2,500 USD). Value-threshold gating happens at
        // IntlShipmentValidator upstream.
        ShipmentRequestDTO r = baseRequest();
        r.setIntl(baseIntl());
        Map<String, Object> ccd = (Map<String, Object>) requestedShipment(r).get("customsClearanceDetail");
        assertNull(ccd.get("exportDetail"),
                "no FTR/AES on the DTO → no exportDetail block on the wire");
    }

    @SuppressWarnings("unchecked")
    @Test
    void ftrExemptionEmittedAsExportComplianceStatement() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        IntlShipmentBlockDTO intl = baseIntl();
        intl.setFtrExemption("NO_EEI_30_37_h");
        r.setIntl(intl);
        Map<String, Object> ccd = (Map<String, Object>) requestedShipment(r).get("customsClearanceDetail");
        Map<String, Object> exportDetail = (Map<String, Object>) ccd.get("exportDetail");
        assertNotNull(exportDetail, "FTR exemption must land in an exportDetail block");
        assertEquals("NO EEI 30.37(h)", exportDetail.get("exportComplianceStatement"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void aesCitationPreferredOverFtrOnCollision() throws Exception {
        // Operator supplied both — real AES filing takes precedence over a
        // claimed exemption. Backend validator makes the two mutually
        // exclusive at the DTO layer; this test guards the connector-side
        // tie-breaker for any path that bypasses the FE UI mutex.
        ShipmentRequestDTO r = baseRequest();
        IntlShipmentBlockDTO intl = baseIntl();
        intl.setFtrExemption("NO_EEI_30_37_a");
        intl.setAesCitation("X20260101123456");
        r.setIntl(intl);
        Map<String, Object> ccd = (Map<String, Object>) requestedShipment(r).get("customsClearanceDetail");
        Map<String, Object> exportDetail = (Map<String, Object>) ccd.get("exportDetail");
        assertEquals("X20260101123456", exportDetail.get("exportComplianceStatement"),
                "AES filing wins the tie — real Census record beats claimed exemption");
    }

    @SuppressWarnings("unchecked")
    @Test
    void ftrExemption30_36MapsToCanadaStatement() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setRecipientCountryCode("CA");
        IntlShipmentBlockDTO intl = baseIntl();
        intl.setFtrExemption("NO_EEI_30_36");
        r.setIntl(intl);
        Map<String, Object> ccd = (Map<String, Object>) requestedShipment(r).get("customsClearanceDetail");
        Map<String, Object> exportDetail = (Map<String, Object>) ccd.get("exportDetail");
        assertEquals("NO EEI 30.36", exportDetail.get("exportComplianceStatement"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void exportDeclarationReferenceEmittedVerbatim() throws Exception {
        // Non-US origin path — CA B13A / GB CDS / EU MRN / AU EDN / JP /
        // IN SB all pass through the same exportComplianceStatement slot,
        // verbatim (no wire mapping — the reference format is the
        // operator's responsibility).
        ShipmentRequestDTO r = baseRequest();
        r.setShipperCountryCode("GB");
        IntlShipmentBlockDTO intl = baseIntl();
        intl.setExportDeclarationReference("GB-CDS-2026-99999");
        r.setIntl(intl);
        Map<String, Object> ccd = (Map<String, Object>) requestedShipment(r).get("customsClearanceDetail");
        Map<String, Object> exportDetail = (Map<String, Object>) ccd.get("exportDetail");
        assertEquals("GB-CDS-2026-99999", exportDetail.get("exportComplianceStatement"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void exportRefPrecedence_aesOverFtrOverGenericRef() throws Exception {
        // Precedence documented in IntlShipmentBlockDTO — AES > FTR >
        // generic ref. Real filing beats claimed exemption; claimed
        // exemption beats untyped non-US reference.
        ShipmentRequestDTO r = baseRequest();
        IntlShipmentBlockDTO intl = baseIntl();
        intl.setAesCitation("X20260101123456");
        intl.setFtrExemption("NO_EEI_30_37_a");
        intl.setExportDeclarationReference("CA-B13A-99999");
        r.setIntl(intl);
        Map<String, Object> ccd = (Map<String, Object>) requestedShipment(r).get("customsClearanceDetail");
        Map<String, Object> exportDetail = (Map<String, Object>) ccd.get("exportDetail");
        assertEquals("X20260101123456", exportDetail.get("exportComplianceStatement"),
                "AES ITN wins the three-way precedence");
    }

    // ===================================================================
    // Sprint 51 — email + company on shipper / recipient contact block
    // ===================================================================

    @Test
    @SuppressWarnings("unchecked")
    void shipperContactCarriesCompanyAndEmail_whenSet() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setShipperCompany("Acme Fulfillment");
        r.setShipperEmail("ops@acme.example");

        Map<String, Object> shipper = (Map<String, Object>) requestedShipment(r).get("shipper");
        Map<String, Object> contact = (Map<String, Object>) shipper.get("contact");
        assertEquals("Acme Warehouse", contact.get("personName"));
        assertEquals("Acme Fulfillment", contact.get("companyName"));
        assertEquals("ops@acme.example", contact.get("emailAddress"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void recipientContactCarriesCompanyAndEmail_whenSet() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setRecipientCompany("Zymeworks");
        r.setRecipientEmail("jane@acme.example");

        // FedEx wire key is "recipients" (plural, array) even for a
        // single recipient — see FedExConnector.java:1700.
        Object[] recipients = (Object[]) requestedShipment(r).get("recipients");
        Map<String, Object> recipient = (Map<String, Object>) recipients[0];
        Map<String, Object> contact = (Map<String, Object>) recipient.get("contact");
        assertEquals("Jane Doe", contact.get("personName"));
        assertEquals("Zymeworks", contact.get("companyName"));
        assertEquals("jane@acme.example", contact.get("emailAddress"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void companyAndEmailOmittedWhenBlank_backwardsCompatContactShape() throws Exception {
        // Pre-Sprint-51 the contact block was Map.of(personName, phoneNumber)
        // with no company / email keys at all. This test pins that the
        // "no company / no email" default shape matches the old behaviour
        // exactly — no extra empty-string keys on the wire.
        Map<String, Object> shipper = (Map<String, Object>) requestedShipment(baseRequest()).get("shipper");
        Map<String, Object> contact = (Map<String, Object>) shipper.get("contact");
        assertNull(contact.get("companyName"),
                "blank shipperCompany must NOT add a companyName key on the wire");
        assertNull(contact.get("emailAddress"),
                "blank shipperEmail must NOT add an emailAddress key on the wire");
    }
}
