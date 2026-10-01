package com.multiship.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.model.WritebackJournalEntity;
import com.multiship.backend.repository.WritebackJournalRepository;
import com.multiship.backend.service.externalsystems.writeback.ExternalSystemWritebackDispatcher;
import com.multiship.backend.service.externalsystems.writeback.WritebackClearRequest;
import com.multiship.backend.service.externalsystems.writeback.WritebackJournalService;
import com.multiship.backend.service.externalsystems.writeback.WritebackPayload;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * D1 — admin surface for the framework-wide writeback journal.
 *
 * <p>List every dispatch attempt across every connector with filters,
 * and re-fire any FAILED attempt through the same dispatcher. Retry
 * writes a fresh row (attempt_number=1 on the new row; retry linkage
 * via timestamp for now — full retry chain in Phase D1b).
 */
@Tag(name = "Writeback journal",
        description = "Every external-system writeback dispatch — SENT, SKIPPED, FAILED — with per-row retry.")
@RestController
@RequestMapping("/api/v1/admin/writeback-journal")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminWritebackJournalController {

    private static final int MAX_PAGE_SIZE = 200;

    private final WritebackJournalRepository repo;
    private final ExternalSystemWritebackDispatcher dispatcher;
    private final ObjectMapper objectMapper;

    @Operation(summary = "Paginated dispatch attempts, sorted newest first. All filters optional.")
    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> list(
            @RequestParam(required = false) String connectionName,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String mode,
            @RequestParam(required = false) Integer orderNo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        int clampedSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        int clampedPage = Math.max(0, page);
        Page<WritebackJournalEntity> result = repo.search(
                blankToNull(connectionName), blankToNull(status), blankToNull(mode), orderNo,
                PageRequest.of(clampedPage, clampedSize, Sort.by(Sort.Direction.DESC, "createdAt")));

        Map<String, Object> body = new HashMap<>();
        body.put("items", result.getContent().stream().map(this::toDto).toList());
        body.put("page", result.getNumber());
        body.put("size", result.getSize());
        body.put("totalElements", result.getTotalElements());
        body.put("totalPages", result.getTotalPages());
        return ok(body, "Journal retrieved");
    }

    @Operation(summary = "Re-dispatch a journal row through the writeback dispatcher. "
            + "Async; a fresh row appears once the dispatch completes.")
    @PostMapping("/{id}/retry")
    public ResponseEntity<ApiResponse<Map<String, Object>>> retry(@PathVariable Long id) {
        WritebackJournalEntity row = repo.findById(id).orElse(null);
        if (row == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    ApiResponse.<Map<String, Object>>builder()
                            .status("ERROR").code(404).errorCode("JOURNAL_ROW_NOT_FOUND")
                            .message("Row " + id + " not found")
                            .timestamp(LocalDateTime.now()).build());
        }
        if (row.getPayloadJson() == null || row.getPayloadJson().isBlank()) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(
                    ApiResponse.<Map<String, Object>>builder()
                            .status("ERROR").code(422).errorCode("JOURNAL_ROW_NO_PAYLOAD")
                            .message("Row has no persisted payload — was written by an older dispatcher.")
                            .timestamp(LocalDateTime.now()).build());
        }
        try {
            if (WritebackJournalService.MODE_GENERATE.equals(row.getMode())) {
                WritebackPayload payload = objectMapper.readValue(
                        row.getPayloadJson(), WritebackPayload.class);
                dispatcher.dispatchOnGenerate(payload);
            } else {
                WritebackClearRequest req = objectMapper.readValue(
                        row.getPayloadJson(), WritebackClearRequest.class);
                dispatcher.dispatchOnClear(req);
            }
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(
                    ApiResponse.<Map<String, Object>>builder()
                            .status("ERROR").code(422).errorCode("JOURNAL_RETRY_FAILED")
                            .message("Retry dispatch failed: " + ex.getMessage())
                            .timestamp(LocalDateTime.now()).build());
        }
        return ok(Map.of("retriedFrom", id,
                "note", "Dispatch queued; a fresh journal row will appear once the async attempt completes."),
                "Retry queued");
    }

    private Map<String, Object> toDto(WritebackJournalEntity row) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", row.getId());
        m.put("connectionName", row.getConnectionName());
        m.put("systemType", row.getSystemType());
        m.put("mode", row.getMode());
        m.put("status", row.getStatus());
        m.put("clientCode", row.getClientCode());
        m.put("orderNo", row.getOrderNo());
        m.put("trackingNumber", row.getTrackingNumber());
        m.put("source", row.getSource());
        m.put("channel", row.getChannel());
        m.put("ackStatus", row.getAckStatus());
        m.put("ackDetail", row.getAckDetail());
        m.put("errorMessage", row.getErrorMessage());
        m.put("latencyMs", row.getLatencyMs());
        m.put("attemptNumber", row.getAttemptNumber());
        m.put("retryOfId", row.getRetryOfId());
        m.put("createdAt", row.getCreatedAt());
        m.put("updatedAt", row.getUpdatedAt());
        return m;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private static <T> ResponseEntity<ApiResponse<T>> ok(T body, String message) {
        return ResponseEntity.ok(ApiResponse.<T>builder()
                .status("SUCCESS").code(200).message(message).data(body)
                .timestamp(LocalDateTime.now()).build());
    }
}
