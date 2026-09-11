package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A staged bulk upload under validation. Counts are per ORDER (all rows sharing
 * an orderRef) as well as per row: an order is valid only when every one of its
 * rows is, and only valid orders are saved to Import history.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StagingUploadDTO {
    private Long id;
    private String fileName;
    /** OPEN while any order is unsaved; SAVED once every order is in Import history. */
    private String status;

    private int totalRows;
    private int validRows;
    private int invalidRows;

    private int totalOrders;
    private int validOrders;
    /** Unsaved orders with at least one row in error — these stay in staging. */
    private int invalidOrders;
    private int savedOrders;
    /** Valid orders not saved yet — what the next Save writes. */
    private int readyOrders;

    private Long lastSavedBatchId;
    /** Rows already saved to Import history (read-only here). */
    private List<Integer> savedRowNumbers;

    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;

    private List<OrderImportRowDTO> rows;
}
