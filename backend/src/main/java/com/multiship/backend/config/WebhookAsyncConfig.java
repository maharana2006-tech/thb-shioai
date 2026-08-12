package com.multiship.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Sprint 50 Tier 1 finding #9 — bounded executor for the webhook
 * state-mutation branch. Prior behaviour ran the full receive() work
 * on the Tomcat request thread; at 500-5000 webhooks/min that saturates
 * the default 200-thread Tomcat pool and everything else on the app
 * stalls. Now the sync path stays fast (parse headers, verify signature,
 * persist audit row, respond) and the expensive tail (state mutation +
 * tracking cache invalidation) runs on this executor.
 *
 * <p>Sizing: 8 core threads × queue 500. Each state-mutation task is
 * ~10-50ms (one Order load + one save + one cache eviction), so 8
 * threads absorb ~160 tasks/second sustained — well above the 5000/min
 * peak the plan calls out. Queue 500 gives ~30s of burst headroom
 * before the CallerRunsPolicy pushes back on the Tomcat thread (which
 * only happens under sustained overload, and is a signal ops needs to
 * scale). This is the Phase-1 in-memory pool; Phase 2 (per the load-test
 * plan) migrates to Redis Streams for durability across restarts.
 */
@Configuration
public class WebhookAsyncConfig {

    /**
     * The executor is bean-named so callers can inject it by type
     * ({@link TaskExecutor}) or by qualifier when there are others.
     * Bean name is fixed so tests can override it with a
     * {@link org.springframework.core.task.SyncTaskExecutor} for
     * deterministic assertions.
     */
    public static final String BEAN_NAME = "webhookExecutor";

    @Bean(name = BEAN_NAME)
    public TaskExecutor webhookExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(8);
        exec.setMaxPoolSize(8);
        exec.setQueueCapacity(500);
        exec.setThreadNamePrefix("webhook-proc-");
        exec.setDaemon(true);
        // CallerRunsPolicy: when saturated, the calling Tomcat thread does
        // the work synchronously — pushing back on the carrier so we
        // don't just drop scans on the floor.
        exec.setRejectedExecutionHandler(
                new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        // Graceful shutdown: with setDaemon(true) above, a JVM stop would
        // otherwise hard-kill any in-flight mutateStateForVerifiedEvent
        // mid-save and leave an OrderTracking row half-updated. The Sprint
        // 49 Tier 1 dedup means a carrier redelivery would eventually heal
        // it, but the 30s drain (matching Spring Boot's default graceful
        // shutdown window) avoids that extra retry loop in the common
        // deploy/restart case.
        exec.setWaitForTasksToCompleteOnShutdown(true);
        exec.setAwaitTerminationSeconds(30);
        exec.initialize();
        return exec;
    }
}
