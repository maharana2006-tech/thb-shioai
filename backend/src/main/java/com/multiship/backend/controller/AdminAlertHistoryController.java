package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.model.AlertHistoryEntity;
import com.multiship.backend.repository.AlertHistoryRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/** V113 — admin surface for the durable alert_history table. */
@Tag(name = "Alert history",
        description = "Durable record of fired alerts (USPS fallback, Stamps SERA, future). Paginated.")
@RestController
@RequestMapping("/api/v1/admin/alerts/history")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminAlertHistoryController {

    private static final int MAX_PAGE_SIZE = 200;

    private final AlertHistoryRepository repo;

    @Operation(summary = "Paginated history, sorted newest first. All filters optional.")
    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> list(
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String tenantCode,
            @RequestParam(required = false) Long orderNo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        int clampedSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        int clampedPage = Math.max(0, page);
        Page<AlertHistoryEntity> result = repo.search(
                blankToNull(source), blankToNull(tenantCode), orderNo,
                PageRequest.of(clampedPage, clampedSize, Sort.by(Sort.Direction.DESC, "firedAt")));

        Map<String, Object> body = new HashMap<>();
        body.put("items", result.getContent().stream().map(AdminAlertHistoryController::toDto).toList());
        body.put("page", result.getNumber());
        body.put("size", result.getSize());
        body.put("totalElements", result.getTotalElements());
        body.put("totalPages", result.getTotalPages());
        return ResponseEntity.ok(ApiResponse.<Map<String, Object>>builder()
                .status("SUCCESS").code(200).data(body).timestamp(LocalDateTime.now()).build());
    }

    private static Map<String, Object> toDto(AlertHistoryEntity r) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", r.getId());
        m.put("firedAt", r.getFiredAt());
        m.put("source", r.getSource());
        m.put("templateKey", r.getTemplateKey());
        m.put("targetOrderNo", r.getTargetOrderNo());
        m.put("tenantCode", r.getTenantCode());
        m.put("importBatchId", r.getImportBatchId());
        m.put("reason", r.getReason());
        return m;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
