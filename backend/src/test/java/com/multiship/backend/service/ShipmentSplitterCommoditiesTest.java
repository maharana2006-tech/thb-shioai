package com.multiship.backend.service;

import com.multiship.backend.dto.CustomsCommodityDTO;
import com.multiship.backend.dto.IntlShipmentBlockDTO;
import com.multiship.backend.dto.ShipmentRequestDTO;
import com.multiship.backend.exception.CommoditiesLimitExceededException;
import com.multiship.backend.model.CarrierShippingLimit;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Sprint 52 — the splitter's new commodities pre-flight. Commodities
 * are NOT split across sub-shipments; a shipment over the carrier cap
 * throws so callers surface a 422 to the operator instead of silently
 * generating a malformed multi-label shipment.
 */
class ShipmentSplitterCommoditiesTest {

    private final ShipmentSplitter splitter = new ShipmentSplitter();

    private static CarrierShippingLimit limitWith(String carrier, Integer maxCommodities) {
        return CarrierShippingLimit.builder()
                .carrierCode(carrier).scope("BOTH")
                .maxPackages(200).maxCommodities(maxCommodities)
                .effectiveFrom(LocalDateTime.now()).active(true).build();
    }

    private static ShipmentRequestDTO reqWithCommodities(int n) {
        List<CustomsCommodityDTO> lines = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            lines.add(CustomsCommodityDTO.builder()
                    .description("Widget " + i).hsCode("610462").countryOfOrigin("US")
                    .quantity(1).unitValue(new BigDecimal("10.00")).build());
        }
        return ShipmentRequestDTO.builder()
                .carrierCode("UPS")
                .intl(IntlShipmentBlockDTO.builder().international(true).commodities(lines).build())
                .build();
    }

    @Test
    void underCapPassesQuietly() {
        assertDoesNotThrow(() -> splitter.assertCommoditiesFit(
                reqWithCommodities(3), limitWith("UPS", 50)));
    }

    @Test
    void atCapPassesQuietly() {
        assertDoesNotThrow(() -> splitter.assertCommoditiesFit(
                reqWithCommodities(50), limitWith("UPS", 50)));
    }

    @Test
    void overCapThrowsCommoditiesLimitExceeded() {
        CommoditiesLimitExceededException ex = assertThrows(
                CommoditiesLimitExceededException.class,
                () -> splitter.assertCommoditiesFit(
                        reqWithCommodities(51), limitWith("UPS", 50)));
        assertEquals(51, ex.getActualCount());
        assertEquals(50, ex.getMaxAllowed());
        assertEquals("UPS", ex.getCarrierCode());
    }

    @Test
    void domesticShipmentIsNoOp() {
        ShipmentRequestDTO domestic = ShipmentRequestDTO.builder().carrierCode("UPS").build();
        assertDoesNotThrow(() -> splitter.assertCommoditiesFit(domestic, limitWith("UPS", 5)));
    }

    @Test
    void emptyCommoditiesIsNoOp() {
        ShipmentRequestDTO empty = ShipmentRequestDTO.builder()
                .carrierCode("UPS")
                .intl(IntlShipmentBlockDTO.builder().international(true).commodities(List.of()).build())
                .build();
        assertDoesNotThrow(() -> splitter.assertCommoditiesFit(empty, limitWith("UPS", 5)));
    }

    @Test
    void nullLimitTreatsAsUnbounded() {
        // Fallback safety — never block a shipment when the limit row
        // is missing (mirror of the resolver's fallback contract).
        assertDoesNotThrow(() -> splitter.assertCommoditiesFit(
                reqWithCommodities(5000), null));
    }

    @Test
    void limitWithNullMaxCommoditiesTreatsAsUnbounded() {
        // Pre-Sprint-52 row where max_commodities was NULL.
        CarrierShippingLimit legacy = limitWith("UPS", null);
        assertDoesNotThrow(() -> splitter.assertCommoditiesFit(
                reqWithCommodities(5000), legacy));
    }
}
