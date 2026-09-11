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

/** One line of a staged upload: the parsed, validated row as JSON (errors and warnings included). */
@Entity
@Table(name = "import_staging_row")
@Getter
@Setter
@NoArgsConstructor
public class ImportStagingRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "upload_id", nullable = false)
    private Long uploadId;

    /** The row number in the uploaded file. */
    @Column(name = "row_no", nullable = false)
    private int rowNo;

    @Column(name = "order_ref", length = 120)
    private String orderRef;

    /** True when this row has no validation errors (an order is valid when all its rows are). */
    @Column(name = "valid", nullable = false)
    private boolean valid;

    /** Import-history batch this row was saved to; null while it is still only staged. */
    @Column(name = "saved_batch_id")
    private Long savedBatchId;

    @Column(name = "row_json", nullable = false, columnDefinition = "TEXT")
    private String rowJson;
}
