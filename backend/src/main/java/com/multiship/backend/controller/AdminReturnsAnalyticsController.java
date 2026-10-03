package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.repository.OrderRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Returns F11 payoff — admin analytics for return_reason distribution
 * over time. Backs /settings/returns-analytics. ADMIN only.
 *
 * <p>Caller picks a lookback window in weeks (default 12, capped at 52).
 * Response is a flat list of (weekStart, reason, count) triples; the FE
 * pivots to a reason-by-week table.
 */
@Tag(name = "Returns analytics",
        description = "Return-reason rollup grouped by ISO week. Null/legacy reasons bucket under UNKNOWN.")
@RestController
@RequestMapping("/api/v1/admin/analytics/returns")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminReturnsAnalyticsController {

    private static final int DEFAULT_WEEKS = 12;
    private static final int MAX_WEEKS = 52;

    private final OrderRepository orderRepository;

    @Operation(summary = "Weekly return-reason rollup since now - weeks.")
    @GetMapping("/reason-rollup")
    public ResponseEntity<ApiResponse<Map<String, Object>>> reasonRollup(
            @RequestParam(defaultValue = "12") int weeks) {
        int w = Math.max(1, Math.min(weeks, MAX_WEEKS));
        LocalDate since = LocalDate.now().minusWeeks(w);
        List<Object[]> rows = orderRepository.getReturnsReasonRollup(since);

        List<Map<String, Object>> items = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            Map<String, Object> item = new HashMap<>(3);
            item.put("weekStart", row[0] != null ? row[0].toString() : null);
            item.put("reason", row[1] != null ? row[1].toString() : "UNKNOWN");
            item.put("count", row[2] != null ? ((Number) row[2]).longValue() : 0L);
            items.add(item);
        }

        Map<String, Object> body = new HashMap<>(3);
        body.put("items", items);
        body.put("weeks", w);
        body.put("sinceDate", since.toString());
        return ResponseEntity.ok(ApiResponse.<Map<String, Object>>builder()
                .status("SUCCESS").code(200).data(body)
                .timestamp(LocalDateTime.now()).build());
    }
}
