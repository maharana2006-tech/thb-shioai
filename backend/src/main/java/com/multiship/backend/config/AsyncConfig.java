package com.multiship.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Audit-fix #10 — a bounded {@link ThreadPoolTaskExecutor} for every
 * {@code @Async} method on the platform.
 *
 * <p>Sprint 46's {@code ExternalWebhookDispatcher.fire()} runs
 * unconditionally under {@code @Async}. Every carrier label ships
 * one LABEL_GENERATED event; a bulk-label burst of 500 labels with
 * 3 active subscriptions would fan out to 1500 async invocations.
 * Without a defined executor bean, Spring falls back to
 * {@code SimpleAsyncTaskExecutor}, which spawns a fresh thread per
 * call — the JVM would exhaust its thread ceiling long before the
 * burst finished.
 *
 * <p>The bean is named {@code taskExecutor} so Spring's
 * {@code @Async} auto-detection picks it up without a qualifier on
 * the annotated method.
 *
 * <p>Sizing rationale:
 * <ul>
 *   <li>core=4 — steady state, keeps four workers alive for prompt
 *       response on low-volume clients</li>
 *   <li>max=32 — headroom for a bulk-label burst</li>
 *   <li>queue=200 — bursts up to 200 tasks queue rather than fanning
 *       out threads; higher throughput at less thread cost</li>
 *   <li>keepAlive=60s — idle non-core workers exit after a minute</li>
 *   <li>rejection=CallerRuns — on saturation, the caller executes the
 *       task synchronously rather than dropping it. Webhook events must
 *       not be silently lost.</li>
 * </ul>
 */
@Configuration
public class AsyncConfig {

    @Bean(name = "taskExecutor")
    public ThreadPoolTaskExecutor taskExecutor() {
        return buildExecutor(4, 32, 200, 60);
    }

    /**
     * Package-private so {@link AsyncConfigTest} can build fresh
     * executors with tiny sizes for saturation / rejection-handler
     * assertions without dragging a full Spring context up.
     */
    static ThreadPoolTaskExecutor buildExecutor(int core, int max, int queueCapacity, int keepAliveSeconds) {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(core);
        exec.setMaxPoolSize(max);
        exec.setQueueCapacity(queueCapacity);
        exec.setKeepAliveSeconds(keepAliveSeconds);
        exec.setThreadNamePrefix("async-");
        // Never silently drop a task — the caller runs it synchronously
        // if the pool + queue are both full. Preferred for webhook
        // delivery over the default AbortPolicy.
        exec.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        exec.setWaitForTasksToCompleteOnShutdown(true);
        exec.setAwaitTerminationSeconds(15);
        exec.initialize();
        return exec;
    }
}
