package com.multiship.backend.service;

import com.multiship.backend.model.CutoffRule;
import com.multiship.backend.model.Holiday;
import com.multiship.backend.repository.CutoffRuleRepository;
import com.multiship.backend.repository.HolidayRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * G7 — decide whether a shipment about to be labelled is "past cutoff"
 * for its (source × carrier × warehouse) combination, and if so,
 * compute the next working-day date for SHIP_DATE. Skips weekends AND
 * every active row in the {@code holiday} table.
 *
 * <p>Precedence for the effective cutoff time:
 * <ol>
 *   <li>Most-specific matching rule (source + carrier + warehouse all
 *       specified) wins over a broader rule (any-null field).</li>
 *   <li>Rule's timezone → client timezone → UTC.</li>
 * </ol>
 * If no active rule matches → shift never fires (returns original date).
 *
 * <p>Called from CarrierServiceImpl before the carrier request is built
 * so the SHIP_DATE stamped on the carrier wire uses the shifted date.
 * Label still generates immediately — this only rewrites what date NDS /
 * the carrier receives.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CutoffShiftService {

    private final CutoffRuleRepository ruleRepo;
    private final HolidayRepository holidayRepo;

    /** Result carrier so callers can log the reason without re-computing. */
    public record ShiftDecision(LocalDate shipDate, boolean shifted, String reason) {}

    /**
     * @param source        MANUAL | BULK | API | WMS | DTC | null
     * @param carrierCode   FEDEX | UPS | USPS | DHL | null
     * @param warehouseId   nullable
     * @param clientTz      IANA tz used when the matched rule doesn't specify
     * @param now           current instant (test seam)
     */
    public ShiftDecision resolveShipDate(String source, String carrierCode,
                                          Long warehouseId, String clientTz,
                                          LocalDateTime now) {
        CutoffRule rule = pickRule(source, carrierCode, warehouseId);
        if (rule == null) {
            return new ShiftDecision(now.toLocalDate(), false, "no cutoff rule matched");
        }
        String tz = rule.getTimezone() != null && !rule.getTimezone().isBlank()
                ? rule.getTimezone()
                : (clientTz != null && !clientTz.isBlank() ? clientTz : "UTC");
        ZonedDateTime localNow = ZonedDateTime.of(now, ZoneId.of("UTC"))
                .withZoneSameInstant(ZoneId.of(tz));
        LocalTime cutoff = rule.getCutoffTime();
        boolean pastCutoff = localNow.toLocalTime().isAfter(cutoff)
                || localNow.toLocalTime().equals(cutoff);
        LocalDate baseDate = localNow.toLocalDate();
        Set<LocalDate> holidays = holidayRepo.findByActiveTrue().stream()
                .map(Holiday::getHolidayDate)
                .collect(Collectors.toSet());
        boolean baseIsWorkday = isWorkday(baseDate, holidays);
        if (!pastCutoff && baseIsWorkday) {
            return new ShiftDecision(baseDate, false,
                    "before cutoff " + cutoff + " " + tz);
        }
        LocalDate shifted = nextWorkday(baseDate, holidays, pastCutoff);
        return new ShiftDecision(shifted, true,
                (pastCutoff ? "past cutoff " + cutoff + " " + tz : "non-workday " + baseDate)
                        + " → " + shifted);
    }

    /**
     * Rank rules by specificity so the most-specific wins. Score:
     *   source specified: +4, carrier specified: +2, warehouse specified: +1.
     * Ties broken by lowest id (deterministic).
     */
    private CutoffRule pickRule(String source, String carrierCode, Long warehouseId) {
        String s = source == null ? null : source.trim().toUpperCase();
        String c = carrierCode == null ? null : carrierCode.trim().toUpperCase();
        List<CutoffRule> candidates = ruleRepo.findByActiveTrue();
        CutoffRule best = null;
        int bestScore = -1;
        for (CutoffRule r : candidates) {
            if (r.getSource() != null && !r.getSource().equalsIgnoreCase(s)) continue;
            if (r.getCarrierCode() != null && !r.getCarrierCode().equalsIgnoreCase(c)) continue;
            if (r.getWarehouseId() != null && !r.getWarehouseId().equals(warehouseId)) continue;
            int score = (r.getSource() != null ? 4 : 0)
                    + (r.getCarrierCode() != null ? 2 : 0)
                    + (r.getWarehouseId() != null ? 1 : 0);
            if (score > bestScore
                    || (score == bestScore && best != null && r.getId() < best.getId())) {
                best = r;
                bestScore = score;
            }
        }
        return best;
    }

    /** Advance by 1 day; if past-cutoff mode, always advance at least once. */
    LocalDate nextWorkday(LocalDate from, Set<LocalDate> holidays, boolean forceAdvance) {
        LocalDate d = forceAdvance ? from.plusDays(1) : from;
        while (!isWorkday(d, holidays)) {
            d = d.plusDays(1);
        }
        return d;
    }

    static boolean isWorkday(LocalDate d, Set<LocalDate> holidays) {
        DayOfWeek dow = d.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) return false;
        return !holidays.contains(d);
    }
}
