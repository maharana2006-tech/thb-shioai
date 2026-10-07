package com.multiship.backend.service.dtc.scheduler;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.model.DtcSchedulerJob;
import com.multiship.backend.model.DtcSchedulerRun;
import com.multiship.backend.model.DtcSchedulerSettings;
import com.multiship.backend.repository.DtcSchedulerJobRepository;
import com.multiship.backend.repository.DtcSchedulerRunRepository;
import com.multiship.backend.repository.DtcSchedulerSettingsRepository;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * DB-driven DTC scheduler (V132). Replaces the hard-coded crons that used to
 * live in DtcTimedBackgroundService.
 *
 * <p>Every minute the tick re-reads {@code dtc_scheduler_settings} and the
 * jobs + windows, so an edit on Settings → Integrations → DTC Scheduler
 * applies from the next minute with no restart. A due job is handed to a
 * small worker pool, so a slow Oracle pull never holds a shared
 * {@code taskScheduler} thread.
 *
 * <p>Overlap / multi-node: a run first takes the job's DB lease
 * ({@code lease_until}). A tick that finds it held — a previous run still
 * going, or another node already on it — skips quietly.
 */
@Slf4j
@Component
public class DtcSchedulerEngine {

    public static final String RUNNING = "RUNNING";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";
    public static final String SKIPPED = "SKIPPED";

    public static final String TRIGGER_SCHEDULE = "SCHEDULE";
    public static final String TRIGGER_MANUAL = "MANUAL";

    /** Longest a run may hold its lease; frees the job if a node dies mid-run. */
    static final int LEASE_MINUTES = 60;
    static final int RUN_HISTORY_DAYS = 30;
    private static final int MAX_MESSAGE = 1000;

    private final DtcSchedulerSettingsRepository settingsRepo;
    private final DtcSchedulerJobRepository jobRepo;
    private final DtcSchedulerRunRepository runRepo;
    private final ObjectMapper objectMapper;
    private final Map<String, DtcSchedulerJobRunner> runners;
    private final String nodeId;
    private final ExecutorService workers;

    public DtcSchedulerEngine(DtcSchedulerSettingsRepository settingsRepo,
                              DtcSchedulerJobRepository jobRepo,
                              DtcSchedulerRunRepository runRepo,
                              ObjectMapper objectMapper,
                              List<DtcSchedulerJobRunner> runners) {
        this.settingsRepo = settingsRepo;
        this.jobRepo = jobRepo;
        this.runRepo = runRepo;
        this.objectMapper = objectMapper;
        this.runners = runners.stream().collect(Collectors.toMap(DtcSchedulerJobRunner::jobKey, Function.identity()));
        this.nodeId = hostName() + "-" + UUID.randomUUID().toString().substring(0, 8);
        AtomicInteger n = new AtomicInteger();
        this.workers = Executors.newFixedThreadPool(Math.max(1, runners.size()), r -> {
            Thread t = new Thread(r, "dtc-scheduler-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    @Scheduled(cron = "0 * * * * *")
    public void tick() {
        try {
            DtcSchedulerSettings settings = settings();
            ZonedDateTime now = ZonedDateTime.now(zoneOf(settings)).truncatedTo(ChronoUnit.MINUTES);
            if (now.getMinute() == 0) pruneHistory();
            if (!Boolean.TRUE.equals(settings.getEnabled())) return;

            for (DtcSchedulerJob job : jobRepo.findAllByOrderBySortOrderAscIdAsc()) {
                if (!Boolean.TRUE.equals(job.getEnabled())) continue;
                if (!runners.containsKey(job.getJobKey())) continue;
                if (DtcScheduleCalculator.isDue(job.getWindows(), now)) {
                    workers.submit(() -> execute(job, TRIGGER_SCHEDULE, "scheduler"));
                }
            }
        } catch (Exception e) {
            log.error("[DTC Scheduler] tick failed", e);
        }
    }

    /**
     * Run a job now, ignoring its windows, its enabled flag and the master
     * switch. Throws when the job is unknown or a run is already going.
     */
    public void runNow(String jobKey, String user) {
        DtcSchedulerJob job = jobRepo.findByJobKey(jobKey)
                .orElseThrow(() -> new java.util.NoSuchElementException("Job " + jobKey + " not found."));
        if (!runners.containsKey(jobKey)) {
            throw new IllegalArgumentException("Job " + jobKey + " has no runner.");
        }
        if (job.getLeaseUntil() != null && job.getLeaseUntil().isAfter(LocalDateTime.now())) {
            throw new IllegalArgumentException(job.getName() + " is already running.");
        }
        workers.submit(() -> execute(job, TRIGGER_MANUAL, user));
    }

    public DtcSchedulerSettings settings() {
        return settingsRepo.findById(DtcSchedulerSettings.SINGLETON_ID).orElseGet(DtcSchedulerSettings::new);
    }

    public ZoneId zoneOf(DtcSchedulerSettings settings) {
        String tz = settings.getTimezone();
        if (tz == null || tz.isBlank()) return ZoneId.systemDefault();
        try {
            return ZoneId.of(tz);
        } catch (Exception e) {
            log.warn("[DTC Scheduler] bad timezone '{}', using server time", tz);
            return ZoneId.systemDefault();
        }
    }

    private void execute(DtcSchedulerJob job, String trigger, String user) {
        String key = job.getJobKey();
        if (jobRepo.tryAcquireLease(key, nodeId, LEASE_MINUTES) == 0) {
            log.debug("[DTC Scheduler] {} already running — skipping this tick", key);
            return;
        }
        LocalDateTime started = LocalDateTime.now();
        long t0 = System.currentTimeMillis();
        DtcSchedulerRun run = new DtcSchedulerRun();
        run.setJobKey(key);
        run.setTriggerType(trigger);
        run.setTriggeredBy(user);
        run.setStartedAt(started);
        run.setStatus(RUNNING);
        String status;
        String message;
        try {
            run = runRepo.save(run);
            log.info("[DTC Scheduler] {} starting ({} by {})", key, trigger, user);
            DtcSchedulerJobRunner.Outcome outcome = runners.get(key).run(params(job));
            status = outcome.status();
            message = outcome.message();
            log.info("[DTC Scheduler] {} {}: {}", key, status, message);
        } catch (Exception e) {
            status = FAILED;
            message = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.error("[DTC Scheduler] {} failed", key, e);
        }
        long duration = System.currentTimeMillis() - t0;
        message = truncate(message);
        try {
            run.setFinishedAt(LocalDateTime.now());
            run.setStatus(status);
            run.setMessage(message);
            run.setDurationMs(duration);
            runRepo.save(run);
            jobRepo.recordLastRun(key, started, status, message, duration);
        } catch (Exception e) {
            log.error("[DTC Scheduler] {} could not record its run", key, e);
        } finally {
            jobRepo.releaseLease(key, nodeId);
        }
    }

    private Map<String, Object> params(DtcSchedulerJob job) throws Exception {
        String json = job.getParamsJson();
        if (json == null || json.isBlank()) return Collections.emptyMap();
        return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
    }

    private void pruneHistory() {
        try {
            int removed = runRepo.deleteOlderThan(LocalDateTime.now().minusDays(RUN_HISTORY_DAYS));
            if (removed > 0) log.info("[DTC Scheduler] pruned {} run(s) older than {} days", removed, RUN_HISTORY_DAYS);
        } catch (Exception e) {
            log.warn("[DTC Scheduler] run-history prune failed: {}", e.getMessage());
        }
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() <= MAX_MESSAGE ? s : s.substring(0, MAX_MESSAGE - 1) + "…";
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "node";
        }
    }

    @PreDestroy
    void shutdown() {
        workers.shutdown();
    }
}
