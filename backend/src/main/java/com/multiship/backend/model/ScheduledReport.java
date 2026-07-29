package com.multiship.backend.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Sprint 45 — per-tenant scheduled report definition. The runner
 * checks eligible schedules on each tick and triggers a run when
 * {@code nextRunAt} has passed.
 */
@Entity
@Table(name = "scheduled_report",
        indexes = @Index(name = "idx_scheduled_report_next", columnList = "next_run_at"))
@Data
public class ScheduledReport {

    public enum Frequency { DAILY, WEEKLY, MONTHLY }

    public enum Dataset { ORDERS, TRACKING, RATE_SHOP, BILLING }

    public enum DeliveryType { DASHBOARD, EMAIL, WEBHOOK }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Owning tenant (nullable = platform-wide). */
    @Column(name = "tenant_id", length = 50)
    private String tenantId;

    @Column(nullable = false, length = 120)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Dataset dataset = Dataset.ORDERS;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Frequency frequency = Frequency.DAILY;

    /**
     * Filter definition as JSON. Deserialised into
     * {@link com.multiship.backend.dto.ReportFilters} at run time.
     * Null = no filters (dataset defaults apply).
     */
    @Column(name = "filters_json", columnDefinition = "text")
    private String filtersJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_type", nullable = false, length = 20)
    private DeliveryType deliveryType = DeliveryType.DASHBOARD;

    /** For EMAIL — comma-separated recipient list. */
    @Column(name = "delivery_email", length = 500)
    private String deliveryEmail;

    /** For WEBHOOK — HTTPS URL to POST the CSV to. */
    @Column(name = "delivery_webhook_url", length = 500)
    private String deliveryWebhookUrl;

    @Column(nullable = false)
    private Boolean active = true;

    /** Next scheduled run. Runner picks up any row where nextRunAt <= now. */
    @Column(name = "next_run_at")
    private LocalDateTime nextRunAt;

    @Column(name = "last_run_at")
    private LocalDateTime lastRunAt;

    /**
     * G6 — who created this schedule (username). Immutable after CREATE:
     * updates preserve the original creator so audit history survives
     * ownership changes. Null on legacy rows created before G6.
     */
    @Column(name = "created_by", length = 100)
    private String createdBy;

    /**
     * G6 — role the creator held at CREATE time (ROLE_ADMIN | ROLE_USER |
     * ROLE_TENANT). Used by the runner to enforce the schedule's scope on
     * the CSV output: a TENANT-created schedule can never pull data
     * outside its own tenant, no matter what filtersJson says. Null on
     * legacy rows — those keep behaving as before.
     */
    @Column(name = "created_by_role", length = 40)
    private String createdByRole;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
