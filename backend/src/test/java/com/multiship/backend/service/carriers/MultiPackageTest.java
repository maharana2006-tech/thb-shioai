package com.multiship.backend.service.carriers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.dto.PackageDetailDTO;
import com.multiship.backend.dto.ShipmentRequestDTO;
import com.multiship.backend.service.fx.FxRateService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28 — multi-package wire emission on all four carriers plus the
 * ShipmentRequestDTO backwards-compat helper. One file per the existing
 * cross-carrier test precedent (ReturnLabelTest, FedExDhlUspsHazMatTest).
 */
class MultiPackageTest {

    private static FxRateService noFx() {
        return new FxRateService() {
            @Override public Optional<BigDecimal> rate(String f, String t) { return Optional.empty(); }
            @Override public Optional<BigDecimal> convert(BigDecimal a, String f, String t) { return Optional.empty(); }
            @Override public boolean supports(String c) { return false; }
        };
    }

    private static ShipmentRequestDTO baseRequest() {
        return ShipmentRequestDTO.builder()
                .carrierCode("UPS").accountNumber("A12345")
                .serviceType("03").packageType("02")
                .weight(new BigDecimal("2.5")).weightUnit("LB")
                .shipperName("Acme").shipperPhone("5551234567")
                .shipperAddressLine1("1 Way").shipperCity("Louisville")
                .shipperState("KY").shipperPostalCode("40209").shipperCountryCode("US")
                .recipientName("Jane").recipientPhone("5559876543")
                .recipientAddressLine1("42 Broadway").recipientCity("New York")
                .recipientState("NY").recipientPostalCode("10001").recipientCountryCode("US")
                .referenceNumber("PO-1001")
                .build();
    }

    private static PackageDetailDTO pkg(int seq, String weight, String packageType) {
        return PackageDetailDTO.builder()
                .sequenceNumber(seq)
                .packageType(packageType)
                .weight(new BigDecimal(weight))
                .weightUnit("LB")
                .build();
    }

    /* -------------------------- effectivePackages() -------------------------- */

    @Test
    void effectivePackagesSynthesizesSinglePackageFromTopLevelFieldsWhenNull() {
        List<PackageDetailDTO> packages = baseRequest().effectivePackages();
        assertEquals(1, packages.size());
        PackageDetailDTO p = packages.get(0);
        assertEquals(Integer.valueOf(1), p.getSequenceNumber());
        assertEquals("02", p.getPackageType());
        assertEquals(0, new BigDecimal("2.5").compareTo(p.getWeight()));
        assertEquals("LB", p.getWeightUnit());
        assertEquals("PO-1001", p.getReference());
    }

    @Test
    void effectivePackagesReturnsExplicitListWhenPopulated() {
        ShipmentRequestDTO r = baseRequest();
        r.setPackages(List.of(pkg(1, "2.5", "02"), pkg(2, "3.0", "02"), pkg(3, "1.0", "02")));
        assertEquals(3, r.effectivePackages().size());
    }

    @Test
    void effectivePackagesReturnsSyntheticWhenExplicitListEmpty() {
        ShipmentRequestDTO r = baseRequest();
        r.setPackages(List.of());
        // Empty list is treated as null → synthetic fallback.
        assertEquals(1, r.effectivePackages().size());
    }

    /* -------------------------- UPS -------------------------- */

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> upsPackageBlocks(ShipmentRequestDTO r) throws Exception {
        UpsConnector c = new UpsConnector(new CarrierProperties(), new ObjectMapper());
        Method m = UpsConnector.class.getDeclaredMethod("buildShipmentPayload", ShipmentRequestDTO.class);
        m.setAccessible(true);
        Map<String, Object> payload = (Map<String, Object>) m.invoke(c, r);
        Map<String, Object> shipmentRequest = (Map<String, Object>) payload.get("ShipmentRequest");
        Map<String, Object> shipment = (Map<String, Object>) shipmentRequest.get("Shipment");
        return (List<Map<String, Object>>) shipment.get("Package");
    }

    @Test
    void upsSinglePackageBackwardsCompatibleFromTopLevelFields() throws Exception {
        List<Map<String, Object>> packages = upsPackageBlocks(baseRequest());
        assertEquals(1, packages.size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void upsMultiPackageEmitsOnePackageBlockPerEntry() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setPackages(List.of(
                pkg(1, "2.5", "02"),
                pkg(2, "3.0", "02"),
                pkg(3, "1.0", "01")));
        List<Map<String, Object>> packages = upsPackageBlocks(r);
        assertEquals(3, packages.size());
        // Per-package packaging type propagates.
        assertEquals("02", ((Map<String, Object>) packages.get(0).get("PackagingType")).get("Code"));
        assertEquals("01", ((Map<String, Object>) packages.get(2).get("PackagingType")).get("Code"));
        // Per-package weight propagates.
        assertEquals("3.0",
                ((Map<String, Object>) packages.get(1).get("PackageWeight")).get("Weight"));
    }

    /* -------------------------- FedEx -------------------------- */

    @SuppressWarnings("unchecked")
    private static Map<String, Object> fedexRequestedShipment(ShipmentRequestDTO r) throws Exception {
        FedExConnector c = new FedExConnector(new CarrierProperties(), new ObjectMapper(), noFx());
        Method m = FedExConnector.class.getDeclaredMethod("buildShipmentPayload", ShipmentRequestDTO.class);
        m.setAccessible(true);
        Map<String, Object> payload = (Map<String, Object>) m.invoke(c, r);
        return (Map<String, Object>) payload.get("requestedShipment");
    }

    @Test
    @SuppressWarnings("unchecked")
    void fedexSinglePackageEmitsTotalPackageCountOfOne() throws Exception {
        Map<String, Object> rs = fedexRequestedShipment(baseRequest());
        assertEquals("1", rs.get("totalPackageCount"));
        List<Map<String, Object>> items = (List<Map<String, Object>>) rs.get("requestedPackageLineItems");
        assertEquals(1, items.size());
        assertEquals("1", items.get(0).get("sequenceNumber"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void fedexMultiPackageIncrementsSequenceNumbersAndTotalCount() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setPackages(List.of(
                pkg(1, "2.5", null),
                pkg(2, "3.0", null),
                pkg(3, "1.0", null)));
        Map<String, Object> rs = fedexRequestedShipment(r);
        assertEquals("3", rs.get("totalPackageCount"),
                "FedEx requires totalPackageCount to match requestedPackageLineItems length");
        List<Map<String, Object>> items = (List<Map<String, Object>>) rs.get("requestedPackageLineItems");
        assertEquals(3, items.size());
        assertEquals("1", items.get(0).get("sequenceNumber"));
        assertEquals("2", items.get(1).get("sequenceNumber"));
        assertEquals("3", items.get(2).get("sequenceNumber"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void fedexDeclaredValueEmittedOnlyOnFirstPackageByDefault() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setDeclaredValue(new BigDecimal("500.00"));
        r.setPackages(List.of(pkg(1, "2.5", null), pkg(2, "3.0", null)));
        Map<String, Object> rs = fedexRequestedShipment(r);
        List<Map<String, Object>> items = (List<Map<String, Object>>) rs.get("requestedPackageLineItems");
        assertNotNull(items.get(0).get("declaredValue"),
                "Shipment-level declared value lands on package 1 when the per-package fields are null");
        assertNull(items.get(1).get("declaredValue"),
                "Subsequent packages get no declared value unless explicitly set on the package");
    }

    /* -------------------------- DHL -------------------------- */

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> dhlPackages(ShipmentRequestDTO r) throws Exception {
        DhlConnector c = new DhlConnector(new CarrierProperties(), new ObjectMapper());
        Method m = DhlConnector.class.getDeclaredMethod("buildShipmentPayload", ShipmentRequestDTO.class);
        m.setAccessible(true);
        Map<String, Object> payload = (Map<String, Object>) m.invoke(c, r);
        Map<String, Object> content = (Map<String, Object>) payload.get("content");
        return (List<Map<String, Object>>) content.get("packages");
    }

    @Test
    void dhlSinglePackageBackwardsCompatibleFromTopLevelFields() throws Exception {
        assertEquals(1, dhlPackages(baseRequest()).size());
    }

    @Test
    void dhlMultiPackageEmitsOneEntryPerBox() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setPackages(List.of(
                pkg(1, "2.5", "3BX"),
                pkg(2, "5.0", "5BX")));
        List<Map<String, Object>> packages = dhlPackages(r);
        assertEquals(2, packages.size());
        assertEquals("3BX", packages.get(0).get("typeCode"));
        assertEquals("5BX", packages.get(1).get("typeCode"));
        assertEquals(0, new BigDecimal("5.0").compareTo((BigDecimal) packages.get(1).get("weight")));
    }

    /* -------------------------- USPS / Stamps -------------------------- */

    private static String stampsCreateIndicium(ShipmentRequestDTO r) throws Exception {
        StampsConnector c = new StampsConnector(new CarrierProperties(), new ObjectMapper());
        Method m = StampsConnector.class.getDeclaredMethod("buildCreateIndiciumEnvelope",
                ShipmentRequestDTO.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(c, r, "AUTH-XYZ");
    }

    @Test
    void stampsSinglePackageEmitsFirstPackageWeight() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setServiceType("USPS GA");
        r.setPackageType("Package");
        String xml = stampsCreateIndicium(r);
        // 2.5 LB → 40 OZ
        assertTrue(xml.contains("<WeightOz>40"),
                "Single-package baseline should still emit WeightOz correctly; got: " + xml);
    }

    @Test
    void stampsMultiPackageEmitsOnlyFirstPackageAndLogsWarning() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setServiceType("USPS GA");
        r.setPackageType("Package");
        r.setPackages(List.of(
                pkg(1, "2.5", "Package"),
                pkg(2, "3.0", "Package")));
        String xml = stampsCreateIndicium(r);
        // Only the first package's weight (2.5 LB → 40 OZ) should be on the wire.
        assertTrue(xml.contains("<WeightOz>40"),
                "Multi-package USPS should still emit the first package's weight; got: " + xml);
        // The second package's weight (3.0 LB → 48 OZ) must NOT be on the wire.
        assertFalse(xml.contains("<WeightOz>48"),
                "SWSIM is single-package per call; the second package must not appear on the wire");
    }

    private static void assertFalse(boolean condition, String message) {
        org.junit.jupiter.api.Assertions.assertFalse(condition, message);
    }

    private static void assertNull(Object obj, String message) {
        org.junit.jupiter.api.Assertions.assertNull(obj, message);
    }
}
