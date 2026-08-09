package com.multiship.backend.service.carriers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.dto.PackageDetailDTO;
import com.multiship.backend.dto.ShipmentRequestDTO;
import com.multiship.backend.service.fx.FxRateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 48 B3 — verifies the /rate/v1/rates/quotes body includes one
 * requestedPackageLineItems entry per package (was hardcoded to a
 * single-item list, causing 100-box shipments to be quoted as 1 box).
 */
class FedExRateShopMultiPackageTest {

    private FedExConnector connector;

    @BeforeEach
    void setUp() {
        CarrierProperties props = new CarrierProperties();
        props.getFedEx().setLabelResponseOption("URL_ONLY");
        FxRateService noFx = new FxRateService() {
            @Override public Optional<BigDecimal> rate(String f, String t) { return Optional.empty(); }
            @Override public Optional<BigDecimal> convert(BigDecimal a, String f, String t) { return Optional.empty(); }
            @Override public boolean supports(String c) { return false; }
        };
        connector = new FedExConnector(props, new ObjectMapper(), noFx);
    }

    @Test
    void singlePackageFallbackYieldsOneLineItem() {
        ShipmentRequestDTO request = ShipmentRequestDTO.builder()
                .carrierCode("FEDEX").accountNumber("A99999")
                .serviceType("FEDEX_GROUND").packageType("YOUR_PACKAGING")
                .weight(new BigDecimal("5.0")).weightUnit("LB")
                .shipperPostalCode("38017").shipperCountryCode("US")
                .recipientPostalCode("28277").recipientCountryCode("US")
                .build();

        Map<String, Object> body = connector.buildRateRequestBody(request);
        @SuppressWarnings("unchecked")
        Map<String, Object> requestedShipment = (Map<String, Object>) body.get("requestedShipment");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) requestedShipment.get("requestedPackageLineItems");
        assertNotNull(items);
        assertEquals(1, items.size(), "single-pkg legacy path should emit exactly one line item");
        @SuppressWarnings("unchecked")
        Map<String, Object> weight = (Map<String, Object>) items.get(0).get("weight");
        assertEquals(new BigDecimal("5.0"), weight.get("value"));
    }

    @Test
    void threePackagesYieldThreeLineItemsWithMatchingWeights() {
        ShipmentRequestDTO request = ShipmentRequestDTO.builder()
                .carrierCode("FEDEX").accountNumber("A99999")
                .serviceType("FEDEX_GROUND").packageType("YOUR_PACKAGING")
                .weight(new BigDecimal("15.75")).weightUnit("LB")
                .shipperPostalCode("38017").shipperCountryCode("US")
                .recipientPostalCode("28277").recipientCountryCode("US")
                .packages(List.of(
                        PackageDetailDTO.builder().sequenceNumber(1).weight(new BigDecimal("4.5")).weightUnit("LB").build(),
                        PackageDetailDTO.builder().sequenceNumber(2).weight(new BigDecimal("8.75")).weightUnit("LB").build(),
                        PackageDetailDTO.builder().sequenceNumber(3).weight(new BigDecimal("2.5")).weightUnit("LB").build()))
                .build();

        Map<String, Object> body = connector.buildRateRequestBody(request);
        @SuppressWarnings("unchecked")
        Map<String, Object> requestedShipment = (Map<String, Object>) body.get("requestedShipment");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) requestedShipment.get("requestedPackageLineItems");
        assertEquals(3, items.size(), "3 packages → 3 line items (was 1 before B3)");
        assertEquals(new BigDecimal("4.5"), ((Map<?, ?>) items.get(0).get("weight")).get("value"));
        assertEquals(new BigDecimal("8.75"), ((Map<?, ?>) items.get(1).get("weight")).get("value"));
        assertEquals(new BigDecimal("2.5"), ((Map<?, ?>) items.get(2).get("weight")).get("value"));
    }

    @Test
    void dimensionsIncludedWhenPackageCarriesThem() {
        ShipmentRequestDTO request = ShipmentRequestDTO.builder()
                .carrierCode("FEDEX").accountNumber("A99999")
                .serviceType("FEDEX_GROUND").packageType("YOUR_PACKAGING")
                .weight(new BigDecimal("10")).weightUnit("LB")
                .shipperPostalCode("38017").shipperCountryCode("US")
                .recipientPostalCode("28277").recipientCountryCode("US")
                .packages(List.of(
                        PackageDetailDTO.builder().sequenceNumber(1)
                                .weight(new BigDecimal("10")).weightUnit("LB")
                                .length(new BigDecimal("12")).width(new BigDecimal("10"))
                                .height(new BigDecimal("8")).dimUnit("IN").build(),
                        PackageDetailDTO.builder().sequenceNumber(2)
                                .weight(new BigDecimal("2")).weightUnit("LB").build())) // no dims
                .build();

        Map<String, Object> body = connector.buildRateRequestBody(request);
        @SuppressWarnings("unchecked")
        Map<String, Object> requestedShipment = (Map<String, Object>) body.get("requestedShipment");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) requestedShipment.get("requestedPackageLineItems");
        assertTrue(items.get(0).containsKey("dimensions"), "pkg 1 has dims → included");
        assertFalse(items.get(1).containsKey("dimensions"), "pkg 2 has no dims → dimensions field skipped");
        @SuppressWarnings("unchecked")
        Map<String, Object> dims = (Map<String, Object>) items.get(0).get("dimensions");
        assertEquals(new BigDecimal("12"), dims.get("length"));
        assertEquals(new BigDecimal("10"), dims.get("width"));
        assertEquals(new BigDecimal("8"), dims.get("height"));
        assertEquals("IN", dims.get("units"));
    }
}
