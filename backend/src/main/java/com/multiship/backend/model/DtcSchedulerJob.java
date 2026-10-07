package com.multiship.backend.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * V132 — one DTC background job (DTC_SYNC today).
 * When it runs is its {@link #windows}; the lease / last-run columns are
 * written by {@code DtcSchedulerEngine} through native updates so a run
 * finishing never overwrites an admin's edit.
 */
@Entity
@Table(name = "dtc_scheduler_job")
@Getter
@Setter
public class DtcSchedulerJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_key", nullable = false, unique = true, length = 40)
    private String jobKey;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    /** Job-specific settings as a JSON object (none used by DTC_SYNC today). */
    @Column(name = "params_json")
    private String paramsJson;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @OneToMany(mappedBy = "job", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("sortOrder ASC, id ASC")
    private List<DtcSchedulerWindow> windows = new ArrayList<>();

    @Column(name = "lease_until", insertable = false, updatable = false)
    private LocalDateTime leaseUntil;

    @Column(name = "last_run_at", insertable = false, updatable = false)
    private LocalDateTime lastRunAt;

    @Column(name = "last_status", insertable = false, updatable = false)
    private String lastStatus;

    @Column(name = "last_message", insertable = false, updatable = false)
    private String lastMessage;

    @Column(name = "last_duration_ms", insertable = false, updatable = false)
    private Long lastDurationMs;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "updated_by", length = 200)
    private String updatedBy;
}
