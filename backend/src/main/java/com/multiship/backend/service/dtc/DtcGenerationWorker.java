package com.multiship.backend.service.dtc;

import com.multiship.backend.model.DtcGenerationJob;
import com.multiship.backend.repository.DtcGenerationJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.annotation.PreDestroy;
import java.net.InetAddress;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Claims queued {@link DtcGenerationJob}s and runs them on a bounded pool —
 * the ImportGenerationWorker pattern applied to DTC batch label generation:
 * SELECT FOR UPDATE SKIP LOCKED claim, heartbeat while running, stale-job
 * requeue for crash recovery.
 */
@Component
@ConditionalOnProperty(name = "dtc.generation.worker.enabled", havingValue = "true", matchIfMissing = true)
public class DtcGenerationWorker {

    private static final Logger log = LoggerFactory.getLogger(DtcGenerationWorker.class);

    private final DtcGenerationJobRepository jobs;
    private final DtcLabelGenerationService generationService;
    private final TransactionTemplate tx;
    private final int maxConcurrent;
    private final long staleAfterSeconds;
    private final ExecutorService pool;
    private final AtomicInteger running = new AtomicInteger();
    private final String workerId;

    public DtcGenerationWorker(DtcGenerationJobRepository jobs,
                               DtcLabelGenerationService generationService,
                               PlatformTransactionManager transactionManager,
                               @Value("${dtc.generation.max-concurrent-jobs:2}") int maxConcurrent,
                               @Value("${dtc.generation.stale-after-seconds:300}") long staleAfterSeconds) {
        this.jobs = jobs;
        this.generationService = generationService;
        this.tx = new TransactionTemplate(transactionManager);
        this.maxConcurrent = Math.max(1, maxConcurrent);
        this.staleAfterSeconds = Math.max(60, staleAfterSeconds);
        this.pool = Executors.newFixedThreadPool(this.maxConcurrent, r -> {
            Thread t = new Thread(r, "dtc-generation-job");
            t.setDaemon(true);
            return t;
        });
        this.workerId = hostName() + ":" + ProcessHandle.current().pid() + ":"
                + UUID.randomUUID().toString().substring(0, 8);
        log.info("DTC generation worker {} ready (up to {} job(s) at once)", workerId, this.maxConcurrent);
    }

    /** Claim queued jobs until this worker's slots are full or the queue is empty. */
    @Scheduled(fixedDelayString = "${dtc.generation.poll-ms:2000}",
               initialDelayString = "${dtc.generation.initial-delay-ms:10000}")
    public void poll() {
        while (running.get() < maxConcurrent) {
            DtcGenerationJob job;
            try {
                job = tx.execute(status -> jobs.lockNextQueued().map(j -> {
                    LocalDateTime now = LocalDateTime.now();
                    j.setStatus(DtcGenerationJob.RUNNING);
                    j.setWorkerId(workerId);
                    if (j.getStartedAt() == null) j.setStartedAt(now);
                    j.setHeartbeatAt(now);
                    return jobs.save(j);
                }).orElse(null));
            } catch (RuntimeException e) {
                log.warn("DTC generation queue poll failed: {}", e.getMessage());
                return;
            }
            if (job == null) return;

            Long jobId = job.getId();
            running.incrementAndGet();
            log.info("DTC generation job {} (tenant {}, batch {}) claimed by {}",
                    jobId, job.getTenantId(), job.getBatchId(), workerId);
            try {
                pool.execute(() -> {
                    try {
                        generationService.executeJob(jobId);
                    } catch (RuntimeException e) {
                        log.error("DTC generation job {} crashed outside its run: {}", jobId, e.toString(), e);
                    } finally {
                        running.decrementAndGet();
                    }
                });
            } catch (RejectedExecutionException rejected) {
                running.decrementAndGet();
                try {
                    jobs.findById(jobId).ifPresent(j -> {
                        j.setStatus(DtcGenerationJob.QUEUED);
                        j.setWorkerId(null);
                        jobs.save(j);
                    });
                } catch (RuntimeException e) {
                    log.warn("DTC generation job {}: could not re-queue after rejection: {}", jobId, e.getMessage());
                }
                return;
            }
        }
    }

    /** Liveness for this worker's running jobs, independent of progress ticks. */
    @Scheduled(fixedDelayString = "${dtc.generation.heartbeat-ms:15000}")
    public void heartbeat() {
        if (running.get() == 0) return;
        try {
            tx.executeWithoutResult(status -> jobs.heartbeat(workerId, LocalDateTime.now()));
        } catch (RuntimeException e) {
            log.warn("DTC generation heartbeat failed: {}", e.getMessage());
        }
    }

    /** Crash recovery: re-queue RUNNING jobs whose worker stopped heart-beating. */
    @Scheduled(fixedDelayString = "${dtc.generation.requeue-check-ms:60000}",
               initialDelayString = "${dtc.generation.initial-delay-ms:10000}")
    public void requeueStale() {
        try {
            int n = tx.execute(status -> jobs.requeueStaleRunning(LocalDateTime.now().minusSeconds(staleAfterSeconds)));
            if (n > 0) {
                log.warn("Re-queued {} DTC generation job(s) whose worker stopped responding for {}s",
                        n, staleAfterSeconds);
            }
        } catch (RuntimeException e) {
            log.warn("DTC generation stale-job check failed: {}", e.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        pool.shutdown();
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "host";
        }
    }
}
