package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.service.ndsshipment.NdsTemplates;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * TEMPORARY debug endpoint for the NDS Shipment prefill track — snapshots
 * the real NDS schema so we can rewrite {@code NdsShipmentLookupRepository}
 * against actual column names instead of the invented DDL that shipped
 * with PR #735/#736/#737.
 *
 * <p><b>Scope + safety:</b>
 * <ul>
 *   <li>ADMIN-gated via {@code @PreAuthorize("hasRole('ADMIN')")}.</li>
 *   <li>SQL text is <b>hardcoded</b> — no arbitrary-SQL runner. Table
 *       names in the {@code WHERE table_name IN (...)} clause are bound
 *       via {@link MapSqlParameterSource} so an ill-intentioned request
 *       body can't inject anything.</li>
 *   <li>Reads {@code ALL_TAB_COLUMNS} + {@code ALL_SYNONYMS} only — no
 *       DML, no DDL, no side effects.</li>
 * </ul>
 *
 * <p><b>Deletion criteria:</b> revert this controller (and its test if
 * one gets added) once the follow-up "rewrite NDS SQL against real DDL"
 * PR merges and {@code docs/tasks/nds-schema.md} is committed. See the
 * NDS Shipment prefill track memory doc for the current status.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/nds-debug")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class NdsDebugController {

    private final NdsTemplates ndsTemplates;

    /** Tables the client-login query enumerates. */
    private static final List<String> CLIENT_TABLES = List.of(
            "OEHEAD", "OEDETL", "TB_SHIP_CONTAINER", "OE_SHIP_CONTAINER",
            "OE_SEND_TO", "SHIPVIA", "TB_CLIPPER_ITEM_DETL", "CLIPPER", "OE_TRACKING");

    /** Tables the production-login query enumerates. */
    private static final List<String> PRODUCTION_TABLES = List.of(
            "TB_SHIP_CONTAINER", "TB_BILLABLE_CONTAINERS", "TB_MANUAL_SHIPMENT");

    public record DescribeRequest(String clientCode) {}

    @PostMapping("/describe")
    public ResponseEntity<ApiResponse<Map<String, Object>>> describe(@RequestBody DescribeRequest req) {
        String clientCode = req == null ? null : req.clientCode();
        Map<String, Object> body = new HashMap<>();

        // --- CLIENT-login snapshot ------------------------------------------------
        if (clientCode != null && !clientCode.isBlank()) {
            var clientJt = ndsTemplates.forClient(clientCode);
            var clientTablesParams = new MapSqlParameterSource("tables", CLIENT_TABLES);
            body.put("clientColumns", clientJt.queryForList("""
                    SELECT table_name, column_id, column_name, data_type,
                           data_length, data_precision, data_scale, nullable
                      FROM all_tab_columns
                     WHERE table_name IN (:tables)
                       AND owner = USER
                     ORDER BY table_name, column_id
                    """, clientTablesParams));
            body.put("synonyms", clientJt.queryForList("""
                    SELECT synonym_name, table_owner, table_name
                      FROM all_synonyms
                     WHERE owner IN (USER, 'PUBLIC')
                       AND table_owner = 'PRODUCTION'
                    """, new MapSqlParameterSource()));
            body.put("clientCodeUsed", clientCode);
        }

        // --- PRODUCTION-login snapshot --------------------------------------------
        var prodJt = ndsTemplates.production();
        var prodTablesParams = new MapSqlParameterSource("tables", PRODUCTION_TABLES);
        body.put("productionColumns", prodJt.queryForList("""
                SELECT table_name, column_id, column_name, data_type,
                       data_length, data_precision, data_scale, nullable
                  FROM all_tab_columns
                 WHERE table_name IN (:tables)
                   AND owner = USER
                 ORDER BY table_name, column_id
                """, prodTablesParams));

        log.info("nds-debug: describe returned clientCols={} prodCols={} synonyms={}",
                sizeOf(body, "clientColumns"), sizeOf(body, "productionColumns"),
                sizeOf(body, "synonyms"));

        return ResponseEntity.ok(ApiResponse.<Map<String, Object>>builder()
                .status("SUCCESS").code(200).timestamp(LocalDateTime.now())
                .data(body).build());
    }

    private static int sizeOf(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v instanceof List<?> l ? l.size() : 0;
    }
}
