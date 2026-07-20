package com.multiship.backend.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "order_label_tracking")
@Data
public class OrderTracking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_no", nullable = false)
    private Integer orderNo;

    @Column(name = "order_suffix")
    private Integer orderSuffix = 0;

    @Column(name = "tracking_number")
    private String trackingNumber;

    @Column(name = "tracking_url")
    private String trackingUrl;

    @Column(name = "ship_via_cd")
    private String shipViaCd;

    @Column(name = "is_label_generated")
    private Boolean isLabelGenerated = false;

    @Column(name = "label_generated_at")
    private LocalDateTime labelGeneratedAt;

    @Column(name = "label_file_path")
    private String labelFilePath;

    @Column(name = "status")
    private String status = "PENDING";

    /** Carrier account the label was generated with (feeds account-book usage stats). */
    @Column(name = "account_number", length = 100)
    private String accountNumber;

    /**
     * Client-supplied Idempotency-Key of the request that generated this
     * label. A retry carrying the same key gets the existing label back as a
     * success instead of a 409 conflict.
     */
    @Column(name = "idempotency_key", length = 100)
    private String idempotencyKey;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}