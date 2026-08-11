package com.multiship.backend.service;

import com.multiship.backend.dto.RoutingEvaluationRequest;
import com.multiship.backend.dto.RoutingEvaluationResult;
import com.multiship.backend.dto.RoutingEvaluationResult.TraceEntry;
import com.multiship.backend.model.RoutingRule;
import com.multiship.backend.model.RoutingRule.ActionType;
import com.multiship.backend.model.ShippingService;
import com.multiship.backend.repository.RoutingRuleRepository;
import com.multiship.backend.repository.ShippingServiceRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class RoutingRuleServiceImpl implements RoutingRuleService {

    private final RoutingRuleRepository ruleRepo;
    private final ShippingServiceRepository serviceRepo;
    /**
     * Sprint 50 Tier 0.5 PR E - path-param clientCode uses Pattern B
     * (requireTenantMatch), rule.clientCode on save uses Pattern A
     * (clampClientCode). Optional so pre-PR-E tests that build the
     * service directly still compile.
     */
    @Autowired(required = false)
    private TenantScopeEnforcer tenantScope;

    @Autowired
    public RoutingRuleServiceImpl(RoutingRuleRepository ruleRepo,
                                  ShippingServiceRepository serviceRepo) {
        this.ruleRepo = ruleRepo;
        this.serviceRepo = serviceRepo;
    }

    // ===== CRUD =====

    @Override
    public List<RoutingRule> listForClient(String clientCode) {
        // Sprint 50 Tier 0.5 PR E - Pattern B: path-param clientCode is
        // the caller's assertion of the tenant they want. A scoped USER
        // hitting /clients/OTHER/routing-rules gets a clean 403.
        if (tenantScope != null) tenantScope.requireTenantMatch(safe(clientCode));
        return ruleRepo.findByClientCodeIgnoreCaseOrderByPriorityAscIdAsc(safe(clientCode));
    }

    @Override
    @Transactional
    public RoutingRule save(RoutingRule rule) {
        if (rule.getClientCode() == null || rule.getClientCode().isBlank()) {
            throw new IllegalArgumentException("clientCode is required");
        }
        // Sprint 50 Tier 0.5 PR E - Pattern A: clamp body clientCode so a
        // scoped USER cannot save a rule targeting a foreign tenant.
        if (tenantScope != null) rule.setClientCode(tenantScope.clampClientCode(rule.getClientCode()));
        if (rule.getName() == null || rule.getName().isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        if (rule.getActionType() == null) rule.setActionType(ActionType.REROUTE);
        if (rule.getActionType() == ActionType.REROUTE
                && rule.getTargetServiceId() == null
                && rule.getTargetWarehouseId() == null) {
            // G2: REROUTE now accepts service, warehouse, or both. At least one must be set
            // or the rule matches but does nothing.
            throw new IllegalArgumentException("REROUTE rules require targetServiceId, targetWarehouseId, or both");
        }
        if (rule.getActionType() == ActionType.BLOCK
                && (rule.getBlockReason() == null || rule.getBlockReason().isBlank())) {
            throw new IllegalArgumentException("BLOCK rules require blockReason");
        }
        if (rule.getActionType() == ActionType.BLOCK && rule.getTargetWarehouseId() != null) {
            // G2: BLOCK actions have no target — clear it so the DB stays honest.
            rule.setTargetWarehouseId(null);
        }
        LocalDateTime now = LocalDateTime.now();
        if (rule.getId() == null) rule.setCreatedAt(now);
        rule.setUpdatedAt(now);
        return ruleRepo.save(rule);
    }

    @Override
    @Transactional
    public void delete(String clientCode, Long ruleId) {
        // Sprint 50 Tier 0.5 PR E - Pattern B: reject before touching the
        // repository so a scoped USER hitting /clients/OTHER/routing-rules/N
        // gets a 403 rather than a silent 200-and-no-op.
        if (tenantScope != null) tenantScope.requireTenantMatch(safe(clientCode));
        ruleRepo.findById(ruleId)
                .filter(r -> safe(clientCode).equalsIgnoreCase(r.getClientCode()))
                .ifPresent(ruleRepo::delete);
    }

    // ===== Evaluation =====

    @Override
    @Transactional(readOnly = true)
    public RoutingEvaluationResult evaluate(String clientCode, RoutingEvaluationRequest req) {
        // Sprint 50 Tier 0.5 PR H - defence-in-depth belt: controller SpEL
        // from PR F already blocks a scoped USER at /clients/OTHER/routing/*,
        // but a future refactor could regress it — reject here too so the
        // service enforces tenant scope regardless of the caller path.
        if (tenantScope != null) tenantScope.requireTenantMatch(safe(clientCode));
        List<RoutingRule> rules = ruleRepo
                .findByClientCodeIgnoreCaseOrderByPriorityAscIdAsc(safe(clientCode));
        List<TraceEntry> trace = new ArrayList<>();

        for (RoutingRule rule : rules) {
            if (!Boolean.TRUE.equals(rule.getActive())) {
                trace.add(traceOf(rule, "SKIPPED_INACTIVE", "Rule is inactive"));
                continue;
            }
            String matchOutcome = matches(rule, req);
            if ("MATCHED".equals(matchOutcome)) {
                trace.add(traceOf(rule, "MATCHED", "Fired: all conditions passed"));
                return buildResult("MATCH", rule, trace);
            }
            trace.add(traceOf(rule, matchOutcome, "Skipped: " + matchOutcome));
        }
        return RoutingEvaluationResult.builder()
                .status("NO_MATCH").trace(trace).build();
    }

    /**
     * Returns "MATCHED" when the rule fires, or the name of the first
     * failing condition otherwise. Used both to decide the outcome and to
     * populate the trace with a specific miss reason.
     *
     * <p>Package-visible so unit tests can drive it directly.
     */
    static String matches(RoutingRule rule, RoutingEvaluationRequest req) {
        // Weight range
        if (rule.getMinWeightLb() != null) {
            if (req.getWeightLb() == null) return "WEIGHT_MISSING";
            if (req.getWeightLb().compareTo(rule.getMinWeightLb()) < 0) return "WEIGHT_BELOW_MIN";
        }
        if (rule.getMaxWeightLb() != null) {
            if (req.getWeightLb() == null) return "WEIGHT_MISSING";
            if (req.getWeightLb().compareTo(rule.getMaxWeightLb()) > 0) return "WEIGHT_ABOVE_MAX";
        }
        // Destination country
        if (rule.getDestCountries() != null && !rule.getDestCountries().isBlank()) {
            Set<String> allowed = csvSet(rule.getDestCountries());
            if (req.getDestCountry() == null || !allowed.contains(req.getDestCountry().toUpperCase())) {
                return "DEST_COUNTRY_MISMATCH";
            }
        }
        // Destination region — case-insensitive. csvSet uppercases the
        // rule values; audit-fix #3 aligns the request side to match so
        // frontend-taxonomy labels ("Europe", "North America") don't miss
        // the rule silently.
        if (rule.getDestRegions() != null && !rule.getDestRegions().isBlank()) {
            Set<String> allowed = csvSet(rule.getDestRegions());
            if (req.getDestRegion() == null || !allowed.contains(req.getDestRegion().toUpperCase())) {
                return "DEST_REGION_MISMATCH";
            }
        }
        // Current selection (carrier / service)
        if (rule.getMatchCarrier() != null
                && !rule.getMatchCarrier().equalsIgnoreCase(req.getCurrentCarrier())) {
            return "CARRIER_MISMATCH";
        }
        if (rule.getMatchServiceId() != null
                && !rule.getMatchServiceId().equals(req.getCurrentServiceId())) {
            return "SERVICE_MISMATCH";
        }
        // Declared value range
        if (rule.getMinDeclaredValue() != null) {
            if (req.getDeclaredValue() == null) return "DECLARED_VALUE_MISSING";
            if (req.getDeclaredValue().compareTo(rule.getMinDeclaredValue()) < 0) {
                return "DECLARED_VALUE_BELOW_MIN";
            }
        }
        if (rule.getMaxDeclaredValue() != null) {
            if (req.getDeclaredValue() == null) return "DECLARED_VALUE_MISSING";
            if (req.getDeclaredValue().compareTo(rule.getMaxDeclaredValue()) > 0) {
                return "DECLARED_VALUE_ABOVE_MAX";
            }
        }
        // Order source
        if (rule.getMatchOrderSource() != null
                && !rule.getMatchOrderSource().equalsIgnoreCase(req.getOrderSource())) {
            return "ORDER_SOURCE_MISMATCH";
        }
        // G2 — currently-resolved warehouse
        if (rule.getMatchWarehouseId() != null
                && !rule.getMatchWarehouseId().equals(req.getCurrentWarehouseId())) {
            return "WAREHOUSE_MISMATCH";
        }
        return "MATCHED";
    }

    private static Set<String> csvSet(String csv) {
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .map(String::toUpperCase)
                .collect(Collectors.toCollection(HashSet::new));
    }

    private RoutingEvaluationResult buildResult(String status, RoutingRule rule, List<TraceEntry> trace) {
        RoutingEvaluationResult.RoutingEvaluationResultBuilder b = RoutingEvaluationResult.builder()
                .status(status)
                .matchedRuleId(rule.getId())
                .matchedRuleName(rule.getName())
                .actionType(rule.getActionType())
                .trace(trace);
        if (rule.getActionType() == ActionType.REROUTE) {
            b.targetServiceId(rule.getTargetServiceId());
            b.targetWarehouseId(rule.getTargetWarehouseId());
            if (rule.getTargetServiceId() != null) {
                serviceRepo.findById(rule.getTargetServiceId())
                        .map(ShippingService::getCarrier)
                        .ifPresent(b::targetCarrier);
            }
        } else if (rule.getActionType() == ActionType.BLOCK) {
            b.blockReason(rule.getBlockReason());
        }
        return b.build();
    }

    private static TraceEntry traceOf(RoutingRule rule, String outcome, String note) {
        return TraceEntry.builder()
                .ruleId(rule.getId()).ruleName(rule.getName())
                .priority(rule.getPriority()).outcome(outcome).note(note)
                .build();
    }

    private static String safe(String v) {
        return v == null ? "" : v;
    }

    /** Helper for evaluator tests that don't need a Spring context. */
    static BigDecimal bd(String v) { return new BigDecimal(v); }
}
