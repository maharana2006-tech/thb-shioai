package com.multiship.backend.dto;

import com.multiship.backend.model.UspsLabelQueueItem;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Read-shape for a persisted {@link UspsLabelQueueItem}. Used by
 * {@code UspsLabelQueueAdminController.GET /items} so admins can see
 * exactly which USPS label requests are queued / in-flight / completed
 * without hitting the entity through the JPA-serialisation path.
 *
 * <p>PR-F2 adds {@link #parentOrderNo} + {@link #sequenceNumber} so the
 * admin list surfaces which rows are MPS pieces (NULL = single-label).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UspsLabelQueueItemDTO {

    private Long id;

    /** Tenant / client code owning this label request. */
    private String tenantCode;

    /** Local shipment identifier (order_label_tracking.id or shipment.id). */
    private Long shipmentId;

    /** Lower value = higher priority (POSIX-nice convention). */
    private Integer priority;

    /** QUEUED | PROCESSING | DONE | FAILED | CANCELLED. */
    private String status;

    private LocalDateTime enqueuedAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;

    private Integer retryCount;
    private String lastError;

    /** Populated on DONE - USPS-assigned tracking number. */
    private String trackingNumber;

    /**
     * PR-F2 - MPS parent order number. {@code null} on single-label
     * enqueue rows; populated on rows that are pieces of an N-piece
     * MPS shipment (see {@code UspsMpsSplitterService}). Aggregate
     * progress lives on {@code /mps-progress/{orderNo}}.
     */
    private Long parentOrderNo;

    /**
     * PR-F2 - 1-based position within the parent MPS order.
     * {@code null} on non-MPS rows.
     */
    private Integer sequenceNumber;

    /** Map an entity to its read-shape DTO. */
    public static UspsLabelQueueItemDTO from(UspsLabelQueueItem row) {
        if (row == null) return null;
        return UspsLabelQueueItemDTO.builder()
                .id(row.getId())
                .tenantCode(row.getTenantCode())
                .shipmentId(row.getShipmentId())
                .priority(row.getPriority())
                .status(row.getStatus() == null ? null : row.getStatus().name())
                .enqueuedAt(row.getEnqueuedAt())
                .startedAt(row.getStartedAt())
                .completedAt(row.getCompletedAt())
                .retryCount(row.getRetryCount())
                .lastError(row.getLastError())
                .trackingNumber(row.getTrackingNumber())
                .parentOrderNo(row.getParentOrderNo())
                .sequenceNumber(row.getSequenceNumber())
                .build();
    }
}
