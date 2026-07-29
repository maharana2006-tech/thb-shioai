package com.multiship.backend.service.fx;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the pure conversion logic of {@link EcbFxRateService} against a
 * canned snapshot injected via reflection so the network isn't touched.
 * The snapshot mirrors the ECB feed shape (EUR = base, other codes as
 * EUR→X rates).
 */
class EcbFxRateServiceTest {

    private EcbFxRateService svc;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void primeCacheWithFakeSnapshot() throws Exception {
        svc = new EcbFxRateService();

        // Build a Snapshot via the private inner class using reflection —
        // avoids exposing a package-private setter just for tests.
        Class<?> snapClass = Class.forName("com.multiship.backend.service.fx.EcbFxRateService$Snapshot");
        var ctor = snapClass.getDeclaredConstructor(Map.class, Instant.class);
        ctor.setAccessible(true);

        Map<String, BigDecimal> rates = new HashMap<>();
        // Rates chosen so cross-conversion math is easy to sanity-check:
        // 1 EUR = 1.08 USD, 1 EUR = 0.85 GBP
        rates.put("USD", new BigDecimal("1.08"));
        rates.put("GBP", new BigDecimal("0.85"));
        rates.put("JPY", new BigDecimal("165.00"));
        Object snapshot = ctor.newInstance(rates, Instant.now());

        Field cacheField = EcbFxRateService.class.getDeclaredField("cache");
        cacheField.setAccessible(true);
        AtomicReference<Object> ref = (AtomicReference<Object>) cacheField.get(svc);
        ref.set(snapshot);
    }

    @Test
    void supportsKnownCurrencies() {
        assertTrue(svc.supports("EUR"));
        assertTrue(svc.supports("USD"));
        assertTrue(svc.supports("gbp"));
    }

    @Test
    void supportsIsFalseForUnknownCurrency() {
        assertTrue(!svc.supports("XYZ"));
        assertTrue(!svc.supports(null));
    }

    @Test
    void identityConversionIsOne() {
        assertEquals(new BigDecimal("100.00"),
                svc.convert(new BigDecimal("100"), "EUR", "EUR").orElseThrow());
        assertEquals(new BigDecimal("50.00"),
                svc.convert(new BigDecimal("50"), "USD", "USD").orElseThrow());
    }

    @Test
    void eurToUsdUsesFeedRateDirectly() {
        // 100 EUR × 1.08 = 108.00 USD
        assertEquals(new BigDecimal("108.00"),
                svc.convert(new BigDecimal("100"), "EUR", "USD").orElseThrow());
    }

    @Test
    void usdToEurInvertsFeedRate() {
        // 108 USD ÷ 1.08 = 100.00 EUR
        assertEquals(new BigDecimal("100.00"),
                svc.convert(new BigDecimal("108"), "USD", "EUR").orElseThrow());
    }

    @Test
    void crossRateComposesTwoLegs() {
        // 100 USD → EUR → GBP: (100 / 1.08) × 0.85 = 78.7037 → 78.70
        Optional<BigDecimal> gbp = svc.convert(new BigDecimal("100"), "USD", "GBP");
        assertTrue(gbp.isPresent());
        assertEquals(new BigDecimal("78.70"), gbp.get());
    }

    @Test
    void unknownCurrencyReturnsEmpty() {
        assertTrue(svc.convert(new BigDecimal("100"), "USD", "XYZ").isEmpty());
        assertTrue(svc.convert(new BigDecimal("100"), "XYZ", "USD").isEmpty());
        assertTrue(svc.rate("XYZ", "USD").isEmpty());
    }

    @Test
    void nullInputsSafe() {
        assertTrue(svc.convert(null, "USD", "EUR").isEmpty());
        assertTrue(svc.rate(null, "USD").isEmpty());
        assertTrue(svc.rate("USD", null).isEmpty());
    }
}
