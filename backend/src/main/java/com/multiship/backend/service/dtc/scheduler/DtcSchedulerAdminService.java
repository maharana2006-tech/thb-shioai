package com.multiship.backend.service.dtc.scheduler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.model.DtcSchedulerJob;
import com.multiship.backend.model.DtcSchedulerRun;
import com.multiship.backend.model.DtcSchedulerSettings;
import com.multiship.backend.model.DtcSchedulerWindow;
import com.multiship.backend.repository.DtcSchedulerJobRepository;
import com.multiship.backend.repository.DtcSchedulerRunRepository;
import com.multiship.backend.repository.DtcSchedulerSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

/**
 * Admin side of the DTC scheduler — reads and validates what the
 * Settings → Integrations → DTC Scheduler page saves. The engine picks up
 * every save on its next minute tick.
 */
@Service
@RequiredArgsConstructor
public class DtcSchedulerAdminService {

    static final int NEXT_RUNS = 5;
    static final int MAX_RUN_HISTORY = 200;

    private final DtcSchedulerSettingsRepository settingsRepo;
    private final DtcSchedulerJobRepository jobRepo;
    private final DtcSchedulerRunRepository runRepo;
    private final DtcSchedulerEngine engine;
    private final ObjectMapper objectMapper;

    // ── Wire shapes ─────────────────────────────────────────────────────────

    public record SettingsDto(boolean enabled, String timezone, String serverTimezone,
                              LocalDateTime updatedAt, String updatedBy) {}

    /** Times are "HH:mm". An interval window has startTime/endTime/intervalMinutes; a daily one has runAt. */
    public record WindowDto(Long id, String label, List<String> days, String startTime, String endTime,
                            Integer intervalMinutes, String runAt, boolean enabled) {}

    public record JobDto(String jobKey, String name, String description, boolean enabled,
                         Map<String, Object> params, List<WindowDto> windows, boolean running,
                         LocalDateTime lastRunAt, String lastStatus, String lastMessage, Long lastDurationMs,
                         List<String> nextRuns, LocalDateTime updatedAt, String updatedBy) {}

    public record OverviewDto(SettingsDto settings, List<JobDto> jobs) {}

    public record SettingsUpdate(Boolean enabled, String timezone) {}

    public record JobUpdate(Boolean enabled, Map<String, Object> params, List<WindowDto> windows) {}

    // ── Reads ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public OverviewDto overview() {
        DtcSchedulerSettings s = engine.settings();
        ZoneId zone = engine.zoneOf(s);
        ZonedDateTime now = ZonedDateTime.now(zone);
        List<JobDto> jobs = jobRepo.findAllByOrderBySortOrderAscIdAsc().stream()
                .map(j -> toDto(j, now, Boolean.TRUE.equals(s.getEnabled())))
                .toList();
        return new OverviewDto(toDto(s), jobs);
    }

    @Transactional(readOnly = true)
    public List<DtcSchedulerRun> runs(String jobKey, int limit) {
        requireJob(jobKey);
        int size = Math.max(1, Math.min(limit, MAX_RUN_HISTORY));
        return runRepo.findByJobKeyOrderByStartedAtDesc(jobKey, PageRequest.of(0, size));
    }

    // ── Writes ──────────────────────────────────────────────────────────────

    @Transactional
    public SettingsDto updateSettings(SettingsUpdate req, String actor) {
        DtcSchedulerSettings s = engine.settings();
        if (req.enabled() != null) s.setEnabled(req.enabled());
        if (req.timezone() != null) {
            String tz = req.timezone().trim();
            if (!tz.isEmpty()) {
                try {
                    ZoneId.of(tz);
                } catch (Exception e) {
                    throw new IllegalArgumentException("Unknown time zone \"" + tz + "\".");
                }
            }
            s.setTimezone(tz.isEmpty() ? null : tz);
        }
        s.setUpdatedAt(LocalDateTime.now());
        s.setUpdatedBy(actor);
        return toDto(settingsRepo.save(s));
    }

    @Transactional
    public JobDto updateJob(String jobKey, JobUpdate req, String actor) {
        DtcSchedulerJob job = requireJob(jobKey);
        if (req.enabled() != null) job.setEnabled(req.enabled());
        if (req.params() != null) {
            if (DtcLabelJobRunner.KEY.equals(jobKey)) validateLabelParams(req.params());
            job.setParamsJson(toJson(req.params()));
        }
        if (req.windows() != null) replaceWindows(job, req.windows());
        job.setUpdatedAt(LocalDateTime.now());
        job.setUpdatedBy(actor);
        DtcSchedulerJob saved = jobRepo.saveAndFlush(job);
        DtcSchedulerSettings s = engine.settings();
        return toDto(saved, ZonedDateTime.now(engine.zoneOf(s)), Boolean.TRUE.equals(s.getEnabled()));
    }

    /**
     * Restores the shipped windows and settings on every job. Each job's on/off,
     * the master switch and the timezone are kept — a reset must not quietly
     * start (or stop) label buying.
     */
    @Transactional
    public OverviewDto resetDefaults(String actor) {
        for (DtcSchedulerJob job : jobRepo.findAllByOrderBySortOrderAscIdAsc()) {
            Defaults d = DEFAULTS.get(job.getJobKey());
            if (d == null) continue;
            job.setParamsJson(d.paramsJson());
            replaceWindows(job, d.windows());
            job.setUpdatedAt(LocalDateTime.now());
            job.setUpdatedBy(actor);
            jobRepo.save(job);
        }
        return overview();
    }

    // ── Validation + mapping ────────────────────────────────────────────────

    private void replaceWindows(DtcSchedulerJob job, List<WindowDto> windows) {
        List<DtcSchedulerWindow> built = new ArrayList<>();
        int order = 0;
        for (WindowDto w : windows) {
            built.add(toEntity(job, w, ++order * 10));
        }
        job.getWindows().clear();
        job.getWindows().addAll(built);
    }

    private DtcSchedulerWindow toEntity(DtcSchedulerJob job, WindowDto w, int sortOrder) {
        String name = w.label() == null || w.label().isBlank() ? "Window " + (sortOrder / 10) : w.label().trim();
        if (name.length() > 100) throw new IllegalArgumentException("Window name is longer than 100 characters.");
        if (w.days() == null || w.days().isEmpty()) {
            throw new IllegalArgumentException(name + ": pick at least one day.");
        }
        List<DayOfWeek> days = new ArrayList<>();
        for (String d : w.days()) {
            DayOfWeek day = DtcScheduleCalculator.dayOf(d);
            if (day == null) throw new IllegalArgumentException(name + ": unknown day \"" + d + "\".");
            if (!days.contains(day)) days.add(day);
        }
        days.sort(null);

        DtcSchedulerWindow e = new DtcSchedulerWindow();
        e.setJob(job);
        e.setLabel(name);
        e.setDays(days.stream().map(d -> d.name().substring(0, 3)).collect(Collectors.joining(",")));
        e.setEnabled(w.enabled());
        e.setSortOrder(sortOrder);

        if (w.runAt() != null && !w.runAt().isBlank()) {
            e.setRunAt(parseTime(name, "run time", w.runAt()));
            return e;
        }
        LocalTime start = parseTime(name, "start time", w.startTime());
        LocalTime end = parseTime(name, "end time", w.endTime());
        if (start.equals(end)) {
            throw new IllegalArgumentException(name + ": start and end time can't be the same.");
        }
        Integer every = w.intervalMinutes();
        if (every == null || every < 1 || every > 1440) {
            throw new IllegalArgumentException(name + ": \"every\" must be between 1 and 1440 minutes.");
        }
        e.setStartTime(start);
        e.setEndTime(end);
        e.setIntervalMinutes(every);
        return e;
    }

    private static void validateLabelParams(Map<String, Object> p) {
        Object tenants = p.get("tenants");
        if (tenants != null && !(tenants instanceof List<?> l && l.stream().allMatch(t -> t instanceof String))) {
            throw new IllegalArgumentException("Tenants must be a list of tenant codes.");
        }
        requireRange(p.get("lookbackHours"), "Lookback hours", 1, 720);
        requireRange(p.get("maxBatchesPerRun"), "Max batches per run", 1, 500);
    }

    private static void requireRange(Object v, String name, int min, int max) {
        if (v == null) return;
        if (!(v instanceof Number n) || n.doubleValue() != n.intValue() || n.intValue() < min || n.intValue() > max) {
            throw new IllegalArgumentException(name + " must be a whole number from " + min + " to " + max + ".");
        }
    }

    private static LocalTime parseTime(String window, String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(window + ": " + field + " is required.");
        }
        try {
            return LocalTime.parse(value.trim()).withSecond(0).withNano(0);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException(window + ": " + field + " \"" + value + "\" is not a time (HH:mm).");
        }
    }

    private DtcSchedulerJob requireJob(String jobKey) {
        return jobRepo.findByJobKey(jobKey)
                .orElseThrow(() -> new NoSuchElementException("Job " + jobKey + " not found."));
    }

    private SettingsDto toDto(DtcSchedulerSettings s) {
        return new SettingsDto(Boolean.TRUE.equals(s.getEnabled()), s.getTimezone(),
                ZoneId.systemDefault().getId(), s.getUpdatedAt(), s.getUpdatedBy());
    }

    private JobDto toDto(DtcSchedulerJob j, ZonedDateTime now, boolean masterOn) {
        List<WindowDto> windows = j.getWindows().stream().map(DtcSchedulerAdminService::toDto).toList();
        List<String> next = masterOn && Boolean.TRUE.equals(j.getEnabled())
                ? DtcScheduleCalculator.nextRuns(j.getWindows(), now, NEXT_RUNS).stream()
                    .map(t -> t.toOffsetDateTime().toString()).toList()
                : List.of();
        boolean running = j.getLeaseUntil() != null && j.getLeaseUntil().isAfter(LocalDateTime.now());
        return new JobDto(j.getJobKey(), j.getName(), j.getDescription(), Boolean.TRUE.equals(j.getEnabled()),
                fromJson(j.getParamsJson()), windows, running,
                j.getLastRunAt(), j.getLastStatus(), j.getLastMessage(), j.getLastDurationMs(),
                next, j.getUpdatedAt(), j.getUpdatedBy());
    }

    private static WindowDto toDto(DtcSchedulerWindow w) {
        List<String> days = DtcScheduleCalculator.parseDays(w.getDays()).stream()
                .map(d -> d.name().substring(0, 3)).toList();
        return new WindowDto(w.getId(), w.getLabel(), days, hhmm(w.getStartTime()), hhmm(w.getEndTime()),
                w.getIntervalMinutes(), hhmm(w.getRunAt()), Boolean.TRUE.equals(w.getEnabled()));
    }

    private static String hhmm(LocalTime t) {
        return t == null ? null : String.format("%02d:%02d", t.getHour(), t.getMinute());
    }

    private String toJson(Map<String, Object> params) {
        if (params.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(params);
        } catch (Exception e) {
            throw new IllegalArgumentException("Job settings are not valid JSON.");
        }
    }

    private Map<String, Object> fromJson(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            JsonNode node = objectMapper.readTree(json);
            return node.isObject() ? objectMapper.convertValue(node, Map.class) : Map.of();
        } catch (Exception e) {
            return Map.of();
        }
    }

    // ── ShipXSync shipped defaults (keep in step with the V132 / V134 seeds) ─

    private record Defaults(String paramsJson, List<WindowDto> windows) {}

    private static final List<String> WEEKDAYS = List.of("MON", "TUE", "WED", "THU", "FRI");
    private static final List<String> WEEKEND = List.of("SAT", "SUN");

    private static WindowDto every(String label, List<String> days, String start, String end, int minutes) {
        return new WindowDto(null, label, days, start, end, minutes, null, true);
    }

    private static final List<WindowDto> SHIPX_WINDOWS = List.of(
            every("Weekday morning", WEEKDAYS, "06:00", "11:59", 5),
            every("Weekday peak", WEEKDAYS, "12:00", "21:59", 1),
            every("Weekday night", WEEKDAYS, "22:00", "05:59", 30),
            every("Weekend", WEEKEND, "00:00", "23:59", 5));

    private static final Map<String, Defaults> DEFAULTS = Map.of(
            DtcSyncJobRunner.KEY, new Defaults(null, SHIPX_WINDOWS),
            DtcLabelJobRunner.KEY, new Defaults(
                    "{\"tenants\":[],\"lookbackHours\":24,\"maxBatchesPerRun\":20}", SHIPX_WINDOWS));
}
