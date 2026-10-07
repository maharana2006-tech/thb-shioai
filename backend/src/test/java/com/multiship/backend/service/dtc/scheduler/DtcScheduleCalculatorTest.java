package com.multiship.backend.service.dtc.scheduler;

import com.multiship.backend.model.DtcSchedulerWindow;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DtcScheduleCalculatorTest {

    private static final ZoneId NY = ZoneId.of("America/New_York");

    // 2026-10-05 is a Monday.
    private static ZonedDateTime at(int day, int hour, int minute) {
        return ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, NY);
    }

    private static DtcSchedulerWindow every(String days, String start, String end, int minutes) {
        DtcSchedulerWindow w = new DtcSchedulerWindow();
        w.setDays(days);
        w.setStartTime(LocalTime.parse(start));
        w.setEndTime(LocalTime.parse(end));
        w.setIntervalMinutes(minutes);
        w.setEnabled(true);
        return w;
    }

    @Test
    void intervalWindowRunsOnTheGridFromItsStart() {
        DtcSchedulerWindow w = every("MON,TUE,WED,THU,FRI", "06:00", "11:59", 5);
        assertTrue(DtcScheduleCalculator.isDue(w, at(5, 6, 0)));
        assertTrue(DtcScheduleCalculator.isDue(w, at(5, 11, 55)));
        assertFalse(DtcScheduleCalculator.isDue(w, at(5, 6, 3)));
        assertFalse(DtcScheduleCalculator.isDue(w, at(5, 12, 0)));
        assertFalse(DtcScheduleCalculator.isDue(w, at(5, 5, 55)));
    }

    @Test
    void windowCrossingMidnightUsesTheRunDay() {
        DtcSchedulerWindow w = every("MON,TUE,WED,THU,FRI", "22:00", "05:59", 30);
        assertTrue(DtcScheduleCalculator.isDue(w, at(5, 22, 30)));   // Mon night
        assertTrue(DtcScheduleCalculator.isDue(w, at(6, 0, 0)));     // Tue early
        assertTrue(DtcScheduleCalculator.isDue(w, at(6, 5, 30)));
        assertFalse(DtcScheduleCalculator.isDue(w, at(6, 6, 0)));
        assertFalse(DtcScheduleCalculator.isDue(w, at(10, 1, 0)));   // Saturday
    }

    @Test
    void weekdayOnlyWindowSkipsWeekend() {
        DtcSchedulerWindow w = every("MON,TUE,WED,THU,FRI", "12:00", "21:59", 1);
        assertTrue(DtcScheduleCalculator.isDue(w, at(9, 21, 59)));   // Friday
        assertFalse(DtcScheduleCalculator.isDue(w, at(10, 12, 0)));  // Saturday
    }

    @Test
    void dailyWindowRunsOnceAtRunAt() {
        DtcSchedulerWindow w = new DtcSchedulerWindow();
        w.setDays("MON,TUE,WED,THU,FRI,SAT,SUN");
        w.setRunAt(LocalTime.of(21, 0));
        w.setEnabled(true);
        assertTrue(DtcScheduleCalculator.isDue(w, at(10, 21, 0)));
        assertFalse(DtcScheduleCalculator.isDue(w, at(10, 21, 1)));
    }

    @Test
    void disabledWindowNeverRuns() {
        DtcSchedulerWindow w = every("MON", "00:00", "23:59", 1);
        w.setEnabled(false);
        assertFalse(DtcScheduleCalculator.isDue(w, at(5, 10, 0)));
    }

    @Test
    void nextRunsCrossesFromPeakToNight() {
        List<DtcSchedulerWindow> ws = List.of(
                every("MON,TUE,WED,THU,FRI", "12:00", "21:59", 1),
                every("MON,TUE,WED,THU,FRI", "22:00", "05:59", 30));
        List<ZonedDateTime> next = DtcScheduleCalculator.nextRuns(ws, at(5, 21, 58), 3);
        assertEquals(List.of(at(5, 21, 59), at(5, 22, 0), at(5, 22, 30)), next);
    }
}
