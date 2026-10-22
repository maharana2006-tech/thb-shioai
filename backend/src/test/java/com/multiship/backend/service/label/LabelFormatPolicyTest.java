package com.multiship.backend.service.label;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Phase 0 gate — one case per carrier plus the precedence rules. If this
 * table changes, a stored label somewhere changes format, so the test is
 * deliberately explicit rather than parameterised.
 */
class LabelFormatPolicyTest {

    private final LabelFormatPolicy policy = new LabelFormatPolicy();

    // ── carrier defaults (nothing overrides) ───────────────────────────────

    @Test void usps_defaults_to_zpl()   { assertEquals("ZPL", policy.requestedFormat("USPS", null, null)); }
    @Test void stamps_defaults_to_zpl() { assertEquals("ZPL", policy.requestedFormat("STAMPS", null, null)); }
    @Test void ups_defaults_to_zpl()    { assertEquals("ZPL", policy.requestedFormat("UPS", null, null)); }
    @Test void fedex_defaults_to_zpl()  { assertEquals("ZPL", policy.requestedFormat("FEDEX", null, null)); }

    @Test
    void dhl_defaults_to_pdf_until_its_zpl_template_is_validated() {
        assertEquals("PDF", policy.requestedFormat("DHL", null, null));
    }

    @Test
    void unknown_carrier_defaults_to_zpl() {
        assertEquals("ZPL", policy.requestedFormat("SOMETHING_NEW", null, null));
    }

    // ── precedence: request > account > carrier default ────────────────────

    @Test
    void account_override_beats_carrier_default() {
        assertEquals("PDF", policy.requestedFormat("UPS", "PDF", null));
    }

    @Test
    void request_override_beats_account_and_carrier() {
        assertEquals("PNG", policy.requestedFormat("UPS", "PDF", "PNG"));
    }

    @Test
    void blank_overrides_are_ignored_not_treated_as_a_format() {
        assertEquals("ZPL", policy.requestedFormat("UPS", "   ", ""));
    }

    @Test
    void format_is_normalised_to_upper_case_and_trimmed() {
        assertEquals("ZPL", policy.requestedFormat("ups", " zpl ", null));
        assertEquals("PDF", policy.requestedFormat("dhl", null, null));
    }

    // ── format → provenance ────────────────────────────────────────────────

    @Test
    void only_real_zpl_is_native() {
        assertEquals("CARRIER_NATIVE", LabelFormatPolicy.sourceFor("ZPL"));
        assertEquals("CARRIER_NATIVE", LabelFormatPolicy.sourceFor("zpl"));
    }

    @Test
    void every_image_or_pdf_is_a_raster() {
        assertEquals("CARRIER_RASTER", LabelFormatPolicy.sourceFor("PDF"));
        assertEquals("CARRIER_RASTER", LabelFormatPolicy.sourceFor("GIF"));
        assertEquals("CARRIER_RASTER", LabelFormatPolicy.sourceFor("PNG"));
    }

    @Test
    void unknown_stays_unknown_rather_than_guessed() {
        assertEquals("UNKNOWN", LabelFormatPolicy.sourceFor("UNKNOWN"));
        assertEquals("UNKNOWN", LabelFormatPolicy.sourceFor(null));
    }
}
