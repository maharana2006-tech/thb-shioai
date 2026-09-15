package com.multiship.backend.service;

import com.multiship.backend.model.ImportGenerationJob;
import com.multiship.backend.repository.ImportGenerationJobRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.InetAddress;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs queued label-generation jobs (V57).
 *
 * <p>Claims one job at a time with {@code SELECT … FOR UPDATE SKIP LOCKED}, so any
 * number of servers can poll the same table without two of them running a job,
 * then runs it on a small bounded pool. While it runs, the worker heart-beats its
 * jobs; a RUNNING job whose heartbeat goes stale (its server died) is put back on
 * the queue and picked up again — the rerun syncs rows with the label batch's live
 * orders first, so orders already labelled aren't sent twice.
 *
 * <p>The carrier rate limiter and per-tenant fairness still sit inside the run
 * itself; this pool only bounds how many imports generate at once.
 */
@Component
@ConditionalOnProperty(name = "import.generation.worker.enabled", havingValue = "true", matchIfMissing = true)
public class ImportGenerationWorker {

    private static final Logger log = LoggerFactory.getLogger(ImportGenerationWorker.class);

    private final ImportGenerationJobRepository jobs;
    private final OrderImportService imports;
    private final TransactionTemplate tx;
    private final int maxConcurrent;
    private final long staleAfterSeconds;
    private final ExecutorService pool;
    private final AtomicInteger running = new AtomicInteger();
    private final String workerId;

    public ImportGenerationWorker(ImportGenerationJobRepository jobs,
                                  OrderImportService imports,
                                  PlatformTransactionManager transactionManager,
                                  @Value("${import.generation.max-concurrent-jobs:4}") int maxConcurrent,
                                  @Value("${import.generation.stale-after-seconds:60}") long staleAfterSeconds) {
        this.jobs = jobs;
        this.imports = imports;
        this.tx = new TransactionTemplate(transactionManager);
        this.maxConcurrent = Math.max(1, maxConcurrent);
        this.staleAfterSeconds = Math.max(30, staleAfterSeconds);
        this.pool = Executors.newFixedThreadPool(this.maxConcurrent, r -> {
            Thread t = new Thread(r, "import-generation-job");
            t.setDaemon(true);
            return t;
        });
        this.workerId = hostName() + ":" + ProcessHandle.current().pid() + ":" + UUID.randomUUID().toString().substring(0, 8);
        log.info("Label generation worker {} ready (up to {} import(s) at once)", workerId, this.maxConcurrent);
    }

    /** Claim queued jobs until this worker's slots are full or the queue is empty. */
    @Scheduled(fixedDelayString = "${import.generation.poll-ms:1000}",
               initialDelayString = "${import.generation.initial-delay-ms:5000}")
    public void poll() {
        while (running.get() < maxConcurrent) {
            ImportGenerationJob job;
            try {
                job = tx.execute(status -> jobs.lockNextQueued().map(j -> {
                    LocalDateTime now = LocalDateTime.now();
                    j.setStatus(ImportGenerationJob.RUNNING);
                    j.setWorkerId(workerId);
                    if (j.getStartedAt() == null) j.setStartedAt(now);
                    j.setHeartbeatAt(now);
                    j.setAttempts(j.getAttempts() + 1);
                    return jobs.save(j);
                }).orElse(null));
            } catch (RuntimeException e) {
                log.warn("Label generation queue poll failed: {}", e.getMessage());
                return;
            }
            if (job == null) return;

            Long jobId = job.getId();
            running.incrementAndGet();
            log.info("Label generation job {} (import #{}) claimed by {} — attempt {}",
                    jobId, job.getImportBatchId(), workerId, job.getAttempts());
            try {
                pool.execute(() -> {
                    try {
                        imports.executeGenerationJob(jobId);
                    } catch (RuntimeException e) {
                        log.error("Label generation job {} crashed outside its run: {}", jobId, e.toString(), e);
                    } finally {
                        running.decrementAndGet();
                    }
                });
            } catch (RejectedExecutionException rejected) {
                running.decrementAndGet();
                // Shutting down: put it back for the next worker.
                try {
                    jobs.findById(jobId).ifPresent(j -> {
                        j.setStatus(ImportGenerationJob.QUEUED);
                        j.setWorkerId(null);
                        jobs.save(j);
                    });
                } catch (RuntimeException e) {
                    log.warn("Label generation job {}: could not re-queue after rejection: {}", jobId, e.getMessage());
                }
                return;
            }
        }
    }

    /** Liveness for this worker's running jobs, independent of progress ticks (a rate-limit wait sends none). */
    @Scheduled(fixedDelayString = "${import.generation.heartbeat-ms:15000}")
    public void heartbeat() {
        if (running.get() == 0) return;
        try {
            jobs.heartbeat(workerId, LocalDateTime.now());
        } catch (RuntimeException e) {
            log.warn("Label generation heartbeat failed: {}", e.getMessage());
        }
    }

    /** Crash recovery: re-queue RUNNING jobs whose worker stopped heart-beating. */
    @Scheduled(fixedDelayString = "${import.generation.requeue-check-ms:30000}",
               initialDelayString = "${import.generation.initial-delay-ms:5000}")
    public void requeueStale() {
        try {
            int n = jobs.requeueStale(LocalDateTime.now().minusSeconds(staleAfterSeconds));
            if (n > 0) {
                log.warn("Re-queued {} label generation job(s) whose worker stopped responding for {}s",
                        n, staleAfterSeconds);
            }
        } catch (RuntimeException e) {
            log.warn("Label generation stale-job check failed: {}", e.getMessage());
        }
    }

    int runningJobs() {
        return running.get();
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
