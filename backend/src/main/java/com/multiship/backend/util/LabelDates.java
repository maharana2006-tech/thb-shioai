package com.multiship.backend.util;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * F6-E — resolves "today" (and derived planned-ship dates) in the shipper's
 * IANA timezone instead of UTC. Pre-F6-E every carrier connector stamped
 * ship / invoice dates with {@code LocalDate.now(ZoneOffset.UTC)}, which for
 * a shipper printing labels at 07:00 in Sydney (still 21:00 UTC the day
 * before) produced a ship date one day in the past — enough to fail
 * "must ship today" service-level checks or land the wrong customs date on
 * the paperless invoice.
 *
 * <p>Callers pass whatever the {@code ShipmentDefaultsResolver} resolved
 * into {@code ShipmentRequestDTO.shipperTimezone}; on null / blank / invalid
 * we quietly fall back to UTC so legacy callers keep the pre-F6-E behavior
 * and a typo doesn't block a shipment.
 */
public final class LabelDates {

    private LabelDates() {}

    /**
     * Return today's date in {@code timezone}, or today-in-UTC when the
     * argument is null, blank, or unparseable as an IANA zone id.
     * Fixed-offset ids ("+05:30", "-08:00", "Z") also work — they parse via
     * {@link ZoneId#of(String)}.
     */
    public static LocalDate today(String timezone) {
        return LocalDate.now(resolveZone(timezone));
    }

    /**
     * Same as {@link #today(String)} but shifted by {@code days} whole days.
     * Used by connectors that stamp a "planned ship date" of tomorrow (DHL
     * plannedShippingDateAndTime is next-business-day at 13:00).
     */
    public static LocalDate todayPlus(String timezone, int days) {
        return LocalDate.now(resolveZone(timezone)).plusDays(days);
    }

    /**
     * Parse a timezone string to a {@link ZoneId}, returning {@link ZoneOffset#UTC}
     * as the fallback. Kept package-visible so a follow-up plumbing layer
     * (label date-time formatters, e.g. FedEx pickup timestamps) can share the
     * same tolerant parse.
     */
    static ZoneId resolveZone(String timezone) {
        if (timezone == null) return ZoneOffset.UTC;
        String trimmed = timezone.trim();
        if (trimmed.isEmpty()) return ZoneOffset.UTC;
        try {
            return ZoneId.of(trimmed);
        } catch (Exception ex) {
            // Malformed / unknown zone — fall back rather than throw so a
            // Client row with a bad timezone doesn't take out the whole
            // label pipeline. The resolver already validated the eight
            // shippingPurpose values; timezone is free-text IANA, and any
            // typo here is a lower-consequence operator error.
            return ZoneOffset.UTC;
        }
    }
}
