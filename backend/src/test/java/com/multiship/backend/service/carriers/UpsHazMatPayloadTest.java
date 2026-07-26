package com.multiship.backend.service.carriers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.dto.DangerousCommodityDTO;
import com.multiship.backend.dto.DangerousGoodsBlockDTO;
import com.multiship.backend.dto.ShipmentRequestDTO;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 26 — assert the UPS Ship API HazMat wire block lands on the
 * Package when a DG block is supplied and ready. Reflection into the
 * private payload builder so we don't need a live sandbox.
 */
class UpsHazMatPayloadTest {

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

    private static DangerousCommodityDTO lithiumBatteries() {
        return DangerousCommodityDTO.builder()
                .unNumber("UN3480")
                .properShippingName("Lithium ion batteries")
                .hazardClass("9")
                .packingGroup("II")
                .quantity(new BigDecimal("2.5"))
                .quantityUnit("KG")
                .packageCount(1)
                .build();
    }

    private static DangerousGoodsBlockDTO validBlock(List<DangerousCommodityDTO> commodities) {
        return DangerousGoodsBlockDTO.builder()
                .regulationSet("IATA")
                .accessibility("INACCESSIBLE")
                .emergencyContactName("Chem Response Ltd")
                .emergencyContactPhone("+1-800-424-9300")
                .signatoryName("Jane Doe")
                .signatoryTitle("Compliance Officer")
                .commodities(commodities)
                .build();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> upsPackage(ShipmentRequestDTO r) throws Exception {
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
    void nonHazmatShipmentOmitsHazMatBlock() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        assertNull(upsPackage(r).get("HazMatPackageInformation"));
    }

    @Test
    void incompleteHazmatBlockOmitsWireEmission() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        DangerousGoodsBlockDTO dg = validBlock(List.of(lithiumBatteries()));
        dg.setEmergencyContactPhone(null);  // Makes isReadyForCarrier false
        r.setDangerousGoods(dg);
        assertNull(upsPackage(r).get("HazMatPackageInformation"),
                "Wire emission gate is DG.isReadyForCarrier(); incomplete blocks stay off the wire");
    }

    @Test
    @SuppressWarnings("unchecked")
    void singleCommodityShipmentEmitsChemicalRecord() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setDangerousGoods(validBlock(List.of(lithiumBatteries())));

        Map<String, Object> pkg = upsPackage(r);
        Map<String, Object> hazmat = (Map<String, Object>) pkg.get("HazMatPackageInformation");
        assertNotNull(hazmat, "HazMatPackageInformation must be present when DG ready");

        // Single commodity → AllPackedInOneIndicator should NOT be present.
        assertNull(hazmat.get("AllPackedInOneIndicator"),
                "AllPackedInOneIndicator only emitted for multi-commodity shipments");

        List<Map<String, Object>> records = (List<Map<String, Object>>) hazmat.get("HazMatChemicalRecord");
        assertEquals(1, records.size());
        Map<String, Object> record = records.get(0);
        assertEquals("UN3480", record.get("IDNumber"));
        assertEquals("Lithium ion batteries", record.get("ProperShippingName"));
        assertEquals("9", record.get("ClassDivisionNumber"));
        assertEquals("II", record.get("PackagingGroupType"));
        assertEquals("Air", record.get("TransportationMode"),
                "IATA regulation → Air transport mode");
        assertEquals("IATA", record.get("RegulationSet"));
        assertEquals("+1-800-424-9300", record.get("EmergencyPhone"));
        assertEquals("Chem Response Ltd", record.get("EmergencyContact"));
        assertEquals("2.5", record.get("Quantity"));
        assertEquals("KG", record.get("UOM"));
        assertEquals("1", record.get("ChemicalRecordIdentifier"));
        assertEquals("1", record.get("PackagingTypeQuantity"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void multiCommodityShipmentEmitsAllPackedIndicator() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        DangerousCommodityDTO second = DangerousCommodityDTO.builder()
                .unNumber("UN1950").properShippingName("Aerosols")
                .hazardClass("2.1").quantity(new BigDecimal("0.5")).quantityUnit("L")
                .packageCount(1).build();
        r.setDangerousGoods(validBlock(List.of(lithiumBatteries(), second)));

        Map<String, Object> pkg = upsPackage(r);
        Map<String, Object> hazmat = (Map<String, Object>) pkg.get("HazMatPackageInformation");
        assertNotNull(hazmat);
        assertTrue(hazmat.containsKey("AllPackedInOneIndicator"),
                "Multi-commodity shipments must emit the all-packed indicator");

        List<Map<String, Object>> records = (List<Map<String, Object>>) hazmat.get("HazMatChemicalRecord");
        assertEquals(2, records.size());
        assertEquals("1", records.get(0).get("ChemicalRecordIdentifier"));
        assertEquals("2", records.get(1).get("ChemicalRecordIdentifier"));
        assertEquals("UN1950", records.get(1).get("IDNumber"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void groundRegulationMapsToGroundTransportMode() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        DangerousGoodsBlockDTO dg = validBlock(List.of(lithiumBatteries()));
        dg.setRegulationSet("DOT");
        r.setDangerousGoods(dg);

        Map<String, Object> pkg = upsPackage(r);
        Map<String, Object> hazmat = (Map<String, Object>) pkg.get("HazMatPackageInformation");
        List<Map<String, Object>> records = (List<Map<String, Object>>) hazmat.get("HazMatChemicalRecord");
        assertEquals("Ground", records.get(0).get("TransportationMode"));
        assertEquals("DOT", records.get(0).get("RegulationSet"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void classWithSubclassPropagatesToClassDivisionNumber() throws Exception {
        // Class 4.1 flammable solid — subclass form.
        DangerousCommodityDTO flammableSolid = DangerousCommodityDTO.builder()
                .unNumber("UN1325").properShippingName("Flammable solid, organic, n.o.s.")
                .hazardClass("4.1").packingGroup("II")
                .quantity(new BigDecimal("1")).quantityUnit("KG")
                .packageCount(1).build();
        ShipmentRequestDTO r = baseRequest();
        r.setDangerousGoods(validBlock(List.of(flammableSolid)));
        Map<String, Object> pkg = upsPackage(r);
        Map<String, Object> hazmat = (Map<String, Object>) pkg.get("HazMatPackageInformation");
        List<Map<String, Object>> records = (List<Map<String, Object>>) hazmat.get("HazMatChemicalRecord");
        assertEquals("4.1", records.get(0).get("ClassDivisionNumber"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void unNumberIsUppercasedOnTheWire() throws Exception {
        DangerousCommodityDTO c = lithiumBatteries();
        c.setUnNumber("un3480");
        ShipmentRequestDTO r = baseRequest();
        r.setDangerousGoods(validBlock(List.of(c)));
        Map<String, Object> pkg = upsPackage(r);
        Map<String, Object> hazmat = (Map<String, Object>) pkg.get("HazMatPackageInformation");
        List<Map<String, Object>> records = (List<Map<String, Object>>) hazmat.get("HazMatChemicalRecord");
        assertEquals("UN3480", records.get(0).get("IDNumber"),
                "UPS wire wants uppercase UN prefix — normalise before send");
    }
}
