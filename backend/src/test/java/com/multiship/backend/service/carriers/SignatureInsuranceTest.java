package com.multiship.backend.service.carriers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.dto.ShipmentRequestDTO;
import com.multiship.backend.service.fx.FxRateService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 35 — signature + insurance wire emission across UPS / FedEx /
 * DHL / USPS. Cross-carrier coverage in one file per the ReturnLabelTest
 * / FedExDhlUspsHazMatTest precedent.
 */
class SignatureInsuranceTest {

    private static FxRateService noFx() {
        return new FxRateService() {
            @Override public Optional<BigDecimal> rate(String f, String t) { return Optional.empty(); }
            @Override public Optional<BigDecimal> convert(BigDecimal a, String f, String t) { return Optional.empty(); }
            @Override public boolean supports(String c) { return false; }
        };
    }

    private static ShipmentRequestDTO baseRequest() {
        return ShipmentRequestDTO.builder()
                .carrierCode("UPS")
                .accountNumber("A12345")
                .serviceType("03")
                .packageType("02")
                .weight(new BigDecimal("2"))
                .weightUnit("LB")
                .shipperName("Acme").shipperPhone("5551234567")
                .shipperAddressLine1("1 Way").shipperCity("Louisville")
                .shipperState("KY").shipperPostalCode("40209").shipperCountryCode("US")
                .recipientName("Jane").recipientPhone("5559876543")
                .recipientAddressLine1("42 Broadway").recipientCity("New York")
                .recipientState("NY").recipientPostalCode("10001").recipientCountryCode("US")
                .referenceNumber("PO-1001")
                .build();
    }

    /* -------------------------- UPS -------------------------- */

    @SuppressWarnings("unchecked")
    private static Map<String, Object> upsFirstPackage(ShipmentRequestDTO r) throws Exception {
        UpsConnector c = new UpsConnector(new CarrierProperties(), new ObjectMapper());
        Method m = UpsConnector.class.getDeclaredMethod("buildShipmentPayload", ShipmentRequestDTO.class);
        m.setAccessible(true);
        Map<String, Object> payload = (Map<String, Object>) m.invoke(c, r);
        Map<String, Object> shipmentRequest = (Map<String, Object>) payload.get("ShipmentRequest");
        Map<String, Object> shipment = (Map<String, Object>) shipmentRequest.get("Shipment");
        List<Map<String, Object>> packages = (List<Map<String, Object>>) shipment.get("Package");
        return packages.get(0);
    }

    @Test
    void upsNoSignatureNoInsuranceOmitsPackageServiceOptions() throws Exception {
        Map<String, Object> pkg = upsFirstPackage(baseRequest());
        assertNull(pkg.get("PackageServiceOptions"),
                "Default (no signature, no insurance) should omit the block entirely");
    }

    @Test
    @SuppressWarnings("unchecked")
    void upsAdultSignatureEmitsDcisTypeThree() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setSignatureOption("ADULT");
        Map<String, Object> pkg = upsFirstPackage(r);
        Map<String, Object> pso = (Map<String, Object>) pkg.get("PackageServiceOptions");
        assertNotNull(pso);
        Map<String, Object> dc = (Map<String, Object>) pso.get("DeliveryConfirmation");
        assertEquals("3", dc.get("DCISType"), "ADULT → DCISType=3");
    }

    @Test
    @SuppressWarnings("unchecked")
    void upsIndirectAndDirectMapToSignatureCode2() throws Exception {
        for (String sig : List.of("INDIRECT", "DIRECT")) {
            ShipmentRequestDTO r = baseRequest();
            r.setSignatureOption(sig);
            Map<String, Object> pkg = upsFirstPackage(r);
            Map<String, Object> pso = (Map<String, Object>) pkg.get("PackageServiceOptions");
            Map<String, Object> dc = (Map<String, Object>) pso.get("DeliveryConfirmation");
            assertEquals("2", dc.get("DCISType"),
                    sig + " should map to signature-required DCISType=2");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void upsInsuredValueEmitsDeclaredValueBlockWithCurrency() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setInsuredValue(new BigDecimal("500.00"));
        r.setInsuredValueCurrency("USD");
        Map<String, Object> pkg = upsFirstPackage(r);
        Map<String, Object> pso = (Map<String, Object>) pkg.get("PackageServiceOptions");
        Map<String, Object> dv = (Map<String, Object>) pso.get("DeclaredValue");
        assertEquals("500.00", dv.get("MonetaryValue"));
        assertEquals("USD", dv.get("CurrencyCode"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void upsInsuredValueCurrencyFallsBackToDeclaredCurrencyThenUsd() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setInsuredValue(new BigDecimal("500.00"));
        r.setDeclaredValueCurrency("EUR");
        Map<String, Object> pkg = upsFirstPackage(r);
        Map<String, Object> pso = (Map<String, Object>) pkg.get("PackageServiceOptions");
        Map<String, Object> dv = (Map<String, Object>) pso.get("DeclaredValue");
        assertEquals("EUR", dv.get("CurrencyCode"));
    }

    /* -------------------------- FedEx -------------------------- */

    @SuppressWarnings("unchecked")
    private static Map<String, Object> fedexFirstLineItem(ShipmentRequestDTO r) throws Exception {
        FedExConnector c = new FedExConnector(new CarrierProperties(), new ObjectMapper(), noFx());
        Method m = FedExConnector.class.getDeclaredMethod("buildShipmentPayload", ShipmentRequestDTO.class);
        m.setAccessible(true);
        Map<String, Object> payload = (Map<String, Object>) m.invoke(c, r);
        Map<String, Object> requestedShipment = (Map<String, Object>) payload.get("requestedShipment");
        List<Map<String, Object>> items = (List<Map<String, Object>>) requestedShipment.get("requestedPackageLineItems");
        return items.get(0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void fedexDirectSignatureEmitsSignatureOptionTypeDirect() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setSignatureOption("DIRECT");
        Map<String, Object> item = fedexFirstLineItem(r);
        Map<String, Object> pss = (Map<String, Object>) item.get("packageSpecialServices");
        assertNotNull(pss);
        assertEquals("DIRECT", pss.get("signatureOptionType"));
        List<String> types = (List<String>) pss.get("specialServiceTypes");
        assertTrue(types.contains("SIGNATURE_OPTION"),
                "specialServiceTypes must include SIGNATURE_OPTION");
    }

    @Test
    void fedexNoSignatureOmitsPackageSpecialServices() throws Exception {
        assertNull(fedexFirstLineItem(baseRequest()).get("packageSpecialServices"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void fedexDeclaredValueWinsOverInsuredValueOnLineItem() throws Exception {
        // Sprint 48 B11 — new priority chain: items > p.declaredValue >
        // shipment-level declaredValue > insuredValue. Customs declared
        // value ($50 split across 1 pkg = $50) now wins on the line item
        // over the shipment-level insurance value. Ops who need to insure
        // for a higher amount than the customs value can set p.declaredValue
        // or use InternationalForms.
        ShipmentRequestDTO r = baseRequest();
        r.setDeclaredValue(new BigDecimal("50.00"));    // customs (feeds context)
        r.setInsuredValue(new BigDecimal("500.00"));    // insurance
        r.setInsuredValueCurrency("USD");
        Map<String, Object> item = fedexFirstLineItem(r);
        Map<String, Object> dv = (Map<String, Object>) item.get("declaredValue");
        assertEquals(0, new BigDecimal("50.00").compareTo((BigDecimal) dv.get("amount")),
                "Sprint 48 B11: declared value (from items/shipment total) wins over insuredValue.");
    }

    /* -------------------------- DHL -------------------------- */

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> dhlValueAddedServices(ShipmentRequestDTO r) {
        DhlConnector c = new DhlConnector(new CarrierProperties(), new ObjectMapper());
        return c.buildDhlValueAddedServices(r);
    }

    @Test
    void dhlNoOptionsReturnsEmptyList() {
        assertTrue(dhlValueAddedServices(baseRequest()).isEmpty());
    }

    @Test
    void dhlIndirectSignatureAddsSfCode() {
        ShipmentRequestDTO r = baseRequest();
        r.setSignatureOption("INDIRECT");
        List<Map<String, Object>> vas = dhlValueAddedServices(r);
        assertEquals(1, vas.size());
        assertEquals("SF", vas.get(0).get("serviceCode"));
    }

    @Test
    void dhlAdultSignatureAddsSiCode() {
        ShipmentRequestDTO r = baseRequest();
        r.setSignatureOption("ADULT");
        List<Map<String, Object>> vas = dhlValueAddedServices(r);
        assertEquals("SI", vas.get(0).get("serviceCode"),
                "DHL ADULT signature maps to SI (Signature Adult), not SF");
    }

    @Test
    void dhlInsuredValueAddsIiEntryWithCurrency() {
        ShipmentRequestDTO r = baseRequest();
        r.setInsuredValue(new BigDecimal("500.00"));
        r.setInsuredValueCurrency("EUR");
        List<Map<String, Object>> vas = dhlValueAddedServices(r);
        assertEquals(1, vas.size());
        assertEquals("II", vas.get(0).get("serviceCode"));
        assertEquals(0, new BigDecimal("500.00").compareTo((BigDecimal) vas.get(0).get("value")));
        assertEquals("EUR", vas.get(0).get("currency"));
    }

    @Test
    void dhlSignatureAndInsuranceEmitTwoEntries() {
        ShipmentRequestDTO r = baseRequest();
        r.setSignatureOption("ADULT");
        r.setInsuredValue(new BigDecimal("500.00"));
        List<Map<String, Object>> vas = dhlValueAddedServices(r);
        assertEquals(2, vas.size());
    }

    /* -------------------------- USPS / Stamps SWSIM -------------------------- */

    private static String stampsEnvelope(ShipmentRequestDTO r) throws Exception {
        StampsConnector c = new StampsConnector(new CarrierProperties(), new ObjectMapper());
        Method m = StampsConnector.class.getDeclaredMethod("buildCreateIndiciumEnvelope",
                ShipmentRequestDTO.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(c, r, "AUTH-XYZ");
    }

    @Test
    void stampsNoSignatureNoInsuranceOmitsBothElements() throws Exception {
        String xml = stampsEnvelope(baseRequest());
        assertFalse(xml.contains("<SignatureConfirmation"));
        assertFalse(xml.contains("<AdultSignatureRequired"));
        assertFalse(xml.contains("<InsuredValue"));
    }

    @Test
    void stampsAdultSignatureEmitsAdultSignatureRequired() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setServiceType("USPS GA");
        r.setPackageType("Package");
        r.setSignatureOption("ADULT");
        String xml = stampsEnvelope(r);
        assertTrue(xml.contains("<AdultSignatureRequired>true</AdultSignatureRequired>"),
                "USPS ADULT maps to AdultSignatureRequired; got: " + xml);
        assertFalse(xml.contains("<SignatureConfirmation>true</SignatureConfirmation>"),
                "USPS ADULT should NOT also emit SignatureConfirmation");
    }

    @Test
    void stampsIndirectAndDirectMapToSignatureConfirmation() throws Exception {
        for (String sig : List.of("INDIRECT", "DIRECT")) {
            ShipmentRequestDTO r = baseRequest();
            r.setServiceType("USPS GA");
            r.setPackageType("Package");
            r.setSignatureOption(sig);
            String xml = stampsEnvelope(r);
            assertTrue(xml.contains("<SignatureConfirmation>true</SignatureConfirmation>"),
                    sig + " should map to SignatureConfirmation; got: " + xml);
        }
    }

    @Test
    void stampsInsuredValueEmitsSeparateInsuredValueElement() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setServiceType("USPS GA");
        r.setPackageType("Package");
        r.setInsuredValue(new BigDecimal("500.00"));
        String xml = stampsEnvelope(r);
        assertTrue(xml.contains("<InsuredValue>500.00</InsuredValue>"), xml);
        // SWSIM insurance is a distinct element from DeclaredValue (which
        // is for customs).
        assertFalse(xml.contains("<DeclaredValue>500.00"),
                "Insurance must not overwrite DeclaredValue on SWSIM — that's customs");
    }
}
