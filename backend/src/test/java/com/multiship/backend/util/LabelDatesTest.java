package com.multiship.backend.util;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F6-E — coverage for the ship-date helper each connector reads through.
 * The connector-side wiring (UPS InvoiceDate, FedEx shipDatestamp, DHL
 * invoice.date + plannedShippingDateAndTime, SWSIM ShipDate ×2) is a
 * straight-line substitution reviewed by eye; the interesting behavior —
 * IANA vs offset parsing, fail-open on garbage — lives here.
 */
class LabelDatesTest {

    // ===== today =====

    @Test
    void today_null_falls_back_to_utc() {
        assertEquals(LocalDate.now(ZoneOffset.UTC), LabelDates.today(null));
    }

    @Test
    void today_blank_falls_back_to_utc() {
        assertEquals(LocalDate.now(ZoneOffset.UTC), LabelDates.today(""));
        assertEquals(LocalDate.now(ZoneOffset.UTC), LabelDates.today("   "));
    }

    @Test
    void today_invalid_zone_falls_back_to_utc() {
        // A typo like "Ameirca/New_York" (transposed letters) must not
        // brick the shipment pipeline; falling back to UTC keeps
        // pre-F6-E behavior for the bad row and the operator sees the
        // wrong date as a lesser evil than a hard rejection.
        assertEquals(LocalDate.now(ZoneOffset.UTC), LabelDates.today("Ameirca/New_York"));
        assertEquals(LocalDate.now(ZoneOffset.UTC), LabelDates.today("Not/A/Zone"));
    }

    @Test
    void today_iana_zone_returns_local_date() {
        // "Pacific/Kiritimati" is UTC+14 — always at least one calendar day
        // ahead of UTC at every hour of the day. Same-day equality would
        // fail if the helper still used UTC.
        LocalDate utc = LocalDate.now(ZoneOffset.UTC);
        LocalDate kiritimati = LabelDates.today("Pacific/Kiritimati");
        // Kiritimati is +14 offset — its local date is either today (UTC)
        // or tomorrow (UTC) depending on wall clock. Never before.
        assertTrue(!kiritimati.isBefore(utc),
                "Kiritimati (+14) should never be before UTC. utc=" + utc
                        + " kiritimati=" + kiritimati);
    }

    @Test
    void today_fixed_offset_id_works() {
        // "+05:30" (Asia/Kolkata offset) and bare "Z" (UTC) are both valid
        // ZoneId inputs. Callers that read a fixed offset from a downstream
        // system (rather than an IANA name) should get sensible parsing.
        LocalDate india = LabelDates.today("+05:30");
        LocalDate viaIana = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        assertEquals(viaIana, india);

        assertEquals(LocalDate.now(ZoneOffset.UTC), LabelDates.today("Z"));
    }

    @Test
    void today_case_sensitive_iana_matches_java_semantics() {
        // ZoneId is case-sensitive by spec (Java threw on "america/new_york"
        // pre-JDK17 and still normalises via ZoneRulesProvider). Any typo
        // must fall back to UTC without throwing.
        assertEquals(LocalDate.now(ZoneOffset.UTC), LabelDates.today("america/new_york"));
    }

    // ===== todayPlus =====

    @Test
    void todayPlus_shifts_by_days_in_target_zone() {
        // Sanity: +1 day in UTC == UTC-today + 1
        assertEquals(LocalDate.now(ZoneOffset.UTC).plusDays(1), LabelDates.todayPlus(null, 1));
        assertEquals(LocalDate.now(ZoneOffset.UTC).plusDays(2), LabelDates.todayPlus("Z", 2));
        assertEquals(LocalDate.now(ZoneOffset.UTC).minusDays(1), LabelDates.todayPlus("", -1));
    }

    @Test
    void todayPlus_iana_shifts_in_local_calendar() {
        LocalDate expected = LocalDate.now(ZoneId.of("Asia/Tokyo")).plusDays(1);
        assertEquals(expected, LabelDates.todayPlus("Asia/Tokyo", 1));
    }
}
