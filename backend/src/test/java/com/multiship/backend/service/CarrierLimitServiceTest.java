package com.multiship.backend.service;

import com.multiship.backend.model.CarrierShippingLimit;
import com.multiship.backend.repository.CarrierShippingLimitRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Sprint 52 — direction-aware resolver + commodities cap. Fixture-driven:
 * every test seeds the repository stub with the specific rows the case
 * under test needs, then asserts resolveLimit picks the most-specific
 * match. Six tiers documented in {@link CarrierLimitService}; each is
 * exercised at least once.
 *
 * <p>Repository logic is stubbed with an in-memory list + the resolver's
 * documented ORDER BY as a Java comparator so we don't depend on JPA
 * behavior for this unit slice.
 */
class CarrierLimitServiceTest {

    private CarrierShippingLimitRepository repo;
    private CarrierLimitService service;
    private final List<CarrierShippingLimit> rows = new ArrayList<>();

    @BeforeEach
    void setUp() {
        rows.clear();
        repo = mock(CarrierShippingLimitRepository.class);
        // Stub findResolvableLimits by mirroring the JPQL predicate + the
        // ORDER BY tiering in Java. Keeps the test pure-unit while still
        // exercising the resolver's picking logic.
        when(repo.findResolvableLimits(anyString(), any(), anyString(), any()))
                .thenAnswer(inv -> filterAndSort(
                        inv.getArgument(0), inv.getArgument(1),
                        inv.getArgument(2), inv.getArgument(3)));
        // The default delegating overload the repository exposes.
        when(repo.findResolvableLimits(anyString(), any(), anyString()))
                .thenAnswer(inv -> filterAndSort(
                        inv.getArgument(0), inv.getArgument(1),
                        inv.getArgument(2), null));
        // The default resolve() calls findResolvableLimits under the hood.
        // Mockito-only fallback: Spring Data's default methods invoke through
        // the proxy in real usage, but here we call the interface method on
        // a mock, so we stub the default too.
        when(repo.resolve(anyString(), any(), anyString(), any()))
                .thenAnswer(inv -> {
                    List<CarrierShippingLimit> hits = filterAndSort(
                            inv.getArgument(0), inv.getArgument(1),
                            inv.getArgument(2), inv.getArgument(3));
                    return hits.isEmpty() ? java.util.Optional.empty()
                            : java.util.Optional.of(hits.get(0));
                });
        when(repo.resolve(anyString(), any(), anyString()))
                .thenAnswer(inv -> {
                    List<CarrierShippingLimit> hits = filterAndSort(
                            inv.getArgument(0), inv.getArgument(1),
                            inv.getArgument(2), null);
                    return hits.isEmpty() ? java.util.Optional.empty()
                            : java.util.Optional.of(hits.get(0));
                });
        service = new CarrierLimitService(repo);
    }

    private List<CarrierShippingLimit> filterAndSort(String carrier, String service, String scope, String direction) {
        String scopeUpper = scope == null ? "BOTH" : scope;
        return rows.stream()
                .filter(l -> l.getCarrierCode().equalsIgnoreCase(carrier))
                .filter(l -> service == null || l.getServiceCode() == null || service.equals(l.getServiceCode()))
                .filter(l -> l.getScope().equals(scopeUpper) || l.getScope().equals("BOTH"))
                .filter(l -> direction == null || l.getDirection() == null || direction.equals(l.getDirection()))
                .filter(l -> Boolean.TRUE.equals(l.getActive()))
                .sorted(Comparator
                        .<CarrierShippingLimit>comparingInt(l -> service != null && service.equals(l.getServiceCode()) ? 0 : 1)
                        .thenComparingInt(l -> scopeUpper.equals(l.getScope()) ? 0 : 1)
                        .thenComparingInt(l -> direction != null && direction.equals(l.getDirection()) ? 0
                                : (l.getDirection() == null ? 1 : 2))
                        .thenComparing(CarrierShippingLimit::getEffectiveFrom, Comparator.reverseOrder()))
                .toList();
    }

    private CarrierShippingLimit row(String carrier, String service, String scope, String direction,
                                     int maxPkg, int maxCommod) {
        CarrierShippingLimit r = CarrierShippingLimit.builder()
                .carrierCode(carrier).serviceCode(service).scope(scope).direction(direction)
                .maxPackages(maxPkg).maxCommodities(maxCommod)
                .maxTotalWeightLb(new BigDecimal("30000"))
                .effectiveFrom(LocalDateTime.now())
                .active(true)
                .build();
        rows.add(r);
        return r;
    }

    /* ---------------- 6-tier resolution order ---------------- */

    @Test
    void tier1_exactCarrierServiceScopeDirectionWins() {
        row("UPS", null, "BOTH", "FORWARD", 200, 50);
        row("UPS", "MAIL_INNOVATIONS", "BOTH", null, 1, 50);
        // Exact match: service + null direction still beats null-service rows.
        CarrierShippingLimit got = service.resolveLimit("UPS", "MAIL_INNOVATIONS", false, "FORWARD");
        assertEquals(1, got.getMaxPackages(), "service-scoped row should beat carrier-default");
    }

    @Test
    void tier2_exactCarrierServiceScopeNullDirection() {
        row("UPS", "MAIL_INNOVATIONS", "BOTH", null, 1, 50);
        CarrierShippingLimit got = service.resolveLimit("UPS", "MAIL_INNOVATIONS", false, "RETURN");
        assertEquals(1, got.getMaxPackages());
    }

    @Test
    void tier3_carrierNullServiceScopeDirection() {
        row("UPS", null, "BOTH", "FORWARD", 200, 50);
        row("UPS", null, "BOTH", "RETURN", 20, 50);
        CarrierShippingLimit forward = service.resolveLimit("UPS", null, false, "FORWARD");
        assertEquals(200, forward.getMaxPackages(), "forward direction should pick the 200-pkg row");
        CarrierShippingLimit ret = service.resolveLimit("UPS", null, false, "RETURN");
        assertEquals(20, ret.getMaxPackages(), "return direction should pick the 20-pkg row");
    }

    @Test
    void tier4_carrierNullServiceScopeNullDirection() {
        row("DHL", null, "BOTH", null, 999, 999);
        CarrierShippingLimit got = service.resolveLimit("DHL", null, true, "FORWARD");
        assertEquals(999, got.getMaxPackages());
        assertEquals(999, got.getMaxCommodities());
    }

    @Test
    void tier5_carrierScopeBothFallback() {
        // Ask for DOMESTIC but only BOTH exists.
        row("STAMPS", null, "BOTH", null, 1, 999);
        CarrierShippingLimit got = service.resolveLimit("STAMPS", null, false, "FORWARD");
        assertEquals(1, got.getMaxPackages());
    }

    @Test
    void tier6_syntheticFallbackWhenNoRow() {
        // No seeded rows for this carrier → synthesised default.
        CarrierShippingLimit got = service.resolveLimit("SOMETHING_NEW", null, false, "FORWARD");
        assertNotNull(got, "fallback must not return null");
        assertEquals(10_000, got.getMaxPackages(), "fallback packages default");
        assertEquals(999, got.getMaxCommodities(), "fallback commodities default");
    }

    /* ---------------- Carrier-specific spec verification ---------------- */

    @Test
    void upsForwardMps200_upsReturnMps20() {
        row("UPS", null, "BOTH", "FORWARD", 200, 50);
        row("UPS", null, "BOTH", "RETURN", 20, 50);
        assertEquals(200, service.resolveLimit("UPS", null, false, "FORWARD").getMaxPackages());
        assertEquals(20, service.resolveLimit("UPS", null, false, "RETURN").getMaxPackages());
    }

    @Test
    void upsMailInnovationsSinglePiece() {
        row("UPS", "MAIL_INNOVATIONS", "BOTH", null, 1, 50);
        assertEquals(1, service.resolveLimit("UPS", "MAIL_INNOVATIONS", false, "FORWARD").getMaxPackages());
    }

    @Test
    void upsSimpleRateSinglePiece() {
        row("UPS", "SIMPLE_RATE", "BOTH", null, 1, 50);
        assertEquals(1, service.resolveLimit("UPS", "SIMPLE_RATE", false, "FORWARD").getMaxPackages());
    }

    @Test
    void fedExDomestic40PkgsCommod999() {
        row("FEDEX", null, "DOMESTIC", null, 40, 999);
        CarrierShippingLimit got = service.resolveLimit("FEDEX", null, false, "FORWARD");
        assertEquals(40, got.getMaxPackages());
        assertEquals(999, got.getMaxCommodities());
    }

    @Test
    void fedExInternational25Pkgs() {
        row("FEDEX", null, "INTERNATIONAL", null, 25, 999);
        assertEquals(25, service.resolveLimit("FEDEX", null, true, "FORWARD").getMaxPackages());
    }

    @Test
    void dhlDefault999x999() {
        row("DHL", null, "BOTH", null, 999, 999);
        CarrierShippingLimit got = service.resolveLimit("DHL", null, true, null);
        assertEquals(999, got.getMaxPackages());
        assertEquals(999, got.getMaxCommodities());
    }

    @Test
    void stamps1Pkg999Commod() {
        row("STAMPS", null, "BOTH", null, 1, 999);
        CarrierShippingLimit got = service.resolveLimit("STAMPS", null, false, "FORWARD");
        assertEquals(1, got.getMaxPackages());
        assertEquals(999, got.getMaxCommodities());
    }

    /* ---------------- maxCommoditiesOf accessor ---------------- */

    @Test
    void maxCommoditiesOfHandlesNullLimit() {
        assertEquals(999, service.maxCommoditiesOf(null));
    }

    @Test
    void maxCommoditiesOfHandlesLimitWithNullField() {
        // Simulates a pre-Sprint-52 row where max_commodities was NULL.
        CarrierShippingLimit legacy = CarrierShippingLimit.builder()
                .carrierCode("UPS").scope("BOTH").maxPackages(200)
                .effectiveFrom(LocalDateTime.now()).active(true).build();
        assertEquals(999, service.maxCommoditiesOf(legacy));
    }

    @Test
    void maxCommoditiesOfReturnsFieldWhenSet() {
        CarrierShippingLimit row = CarrierShippingLimit.builder()
                .carrierCode("UPS").scope("BOTH").maxPackages(200).maxCommodities(50)
                .effectiveFrom(LocalDateTime.now()).active(true).build();
        assertEquals(50, service.maxCommoditiesOf(row));
    }

    /* ---------------- Legacy 3-arg overload ---------------- */

    @Test
    void legacyThreeArgOverloadDelegatesWithNullDirection() {
        row("UPS", null, "BOTH", null, 200, 50);
        CarrierShippingLimit got = service.resolveLimit("UPS", null, false);
        assertEquals(200, got.getMaxPackages());
    }
}
