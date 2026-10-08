package com.multiship.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * V135 — one row per carrier pickup or end-of-day close event, from the
 * Settings page (MANUAL) or the scheduled CARRIER_CLOSEOUT job (SCHEDULED).
 * Pure audit trail so a scheduled close that failed leaves a trace.
 */
@Entity
@Table(name = "carrier_eod_log")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CarrierEodLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** PICKUP | CLOSEOUT */
    @Column(name = "kind", nullable = false, length = 10)
    private String kind;

    @Column(name = "carrier_code", nullable = false, length = 20)
    private String carrierCode;

    @Column(name = "account_number", length = 100)
    private String accountNumber;

    @Column(name = "customer_no", length = 100)
    private String customerNo;

    @Column(name = "warehouse_code", length = 50)
    private String warehouseCode;

    @Column(name = "event_date")
    private LocalDate eventDate;

    /** Carrier confirmation number (pickup) or manifest id / BOL (close). */
    @Column(name = "reference", length = 200)
    private String reference;

    @Column(name = "tracking_count", nullable = false)
    private int trackingCount;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "message", length = 1000)
    private String message;

    /** MANUAL | SCHEDULED */
    @Column(name = "source", nullable = false, length = 20)
    private String source;

    @Column(name = "created_by", length = 200)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
