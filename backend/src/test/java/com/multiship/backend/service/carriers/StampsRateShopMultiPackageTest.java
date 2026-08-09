package com.multiship.backend.service.carriers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.service.carriers.CarrierConnector.RateOption;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 48 B3 — Stamps GetRates is per-package; multi-package rate-shop
 * loops N calls and aggregates. This test drives the aggregator with
 * canned per-package rate lists.
 */
class StampsRateShopMultiPackageTest {

    private StampsConnector connector;

    @BeforeEach
    void setUp() {
        connector = new StampsConnector(new CarrierProperties(), new ObjectMapper());
    }

    private RateOption rate(String service, String amount) {
        return new RateOption("USPS", service, "USPS " + service,
                new BigDecimal(amount), "USD", null, null);
    }

    @Test
    void singlePackagePassesThroughUnchanged() {
        List<RateOption> aggregated = connector.aggregateStampsRates(List.of(
                List.of(rate("Priority", "8.50"), rate("First Class", "3.20"))));
        assertEquals(2, aggregated.size());
        assertEquals(new BigDecimal("8.50"), aggregated.get(0).totalAmount());
        assertEquals(new BigDecimal("3.20"), aggregated.get(1).totalAmount());
    }

    @Test
    void threePackagesSumPerServiceTotals() {
        // 3 packages, each with Priority + First Class quotes.
        List<RateOption> aggregated = connector.aggregateStampsRates(List.of(
                List.of(rate("Priority", "8.50"), rate("First Class", "3.20")),
                List.of(rate("Priority", "9.00"), rate("First Class", "3.20")),
                List.of(rate("Priority", "10.75"), rate("First Class", "3.60"))));

        assertEquals(2, aggregated.size());
        RateOption priority = aggregated.stream().filter(r -> r.serviceCode().equals("Priority")).findFirst().orElseThrow();
        RateOption first = aggregated.stream().filter(r -> r.serviceCode().equals("First Class")).findFirst().orElseThrow();
        assertEquals(new BigDecimal("28.25"), priority.totalAmount(), "8.50 + 9.00 + 10.75");
        assertEquals(new BigDecimal("10.00"), first.totalAmount(), "3.20 + 3.20 + 3.60");
    }

    @Test
    void serviceOfferedOnOnlySomePackagesIsDropped() {
        // pkg 2 doesn't have Priority quoted (e.g. over weight limit for Priority);
        // aggregated result should not include Priority — reporting it as if it
        // were the full shipment cost would misrepresent reality.
        List<RateOption> aggregated = connector.aggregateStampsRates(List.of(
                List.of(rate("Priority", "8.50"), rate("First Class", "3.20")),
                List.of(rate("First Class", "3.50")),
                List.of(rate("Priority", "10.75"), rate("First Class", "3.60"))));

        assertEquals(1, aggregated.size(), "Priority missing on pkg 2 → dropped from aggregate");
        assertEquals("First Class", aggregated.get(0).serviceCode());
        assertEquals(new BigDecimal("10.30"), aggregated.get(0).totalAmount(), "3.20 + 3.50 + 3.60");
    }

    @Test
    void emptyInputReturnsEmpty() {
        assertTrue(connector.aggregateStampsRates(List.of()).isEmpty());
    }

    @Test
    void allPackagesFailingReturnsEmpty() {
        // Every pkg returned empty (e.g. all SWSIM calls errored) — aggregate is empty.
        List<RateOption> aggregated = connector.aggregateStampsRates(List.of(
                List.of(), List.of(), List.of()));
        assertTrue(aggregated.isEmpty());
    }
}
