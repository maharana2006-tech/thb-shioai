package com.multiship.backend.service;

import com.multiship.backend.dto.ShipmentRequestDTO;
import com.multiship.backend.service.RateCacheService.CachedRates;
import com.multiship.backend.service.carriers.CarrierConnector.RateOption;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link RateCacheServiceImpl}. All in-memory; no
 * dependencies to mock.
 */
class RateCacheServiceImplTest {

    private RateCacheServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new RateCacheServiceImpl();
    }

    private static ShipmentRequestDTO baseRequest() {
        return ShipmentRequestDTO.builder()
                .carrierCode("UPS").accountNumber("A12345")
                .serviceType("03").packageType("02")
                .weight(new BigDecimal("2.5")).weightUnit("LB")
                .shipperCountryCode("US").shipperPostalCode("40209")
                .recipientCountryCode("US").recipientPostalCode("10001")
                .build();
    }

    private static RateOption option(String service, String amount) {
        return new RateOption("UPS", service, "UPS " + service,
                new BigDecimal(amount), "USD", null, 2);
    }

    /* -------------------------- Fingerprint -------------------------- */

    @Test
    void keyReturnsNullOnMissingCarrierOrRequest() {
        assertEquals(null, RateCacheServiceImpl.key(null, baseRequest()));
        assertEquals(null, RateCacheServiceImpl.key("", baseRequest()));
        assertEquals(null, RateCacheServiceImpl.key("UPS", null));
    }

    @Test
    void keyIsIdenticalForIdenticalShipments() {
        assertEquals(
                RateCacheServiceImpl.key("UPS", baseRequest()),
                RateCacheServiceImpl.key("UPS", baseRequest()));
    }

    @Test
    void keyDiffersByPricingRelevantFields() {
        ShipmentRequestDTO base = baseRequest();
        String baseKey = RateCacheServiceImpl.key("UPS", base);

        // Weight change → different key.
        ShipmentRequestDTO heavier = baseRequest();
        heavier.setWeight(new BigDecimal("5"));
        assertNotEquals(baseKey, RateCacheServiceImpl.key("UPS", heavier));

        // Destination postal → different key.
        ShipmentRequestDTO otherZip = baseRequest();
        otherZip.setRecipientPostalCode("94103");
        assertNotEquals(baseKey, RateCacheServiceImpl.key("UPS", otherZip));

        // Carrier change → different key.
        assertNotEquals(baseKey, RateCacheServiceImpl.key("FEDEX", base));

        // Residential flag → different key.
        ShipmentRequestDTO residential = baseRequest();
        residential.setRecipientResidential(true);
        assertNotEquals(baseKey, RateCacheServiceImpl.key("UPS", residential));
    }

    @Test
    void keyIgnoresNonPricingFields() {
        // Reference numbers, shipper name, phone — none of these change price.
        ShipmentRequestDTO withRef = baseRequest();
        withRef.setReferenceNumber("PO-999");
        withRef.setShipperName("Different Shipper");
        withRef.setShipperPhone("5559998888");
        assertEquals(RateCacheServiceImpl.key("UPS", baseRequest()),
                RateCacheServiceImpl.key("UPS", withRef));
    }

    @Test
    void keyIsCaseInsensitive() {
        // "ups" and "UPS" hit the same key; postal codes trimmed + uppercased.
        ShipmentRequestDTO lower = baseRequest();
        lower.setShipperCountryCode("us");
        lower.setRecipientCountryCode(" us ");
        assertEquals(RateCacheServiceImpl.key("UPS", baseRequest()),
                RateCacheServiceImpl.key("ups", lower));
    }

    /* -------------------------- lookup + store -------------------------- */

    @Test
    void lookupMissingReturnsEmpty() {
        assertTrue(service.lookup("UPS", baseRequest()).isEmpty());
        assertEquals(1L, service.stats().misses());
    }

    @Test
    void storeThenLookupHits() {
        service.store("UPS", baseRequest(),
                List.of(option("03", "10.00"), option("01", "42.00")));
        Optional<CachedRates> hit = service.lookup("UPS", baseRequest());
        assertTrue(hit.isPresent());
        assertEquals(2, hit.get().options().size());
        assertEquals(1L, service.stats().hits());
    }

    @Test
    void storeIsIgnoredForEmptyOptions() {
        service.store("UPS", baseRequest(), List.of());
        assertTrue(service.lookup("UPS", baseRequest()).isEmpty());
        assertEquals(0L, service.stats().stores(),
                "Empty results shouldn't populate the cache");
    }

    @Test
    void storeIsIgnoredForNullOptions() {
        service.store("UPS", baseRequest(), null);
        assertEquals(0L, service.stats().stores());
    }

    @Test
    void expiredEntryEvictedOnLookup() {
        // Backdoor an entry with an already-past expiry.
        String key = RateCacheServiceImpl.key("UPS", baseRequest());
        service.putRaw(key,
                List.of(option("03", "10.00")),
                LocalDateTime.now(ZoneOffset.UTC).minusMinutes(6),
                Instant.now().minusSeconds(1));
        assertTrue(service.lookup("UPS", baseRequest()).isEmpty(),
                "Expired entries should be treated as misses");
        // A second lookup should also miss (the entry was evicted).
        assertTrue(service.lookup("UPS", baseRequest()).isEmpty());
    }

    @Test
    void differentShipmentsDoNotShareCacheEntries() {
        service.store("UPS", baseRequest(),
                List.of(option("03", "10.00")));
        ShipmentRequestDTO different = baseRequest();
        different.setWeight(new BigDecimal("5"));
        assertTrue(service.lookup("UPS", different).isEmpty(),
                "Weight-different shipments must not share cache entries");
    }

    /* -------------------------- invalidation -------------------------- */

    @Test
    void invalidateAllRemovesEveryEntry() {
        service.store("UPS", baseRequest(), List.of(option("03", "10.00")));
        ShipmentRequestDTO fedexRequest = baseRequest();
        fedexRequest.setCarrierCode("FEDEX");
        service.store("FEDEX", fedexRequest, List.of(option("FEDEX_GROUND", "11.00")));

        int removed = service.invalidate(null);
        assertEquals(2, removed);
        assertTrue(service.lookup("UPS", baseRequest()).isEmpty());
        assertTrue(service.lookup("FEDEX", fedexRequest).isEmpty());
    }

    @Test
    void invalidateByCarrierScopesToPrefix() {
        service.store("UPS", baseRequest(), List.of(option("03", "10.00")));
        ShipmentRequestDTO fedexRequest = baseRequest();
        service.store("FEDEX", fedexRequest, List.of(option("FEDEX_GROUND", "11.00")));

        int removed = service.invalidate("UPS");
        assertEquals(1, removed);
        assertTrue(service.lookup("UPS", baseRequest()).isEmpty(), "UPS entry gone");
        // FedEx entry still present.
        assertTrue(service.lookup("FEDEX", fedexRequest).isPresent());
    }

    @Test
    void invalidateByCarrierIsCaseInsensitive() {
        service.store("UPS", baseRequest(), List.of(option("03", "10.00")));
        int removed = service.invalidate("ups");
        assertEquals(1, removed);
    }

    /* -------------------------- stats -------------------------- */

    @Test
    void statsCountersReflectActivity() {
        service.lookup("UPS", baseRequest());  // miss
        service.store("UPS", baseRequest(), List.of(option("03", "10.00")));  // store
        service.lookup("UPS", baseRequest());  // hit

        RateCacheService.CacheStats stats = service.stats();
        assertEquals(1, stats.size());
        assertEquals(1L, stats.hits());
        assertEquals(1L, stats.misses());
        assertEquals(1L, stats.stores());
    }

    /* -------------------------- CachedRates timestamps -------------------------- */

    @Test
    void cachedAtTimestampReflectsStoreTime() {
        LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1);
        service.store("UPS", baseRequest(), List.of(option("03", "10.00")));
        LocalDateTime after = LocalDateTime.now(ZoneOffset.UTC).plusSeconds(1);

        Optional<CachedRates> hit = service.lookup("UPS", baseRequest());
        assertTrue(hit.isPresent());
        LocalDateTime cachedAt = hit.get().cachedAt();
        assertTrue(cachedAt.isAfter(before), "cachedAt should be after " + before);
        assertTrue(cachedAt.isBefore(after), "cachedAt should be before " + after);
    }

    private static void assertNotEquals(Object a, Object b) {
        org.junit.jupiter.api.Assertions.assertNotEquals(a, b);
    }
}
