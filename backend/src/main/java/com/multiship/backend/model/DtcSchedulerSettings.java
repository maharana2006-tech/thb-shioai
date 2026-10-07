package com.multiship.backend.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * V132 — single-row master settings for the DB-driven DTC scheduler
 * (Settings → Integrations → DTC Scheduler).
 */
@Entity
@Table(name = "dtc_scheduler_settings")
@Data
public class DtcSchedulerSettings {

    public static final short SINGLETON_ID = 1;

    @Id
    private Short id = SINGLETON_ID;

    /** Master switch — off stops every scheduled run (Run now still works). */
    @Column(name = "enabled", nullable = false)
    private Boolean enabled = false;

    /** IANA tz the windows are read in. Null = server local time. */
    @Column(name = "timezone", length = 64)
    private String timezone;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "updated_by", length = 200)
    private String updatedBy;
}
