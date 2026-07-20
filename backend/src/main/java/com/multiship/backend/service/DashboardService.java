package com.multiship.backend.service;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.util.CustomsTerritories;
import lombok.RequiredArgsConstructor;
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

    @Transactional(readOnly = true)
    public ApiResponse<Map<String, Object>> dashboard() {
        Map<String, Object> data = new LinkedHashMap<>();

        // ===== pipeline (reuse the queue aggregate) =====
        Map<String, Long> queue = orderService.getQueueStats().getData();
        data.put("queue", queue);

        // ===== today's pulse =====
        Long labelsToday = jdbc.queryForObject(
                "SELECT COUNT(*) FROM order_label_tracking WHERE is_label_generated = true "
                        + "AND label_generated_at >= CURRENT_DATE", Long.class);
        Long labelsYesterday = jdbc.queryForObject(
                "SELECT COUNT(*) FROM order_label_tracking WHERE is_label_generated = true "
                        + "AND label_generated_at >= CURRENT_DATE - 1 AND label_generated_at < CURRENT_DATE",
                Long.class);
        Long intlPending = jdbc.queryForObject(
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
        jdbc.query("SELECT to_char(label_generated_at, 'YYYY-MM-DD') d, COUNT(*) c "
                        + "FROM order_label_tracking WHERE is_label_generated = true "
                        + "AND label_generated_at >= CURRENT_DATE - 6 GROUP BY d",
                rs -> {
                    String d = rs.getString("d");
                    long c = rs.getLong("c");
                    byDay.computeIfPresent(d, (k, v) -> c);
                });
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
        jdbc.query("SELECT ship_via_cd, COUNT(*) c FROM order_label_tracking "
                        + "WHERE is_label_generated = true GROUP BY ship_via_cd",
                rs -> {
                    String canonical = ShippingConfigService.canonicalCarrierFor(rs.getString("ship_via_cd"));
                    split.merge(canonical.isEmpty() ? "OTHER" : canonical, rs.getLong("c"), Long::sum);
                });
        data.put("carrierSplit", split);

        // ===== recent labels =====
        List<Map<String, Object>> recent = jdbc.query(
                "SELECT t.order_no, t.ship_via_cd, t.tracking_number, t.label_generated_at, "
                        + "COALESCE(b.tenant_id, b.cust_no) AS client, b.shipto_city, b.shipto_country_cd "
                        + "FROM order_label_tracking t JOIN label_batch b ON b.order_no = t.order_no "
                        + "WHERE t.is_label_generated = true ORDER BY t.label_generated_at DESC NULLS LAST LIMIT 8",
                (rs, i) -> {
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
                });
        data.put("recentLabels", recent);

        // ===== setup health (config debt the data can already predict) =====
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("unverifiedAccounts", jdbc.queryForObject(
                "SELECT COUNT(*) FROM carrier_account_ref WHERE COALESCE(active, true) = true "
                        + "AND COALESCE(verified, false) = false", Long.class));
        health.put("clientsWithoutDefault", jdbc.queryForObject(
                "SELECT COUNT(*) FROM clients c WHERE c.status = 'ACTIVE' AND NOT EXISTS "
                        + "(SELECT 1 FROM carrier_account_ref r WHERE UPPER(r.customer_no) = UPPER(c.client_code) "
                        + "AND r.client_default = true AND COALESCE(r.active, true) = true)", Long.class));
        health.put("rulesToDisabledServices", jdbc.queryForObject(
                "SELECT COUNT(*) FROM shipvia_service_mapping m JOIN shipping_service s ON s.id = m.service_id "
                        + "WHERE s.enabled = false", Long.class));

        // Customs-gap lanes: pending cross-BORDER shipments whose client has no
        // profile covering the destination. Same-customs-territory lanes
        // (intra-EU…) are filtered out in Java — they need no customs at all.
        List<Map<String, Object>> gapLanes = jdbc.query(
                "SELECT DISTINCT COALESCE(b.tenant_id, b.cust_no) AS client, UPPER(b.shipto_country_cd) AS dest, "
                        + "COALESCE(c.country_code, 'US') AS origin "
                        + "FROM label_batch b "
                        + "LEFT JOIN order_label_tracking t ON t.order_no = b.order_no "
                        + "LEFT JOIN clients c ON UPPER(c.client_code) = UPPER(COALESCE(b.tenant_id, b.cust_no)) "
                        + "WHERE COALESCE(t.is_label_generated, false) = false "
                        + "AND b.shipto_country_cd IS NOT NULL AND b.shipto_country_cd <> '' "
                        + "AND NOT EXISTS (SELECT 1 FROM client_customs_profile p "
                        + "  JOIN customs_profile_country pc ON pc.profile_id = p.id "
                        + "  WHERE UPPER(p.client_code) = UPPER(COALESCE(b.tenant_id, b.cust_no)) "
                        + "  AND pc.country = UPPER(b.shipto_country_cd))",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("client", rs.getString("client"));
                    m.put("country", rs.getString("dest"));
                    m.put("origin", rs.getString("origin"));
                    return m;
                }).stream()
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
}
