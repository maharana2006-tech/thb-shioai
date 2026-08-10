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
 * A saved CSV/XLSX import. "Commit" persists the parsed rows here as a data
 * record (it does NOT generate labels) so the import shows up in the Data
 * History page. The full row payload is stored as JSON for the detail view.
 */
@Entity
@Table(name = "import_batch")
@Getter
@Setter
@NoArgsConstructor
public class ImportBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "created_by", length = 120)
    private String createdBy;

    /** Original uploaded file name (e.g. "orders-jan.xlsx") the data came from. */
    @Column(name = "file_name", length = 260)
    private String fileName;

    /**
     * Lifecycle status of the import:
     * INITIATE (created, nothing committed), IN_PROGRESS (being applied),
     * PARTIAL_COMPLETE (some rows saved, some skipped), COMPLETE (all saved).
     */
    @Column(name = "status", length = 24)
    private String status;

    /**
     * Label batch id — the number stamped on every order this import generated
     * a label for, so the whole file's orders can be found together in All
     * Orders. Null until the first label is generated for this import.
     */
    @Column(name = "label_batch_id")
    private Integer labelBatchId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "total_rows")
    private int totalRows;

    @Column(name = "saved_rows")
    private int savedRows;

    @Column(name = "invalid_rows")
    private int invalidRows;

    /** Serialized List&lt;OrderImportRowDTO&gt; — the full imported data for the detail view. */
    @Column(name = "rows_json", columnDefinition = "TEXT")
    private String rowsJson;
}
