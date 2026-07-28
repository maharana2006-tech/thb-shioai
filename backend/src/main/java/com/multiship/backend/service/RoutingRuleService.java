package com.multiship.backend.service;

import com.multiship.backend.dto.RoutingEvaluationRequest;
import com.multiship.backend.dto.RoutingEvaluationResult;
import com.multiship.backend.model.RoutingRule;

import java.util.List;

/**
 * Sprint 44 — per-client routing rule CRUD + evaluation.
 */
public interface RoutingRuleService {

    List<RoutingRule> listForClient(String clientCode);

    RoutingRule save(RoutingRule rule);

    void delete(String clientCode, Long ruleId);

    /**
     * Run the ordered active rule set for the client against the given
     * shipment / current selection. Returns the first-match result plus a
     * per-rule trace. When no rule matches, {@code status="NO_MATCH"} and
     * the caller keeps its selection.
     */
    RoutingEvaluationResult evaluate(String clientCode, RoutingEvaluationRequest request);
}
