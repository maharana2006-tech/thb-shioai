package com.multiship.backend.service.carriers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.dto.CustomsCommodityDTO;
import com.multiship.backend.dto.IntlShipmentBlockDTO;
import com.multiship.backend.dto.ShipmentRequestDTO;
import com.multiship.backend.service.fx.FxRateService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 5.5 tests — FedExConnector's IOSS threshold now goes through
 * {@link FxRateService} first, falling back to the fixed local table only
 * when FX is unavailable. This class covers the LIVE-FX code path
 * specifically; FedExConnectorPayloadTest covers the fixed-table fallback
 * (via a stub FxRateService that always returns empty).
 */
class FedExConnectorFxIossTest {

    /** Programmable FX stub — takes an EUR→X rate table and inverts on demand. */
    private static FxRateService fxWith(Map<String, BigDecimal> eurToX) {
        return new FxRateService() {
            @Override
            public Optional<BigDecimal> rate(String from, String to) {
                if (from == null || to == null) return Optional.empty();
                String f = from.trim().toUpperCase();
                String t = to.trim().toUpperCase();
                if (f.equals(t)) return Optional.of(BigDecimal.ONE);
                BigDecimal fRate = "EUR".equals(f) ? BigDecimal.ONE : eurToX.get(f);
                BigDecimal tRate = "EUR".equals(t) ? BigDecimal.ONE : eurToX.get(t);
                if (fRate == null || tRate == null) return Optional.empty();
                return Optional.of(tRate.divide(fRate, 8, java.math.RoundingMode.HALF_UP));
            }
            @Override
            public Optional<BigDecimal> convert(BigDecimal amount, String from, String to) {
                if (amount == null) return Optional.empty();
                return rate(from, to).map(r -> amount.multiply(r).setScale(2, java.math.RoundingMode.HALF_UP));
            }
            @Override
            public boolean supports(String currency) {
                if (currency == null) return false;
                String c = currency.trim().toUpperCase();
                return "EUR".equals(c) || eurToX.containsKey(c);
            }
        };
    }

    private static FedExConnector connectorWith(FxRateService fx) {
        CarrierProperties props = new CarrierProperties();
        props.getFedEx().setLabelResponseOption("URL_ONLY");
        return new FedExConnector(props, new ObjectMapper(), fx);
    }

    private static Method buildShipmentPayloadMethod() throws Exception {
        Method m = FedExConnector.class.getDeclaredMethod("buildShipmentPayload", ShipmentRequestDTO.class);
        m.setAccessible(true);
        return m;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> build(FedExConnector c, ShipmentRequestDTO r) throws Exception {
        return (Map<String, Object>) buildShipmentPayloadMethod().invoke(c, r);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> shipperTins(FedExConnector c, ShipmentRequestDTO r) throws Exception {
        Map<String, Object> rs = (Map<String, Object>) build(c, r).get("requestedShipment");
        Map<String, Object> shipper = (Map<String, Object>) rs.get("shipper");
        return (List<Map<String, Object>>) shipper.get("tins");
    }

    private static ShipmentRequestDTO baseRequest() {
        return ShipmentRequestDTO.builder()
                .carrierCode("FEDEX")
                .accountNumber("A99999")
                .serviceType("INTERNATIONAL_PRIORITY")
                .packageType("YOUR_PACKAGING")
                .weight(new BigDecimal("2.5"))
                .weightUnit("LB")
                .shipperName("Acme").shipperPhone("5551234567")
                .shipperAddressLine1("1 Way").shipperCity("Louisville")
                .shipperState("KY").shipperPostalCode("40209").shipperCountryCode("US")
                .recipientName("Jane").recipientPhone("5559876543")
                .recipientAddressLine1("1 Straße").recipientCity("Berlin")
                .recipientState("").recipientPostalCode("10115").recipientCountryCode("DE")
                .referenceNumber("PO-1")
                .declaredValue(new BigDecimal("120.00"))
                .declaredValueCurrency("USD")
                .build();
    }

    private static IntlShipmentBlockDTO iossCandidate(String currency, BigDecimal total, String iossNumber) {
        return IntlShipmentBlockDTO.builder()
                .international(true).incoterms("DDP")
                .customsCurrency(currency).customsTotalValue(total)
                .reasonForExport("SALE").weightUnit("KG")
                .importerIoss(iossNumber)
                .commodities(List.of(CustomsCommodityDTO.builder()
                        .description("Widget").hsCode("6104.62.20").countryOfOrigin("US")
                        .quantity(1).unitValue(total).build()))
                .build();
    }

    /** 1 EUR = 1.08 USD → 120 USD = 111.11 EUR, well under €150. */
    @Test
    void liveFxKeepsIossOnBelowThresholdInUsd() throws Exception {
        FedExConnector c = connectorWith(fxWith(Map.of("USD", new BigDecimal("1.08"))));
        ShipmentRequestDTO r = baseRequest();
        r.setIntl(iossCandidate("USD", new BigDecimal("120.00"), "IM3702000001"));
        List<Map<String, Object>> tins = shipperTins(c, r);
        assertNotNull(tins);
        assertTrue(tins.stream().anyMatch(t -> "IOSS".equals(t.get("tinType"))));
    }

    /** 200 USD = 185.19 EUR — above €150 even after conversion. */
    @Test
    void liveFxSuppressesIossAboveThresholdInUsd() throws Exception {
        FedExConnector c = connectorWith(fxWith(Map.of("USD", new BigDecimal("1.08"))));
        ShipmentRequestDTO r = baseRequest();
        r.setIntl(iossCandidate("USD", new BigDecimal("200.00"), "IM3702000001"));
        List<Map<String, Object>> tins = shipperTins(c, r);
        assertFalse(tins != null && tins.stream().anyMatch(t -> "IOSS".equals(t.get("tinType"))));
    }

    /** 10000 JPY at 165 EUR/JPY = 60.61 EUR — under €150. Fixed table
     *  doesn't have JPY, so this only passes via live FX. */
    @Test
    void liveFxCoversCurrenciesFixedTableDoesnt() throws Exception {
        FedExConnector c = connectorWith(fxWith(Map.of("JPY", new BigDecimal("165.00"))));
        ShipmentRequestDTO r = baseRequest();
        r.setIntl(iossCandidate("JPY", new BigDecimal("10000"), "IM3702000001"));
        List<Map<String, Object>> tins = shipperTins(c, r);
        assertNotNull(tins);
        assertTrue(tins.stream().anyMatch(t -> "IOSS".equals(t.get("tinType"))),
                "JPY isn't in the fixed table — live FX should still gate correctly");
    }

    /** FX returns empty (feed outage or unknown currency) → falls back to
     *  the fixed table. USD 120 vs fixed $165 → below → IOSS applies. */
    @Test
    void fxOutageFallsBackToFixedTable() throws Exception {
        FxRateService silent = new FxRateService() {
            @Override public Optional<BigDecimal> rate(String f, String t) { return Optional.empty(); }
            @Override public Optional<BigDecimal> convert(BigDecimal a, String f, String t) { return Optional.empty(); }
            @Override public boolean supports(String currency) { return false; }
        };
        FedExConnector c = connectorWith(silent);
        ShipmentRequestDTO r = baseRequest();
        r.setIntl(iossCandidate("USD", new BigDecimal("120.00"), "IM3702000001"));
        List<Map<String, Object>> tins = shipperTins(c, r);
        assertNotNull(tins);
        assertTrue(tins.stream().anyMatch(t -> "IOSS".equals(t.get("tinType"))),
                "Fixed table should keep IOSS on when live FX is down and value < table threshold");
    }

    /** Non-EU destination → IOSS never emits, regardless of FX. */
    @Test
    void nonEuDestinationSuppressesIossEvenWithFx() throws Exception {
        FedExConnector c = connectorWith(fxWith(Map.of("USD", new BigDecimal("1.08"))));
        ShipmentRequestDTO r = baseRequest();
        r.setRecipientCountryCode("GB"); // post-Brexit, not in EU territory
        r.setIntl(iossCandidate("USD", new BigDecimal("50.00"), "IM3702000001"));
        List<Map<String, Object>> tins = shipperTins(c, r);
        assertFalse(tins != null && tins.stream().anyMatch(t -> "IOSS".equals(t.get("tinType"))));
    }
}
