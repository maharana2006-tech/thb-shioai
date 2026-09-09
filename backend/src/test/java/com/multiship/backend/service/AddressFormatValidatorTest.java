package com.multiship.backend.service;

import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.ShipmentValidationResult.ValidationIssue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 52 PR β — AddressFormatValidator per-country pins. Covers all
 * six countries with a happy-path + a format-fail + a state-enum-fail
 * per country. Country-not-listed case pins the "unknown countries pass
 * silently" contract.
 */
class AddressFormatValidatorTest {

    // ─── Country not listed — pass silently ────────────────────────────

    @Test
    void unknownCountry_returnsEmpty() {
        // Japan: no rules in the validator. Must not error just because
        // the country isn't in the maps.
        assertTrue(AddressFormatValidator.validate("JP", "100-0001", "Tokyo", "recipient").isEmpty());
        assertTrue(AddressFormatValidator.validate("DE", "10115", "BE", "recipient").isEmpty());
    }

    @Test
    void blankCountry_returnsEmpty() {
        assertTrue(AddressFormatValidator.validate("", "12345", "CA", "recipient").isEmpty());
        assertTrue(AddressFormatValidator.validate(null, "12345", "CA", "recipient").isEmpty());
    }

    // ─── US ────────────────────────────────────────────────────────────

    @Test
    void us_validZipAndState_returnsEmpty() {
        assertTrue(AddressFormatValidator.validate("US", "94105", "CA", "recipient").isEmpty());
        assertTrue(AddressFormatValidator.validate("US", "94105-1234", "CA", "recipient").isEmpty());
    }

    @Test
    void us_invalidZipFormat_errorsWithPostalField() {
        List<ValidationIssue> issues = AddressFormatValidator.validate("US", "abcde", "CA", "recipient");
        assertEquals(1, issues.size());
        assertEquals("recipient.postalCode", issues.get(0).getField());
        assertEquals(ErrorCode.VALIDATION_ERROR.name(), issues.get(0).getCode());
        assertTrue(issues.get(0).getMessage().contains("US"));
        assertTrue(issues.get(0).getMessage().contains("12345"));
    }

    @Test
    void us_stateFullName_errorsWithStateField() {
        // "Delaware" instead of "DE" — the exact case that got PASS on
        // the report. Format validator must flag it.
        List<ValidationIssue> issues = AddressFormatValidator.validate("US", "19709", "Delaware", "recipient");
        assertEquals(1, issues.size());
        assertEquals("recipient.state", issues.get(0).getField());
        assertTrue(issues.get(0).getMessage().contains("Delaware"));
        assertTrue(issues.get(0).getMessage().contains("2-letter code"));
    }

    @Test
    void us_bothInvalid_errorsBoth() {
        List<ValidationIssue> issues = AddressFormatValidator.validate("US", "99999-99999", "XX", "recipient");
        assertEquals(2, issues.size());
    }

    @Test
    void us_territoryAndOutlyingStates_valid() {
        assertTrue(AddressFormatValidator.validate("US", "00601", "PR", "recipient").isEmpty());
        assertTrue(AddressFormatValidator.validate("US", "96799", "AS", "recipient").isEmpty());
        // Military APO/FPO/DPO handled via AA/AE/AP codes.
        assertTrue(AddressFormatValidator.validate("US", "09000", "AE", "recipient").isEmpty());
    }

    // ─── Canada ────────────────────────────────────────────────────────

    @Test
    void ca_validPostalAndProvince() {
        assertTrue(AddressFormatValidator.validate("CA", "M5V 3L9", "ON", "recipient").isEmpty());
        // Space-optional per Canada Post.
        assertTrue(AddressFormatValidator.validate("CA", "M5V3L9", "ON", "recipient").isEmpty());
        // Lowercase in — validator uppercases.
        assertTrue(AddressFormatValidator.validate("CA", "m5v 3l9", "on", "recipient").isEmpty());
    }

    @Test
    void ca_invalidPostal_errors() {
        List<ValidationIssue> issues = AddressFormatValidator.validate("CA", "12345", "ON", "recipient");
        assertEquals(1, issues.size());
        assertTrue(issues.get(0).getMessage().contains("A1A 1A1"));
    }

    @Test
    void ca_invalidProvince_errors() {
        List<ValidationIssue> issues = AddressFormatValidator.validate("CA", "M5V 3L9", "Ontario", "recipient");
        assertEquals(1, issues.size());
        assertEquals("recipient.state", issues.get(0).getField());
    }

    // ─── Australia ─────────────────────────────────────────────────────

    @Test
    void au_valid() {
        assertTrue(AddressFormatValidator.validate("AU", "2000", "NSW", "recipient").isEmpty());
    }

    @Test
    void au_invalidPostal_errors() {
        assertEquals(1, AddressFormatValidator.validate("AU", "abcd", "NSW", "recipient").size());
    }

    @Test
    void au_invalidState_errors() {
        assertEquals(1, AddressFormatValidator.validate("AU", "2000", "New South Wales", "recipient").size());
    }

    // ─── Mexico ────────────────────────────────────────────────────────

    @Test
    void mx_valid() {
        assertTrue(AddressFormatValidator.validate("MX", "06000", "CMX", "recipient").isEmpty());
    }

    @Test
    void mx_invalidPostal_errors() {
        assertEquals(1, AddressFormatValidator.validate("MX", "6000", "CMX", "recipient").size());
    }

    // ─── Brazil ────────────────────────────────────────────────────────

    @Test
    void br_valid() {
        // With and without hyphen — both accepted.
        assertTrue(AddressFormatValidator.validate("BR", "01310-100", "SP", "recipient").isEmpty());
        assertTrue(AddressFormatValidator.validate("BR", "01310100", "SP", "recipient").isEmpty());
    }

    @Test
    void br_invalidPostal_errors() {
        assertEquals(1, AddressFormatValidator.validate("BR", "0131", "SP", "recipient").size());
    }

    @Test
    void br_invalidState_errors() {
        assertEquals(1, AddressFormatValidator.validate("BR", "01310-100", "SaoPaulo", "recipient").size());
    }

    // ─── United Kingdom ────────────────────────────────────────────────

    @Test
    void uk_valid() {
        assertTrue(AddressFormatValidator.validate("GB", "SW1A 1AA", null, "recipient").isEmpty());
        assertTrue(AddressFormatValidator.validate("UK", "SW1A 1AA", null, "recipient").isEmpty());
        assertTrue(AddressFormatValidator.validate("GB", "EC1A1BB", null, "recipient").isEmpty()); // space optional
    }

    @Test
    void uk_invalidPostal_errors() {
        assertEquals(1, AddressFormatValidator.validate("GB", "12345", null, "recipient").size());
    }

    // ─── Field prefix wiring ───────────────────────────────────────────

    @Test
    void senderPrefix_producesSenderField() {
        List<ValidationIssue> issues = AddressFormatValidator.validate("US", "abcde", "CA", "sender");
        assertEquals("sender.postalCode", issues.get(0).getField());
    }

    // ─── Missing fields on a known country — silently pass ─────────────

    @Test
    void us_blankPostal_and_blankState_returnsEmpty() {
        // Blank is a presence-check concern (handled elsewhere in
        // ShipmentValidationService). This validator only errors on
        // NON-BLANK BUT MALFORMED values.
        assertTrue(AddressFormatValidator.validate("US", "", "CA", "recipient").isEmpty());
        assertTrue(AddressFormatValidator.validate("US", "94105", "", "recipient").isEmpty());
        assertTrue(AddressFormatValidator.validate("US", null, null, "recipient").isEmpty());
    }

    // ─── PR A — postalCodeRequiredFor whitelist ────────────────────────

    @org.junit.jupiter.api.Test
    void postalCodeRequired_isTrueForRegularCountries() {
        assertTrue(AddressFormatValidator.postalCodeRequiredFor("US"));
        assertTrue(AddressFormatValidator.postalCodeRequiredFor("gb"));
        assertTrue(AddressFormatValidator.postalCodeRequiredFor("DE"));
        assertTrue(AddressFormatValidator.postalCodeRequiredFor("JP"));
    }

    @org.junit.jupiter.api.Test
    void postalCodeRequired_isFalseForHongKong() {
        // Live 49-country audit — HK is the biggest miss. UPS
        // "Recipient postal code is required" fires on the wire; we
        // catch it upstream now.
        assertFalse(AddressFormatValidator.postalCodeRequiredFor("HK"));
        assertFalse(AddressFormatValidator.postalCodeRequiredFor("hk"));
    }

    @org.junit.jupiter.api.Test
    void postalCodeRequired_isFalseForUae() {
        // UAE also flagged by the 49-country matrix — no street-address
        // postal system.
        assertFalse(AddressFormatValidator.postalCodeRequiredFor("AE"));
    }

    @org.junit.jupiter.api.Test
    void postalCodeRequired_isTrueForBlankCountry() {
        // Missing country → strict path (safe default; the presence check
        // for country runs alongside so this doesn't double-error).
        assertTrue(AddressFormatValidator.postalCodeRequiredFor(""));
        assertTrue(AddressFormatValidator.postalCodeRequiredFor(null));
    }

    @org.junit.jupiter.api.Test
    void postalCodeRequired_env_extension_addsCountryToWhitelist() {
        // Config-driven extension: JP hasn't dropped its postal system,
        // but if a future deployment wants to skip the check for a
        // corridor (e.g. carrier accepts blank experimentally), the
        // property lets us opt-out without a code deploy.
        assertTrue(AddressFormatValidator.postalCodeRequiredFor("JP"));
        assertFalse(AddressFormatValidator.postalCodeRequiredFor("JP", java.util.Set.of("JP")));
        assertFalse(AddressFormatValidator.postalCodeRequiredFor("jp", java.util.Set.of("JP")));
    }

    @org.junit.jupiter.api.Test
    void noPostalCodeCountries_containsCoreList() {
        // Wikipedia-sourced core list — pin a few important ones.
        assertTrue(AddressFormatValidator.NO_POSTAL_CODE_COUNTRIES.contains("HK"));
        assertTrue(AddressFormatValidator.NO_POSTAL_CODE_COUNTRIES.contains("AE"));
        assertTrue(AddressFormatValidator.NO_POSTAL_CODE_COUNTRIES.contains("QA"));
        assertTrue(AddressFormatValidator.NO_POSTAL_CODE_COUNTRIES.contains("BS"));
        assertTrue(AddressFormatValidator.NO_POSTAL_CODE_COUNTRIES.contains("FJ"));
    }
}
