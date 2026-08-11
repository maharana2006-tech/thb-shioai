package com.multiship.backend.service;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.util.CustomsTerritories;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The dashboard aggregate: today's pulse, the resolution pipeline, a 7-day
 * trend, carrier split, recent labels, and SETUP HEALTH — config debt the
 * master data can already predict (customs-gap lanes, clients without a
 * default account, rules pointing at disabled services). Every number is a
 * door: the frontend deep-links each one into a pre-filtered page.
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    private final JdbcTemplate jdbc;
    private final OrderService orderService;

    /**
     * Sprint 50 Tier 0.5 PR H - clamp every dashboard aggregate to the
     * caller's tenant when the caller is a scoped USER. Platform operators
     * (empty scope) see org-wide numbers unchanged. Optional so pre-PR-E
     * unit tests that construct this service directly still compile.
     */
    @Autowired(required = false)
    private TenantScopeEnforcer tenantScope;

    @Transactional(readOnly = true)
    public ApiResponse<Map<String, Object>> dashboard() {
        Map<String, Object> data = new LinkedHashMap<>();

        // Scope filter — empty for platform operators, tenant string otherwise.
        // Every raw-JDBC aggregate below appends a scope predicate when present.
        Optional<String> scope = tenantScope == null ? Optional.empty() : tenantScope.resolveScope();
        String scopeVal = scope.orElse(null);

        // ===== pipeline (reuse the queue aggregate) =====
        // orderService.getQueueStats() is already tenant-aware (Sprint 50 PR E).
        Map<String, Long> queue = orderService.getQueueStats().getData();
        data.put("queue", queue);

        // ===== today's pulse =====
        // labelsToday / labelsYesterday count generated labels in order_label_tracking.
        // Join label_batch to expose tenant_id/cust_no for the scope filter.
        Long labelsToday = scope.isPresent()
                ? jdbc.queryForObject(
                    "SELECT COUNT(*) FROM order_label_tracking t "
                            + "JOIN label_batch b ON b.order_no = t.order_no "
                            + "WHERE t.is_label_generated = true "
                            + "AND t.label_generated_at >= CURRENT_DATE "
                            + "AND UPPER(COALESCE(b.tenant_id, b.cust_no)) = UPPER(?)",
                    Long.class, scopeVal)
                : jdbc.queryForObject(
                    "SELECT COUNT(*) FROM order_label_tracking WHERE is_label_generated = true "
                            + "AND label_generated_at >= CURRENT_DATE", Long.class);
        Long labelsYesterday = scope.isPresent()
                ? jdbc.queryForObject(
                    "SELECT COUNT(*) FROM order_label_tracking t "
                            + "JOIN label_batch b ON b.order_no = t.order_no "
                            + "WHERE t.is_label_generated = true "
                            + "AND t.label_generated_at >= CURRENT_DATE - 1 AND t.label_generated_at < CURRENT_DATE "
                            + "AND UPPER(COALESCE(b.tenant_id, b.cust_no)) = UPPER(?)",
                    Long.class, scopeVal)
                : jdbc.queryForObject(
                    "SELECT COUNT(*) FROM order_label_tracking WHERE is_label_generated = true "
                            + "AND label_generated_at >= CURRENT_DATE - 1 AND label_generated_at < CURRENT_DATE",
                    Long.class);
        Long intlPending = scope.isPresent()
                ? jdbc.queryForObject(
                    "SELECT COUNT(*) FROM label_batch b LEFT JOIN order_label_tracking t ON t.order_no = b.order_no "
                            + "WHERE COALESCE(t.is_label_generated, false) = false "
                            + "AND b.shipto_country_cd IS NOT NULL AND UPPER(b.shipto_country_cd) <> 'US' "
                            + "AND UPPER(COALESCE(b.tenant_id, b.cust_no)) = UPPER(?)",
                    Long.class, scopeVal)
                : jdbc.queryForObject(
                    "SELECT COUNT(*) FROM label_batch b LEFT JOIN order_label_tracking t ON t.order_no = b.order_no "
                            + "WHERE COALESCE(t.is_label_generated, false) = false "
                            + "AND b.shipto_country_cd IS NOT NULL AND UPPER(b.shipto_country_cd) <> 'US'",
                    Long.class);
        long pendingNow = queue != null
                ? queue.getOrDefault("ready", 0L) + queue.getOrDefault("needsDetails", 0L)
                        + queue.getOrDefault("chooseAccount", 0L) + queue.getOrDefault("clientMissing", 0L)
                : 0L;
        long exceptionsNow = queue != null
                ? queue.getOrDefault("needsDetails", 0L) + queue.getOrDefault("chooseAccount", 0L)
                        + queue.getOrDefault("clientMissing", 0L) + queue.getOrDefault("failed", 0L)
                : 0L;
        Map<String, Object> today = new LinkedHashMap<>();
        today.put("labelsToday", labelsToday);
        today.put("labelsYesterday", labelsYesterday);
        today.put("pendingNow", pendingNow);
        today.put("exceptionsNow", exceptionsNow);
        today.put("intlPending", intlPending);
        data.put("today", today);

        // ===== 7-day generation trend (zero-filled) =====
        Map<String, Long> byDay = new LinkedHashMap<>();
        for (int i = 6; i >= 0; i--) byDay.put(LocalDate.now().minusDays(i).toString(), 0L);
        if (scope.isPresent()) {
            jdbc.query("SELECT to_char(t.label_generated_at, 'YYYY-MM-DD') d, COUNT(*) c "
                            + "FROM order_label_tracking t JOIN label_batch b ON b.order_no = t.order_no "
                            + "WHERE t.is_label_generated = true "
                            + "AND t.label_generated_at >= CURRENT_DATE - 6 "
                            + "AND UPPER(COALESCE(b.tenant_id, b.cust_no)) = UPPER(?) "
                            + "GROUP BY d",
                    rs -> {
                        String d = rs.getString("d");
                        long c = rs.getLong("c");
                        byDay.computeIfPresent(d, (k, v) -> c);
                    }, scopeVal);
        } else {
            jdbc.query("SELECT to_char(label_generated_at, 'YYYY-MM-DD') d, COUNT(*) c "
                            + "FROM order_label_tracking WHERE is_label_generated = true "
                            + "AND label_generated_at >= CURRENT_DATE - 6 GROUP BY d",
                    rs -> {
                        String d = rs.getString("d");
                        long c = rs.getLong("c");
                        byDay.computeIfPresent(d, (k, v) -> c);
                    });
        }
        List<Map<String, Object>> trend = new ArrayList<>();
        byDay.forEach((d, c) -> {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("date", d);
            p.put("count", c);
            trend.add(p);
        });
        data.put("trend", trend);

        // ===== carrier split of generated labels =====
        Map<String, Long> split = new LinkedHashMap<>();
        if (scope.isPresent()) {
            jdbc.query("SELECT t.ship_via_cd, COUNT(*) c FROM order_label_tracking t "
                            + "JOIN label_batch b ON b.order_no = t.order_no "
                            + "WHERE t.is_label_generated = true "
                            + "AND UPPER(COALESCE(b.tenant_id, b.cust_no)) = UPPER(?) "
                            + "GROUP BY t.ship_via_cd",
                    rs -> {
                        String canonical = ShippingConfigService.canonicalCarrierFor(rs.getString("ship_via_cd"));
                        split.merge(canonical.isEmpty() ? "OTHER" : canonical, rs.getLong("c"), Long::sum);
                    }, scopeVal);
        } else {
            jdbc.query("SELECT ship_via_cd, COUNT(*) c FROM order_label_tracking "
                            + "WHERE is_label_generated = true GROUP BY ship_via_cd",
                    rs -> {
                        String canonical = ShippingConfigService.canonicalCarrierFor(rs.getString("ship_via_cd"));
                        split.merge(canonical.isEmpty() ? "OTHER" : canonical, rs.getLong("c"), Long::sum);
                    });
        }
        data.put("carrierSplit", split);

        // ===== recent labels =====
        List<Map<String, Object>> recent = scope.isPresent()
                ? jdbc.query(
                    "SELECT t.order_no, t.ship_via_cd, t.tracking_number, t.label_generated_at, "
                            + "COALESCE(b.tenant_id, b.cust_no) AS client, b.shipto_city, b.shipto_country_cd "
                            + "FROM order_label_tracking t JOIN label_batch b ON b.order_no = t.order_no "
                            + "WHERE t.is_label_generated = true "
                            + "AND UPPER(COALESCE(b.tenant_id, b.cust_no)) = UPPER(?) "
                            + "ORDER BY t.label_generated_at DESC NULLS LAST LIMIT 8",
                    (rs, i) -> mapRecent(rs), scopeVal)
                : jdbc.query(
                    "SELECT t.order_no, t.ship_via_cd, t.tracking_number, t.label_generated_at, "
                            + "COALESCE(b.tenant_id, b.cust_no) AS client, b.shipto_city, b.shipto_country_cd "
                            + "FROM order_label_tracking t JOIN label_batch b ON b.order_no = t.order_no "
                            + "WHERE t.is_label_generated = true ORDER BY t.label_generated_at DESC NULLS LAST LIMIT 8",
                    (rs, i) -> mapRecent(rs));
        data.put("recentLabels", recent);

        // ===== setup health (config debt the data can already predict) =====
        Map<String, Object> health = new LinkedHashMap<>();
        // unverifiedAccounts — clamp by customer_no (holds the client code).
        health.put("unverifiedAccounts", scope.isPresent()
                ? jdbc.queryForObject(
                    "SELECT COUNT(*) FROM carrier_account_ref WHERE COALESCE(active, true) = true "
                            + "AND COALESCE(verified, false) = false "
                            + "AND UPPER(TRIM(COALESCE(customer_no, ''))) = UPPER(?)", Long.class, scopeVal)
                : jdbc.queryForObject(
                    "SELECT COUNT(*) FROM carrier_account_ref WHERE COALESCE(active, true) = true "
                            + "AND COALESCE(verified, false) = false", Long.class));
        // clientsWithoutDefault — scoped caller sees 0 or 1 (their own client).
        health.put("clientsWithoutDefault", scope.isPresent()
                ? jdbc.queryForObject(
                    "SELECT COUNT(*) FROM clients c WHERE c.status = 'ACTIVE' "
                            + "AND UPPER(c.client_code) = UPPER(?) "
                            + "AND NOT EXISTS (SELECT 1 FROM carrier_account_ref r "
                            + "WHERE UPPER(r.customer_no) = UPPER(c.client_code) "
                            + "AND r.client_default = true AND COALESCE(r.active, true) = true)",
                    Long.class, scopeVal)
                : jdbc.queryForObject(
                    "SELECT COUNT(*) FROM clients c WHERE c.status = 'ACTIVE' AND NOT EXISTS "
                            + "(SELECT 1 FROM carrier_account_ref r WHERE UPPER(r.customer_no) = UPPER(c.client_code) "
                            + "AND r.client_default = true AND COALESCE(r.active, true) = true)", Long.class));
        // rulesToDisabledServices is a platform-wide catalog signal. A scoped
        // USER shouldn't be seeing catalog debt for services they don't own —
        // report 0 for scoped callers rather than leak the global count.
        health.put("rulesToDisabledServices", scope.isPresent()
                ? 0L
                : jdbc.queryForObject(
                    "SELECT COUNT(*) FROM shipvia_service_mapping m JOIN shipping_service s ON s.id = m.service_id "
                            + "WHERE s.enabled = false", Long.class));

        // Customs-gap lanes: pending cross-BORDER shipments whose client has no
        // profile covering the destination. Same-customs-territory lanes
        // (intra-EU…) are filtered out in Java — they need no customs at all.
        String gapSql = "SELECT DISTINCT COALESCE(b.tenant_id, b.cust_no) AS client, UPPER(b.shipto_country_cd) AS dest, "
                + "COALESCE(c.country_code, 'US') AS origin "
                + "FROM label_batch b "
                + "LEFT JOIN order_label_tracking t ON t.order_no = b.order_no "
                + "LEFT JOIN clients c ON UPPER(c.client_code) = UPPER(COALESCE(b.tenant_id, b.cust_no)) "
                + "WHERE COALESCE(t.is_label_generated, false) = false "
                + "AND b.shipto_country_cd IS NOT NULL AND b.shipto_country_cd <> '' "
                + (scope.isPresent()
                        ? "AND UPPER(COALESCE(b.tenant_id, b.cust_no)) = UPPER(?) "
                        : "")
                + "AND NOT EXISTS (SELECT 1 FROM client_customs_profile p "
                + "  JOIN customs_profile_country pc ON pc.profile_id = p.id "
                + "  WHERE UPPER(p.client_code) = UPPER(COALESCE(b.tenant_id, b.cust_no)) "
                + "  AND pc.country = UPPER(b.shipto_country_cd))";
        List<Map<String, Object>> gapLanes = (scope.isPresent()
                ? jdbc.query(gapSql, (rs, i) -> mapGapLane(rs), scopeVal)
                : jdbc.query(gapSql, (rs, i) -> mapGapLane(rs))).stream()
                .filter(l -> !CustomsTerritories.sameTerritory(
                        String.valueOf(l.get("origin")).toUpperCase(Locale.ROOT),
                        String.valueOf(l.get("country"))))
                .limit(10)
                .toList();
        health.put("customsGapLanes", gapLanes);
        data.put("health", health);

        return ApiResponse.<Map<String, Object>>builder()
                .status("SUCCESS").code(200).message("Dashboard retrieved.")
                .timestamp(LocalDateTime.now()).data(data).build();
    }

    private static Map<String, Object> mapRecent(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("orderNo", rs.getInt("order_no"));
        m.put("client", rs.getString("client"));
        m.put("carrier", ShippingConfigService.canonicalCarrierFor(rs.getString("ship_via_cd")));
        m.put("trackingNumber", rs.getString("tracking_number"));
        m.put("city", rs.getString("shipto_city"));
        m.put("country", rs.getString("shipto_country_cd"));
        LocalDateTime at = rs.getTimestamp("label_generated_at") != null
                ? rs.getTimestamp("label_generated_at").toLocalDateTime() : null;
        m.put("generatedAt", at != null ? at.toString() : null);
        return m;
    }

    private static Map<String, Object> mapGapLane(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("client", rs.getString("client"));
        m.put("country", rs.getString("dest"));
        m.put("origin", rs.getString("origin"));
        return m;
    }
}
