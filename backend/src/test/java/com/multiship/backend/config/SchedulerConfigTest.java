package com.multiship.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PR-P1 (audit PERF-M15) — pins the explicit {@link ThreadPoolTaskScheduler}
 * bean shape. Without this config, Spring auto-creates a default scheduler
 * with {@code maxPoolSize = Integer.MAX_VALUE} and an unbounded queue —
 * any {@code @Scheduled} tick that stalls under load spawns a fresh thread
 * on every subsequent tick until OOM.
 *
 * <p>Pure unit test — construct the config directly, ask for the bean,
 * assert its concrete shape. No Spring context / no DB required.
 */
class SchedulerConfigTest {

    @Test
    void taskScheduler_isBounded_atPoolSize4() {
        SchedulerConfig cfg = new SchedulerConfig();
        ThreadPoolTaskScheduler s = cfg.taskScheduler(new ThreadPoolTaskSchedulerBuilder());
        s.initialize();
        try {
            assertNotNull(s, "SchedulerConfig must produce a ThreadPoolTaskScheduler bean");
            assertEquals(4, s.getScheduledThreadPoolExecutor().getCorePoolSize(),
                    "Scheduler pool size must be the P1-configured 4; default is 1 (or MAX_VALUE without config).");
            // JDK ScheduledThreadPoolExecutor.getMaximumPoolSize returns
            // Integer.MAX_VALUE by design — a scheduled executor doesn't
            // "grow" its pool the way a general ThreadPoolExecutor does.
            // The real bound comes from corePoolSize + the fact that
            // schedule() doesn't spawn beyond core. So the test asserts
            // corePoolSize = 4 as the meaningful bound.
            assertEquals("scheduler-", s.getThreadNamePrefix(),
                    "Thread name prefix must match the P1 config so ops can identify tick threads.");
            // Sanity: pool has cores available (not the pre-init 0 state).
            assertTrue(s.getScheduledThreadPoolExecutor().getCorePoolSize() > 0);
            // Sanity: not the auto-config default (poolSize=1).
            assertNotEquals(1, s.getScheduledThreadPoolExecutor().getCorePoolSize(),
                    "poolSize=1 is the pre-P1 default; regression here reintroduces PERF-M15.");
        } finally {
            s.shutdown();
        }
    }
}
