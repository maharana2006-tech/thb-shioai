package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Wire response for bulk-label endpoints. Sprint 37. Excludes the
 * base64 zip payload — that comes back only from the download endpoint
 * to keep the polling response light.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BulkLabelJobDTO {

    private Long id;
    /** PENDING | RUNNING | COMPLETED | FAILED. */
    private String status;
    private int totalCount;
    private int successfulCount;
    private int failedCount;
    private String failureMessage;
    /**
     * Bulk MED — structured per-order failures for FE table-rendering.
     * Serves as a progressive enhancement alongside the legacy
     * {@link #failureMessage} text blob; when present, the FE should
     * prefer it. Shape: JSON array of {orderNo, code, message, at}.
     * Null on jobs that landed before V51 (nothing to render) OR on
     * fully-successful jobs (nothing failed).
     */
    private String failureDetailsJson;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    /** True when {@code resultZipBase64} is populated and downloadable. */
    private boolean downloadable;
}
