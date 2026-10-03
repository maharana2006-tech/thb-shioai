package com.multiship.backend.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** V113 — durable record of a fired alert. See the migration for scope. */
@Entity
@Table(name = "alert_history")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlertHistoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "fired_at", nullable = false)
    private LocalDateTime firedAt;

    @Column(nullable = false, length = 80)
    private String source;

    @Column(name = "template_key", length = 120)
    private String templateKey;

    @Column(name = "target_order_no")
    private Long targetOrderNo;

    @Column(name = "tenant_code", length = 64)
    private String tenantCode;

    @Column(name = "import_batch_id")
    private Long importBatchId;

    @Column(columnDefinition = "TEXT")
    private String reason;

    @Column(name = "payload_json", columnDefinition = "TEXT")
    private String payloadJson;
}
