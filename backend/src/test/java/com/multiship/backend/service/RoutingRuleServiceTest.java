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
        assertThrows(IllegalArgumentException.class, () -> service.save(r));
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
