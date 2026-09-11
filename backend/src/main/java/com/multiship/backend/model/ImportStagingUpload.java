package com.multiship.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * A bulk-upload file parked for validation. Nothing from it reaches Import
 * history until Save, and Save only moves fully valid orders (see V52).
 */
@Entity
@Table(name = "import_staging_upload")
@Getter
@Setter
@NoArgsConstructor
public class ImportStagingUpload {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "created_by", length = 120)
    private String createdBy;

    @Column(name = "file_name", length = 260)
    private String fileName;

    @Column(name = "content_hash", length = 128)
    private String contentHash;

    /** OPEN while any order is still unsaved; SAVED once every order is in Import history. */
    @Column(name = "status", length = 20, nullable = false)
    private String status = "OPEN";

    @Column(name = "total_rows", nullable = false)
    private int totalRows;

    @Column(name = "valid_rows", nullable = false)
    private int validRows;

    @Column(name = "invalid_rows", nullable = false)
    private int invalidRows;

    @Column(name = "total_orders", nullable = false)
    private int totalOrders;

    @Column(name = "valid_orders", nullable = false)
    private int validOrders;

    @Column(name = "saved_orders", nullable = false)
    private int savedOrders;

    @Column(name = "last_saved_batch_id")
    private Long lastSavedBatchId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
