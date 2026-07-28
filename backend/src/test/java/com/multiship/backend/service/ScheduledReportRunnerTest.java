package com.multiship.backend.service;

import com.multiship.backend.model.ScheduledReport.Frequency;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sprint 45 — nextRun schedule math. The runner itself is verified in
 * an integration-style smoke test on the full suite; the pure-function
 * boundary lives here.
 */
class ScheduledReportRunnerTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 28, 12, 0);

    @Test
    void daily_addsOneDay() {
        assertEquals(NOW.plusDays(1), ScheduledReportRunner.nextRun(Frequency.DAILY, NOW));
    }

    @Test
    void weekly_addsOneWeek() {
        assertEquals(NOW.plusWeeks(1), ScheduledReportRunner.nextRun(Frequency.WEEKLY, NOW));
    }

    @Test
    void monthly_addsOneMonth() {
        assertEquals(NOW.plusMonths(1), ScheduledReportRunner.nextRun(Frequency.MONTHLY, NOW));
    }
}
