package com.multiship.backend.service.carriers.usps.queue;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * PR-F1 Agent-2 stub — real entity + fields are Agent-1's responsibility
 * (V61 migration). Delete this file at merge time. Only the fields
 * accessed from {@code UspsLabelQueueWiring.processQueueItem} are
 * declared here so the wiring compiles.
 *
 * <p>Real fields (from PR-F1 brief): {@code id, tenantCode, shipmentId,
 * status, retryCount, createdAt, ...}. Agent-2 only reads
 * {@code tenantCode} and {@code shipmentId}; a broader API on the real
 * class is fine as long as those two accessors survive.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UspsLabelQueueItem {

    private Long id;

    private String tenantCode;

    private Long shipmentId;

    /** e.g. PENDING / RUNNING / DONE / FAILED — free-form stub. */
    private String status;

    private Integer retryCount;

    private LocalDateTime createdAt;
}
