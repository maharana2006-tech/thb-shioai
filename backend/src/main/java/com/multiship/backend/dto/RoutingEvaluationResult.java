package com.multiship.backend.dto;

import com.multiship.backend.model.RoutingRule.ActionType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Sprint 44 — the outcome of running the ordered rule set for a client.
 *
 * <p>{@link #matchedRuleId} is the first rule that fired (null when no
 * rule matched — the caller keeps its current selection). {@link #trace}
 * gives a per-rule audit so the dry-run UI shows why later rules were
 * skipped ("rule 3 skipped: weight range" etc.).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoutingEvaluationResult {

    /** MATCH | NO_MATCH — did any active rule fire? */
    private String status;

    private Long matchedRuleId;
    private String matchedRuleName;

    /** REROUTE or BLOCK when status=MATCH; null otherwise. */
    private ActionType actionType;

    /** REROUTE target (mirrors RoutingRule.targetServiceId). Null when the
     *  rule only rewrites the warehouse. */
    private Long targetServiceId;
    /** Convenience: resolved carrier for the target service. */
    private String targetCarrier;

    /** G2 — REROUTE target warehouse. Null when the rule only rewrites the
     *  service (unchanged fulfilment location). */
    private Long targetWarehouseId;

    /** BLOCK reason. */
    private String blockReason;

    /** Per-rule trace: which rule matched and why the others didn't. */
    private List<TraceEntry> trace;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TraceEntry {
        private Long ruleId;
        private String ruleName;
        private Integer priority;
        /** MATCHED | SKIPPED_INACTIVE | condition name that failed (e.g. "WEIGHT_RANGE"). */
        private String outcome;
        /** Human-readable one-liner. */
        private String note;
    }
}
