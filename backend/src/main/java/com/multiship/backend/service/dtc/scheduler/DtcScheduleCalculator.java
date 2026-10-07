package com.multiship.backend.service.dtc.scheduler;

import com.multiship.backend.model.DtcSchedulerWindow;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Pure window maths for the DTC scheduler — no Spring, no DB.
 *
 * <p>An interval window fires at its start time and then every N minutes
 * until its end time (inclusive); the range may cross midnight, e.g.
 * 22:00–05:59. The window's days are the days the run happens on, so with
 * MON–FRI 22:00–05:59, Monday 00:00–05:59 runs and Saturday 00:00–05:59
 * does not. A daily window fires once at its run-at time.
 */
public final class DtcScheduleCalculator {

    private static final int MINUTES_PER_DAY = 24 * 60;
    /** How far ahead {@link #nextRuns} looks — a little over a week covers every window. */
    private static final int LOOKAHEAD_MINUTES = 8 * MINUTES_PER_DAY;

    private DtcScheduleCalculator() {}

    /** True when {@code window} has a run at the minute {@code at} falls in. */
    public static boolean isDue(DtcSchedulerWindow window, ZonedDateTime at) {
        if (!Boolean.TRUE.equals(window.getEnabled())) return false;
        if (!parseDays(window.getDays()).contains(at.getDayOfWeek())) return false;
        int now = minuteOfDay(at.toLocalTime());

        if (window.getRunAt() != null) {
            return now == minuteOfDay(window.getRunAt());
        }
        if (window.getStartTime() == null || window.getEndTime() == null
                || window.getIntervalMinutes() == null || window.getIntervalMinutes() < 1) {
            return false;
        }
        int start = minuteOfDay(window.getStartTime());
        int end = minuteOfDay(window.getEndTime());
        boolean inside = start <= end
                ? now >= start && now <= end
                : now >= start || now <= end;
        if (!inside) return false;
        int sinceStart = Math.floorMod(now - start, MINUTES_PER_DAY);
        return sinceStart % window.getIntervalMinutes() == 0;
    }

    public static boolean isDue(Collection<DtcSchedulerWindow> windows, ZonedDateTime at) {
        for (DtcSchedulerWindow w : windows) {
            if (isDue(w, at)) return true;
        }
        return false;
    }

    /** The next {@code count} run minutes strictly after {@code from}. */
    public static List<ZonedDateTime> nextRuns(Collection<DtcSchedulerWindow> windows, ZonedDateTime from, int count) {
        List<ZonedDateTime> out = new ArrayList<>(count);
        if (windows.isEmpty() || count <= 0) return out;
        ZonedDateTime t = from.truncatedTo(ChronoUnit.MINUTES);
        for (int i = 0; i < LOOKAHEAD_MINUTES && out.size() < count; i++) {
            t = t.plusMinutes(1);
            if (isDue(windows, t)) out.add(t);
        }
        return out;
    }

    /** "MON,TUE" → {MONDAY, TUESDAY}; unknown tokens are ignored. */
    public static Set<DayOfWeek> parseDays(String days) {
        Set<DayOfWeek> out = EnumSet.noneOf(DayOfWeek.class);
        if (days == null) return out;
        for (String token : days.split(",")) {
            DayOfWeek d = dayOf(token.trim());
            if (d != null) out.add(d);
        }
        return out;
    }

    /** "MON" / "monday" → MONDAY; null when unrecognised. */
    public static DayOfWeek dayOf(String token) {
        if (token == null || token.length() < 3) return null;
        String p = token.substring(0, 3).toUpperCase();
        for (DayOfWeek d : DayOfWeek.values()) {
            if (d.name().startsWith(p)) return d;
        }
        return null;
    }

    private static int minuteOfDay(LocalTime t) {
        return t.getHour() * 60 + t.getMinute();
    }
}
