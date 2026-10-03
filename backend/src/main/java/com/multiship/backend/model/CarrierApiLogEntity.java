package com.multiship.backend.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** V114 — durable per-request carrier API log. See the migration for scope. */
@Entity
@Table(name = "carrier_api_log")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CarrierApiLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_id", length = 80)
    private String requestId;

    @Column(nullable = false, length = 32)
    private String carrier;

    @Column(nullable = false, length = 8)
    private String method;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String url;

    @Column(name = "request_body", columnDefinition = "TEXT")
    private String requestBody;

    @Column(name = "response_body", columnDefinition = "TEXT")
    private String responseBody;

    @Column(name = "status_code")
    private Integer statusCode;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "order_no")
    private Long orderNo;

    @Column(length = 120)
    private String tracking;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
