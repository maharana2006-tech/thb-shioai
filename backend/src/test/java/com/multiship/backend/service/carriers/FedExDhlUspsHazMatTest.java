package com.multiship.backend.service.carriers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.dto.DangerousCommodityDTO;
import com.multiship.backend.dto.DangerousGoodsBlockDTO;
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
 * Sprint 27 — DG wire emission on FedEx, DHL, and USPS/Stamps. One file
 * per the ResidentialAndPhoneTest / ReturnLabelTest precedent — future
 * reviewers get the full non-UPS DG matrix in one place.
 */
class FedExDhlUspsHazMatTest {

    private static FxRateService noFx() {
        return new FxRateService() {
            @Override public Optional<BigDecimal> rate(String f, String t) { return Optional.empty(); }
            @Override public Optional<BigDecimal> convert(BigDecimal a, String f, String t) { return Optional.empty(); }
            @Override public boolean supports(String c) { return false; }
        };
    }

    private static ShipmentRequestDTO baseRequest() {
        return ShipmentRequestDTO.builder()
                .carrierCode("FEDEX").accountNumber("A99999")
                .serviceType("FEDEX_GROUND").packageType("YOUR_PACKAGING")
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
                .unNumber("UN3480").properShippingName("Lithium ion batteries")
                .hazardClass("9").packingGroup("II")
                .quantity(new BigDecimal("2.5")).quantityUnit("KG")
                .packageCount(1).build();
    }

    private static DangerousGoodsBlockDTO validBlock(List<DangerousCommodityDTO> commodities) {
        return DangerousGoodsBlockDTO.builder()
                .regulationSet("IATA").accessibility("INACCESSIBLE")
                .emergencyContactName("Chem Response Ltd")
                .emergencyContactPhone("+1-800-424-9300")
                .signatoryName("Jane Doe").signatoryTitle("Compliance Officer")
                .commodities(commodities).build();
    }

    /* ==================== FedEx ==================== */

    @SuppressWarnings("unchecked")
    private static Map<String, Object> fedexRequestedShipment(ShipmentRequestDTO r) throws Exception {
        FedExConnector c = new FedExConnector(new CarrierProperties(), new ObjectMapper(), noFx());
        Method m = FedExConnector.class.getDeclaredMethod("buildShipmentPayload", ShipmentRequestDTO.class);
        m.setAccessible(true);
        Map<String, Object> payload = (Map<String, Object>) m.invoke(c, r);
        return (Map<String, Object>) payload.get("requestedShipment");
    }

    @Test
    void fedexNonHazmatOmitsShipmentSpecialServicesRequested() throws Exception {
        // No intl block AND no DG block → no shipmentSpecialServicesRequested.
        Map<String, Object> req = fedexRequestedShipment(baseRequest());
        assertNull(req.get("shipmentSpecialServicesRequested"),
                "Plain domestic non-hazmat: block should be absent to save bytes");
    }

    @Test
    @SuppressWarnings("unchecked")
    void fedexReadyDgEmitsDangerousGoodsDetail() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setDangerousGoods(validBlock(List.of(lithiumBatteries())));

        Map<String, Object> req = fedexRequestedShipment(r);
        Map<String, Object> specials = (Map<String, Object>) req.get("shipmentSpecialServicesRequested");
        assertNotNull(specials);

        List<String> types = (List<String>) specials.get("specialServiceTypes");
        assertTrue(types.contains("DANGEROUS_GOODS"),
                "specialServiceTypes must include DANGEROUS_GOODS");

        Map<String, Object> detail = (Map<String, Object>) specials.get("dangerousGoodsDetail");
        assertNotNull(detail);
        assertEquals("INACCESSIBLE", detail.get("accessibility"));

        Map<String, Object> signatory = (Map<String, Object>) detail.get("signatory");
        assertEquals("Jane Doe", signatory.get("contactName"));
        assertEquals("Compliance Officer", signatory.get("title"));

        Map<String, Object> emergency = (Map<String, Object>) detail.get("emergencyContactNumber");
        assertEquals("+1-800-424-9300", emergency.get("personalNumber"));

        List<Map<String, Object>> commodities = (List<Map<String, Object>>) detail.get("hazardousCommodities");
        assertEquals(1, commodities.size());
        Map<String, Object> description = (Map<String, Object>) commodities.get(0).get("description");
        assertEquals("UN3480", description.get("id"));
        assertEquals("II", description.get("packingGroup"));
        assertEquals("9", description.get("hazardClass"));
        assertEquals("Lithium ion batteries", description.get("properShippingName"));

        Map<String, Object> qty = (Map<String, Object>) commodities.get(0).get("quantity");
        assertEquals("kg", qty.get("units"),
                "FedEx wants lowercase units on the wire");
        List<Map<String, Object>> receptacles = (List<Map<String, Object>>) commodities.get(0).get("innerReceptacles");
        assertEquals(1, receptacles.size(), "Every commodity needs an innerReceptacle entry");
    }

    @Test
    @SuppressWarnings("unchecked")
    void fedexEtdAndDgCoexistUnderOneSpecialServicesBlock() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setRecipientCountryCode("GB");  // Trigger int'l → ETD
        r.setDangerousGoods(validBlock(List.of(lithiumBatteries())));
        // Add a minimal intl block so isReadyForCarrier() passes ETD gate.
        com.multiship.backend.dto.IntlShipmentBlockDTO intl =
                com.multiship.backend.dto.IntlShipmentBlockDTO.builder()
                        .incoterms("DDP").customsCurrency("USD")
                        .reasonForExport("SALE")
                        .commodities(List.of(com.multiship.backend.dto.CustomsCommodityDTO.builder()
                                .description("Widget").hsCode("847130").quantity(1)
                                .unitValue(new BigDecimal("100"))
                                .countryOfOrigin("US").build()))
                        .build();
        r.setIntl(intl);

        Map<String, Object> req = fedexRequestedShipment(r);
        Map<String, Object> specials = (Map<String, Object>) req.get("shipmentSpecialServicesRequested");
        assertNotNull(specials);
        List<String> types = (List<String>) specials.get("specialServiceTypes");
        assertTrue(types.contains("ELECTRONIC_TRADE_DOCUMENTS"));
        assertTrue(types.contains("DANGEROUS_GOODS"));
        assertNotNull(specials.get("etdDetail"));
        assertNotNull(specials.get("dangerousGoodsDetail"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void fedexUnNumberIsUppercasedOnTheWire() throws Exception {
        DangerousCommodityDTO c = lithiumBatteries();
        c.setUnNumber("un3480");
        ShipmentRequestDTO r = baseRequest();
        r.setDangerousGoods(validBlock(List.of(c)));
        Map<String, Object> req = fedexRequestedShipment(r);
        Map<String, Object> detail = (Map<String, Object>) ((Map<String, Object>)
                req.get("shipmentSpecialServicesRequested")).get("dangerousGoodsDetail");
        List<Map<String, Object>> commodities = (List<Map<String, Object>>) detail.get("hazardousCommodities");
        assertEquals("UN3480",
                ((Map<String, Object>) commodities.get(0).get("description")).get("id"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void fedexCargoAircraftOnlyPropagates() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        DangerousGoodsBlockDTO dg = validBlock(List.of(lithiumBatteries()));
        dg.setCargoAircraftOnly(true);
        r.setDangerousGoods(dg);
        Map<String, Object> req = fedexRequestedShipment(r);
        Map<String, Object> detail = (Map<String, Object>) ((Map<String, Object>)
                req.get("shipmentSpecialServicesRequested")).get("dangerousGoodsDetail");
        assertEquals(Boolean.TRUE, detail.get("cargoAircraftOnly"));
    }

    /* ==================== DHL ==================== */

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dhlContent(ShipmentRequestDTO r) throws Exception {
        DhlConnector c = new DhlConnector(new CarrierProperties(), new ObjectMapper());
        Method m = DhlConnector.class.getDeclaredMethod("buildShipmentPayload", ShipmentRequestDTO.class);
        m.setAccessible(true);
        Map<String, Object> payload = (Map<String, Object>) m.invoke(c, r);
        return (Map<String, Object>) payload.get("content");
    }

    @Test
    void dhlNonHazmatOmitsDangerousGoodsArray() throws Exception {
        assertNull(dhlContent(baseRequest()).get("dangerousGoods"));
    }

    @Test
    void dhlIncompleteHazmatOmitsWireEmission() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        DangerousGoodsBlockDTO dg = validBlock(List.of(lithiumBatteries()));
        dg.setSignatoryName(null);
        r.setDangerousGoods(dg);
        assertNull(dhlContent(r).get("dangerousGoods"),
                "isReadyForCarrier gate must veto incomplete blocks");
    }

    @Test
    @SuppressWarnings("unchecked")
    void dhlReadyDgEmitsDangerousGoodsArrayWithStrippedUnCode() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setDangerousGoods(validBlock(List.of(lithiumBatteries())));

        Map<String, Object> content = dhlContent(r);
        List<Map<String, Object>> dg = (List<Map<String, Object>>) content.get("dangerousGoods");
        assertNotNull(dg);
        assertEquals(1, dg.size());

        Map<String, Object> entry = dg.get(0);
        assertEquals("1", entry.get("contentId"));
        // DHL wants digits only — "3480" not "UN3480".
        assertEquals("3480", entry.get("unCode"));
        assertEquals("Lithium ion batteries", entry.get("properShippingName"));
        assertEquals("9", entry.get("hazardClass"));
        // Note: DHL spells this differently than FedEx — packagingGroup, not packingGroup.
        assertEquals("II", entry.get("packagingGroup"));
        assertFalse(entry.containsKey("packingGroup"),
                "DHL wire wants 'packagingGroup', not the FedEx 'packingGroup' spelling");

        Map<String, Object> netWeight = (Map<String, Object>) entry.get("netWeight");
        // DHL DG block is KG-only regardless of caller unit.
        assertEquals("KG", netWeight.get("unit"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void dhlMultiCommodityAssignsSequentialContentIds() throws Exception {
        DangerousCommodityDTO aerosol = DangerousCommodityDTO.builder()
                .unNumber("UN1950").properShippingName("Aerosols")
                .hazardClass("2.1").quantity(new BigDecimal("0.5")).quantityUnit("L")
                .packageCount(1).build();
        ShipmentRequestDTO r = baseRequest();
        r.setDangerousGoods(validBlock(List.of(lithiumBatteries(), aerosol)));

        List<Map<String, Object>> dg = (List<Map<String, Object>>) dhlContent(r).get("dangerousGoods");
        assertEquals(2, dg.size());
        assertEquals("1", dg.get(0).get("contentId"));
        assertEquals("2", dg.get(1).get("contentId"));
        assertEquals("1950", dg.get(1).get("unCode"));
    }

    /* ==================== USPS / Stamps SWSIM ==================== */

    private String stampsEnvelope(ShipmentRequestDTO r) throws Exception {
        StampsConnector c = new StampsConnector(new CarrierProperties(), new ObjectMapper());
        Method m = StampsConnector.class.getDeclaredMethod("buildCreateIndiciumEnvelope",
                ShipmentRequestDTO.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(c, r, "AUTH-XYZ");
    }

    @Test
    void stampsNonHazmatOmitsHazardousMaterialsFlag() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setServiceType("USPS GA");
        r.setPackageType("Package");
        assertFalse(stampsEnvelope(r).contains("<HazardousMaterials"));
    }

    @Test
    void stampsIncompleteHazmatOmitsFlag() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setServiceType("USPS GA");
        r.setPackageType("Package");
        DangerousGoodsBlockDTO dg = validBlock(List.of(lithiumBatteries()));
        dg.setEmergencyContactPhone(null);
        r.setDangerousGoods(dg);
        assertFalse(stampsEnvelope(r).contains("<HazardousMaterials"),
                "isReadyForCarrier gate keeps the SWSIM flag off the wire");
    }

    @Test
    void stampsReadyHazmatEmitsFlagInsideRateBlock() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setServiceType("USPS GA");
        r.setPackageType("Package");
        r.setDangerousGoods(validBlock(List.of(lithiumBatteries())));
        String xml = stampsEnvelope(r);
        assertTrue(xml.contains("<HazardousMaterials>true</HazardousMaterials>"),
                "Expected <HazardousMaterials> inside the Rate block; got: " + xml);
        // Sanity — flag must be INSIDE the Rate block, not floating at the top.
        int rateOpen = xml.indexOf("<Rate>");
        int rateClose = xml.indexOf("</Rate>");
        int flagAt = xml.indexOf("<HazardousMaterials>");
        assertTrue(flagAt > rateOpen && flagAt < rateClose,
                "HazardousMaterials flag must live inside <Rate>...</Rate>");
    }
}
