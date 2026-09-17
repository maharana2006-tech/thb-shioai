package com.multiship.backend.config;

import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;

/**
 * PR-P1 (audit finding PERF-M15) — bound the {@code taskScheduler} bean.
 *
 * <p>Without this config, Spring Boot auto-creates a default
 * {@link ThreadPoolTaskScheduler} whose {@code maxPoolSize} is
 * {@link Integer#MAX_VALUE} and whose queue is effectively unbounded
 * (backing {@code LinkedBlockingQueue} with no capacity limit). Every
 * {@code @Scheduled} method that stalls under load spawns a fresh
 * thread on the next tick — no cap, no back-pressure. Observed via
 * profiler on 2026-09-17:
 *
 * <pre>{@code
 * executor{name="taskScheduler"} pool_max_threads=2.147483647E9
 * executor{name="taskScheduler"} queue_remaining_tasks=2.147483647E9
 * }</pre>
 *
 * <p>The 4-thread pool below is sized for the current @Scheduled surface
 * (usps-queue tick, void-reconciliation tick, generation-worker poll,
 * fallback-alert eviction, sse-emitter ping — total &lt; 10 hot ticks per
 * minute). Increase in tandem with any new hot scheduled surface.
 *
 * <p>{@code awaitTermination(30s)} lets in-flight ticks finish on
 * shutdown so partial state isn't dropped — mirrors the safety Spring
 * Boot's default gives to web request threads.
 */
@Configuration
@EnableScheduling
public class SchedulerConfig {

    @Bean
    public ThreadPoolTaskScheduler taskScheduler(ThreadPoolTaskSchedulerBuilder builder) {
        return builder
                .poolSize(4)
                .threadNamePrefix("scheduler-")
                .awaitTermination(true)
                .awaitTerminationPeriod(Duration.ofSeconds(30))
                .build();
    }
}
