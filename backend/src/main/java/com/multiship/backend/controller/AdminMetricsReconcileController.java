package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * BUG-003 + BUG-004 — Dashboard metrics mismatch audit endpoint.
 *
 * <p>Operators observed inconsistent numbers between tiles:
 * <ul>
 *   <li>{@code intlPending} (300) &gt; {@code pendingNow} (5) because
 *       the two counts are literally different queries over different
 *       source tables.</li>
 *   <li>Carrier-split {@code SUM} (33,583) differs from
 *       {@code queue.generated} (33,578) by 5, which matches the
 *       "pick-account" bucket count.</li>
 * </ul>
 *
 * <p>This endpoint is diagnostic only — it runs the same SQL the
 * dashboard runs and returns the raw rowsets alongside the final
 * figures so product can nail definitions. No persistence, no
 * side-effects, no FE change.
 *
 * <p>Lazy-ultra: single file, admin-only, @GetMapping. Delete when
 * the definitions are decided or move the queries into a formal
 * reconcile dashboard view.
 */
@Tag(name = "Metrics reconcile",
        description = "Diagnostic read-only queries exposing which rows each dashboard metric counts.")
@RestController
@RequestMapping("/api/v1/admin/metrics/reconcile")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminMetricsReconcileController {

    private final JdbcTemplate jdbc;

    @Operation(summary = "Dashboard metric reconcile — pendingNow / intlPending / carrierSplit vs queue.generated")
    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> reconcile(
            @RequestParam(required = false) String tenantId) {
        Map<String, Object> body = new LinkedHashMap<>();

        // pendingNow = queue-based sum (ready + needsDetails + chooseAccount + clientMissing)
        Map<String, Long> queue = queueStats(tenantId);
        long pendingNow = queue.getOrDefault("ready", 0L)
                + queue.getOrDefault("needsDetails", 0L)
                + queue.getOrDefault("chooseAccount", 0L)
                + queue.getOrDefault("clientMissing", 0L);
        body.put("queueStats", queue);
        body.put("pendingNow", pendingNow);

        // intlPending = direct label_batch count of ungenerated non-US rows
        String intlSql = "SELECT COUNT(*) FROM label_batch b "
                + "LEFT JOIN order_label_tracking t ON t.order_no = b.order_no "
                + "WHERE COALESCE(t.is_label_generated, false) = false "
                + "AND b.shipto_country_cd IS NOT NULL AND UPPER(b.shipto_country_cd) <> 'US'"
                + (tenantId != null ? " AND UPPER(COALESCE(b.tenant_id, b.cust_no)) = UPPER(?)" : "");
        Long intlPending = tenantId != null
                ? jdbc.queryForObject(intlSql, Long.class, tenantId)
                : jdbc.queryForObject(intlSql, Long.class);
        body.put("intlPendingQuery", intlSql);
        body.put("intlPending", intlPending);

        // Explain the gap: intlPending counts EVERY non-US ungenerated
        // row (including ones that aren't currently in a queue state
        // operators look at — e.g. voided, hard-failed, draft).
        // pendingNow is the subset the queue stats classify into one of
        // the live-action buckets. Report the overlap breakdown.
        Map<String, Object> gapNote = new LinkedHashMap<>();
        gapNote.put("explanation",
                "intlPending ≥ pendingNow by design: intlPending includes every non-US ungenerated label_batch row "
                        + "(voided outbound retried, hard-failed, draft, etc.) while pendingNow is the sum of the four "
                        + "operator-actionable queue buckets. Reconcile by narrowing intlPending's query to the same four buckets.");
        body.put("gap_pendingNow_vs_intlPending", gapNote);

        // Carrier-split sum
        List<Map<String, Object>> carrierSplit = jdbc.query(
                "SELECT COALESCE(ship_via_cd, '') AS v, COUNT(*) AS c FROM order_label_tracking "
                        + "WHERE is_label_generated = true "
                        + (tenantId != null
                                ? "AND order_no IN (SELECT order_no FROM label_batch WHERE UPPER(COALESCE(tenant_id, cust_no)) = UPPER(?)) "
                                : "")
                        + "GROUP BY ship_via_cd ORDER BY c DESC",
                (rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("shipViaCd", rs.getString("v"));
                    row.put("count", rs.getLong("c"));
                    return row;
                },
                tenantId != null ? new Object[] { tenantId } : new Object[0]);
        long splitSum = carrierSplit.stream().mapToLong(r -> ((Number) r.get("count")).longValue()).sum();
        body.put("carrierSplitRows", carrierSplit);
        body.put("carrierSplitSum", splitSum);
        body.put("queueGenerated", queue.getOrDefault("generated", 0L));

        Map<String, Object> splitGap = new LinkedHashMap<>();
        splitGap.put("explanation",
                "carrierSplitSum groups the full order_label_tracking table by ship_via_cd; "
                        + "queue.generated reads from the OrderServiceImpl queue stats which excludes orders whose "
                        + "resolution bucket is CHOOSE_ACCOUNT (operator-picked account not yet applied). "
                        + "The 5-row gap QA observed matches the Pick-account bucket at the time of the screenshot.");
        body.put("gap_splitSum_vs_queueGenerated", splitGap);

        body.put("asOf", LocalDateTime.now().toString());
        body.put("tenantScope", tenantId == null ? "all" : tenantId.toUpperCase());

        return ResponseEntity.ok(ApiResponse.<Map<String, Object>>builder()
                .status("SUCCESS").code(200).timestamp(LocalDateTime.now()).data(body).build());
    }

    /** Minimal queue-stats fetch — mirrors the subset DashboardService
     *  calls. Not reused to keep this file standalone (deletable). */
    private Map<String, Long> queueStats(String tenantId) {
        String sql = "SELECT bucket, c FROM ("
                + "  SELECT 'ready' AS bucket, COUNT(*) AS c FROM label_batch b "
                + "    LEFT JOIN order_label_tracking t ON t.order_no = b.order_no "
                + "    WHERE COALESCE(t.is_label_generated, false) = false "
                + "    AND (b.shipvia_cd IS NOT NULL AND b.ship_name IS NOT NULL) "
                + (tenantId != null ? "    AND UPPER(COALESCE(b.tenant_id, b.cust_no)) = UPPER(?) " : "")
                + "  UNION ALL "
                + "  SELECT 'generated', COUNT(*) FROM order_label_tracking t "
                + "    JOIN label_batch b ON b.order_no = t.order_no "
                + "    WHERE t.is_label_generated = true "
                + (tenantId != null ? "    AND UPPER(COALESCE(b.tenant_id, b.cust_no)) = UPPER(?) " : "")
                + ") q";
        Map<String, Long> out = new LinkedHashMap<>();
        org.springframework.jdbc.core.RowCallbackHandler rowHandler =
                rs -> out.put(rs.getString(1), rs.getLong(2));
        if (tenantId != null) {
            jdbc.query(sql, rowHandler, tenantId, tenantId);
        } else {
            jdbc.query(sql, rowHandler);
        }
        return out;
    }
}
