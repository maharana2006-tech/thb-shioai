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
 * Golden-value tests for {@link UpsConnector#buildShipmentPayload} — the
 * translation from our carrier-neutral DTO into UPS Ship API 2205 JSON is
 * the layer most likely to drift silently, so we assert on the exact JSON
 * shape UPS validates against.
 *
 * <p>Uses reflection to call the private payload builder rather than going
 * through {@code createShipment} — the network call isn't the SUT here.
 */
class UpsConnectorPayloadTest {

    private UpsConnector connector;
    private Method buildShipmentPayload;

    @BeforeEach
    void setUp() throws Exception {
        connector = new UpsConnector(new CarrierProperties(), new ObjectMapper());
        buildShipmentPayload = UpsConnector.class.getDeclaredMethod("buildShipmentPayload", ShipmentRequestDTO.class);
        buildShipmentPayload.setAccessible(true);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> build(ShipmentRequestDTO request) throws Exception {
        return (Map<String, Object>) buildShipmentPayload.invoke(connector, request);
    }

    private ShipmentRequestDTO domesticRequest() {
        return ShipmentRequestDTO.builder()
                .carrierCode("UPS")
                .accountNumber("A12345")
                .serviceType("03")
                .packageType("02")
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
                .recipientAddressLine1("42 Broadway")
                .recipientCity("New York")
                .recipientState("NY")
                .recipientPostalCode("10001")
                .recipientCountryCode("US")
                .referenceNumber("PO-1001")
                .build();
    }

    private IntlShipmentBlockDTO validIntl() {
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
                        .build()))
                .build();
    }

    @SuppressWarnings("unchecked")
    @Test
    void domesticPayloadOmitsInternationalForms() throws Exception {
        Map<String, Object> payload = build(domesticRequest());
        Map<String, Object> shipment = (Map<String, Object>) ((Map<String, Object>)
                payload.get("ShipmentRequest")).get("Shipment");
        assertNull(shipment.get("ShipmentServiceOptions"), "Domestic payload should not include InternationalForms");
        assertNull(shipment.get("SoldTo"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void kgWeightPreservedOnTheWire() throws Exception {
        ShipmentRequestDTO r = domesticRequest();
        r.setWeightUnit("KG");
        r.setWeight(new BigDecimal("1.5"));

        Map<String, Object> payload = build(r);
        Map<String, Object> pkg = (Map<String, Object>) ((List<Object>) ((Map<String, Object>)
                ((Map<String, Object>) payload.get("ShipmentRequest")).get("Shipment")).get("Package")).get(0);
        Map<String, Object> weight = (Map<String, Object>) pkg.get("PackageWeight");
        Map<String, Object> uom = (Map<String, Object>) weight.get("UnitOfMeasurement");
        assertEquals("KGS", uom.get("Code"), "KG on the DTO should serialize as KGS to UPS");
        assertEquals("1.5", weight.get("Weight"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void internationalPayloadEmitsInternationalForms() throws Exception {
        ShipmentRequestDTO r = domesticRequest();
        r.setRecipientCountryCode("GB");
        r.setIntl(validIntl());

        Map<String, Object> payload = build(r);
        Map<String, Object> shipment = (Map<String, Object>) ((Map<String, Object>)
                payload.get("ShipmentRequest")).get("Shipment");
        Map<String, Object> forms = (Map<String, Object>) ((Map<String, Object>)
                shipment.get("ShipmentServiceOptions")).get("InternationalForms");
        assertEquals("01", forms.get("FormType"));
        assertEquals("DDP", forms.get("TermsOfShipment"));
        assertEquals("SALE", forms.get("ReasonForExport"));
        assertEquals("EUR", forms.get("CurrencyCode"));
        List<Map<String, Object>> products = (List<Map<String, Object>>) forms.get("Product");
        assertEquals(1, products.size());
        Map<String, Object> product = products.get(0);
        assertEquals("Widget", product.get("Description"));
        assertEquals("6104.62.20", product.get("CommodityCode"));
        assertEquals("US", product.get("OriginCountryCode"));
        Map<String, Object> unit = (Map<String, Object>) product.get("Unit");
        assertEquals("10", unit.get("Number"));
        assertEquals("50.00", unit.get("Value"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void ddpAddsSecondBillShipperShipmentCharge() throws Exception {
        ShipmentRequestDTO r = domesticRequest();
        r.setRecipientCountryCode("GB");
        r.setIntl(validIntl()); // incoterms=DDP already

        Map<String, Object> shipment = (Map<String, Object>) ((Map<String, Object>)
                build(r).get("ShipmentRequest")).get("Shipment");
        List<Map<String, Object>> charges = (List<Map<String, Object>>) ((Map<String, Object>)
                shipment.get("PaymentInformation")).get("ShipmentCharge");
        assertEquals(2, charges.size(), "DDP should split freight + duties into two charges");
        assertEquals("01", charges.get(0).get("Type"));
        assertEquals("02", charges.get(1).get("Type"));
        Map<String, Object> dutyCharge = charges.get(1);
        assertNotNull(dutyCharge.get("BillShipper"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void dapKeepsSingleFreightCharge() throws Exception {
        ShipmentRequestDTO r = domesticRequest();
        r.setRecipientCountryCode("GB");
        IntlShipmentBlockDTO intl = validIntl();
        intl.setIncoterms("DAP");
        r.setIntl(intl);

        List<Map<String, Object>> charges = (List<Map<String, Object>>) ((Map<String, Object>) ((Map<String, Object>)
                ((Map<String, Object>) build(r).get("ShipmentRequest")).get("Shipment")).get("PaymentInformation"))
                .get("ShipmentCharge");
        assertEquals(1, charges.size(), "DAP should leave duties to consignee — freight only");
    }

    @SuppressWarnings("unchecked")
    @Test
    void thirdPartyDutyRoutesToBillThirdParty() throws Exception {
        ShipmentRequestDTO r = domesticRequest();
        r.setRecipientCountryCode("GB");
        IntlShipmentBlockDTO intl = validIntl();
        intl.setIncoterms("DAP");
        intl.setDutyBillTo("THIRD_PARTY");
        intl.setDutyAccount("PAYER-999");
        r.setIntl(intl);

        List<Map<String, Object>> charges = (List<Map<String, Object>>) ((Map<String, Object>) ((Map<String, Object>)
                ((Map<String, Object>) build(r).get("ShipmentRequest")).get("Shipment")).get("PaymentInformation"))
                .get("ShipmentCharge");
        assertEquals(2, charges.size());
        Map<String, Object> billThirdParty = (Map<String, Object>) charges.get(1).get("BillThirdParty");
        assertNotNull(billThirdParty);
        assertEquals("PAYER-999", billThirdParty.get("AccountNumber"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void soldTo_alwaysEmitted_defaultsToConsignee_whenNoDistinctImporter() throws Exception {
        // PR B (2026-09-09) — SoldTo is ALWAYS emitted for intl now.
        // Turkey rejects with "128115 Invalid or missing sold to phone
        // number" when SoldTo is absent. No distinct importer named →
        // mirror the ShipTo party (Option="01" = consignee is importer).
        ShipmentRequestDTO r = domesticRequest();
        r.setRecipientCountryCode("TR");
        r.setIntl(validIntl()); // no importer fields set

        Map<String, Object> soldTo = (Map<String, Object>) ((Map<String, Object>)
                ((Map<String, Object>) build(r).get("ShipmentRequest")).get("Shipment")).get("SoldTo");

        assertNotNull(soldTo, "SoldTo MUST be present — Turkey and others reject 128115 when it's absent");
        assertEquals("01", soldTo.get("Option"),
                "no distinct importer named → Option=01 (consignee is importer)");
        assertEquals("Jane Doe", soldTo.get("Name"),
                "Name mirrors the recipient party");
        Map<String, Object> phone = (Map<String, Object>) soldTo.get("Phone");
        assertNotNull(phone, "UPS 128115 fix requires Phone.Number populated on SoldTo");
        assertEquals("5559876543", phone.get("Number"),
                "recipient phone flows through to SoldTo.Phone.Number");
        Map<String, Object> addr = (Map<String, Object>) soldTo.get("Address");
        assertEquals("TR", addr.get("CountryCode"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void soldTo_usesDistinctImporter_whenIntlBlockNamesOne() throws Exception {
        // Distinct importer path preserved — pre-PR-B behavior when the
        // operator explicitly names an importer (e.g. a customs broker,
        // parent company acting as importer of record).
        ShipmentRequestDTO r = domesticRequest();
        r.setRecipientCountryCode("GB");
        IntlShipmentBlockDTO intl = validIntl();
        intl.setImporterName("Acme UK Ltd");
        intl.setImporterAddressLine1("1 Kings Way");
        intl.setImporterCity("London");
        intl.setImporterPostcode("W1 1AA");
        intl.setImporterCountry("GB");
        intl.setImporterPhone("+442071234567");
        r.setIntl(intl);

        Map<String, Object> soldTo = (Map<String, Object>) ((Map<String, Object>)
                ((Map<String, Object>) build(r).get("ShipmentRequest")).get("Shipment")).get("SoldTo");
        assertNotNull(soldTo);
        assertEquals("02", soldTo.get("Option"));
        assertEquals("Acme UK Ltd", soldTo.get("Name"));
        Map<String, Object> phone = (Map<String, Object>) soldTo.get("Phone");
        assertNotNull(phone);
        assertEquals("+442071234567", phone.get("Number"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void soldTo_distinctImporter_fallsBackToRecipientPhone_whenImporterPhoneBlank() throws Exception {
        // Defense-in-depth: if the operator names an importer but leaves
        // the importer phone blank, use the recipient phone rather than
        // emitting an empty Phone block that UPS 128115 also rejects.
        ShipmentRequestDTO r = domesticRequest();
        r.setRecipientCountryCode("GB");
        IntlShipmentBlockDTO intl = validIntl();
        intl.setImporterName("Acme UK Ltd");
        intl.setImporterAddressLine1("1 Kings Way");
        intl.setImporterCity("London");
        intl.setImporterPostcode("W1 1AA");
        intl.setImporterCountry("GB");
        // no importerPhone
        r.setIntl(intl);

        Map<String, Object> soldTo = (Map<String, Object>) ((Map<String, Object>)
                ((Map<String, Object>) build(r).get("ShipmentRequest")).get("Shipment")).get("SoldTo");
        Map<String, Object> phone = (Map<String, Object>) soldTo.get("Phone");
        assertNotNull(phone, "importer phone blank → fall back to recipient phone, not empty Phone");
        assertEquals("5559876543", phone.get("Number"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void soldTo_notEmittedForDomesticShipment_regressionGuard() throws Exception {
        // PR B applies only to intl shipments. Domestic (US→US) must
        // still omit SoldTo — UPS Ship API accepts domestic without one,
        // and emitting it would waste bytes and confuse operators
        // reading the logs.
        Map<String, Object> shipment = (Map<String, Object>) ((Map<String, Object>)
                build(domesticRequest()).get("ShipmentRequest")).get("Shipment");
        assertNull(shipment.get("SoldTo"),
                "domestic shipments must not emit SoldTo (pre-PR-B behavior preserved)");
    }

    @Test
    void requestSubVersionMatchesUpsShipApiTwentyTwoOhFive() throws Exception {
        Map<String, Object> shipmentRequest = (Map<String, Object>) build(domesticRequest()).get("ShipmentRequest");
        @SuppressWarnings("unchecked")
        Map<String, Object> requestBlock = (Map<String, Object>) shipmentRequest.get("Request");
        assertEquals("2205", requestBlock.get("SubVersion"));
    }

    @Test
    void intlReadyGateBlocksIncompleteBlock() throws Exception {
        ShipmentRequestDTO r = domesticRequest();
        r.setRecipientCountryCode("GB");
        IntlShipmentBlockDTO intl = IntlShipmentBlockDTO.builder()
                .international(true) // no commodities/currency/incoterms
                .build();
        r.setIntl(intl);
        assertFalse(intl.isReadyForCarrier());

        @SuppressWarnings("unchecked")
        Map<String, Object> shipment = (Map<String, Object>) ((Map<String, Object>)
                build(r).get("ShipmentRequest")).get("Shipment");
        assertNull(shipment.get("ShipmentServiceOptions"),
                "Incomplete intl block should skip InternationalForms silently");
    }

    // ===== UPS-9 — reasonForExport mapping =====

    @Test
    void reasonForExportMapsToUpsEnum() {
        // Pre-UPS-9 the ReasonForExport field was firstNonBlank(x, "SALE").toUpperCase()
        // — the resolver's 8-value SHIPPING_PURPOSE_ENUM was passed through
        // as-is. UPS accepts only 7 values (SALE/GIFT/SAMPLE/RETURN/REPAIR/
        // INTERCOMPANYDATA/DOCUMENTS), so MERCHANDISE, PERSONAL_USE, and
        // REPAIR_AND_RETURN reached UPS as unsupported strings. Now the
        // connector maps to UPS's enum explicitly. Mirrors FDX-D on FedEx.
        java.util.LinkedHashMap<String, String> mapping = new java.util.LinkedHashMap<>();
        mapping.put("SALE", "SALE");
        mapping.put("MERCHANDISE", "SALE");           // UPS-9 — was passed as MERCHANDISE (invalid); commercial = SALE
        mapping.put("GIFT", "GIFT");
        mapping.put("SAMPLE", "SAMPLE");
        mapping.put("PERSONAL_USE", "SAMPLE");        // UPS-9 — was passed as PERSONAL_USE (invalid); UPS has no PERSONAL_EFFECTS
        mapping.put("RETURN", "RETURN");
        mapping.put("REPAIR", "REPAIR");
        mapping.put("REPAIR_AND_RETURN", "REPAIR");   // UPS-9 — was passed as REPAIR_AND_RETURN (invalid); consolidate
        mapping.put("DOCUMENTS", "DOCUMENTS");
        for (java.util.Map.Entry<String, String> entry : mapping.entrySet()) {
            assertEquals(entry.getValue(),
                    UpsConnector.mapUpsReasonForExport(entry.getKey()),
                    "Reason " + entry.getKey());
        }
    }

    @Test
    void reasonForExportUnknownFallsToSaleWithWarning() {
        // Unknown values still default to SALE (matches pre-UPS-9 default);
        // helper logs a warning via log.warn so future audits catch drift.
        assertEquals("SALE", UpsConnector.mapUpsReasonForExport(null));
        assertEquals("SALE", UpsConnector.mapUpsReasonForExport("GARBAGE"));
        assertEquals("SALE", UpsConnector.mapUpsReasonForExport(""));
    }

    @Test
    void reasonForExportIsCaseInsensitive() {
        assertEquals("REPAIR", UpsConnector.mapUpsReasonForExport("repair_and_return"));
        assertEquals("GIFT", UpsConnector.mapUpsReasonForExport("Gift"));
        assertEquals("SAMPLE", UpsConnector.mapUpsReasonForExport("personal_use"));
    }

    // ===== UPS-4b — LabelImageFormat wired from ShipmentRequestDTO =====

    @SuppressWarnings("unchecked")
    @Test
    void labelImageFormat_defaultsToGIF_whenDtoLeavesItNull() throws Exception {
        // Pre-UPS-4b behavior preserved for callers that don't populate
        // the new DTO field. Matches the pre-fix hardcode exactly.
        ShipmentRequestDTO r = domesticRequest();
        r.setLabelImageFormat(null);
        Map<String, Object> shipmentRequest = (Map<String, Object>) build(r).get("ShipmentRequest");
        Map<String, Object> labelSpec = (Map<String, Object>) shipmentRequest.get("LabelSpecification");
        Map<String, Object> format = (Map<String, Object>) labelSpec.get("LabelImageFormat");
        assertEquals("GIF", format.get("Code"),
                "null labelImageFormat must fall to the pre-UPS-4b hardcode for back-compat");
    }

    @SuppressWarnings("unchecked")
    @Test
    void labelImageFormat_pdfOnDtoLandsOnUpsWire() throws Exception {
        // Operator-set PDF (high-quality printer) must reach the wire so
        // UPS returns a sharp vector label instead of rasterised GIF.
        // Fixes the pre-UPS-4b bug where ZPL-printer shippers had no way
        // to override the fuzzy default.
        ShipmentRequestDTO r = domesticRequest();
        r.setLabelImageFormat("PDF");
        Map<String, Object> shipmentRequest = (Map<String, Object>) build(r).get("ShipmentRequest");
        Map<String, Object> labelSpec = (Map<String, Object>) shipmentRequest.get("LabelSpecification");
        Map<String, Object> format = (Map<String, Object>) labelSpec.get("LabelImageFormat");
        assertEquals("PDF", format.get("Code"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void labelImageFormat_lowerCaseNormalisedToUpper() throws Exception {
        // Defensive normalisation for programmatic callers that pass
        // lower-case — UPS's enum is strict on case.
        ShipmentRequestDTO r = domesticRequest();
        r.setLabelImageFormat("zpl");
        Map<String, Object> shipmentRequest = (Map<String, Object>) build(r).get("ShipmentRequest");
        Map<String, Object> labelSpec = (Map<String, Object>) shipmentRequest.get("LabelSpecification");
        Map<String, Object> format = (Map<String, Object>) labelSpec.get("LabelImageFormat");
        assertEquals("ZPL", format.get("Code"));
    }

    // ===================================================================
    // Sprint 51 — email + company on Shipper / ShipTo party block
    // ===================================================================

    @Test
    @SuppressWarnings("unchecked")
    void shipperName_isCompany_whenCompanySet_attentionNameIsPerson() throws Exception {
        // UPS's Name field is the business identifier printed on the label
        // (matches the "carrier's business-name convention"); AttentionName
        // routes the parcel to the person. When shipperCompany is populated,
        // Name = company + AttentionName = personal name — matches how
        // UPS's own portal builds the label.
        ShipmentRequestDTO r = domesticRequest();
        r.setShipperCompany("Acme Fulfillment");
        r.setShipperEmail("ops@acme.example");

        Map<String, Object> shipment = (Map<String, Object>) ((Map<String, Object>)
                build(r).get("ShipmentRequest")).get("Shipment");
        Map<String, Object> shipper = (Map<String, Object>) shipment.get("Shipper");
        assertEquals("Acme Fulfillment", shipper.get("Name"));
        assertEquals("Acme Warehouse", shipper.get("AttentionName"));
        assertEquals("ops@acme.example", shipper.get("EMailAddress"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void shipperName_fallsBackToPersonName_whenCompanyBlank() throws Exception {
        // Backwards-compat: pre-Sprint-51 both Name and AttentionName held
        // the personal name (no separate company). No company on the DTO
        // must preserve that verbatim so existing labels don't change.
        Map<String, Object> shipment = (Map<String, Object>) ((Map<String, Object>)
                build(domesticRequest()).get("ShipmentRequest")).get("Shipment");
        Map<String, Object> shipper = (Map<String, Object>) shipment.get("Shipper");
        assertEquals("Acme Warehouse", shipper.get("Name"));
        assertEquals("Acme Warehouse", shipper.get("AttentionName"));
        assertNull(shipper.get("EMailAddress"),
                "blank shipperEmail must NOT add EMailAddress to the wire");
    }

    @Test
    @SuppressWarnings("unchecked")
    void recipientName_carriesCompanyAndEmail_whenSet() throws Exception {
        ShipmentRequestDTO r = domesticRequest();
        r.setRecipientCompany("Zymeworks");
        r.setRecipientEmail("jane@acme.example");

        Map<String, Object> shipment = (Map<String, Object>) ((Map<String, Object>)
                build(r).get("ShipmentRequest")).get("Shipment");
        Map<String, Object> shipTo = (Map<String, Object>) shipment.get("ShipTo");
        assertEquals("Zymeworks", shipTo.get("Name"));
        assertEquals("Jane Doe", shipTo.get("AttentionName"));
        assertEquals("jane@acme.example", shipTo.get("EMailAddress"));
    }

    // ===================================================================
    // PR C (2026-09-09) — paperless-invoice deny list
    // ===================================================================

    @SuppressWarnings("unchecked")
    @Test
    void paperless_denied_egypt_omitsInternationalForms() throws Exception {
        // Live 49-country matrix alert — UPS rejects "120372 The
        // selected origin and destination pair does not accept
        // paperless invoice" for Egypt. Our fix: skip emitting
        // ShipmentServiceOptions.InternationalForms for EG; the printed
        // CI is still available on demand via
        // /orders/{n}/commercial-invoice.
        ShipmentRequestDTO r = domesticRequest();
        r.setRecipientCountryCode("EG");
        r.setIntl(validIntl());

        Map<String, Object> shipment = (Map<String, Object>) ((Map<String, Object>)
                build(r).get("ShipmentRequest")).get("Shipment");
        assertNull(shipment.get("ShipmentServiceOptions"),
                "EG is on the paperless-invoice denylist — InternationalForms must be omitted");
        // Shipment.InvoiceLineTotal is still emitted (UPS requires it for
        // intl regardless of paperless status — 120502 fires without it).
        assertNotNull(shipment.get("InvoiceLineTotal"),
                "Shipment.InvoiceLineTotal must still be emitted for intl "
                + "shipments even when paperless is disabled (UPS 120502)");
    }

    @SuppressWarnings("unchecked")
    @Test
    void paperless_denied_brazil_omitsInternationalForms() throws Exception {
        // Same audit — Brazil. Second country flagged by the operator.
        ShipmentRequestDTO r = domesticRequest();
        r.setRecipientCountryCode("BR");
        r.setIntl(validIntl());

        Map<String, Object> shipment = (Map<String, Object>) ((Map<String, Object>)
                build(r).get("ShipmentRequest")).get("Shipment");
        assertNull(shipment.get("ShipmentServiceOptions"),
                "BR is on the paperless-invoice denylist — InternationalForms must be omitted");
    }

    @SuppressWarnings("unchecked")
    @Test
    void paperless_allowed_britain_stillEmitsInternationalForms_regressionGuard() throws Exception {
        // Regression: the denylist must not accidentally suppress
        // paperless for the 99% of destinations UPS DOES support. GB is
        // a canary — pre-PR-C paperless behavior preserved exactly.
        ShipmentRequestDTO r = domesticRequest();
        r.setRecipientCountryCode("GB");
        r.setIntl(validIntl());

        Map<String, Object> shipment = (Map<String, Object>) ((Map<String, Object>)
                build(r).get("ShipmentRequest")).get("Shipment");
        Map<String, Object> serviceOptions = (Map<String, Object>) shipment.get("ShipmentServiceOptions");
        assertNotNull(serviceOptions, "GB is not on the denylist; InternationalForms must be present");
        assertNotNull(serviceOptions.get("InternationalForms"));
    }

    @Test
    void paperlessInvoiceAcceptedFor_helper_isCaseInsensitiveAndBlankSafe() {
        // Direct-hit unit test of the predicate — case handling and
        // blank-safety pinned so a future refactor can't silently
        // regress on "eG" or "" inputs.
        UpsConnector c = new UpsConnector(new CarrierProperties(), new ObjectMapper());
        assertTrue(c.paperlessInvoiceAcceptedFor("US"));
        assertTrue(c.paperlessInvoiceAcceptedFor("GB"));
        assertFalse(c.paperlessInvoiceAcceptedFor("EG"));
        assertFalse(c.paperlessInvoiceAcceptedFor("eg"));
        assertFalse(c.paperlessInvoiceAcceptedFor("BR"));
        assertTrue(c.paperlessInvoiceAcceptedFor(""), "blank country → accepted (upstream presence check catches missing country)");
        assertTrue(c.paperlessInvoiceAcceptedFor(null));
    }
}
