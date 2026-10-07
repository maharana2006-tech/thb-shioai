package com.multiship.backend.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalTime;

/**
 * V132 — when a {@link DtcSchedulerJob} runs. Either an interval window
 * (days × startTime..endTime, every intervalMinutes — the range may cross
 * midnight) or a daily window (days × runAt). {@code days} is the day the
 * run happens on, as a comma list of MON..SUN.
 */
@Entity
@Table(name = "dtc_scheduler_window")
@Getter
@Setter
public class DtcSchedulerWindow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id", nullable = false)
    private DtcSchedulerJob job;

    @Column(name = "label", length = 100)
    private String label;

    @Column(name = "days", nullable = false, length = 40)
    private String days;

    @Column(name = "start_time")
    private LocalTime startTime;

    @Column(name = "end_time")
    private LocalTime endTime;

    @Column(name = "interval_minutes")
    private Integer intervalMinutes;

    @Column(name = "run_at")
    private LocalTime runAt;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;
}
