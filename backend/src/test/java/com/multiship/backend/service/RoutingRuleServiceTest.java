package com.multiship.backend.service;

import com.multiship.backend.dto.RoutingEvaluationRequest;
import com.multiship.backend.dto.RoutingEvaluationResult;
import com.multiship.backend.model.RoutingRule;
import com.multiship.backend.model.RoutingRule.ActionType;
import com.multiship.backend.model.ShippingService;
import com.multiship.backend.repository.RoutingRuleRepository;
import com.multiship.backend.repository.ShippingServiceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Sprint 44 — routing rule engine. Covers per-condition matching, the
 * priority-first-match-wins invariant, empty-conditions catch-all,
 * REROUTE vs BLOCK actions, and the trace shape.
 */
class RoutingRuleServiceTest {

    private RoutingRuleRepository ruleRepo;
    private ShippingServiceRepository serviceRepo;
    private RoutingRuleServiceImpl service;

    @BeforeEach
    void setUp() {
        ruleRepo = mock(RoutingRuleRepository.class);
        serviceRepo = mock(ShippingServiceRepository.class);
        service = new RoutingRuleServiceImpl(ruleRepo, serviceRepo);
    }

    // ===== save() validation =====

    @Test
    void save_rejectsRerouteWithoutTarget() {
        RoutingRule r = base();
        r.setActionType(ActionType.REROUTE);
        r.setTargetServiceId(null);
        r.setTargetWarehouseId(null);
        assertThrows(IllegalArgumentException.class, () -> service.save(r));
    }

    @Test
    void save_acceptsRerouteWithOnlyTargetWarehouse() {
        // G2 — REROUTE may set only warehouse, only service, or both.
        RoutingRule r = base();
        r.setActionType(ActionType.REROUTE);
        r.setTargetServiceId(null);
        r.setTargetWarehouseId(7L);
        when(ruleRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        RoutingRule saved = service.save(r);
        assertEquals(7L, saved.getTargetWarehouseId());
    }

    @Test
    void save_blockClearsTargetWarehouseIfSet() {
        // G2 — BLOCK has no target, so the writer strips a stray target.
        RoutingRule r = base();
        r.setActionType(ActionType.BLOCK);
        r.setBlockReason("nope");
        r.setTargetWarehouseId(7L);
        when(ruleRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        RoutingRule saved = service.save(r);
        assertNull(saved.getTargetWarehouseId());
    }

    @Test
    void save_blockClearsBothTargetServiceIdAndWarehouseId() {
        // Audit B8 — pre-fix, save() only cleared targetWarehouseId when
        // switching REROUTE → BLOCK, leaving a stale targetServiceId. Now
        // both pointer fields are wiped so the DB stays honest.
        RoutingRule r = base();
        r.setActionType(ActionType.BLOCK);
        r.setBlockReason("weight over max");
        r.setTargetServiceId(42L);
        r.setTargetWarehouseId(9L);
        when(ruleRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        RoutingRule saved = service.save(r);
        assertNull(saved.getTargetServiceId(),  "BLOCK must clear targetServiceId");
        assertNull(saved.getTargetWarehouseId(), "BLOCK must clear targetWarehouseId");
    }

    @Test
    void delete_crossTenantRuleThrows() {
        // Audit B5 — pre-fix, deleting a rule id belonging to a different
        // client silently returned success. Now the service throws so the
        // controller can 400 with the actual reason.
        RoutingRule other = base();
        other.setId(77L);
        other.setClientCode("OTHER");
        when(ruleRepo.findById(77L)).thenReturn(Optional.of(other));

        assertThrows(IllegalArgumentException.class, () -> service.delete("MINE", 77L));
        verify(ruleRepo, never()).delete(any());
    }

    @Test
    void delete_missingRuleIsIdempotent() {
        // Audit B5 — a delete of a non-existent id remains idempotent
        // (200 / no-op), only cross-tenant is a hard reject.
        when(ruleRepo.findById(999L)).thenReturn(Optional.empty());
        assertDoesNotThrow(() -> service.delete("MINE", 999L));
        verify(ruleRepo, never()).delete(any());
    }

    @Test
    void save_rejectsBlockWithoutReason() {
        RoutingRule r = base();
        r.setActionType(ActionType.BLOCK);
        r.setBlockReason("   ");
        assertThrows(IllegalArgumentException.class, () -> service.save(r));
    }

    @Test
    void save_setsTimestampsAndDefaultsAction() {
        RoutingRule r = base();
        r.setActionType(null);
        r.setTargetServiceId(42L);
        when(ruleRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        RoutingRule saved = service.save(r);
        assertNotNull(saved.getCreatedAt());
        assertNotNull(saved.getUpdatedAt());
        assertEquals(ActionType.REROUTE, saved.getActionType());
    }

    // ===== Sprint 50 Tier 0.5 PR E: tenant-scope =====

    @Test
    void scopedUserCannotListForeignTenantRoutingRules() {
        // Arrange: put a scoped USER (ACME) in the security context.
        var authorities = List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER"));
        var principal = org.springframework.security.core.userdetails.User
                .withUsername("acmeuser").password("").authorities(authorities).build();
        var token = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                principal, null, authorities);
        token.setDetails(new com.multiship.backend.config.JwtAuthenticationFilter.AuthDetails("ACME"));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(token);
        try {
            RoutingRuleServiceImpl scopedService = new RoutingRuleServiceImpl(ruleRepo, serviceRepo);
            // Manually wire the (optional) enforcer via reflection to keep
            // pure Mockito style.
            java.lang.reflect.Field f = RoutingRuleServiceImpl.class.getDeclaredField("tenantScope");
            f.setAccessible(true);
            f.set(scopedService, new TenantScopeEnforcer(new com.multiship.backend.config.AccessScopePolicy(true)));

            assertThrows(org.springframework.security.access.AccessDeniedException.class,
                    () -> scopedService.listForClient("OTHER"));
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    // ===== matches() — per condition =====

    @Test
    void matches_weightRange() {
        RoutingRule r = base();
        r.setMinWeightLb(new BigDecimal("5"));
        r.setMaxWeightLb(new BigDecimal("10"));
        // Below min
        assertEquals("WEIGHT_BELOW_MIN",
                RoutingRuleServiceImpl.matches(r, req().weightLb(new BigDecimal("3")).build()));
        // Above max
        assertEquals("WEIGHT_ABOVE_MAX",
                RoutingRuleServiceImpl.matches(r, req().weightLb(new BigDecimal("12")).build()));
        // In range
        assertEquals("MATCHED",
                RoutingRuleServiceImpl.matches(r, req().weightLb(new BigDecimal("7")).build()));
        // Missing weight but weight-range condition set
        assertEquals("WEIGHT_MISSING",
                RoutingRuleServiceImpl.matches(r, req().build()));
    }

    @Test
    void matches_destCountry() {
        RoutingRule r = base();
        r.setDestCountries("DE, FR, IT");
        assertEquals("MATCHED",
                RoutingRuleServiceImpl.matches(r, req().destCountry("DE").build()));
        assertEquals("MATCHED",
                RoutingRuleServiceImpl.matches(r, req().destCountry("fr").build()),
                "case-insensitive");
        assertEquals("DEST_COUNTRY_MISMATCH",
                RoutingRuleServiceImpl.matches(r, req().destCountry("US").build()));
        assertEquals("DEST_COUNTRY_MISMATCH",
                RoutingRuleServiceImpl.matches(r, req().build()));
    }

    @Test
    void matches_destRegion() {
        RoutingRule r = base();
        r.setDestRegions("EUROPE");
        assertEquals("MATCHED",
                RoutingRuleServiceImpl.matches(r, req().destRegion("EUROPE").build()));
        assertEquals("DEST_REGION_MISMATCH",
                RoutingRuleServiceImpl.matches(r, req().destRegion("Asia").build()));
    }

    @Test
    void matches_currentCarrierAndService() {
        RoutingRule r = base();
        r.setMatchCarrier("UPS");
        r.setMatchServiceId(42L);
        assertEquals("CARRIER_MISMATCH",
                RoutingRuleServiceImpl.matches(r, req().currentCarrier("FEDEX").currentServiceId(42L).build()));
        assertEquals("SERVICE_MISMATCH",
                RoutingRuleServiceImpl.matches(r, req().currentCarrier("UPS").currentServiceId(99L).build()));
        assertEquals("MATCHED",
                RoutingRuleServiceImpl.matches(r, req().currentCarrier("ups").currentServiceId(42L).build()));
    }

    @Test
    void matches_declaredValueRange() {
        RoutingRule r = base();
        r.setMinDeclaredValue(new BigDecimal("100"));
        r.setMaxDeclaredValue(new BigDecimal("1000"));
        assertEquals("DECLARED_VALUE_BELOW_MIN",
                RoutingRuleServiceImpl.matches(r, req().declaredValue(new BigDecimal("50")).build()));
        assertEquals("DECLARED_VALUE_ABOVE_MAX",
                RoutingRuleServiceImpl.matches(r, req().declaredValue(new BigDecimal("5000")).build()));
        assertEquals("MATCHED",
                RoutingRuleServiceImpl.matches(r, req().declaredValue(new BigDecimal("500")).build()));
        assertEquals("DECLARED_VALUE_MISSING",
                RoutingRuleServiceImpl.matches(r, req().build()));
    }

    @Test
    void matches_orderSource() {
        RoutingRule r = base();
        r.setMatchOrderSource("ERP");
        assertEquals("ORDER_SOURCE_MISMATCH",
                RoutingRuleServiceImpl.matches(r, req().orderSource("MANUAL").build()));
        assertEquals("MATCHED",
                RoutingRuleServiceImpl.matches(r, req().orderSource("erp").build()));
    }

    @Test
    void matches_emptyConditionsAlwaysMatch() {
        RoutingRule r = base();  // no conditions set
        assertEquals("MATCHED",
                RoutingRuleServiceImpl.matches(r, req().build()));
    }

    // ===== evaluate() — priority + first-match =====

    @Test
    void evaluate_firstMatchWinsByPriority() {
        RoutingRule r1 = base(); r1.setId(1L); r1.setName("first"); r1.setPriority(10);
        r1.setMinWeightLb(new BigDecimal("5"));
        r1.setTargetServiceId(42L);

        RoutingRule r2 = base(); r2.setId(2L); r2.setName("second"); r2.setPriority(20);
        r2.setTargetServiceId(99L);  // catch-all fallback

        when(ruleRepo.findByClientCodeIgnoreCaseOrderByPriorityAscIdAsc("ARHDEV"))
                .thenReturn(List.of(r1, r2));
        when(serviceRepo.findById(42L))
                .thenReturn(Optional.of(svc("UPS", "03", "UPS Ground")));

        RoutingEvaluationResult result = service.evaluate("ARHDEV",
                req().weightLb(new BigDecimal("7")).build());

        assertEquals("MATCH", result.getStatus());
        assertEquals(1L, result.getMatchedRuleId());
        assertEquals(42L, result.getTargetServiceId());
        assertEquals("UPS", result.getTargetCarrier());
    }

    @Test
    void evaluate_skipsInactiveRules() {
        RoutingRule r1 = base(); r1.setId(1L); r1.setActive(false);
        r1.setTargetServiceId(42L);
        RoutingRule r2 = base(); r2.setId(2L);
        r2.setTargetServiceId(99L);

        when(ruleRepo.findByClientCodeIgnoreCaseOrderByPriorityAscIdAsc("ARHDEV"))
                .thenReturn(List.of(r1, r2));
        when(serviceRepo.findById(99L))
                .thenReturn(Optional.of(svc("FEDEX", "FEDEX_GROUND", "FedEx Ground")));

        RoutingEvaluationResult result = service.evaluate("ARHDEV", req().build());

        assertEquals(2L, result.getMatchedRuleId());
        // Trace shows the inactive rule was skipped
        assertTrue(result.getTrace().stream()
                .anyMatch(e -> e.getRuleId() == 1L && "SKIPPED_INACTIVE".equals(e.getOutcome())));
    }

    @Test
    void evaluate_noMatchReturnsNullResult() {
        RoutingRule r = base(); r.setId(1L);
        r.setMinWeightLb(new BigDecimal("100"));
        r.setTargetServiceId(42L);

        when(ruleRepo.findByClientCodeIgnoreCaseOrderByPriorityAscIdAsc("ARHDEV"))
                .thenReturn(List.of(r));

        RoutingEvaluationResult result = service.evaluate("ARHDEV",
                req().weightLb(new BigDecimal("5")).build());
        assertEquals("NO_MATCH", result.getStatus());
        assertNull(result.getMatchedRuleId());
        assertEquals(1, result.getTrace().size());
        assertEquals("WEIGHT_BELOW_MIN", result.getTrace().get(0).getOutcome());
    }

    // ===== Audit-fix #3 — null-safe carrier / source + destRegion case =====

    @Test
    void matches_destRegion_isCaseInsensitive() {
        // csvSet uppercases rule values, so the rule stores {"EUROPE"}.
        // Requests can arrive from the frontend with the taxonomy's
        // capitalised label ("Europe" per utils/countries.ts REGIONS).
        // Before the fix, the contains check was case-sensitive on the
        // request side — "Europe" never matched "EUROPE".
        RoutingRule r = base();
        r.setDestRegions("Europe");
        assertEquals("MATCHED",
                RoutingRuleServiceImpl.matches(r, req().destRegion("Europe").build()),
                "same-case region should match");
        assertEquals("MATCHED",
                RoutingRuleServiceImpl.matches(r, req().destRegion("europe").build()),
                "lowercase region should match");
        assertEquals("MATCHED",
                RoutingRuleServiceImpl.matches(r, req().destRegion("EUROPE").build()),
                "uppercase region should match");
    }

    @Test
    void matches_currentCarrierNull_returnsCarrierMismatchInsteadOfNPE() {
        // A rule that keys off matchCarrier must gracefully handle a
        // request without a currentCarrier — return CARRIER_MISMATCH
        // (rule can't fire without the fact it needs), not NPE.
        RoutingRule r = base();
        r.setMatchCarrier("UPS");
        assertEquals("CARRIER_MISMATCH",
                RoutingRuleServiceImpl.matches(r, req().build()));
    }

    @Test
    void matches_orderSourceNull_returnsSourceMismatchInsteadOfNPE() {
        RoutingRule r = base();
        r.setMatchOrderSource("ERP");
        assertEquals("ORDER_SOURCE_MISMATCH",
                RoutingRuleServiceImpl.matches(r, req().build()));
    }

    // ===== G2 — warehouse dimension =====

    @Test
    void matches_currentWarehouseId() {
        RoutingRule r = base();
        r.setMatchWarehouseId(42L);
        assertEquals("MATCHED",
                RoutingRuleServiceImpl.matches(r, req().currentWarehouseId(42L).build()));
        assertEquals("WAREHOUSE_MISMATCH",
                RoutingRuleServiceImpl.matches(r, req().currentWarehouseId(99L).build()));
        assertEquals("WAREHOUSE_MISMATCH",
                RoutingRuleServiceImpl.matches(r, req().build()),
                "null current warehouse fails a warehouse-scoped rule");
    }

    @Test
    void matches_noWarehouseConditionMatchesAny() {
        // Rule without matchWarehouseId doesn't care what warehouse the
        // request carries — backward-compat for pre-G2 rules.
        RoutingRule r = base();
        assertEquals("MATCHED",
                RoutingRuleServiceImpl.matches(r, req().currentWarehouseId(42L).build()));
        assertEquals("MATCHED",
                RoutingRuleServiceImpl.matches(r, req().build()));
    }

    @Test
    void evaluate_targetWarehouseIdOnResult() {
        RoutingRule r = base(); r.setId(1L);
        r.setTargetServiceId(null); // warehouse-only reroute
        r.setTargetWarehouseId(7L);
        when(ruleRepo.findByClientCodeIgnoreCaseOrderByPriorityAscIdAsc("ARHDEV"))
                .thenReturn(List.of(r));

        RoutingEvaluationResult result = service.evaluate("ARHDEV", req().build());
        assertEquals("MATCH", result.getStatus());
        assertEquals(ActionType.REROUTE, result.getActionType());
        assertEquals(7L, result.getTargetWarehouseId());
        assertNull(result.getTargetServiceId());
    }

    @Test
    void evaluate_blockActionReturnsBlockReason() {
        RoutingRule r = base(); r.setId(1L);
        r.setActionType(ActionType.BLOCK);
        r.setBlockReason("Weight over carrier max");
        r.setMinWeightLb(new BigDecimal("30"));

        when(ruleRepo.findByClientCodeIgnoreCaseOrderByPriorityAscIdAsc("ARHDEV"))
                .thenReturn(List.of(r));

        RoutingEvaluationResult result = service.evaluate("ARHDEV",
                req().weightLb(new BigDecimal("50")).build());
        assertEquals("MATCH", result.getStatus());
        assertEquals(ActionType.BLOCK, result.getActionType());
        assertEquals("Weight over carrier max", result.getBlockReason());
        assertNull(result.getTargetServiceId(),
                "BLOCK actions don't reroute");
    }

    // ===== helpers =====

    private static RoutingRule base() {
        RoutingRule r = new RoutingRule();
        r.setClientCode("ARHDEV");
        r.setName("test rule");
        r.setPriority(100);
        r.setActive(true);
        r.setActionType(ActionType.REROUTE);
        r.setTargetServiceId(42L);
        return r;
    }

    private static RoutingEvaluationRequest.RoutingEvaluationRequestBuilder req() {
        return RoutingEvaluationRequest.builder();
    }

    private static ShippingService svc(String carrier, String code, String name) {
        return ShippingService.builder()
                .carrier(carrier)
                .serviceCode(code)
                .name(name)
                .scope("BOTH")
                .originCountry("US")
                .enabled(true)
                .sortOrder(0)
                .build();
    }
}
