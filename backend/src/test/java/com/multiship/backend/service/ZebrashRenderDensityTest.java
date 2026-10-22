package com.multiship.backend.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Density inference for {@link ZebrashRenderer#computeSpec} — the fix for
 * the UI-vs-printer label mismatch. The printer renders raw ZPL at its
 * native density; the emulator must match it instead of the old hardcoded
 * 4"/8dpmm. Pure geometry math, no binary (mirrors
 * {@link ZebrashCompositorBadgeTest}'s binary-free approach).
 *
 * <p>Canvas px width = {@code widthIn × 25.4 × dpmm}; ZPL dots land 1:1,
 * so "not clipped" means that product ≥ {@code ^PW}.
 */
class ZebrashRenderDensityTest {

    private static byte[] zpl(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static int canvasWidthPx(ZebrashRenderer.RenderSpec s) {
        return (int) Math.round(s.widthIn() * 25.4 * s.dpmm());
    }

    private static int canvasHeightPx(ZebrashRenderer.RenderSpec s) {
        return (int) Math.round(s.heightIn() * 25.4 * s.dpmm());
    }

    // ── (a) Guard: 203-dpi labels are byte-for-byte unchanged ──────────────

    @Test
    void standard_203dpi_label_keeps_legacy_geometry() {
        ZebrashRenderer.RenderSpec s =
                ZebrashRenderer.computeSpec(zpl("^XA^CI28^PW812^LL1218^FO50,50^FDhi^FS^XZ"), 0);
        assertEquals(8, s.dpmm());
        assertEquals(4.0, s.widthIn(), 0.0001);
        assertEquals(6.0, s.heightIn(), 0.0001);
    }

    @Test
    void zpl_without_print_width_keeps_legacy_geometry() {
        ZebrashRenderer.RenderSpec s =
                ZebrashRenderer.computeSpec(zpl("^XA^FO50,50^FDhi^FS^XZ"), 0);
        assertEquals(8, s.dpmm());
        assertEquals(4.0, s.widthIn(), 0.0001);
        assertEquals(6.0, s.heightIn(), 0.0001);
    }

    // ── (b) 300-dpi label renders full-width, not clipped to 812 ───────────

    @Test
    void label_300dpi_infers_12dpmm_and_fits_full_width() {
        ZebrashRenderer.RenderSpec s =
                ZebrashRenderer.computeSpec(zpl("^XA^PW1200^LL1800^FO1100,1700^FDedge^FS^XZ"), 0);
        assertEquals(12, s.dpmm(), "^PW1200 on 4\" stock ≈ 300 dpi");
        // Canvas holds all 1200 dots — the bug was clipping to 812.
        assertTrue(canvasWidthPx(s) >= 1200,
                "canvas " + canvasWidthPx(s) + "px must hold ^PW1200 dots");
        assertTrue(canvasWidthPx(s) < 1300, "but not wildly oversized: " + canvasWidthPx(s));
        // And the field at y=1700 isn't clipped vertically either.
        assertTrue(canvasHeightPx(s) >= 1800,
                "canvas height " + canvasHeightPx(s) + "px must hold ^LL1800 dots");
    }

    @Test
    void force_dpmm_overrides_inference() {
        // Non-4" stock: ^PW alone is ambiguous, operator forces 12dpmm.
        byte[] z = zpl("^XA^FO50,50^FDhi^FS^XZ");
        assertEquals(8, ZebrashRenderer.computeSpec(z, 0).dpmm());
        assertEquals(12, ZebrashRenderer.computeSpec(z, 12).dpmm());
    }

    // ── (c) PDF page follows the label's true size (no down-scale) ─────────

    @Test
    void pdf_page_matches_label_physical_size_not_fixed_4x6() {
        // Tall 203-dpi label (^LL2400 ≈ 11.8", capped at 8"). The old PDF
        // path crammed it onto a fixed 432pt (6") page, down-scaling the
        // content; the page height must now follow the label.
        ZebrashRenderer.RenderSpec s =
                ZebrashRenderer.computeSpec(zpl("^XA^PW812^LL2400^FO50,2300^FDfoot^FS^XZ"), 0);
        float pageHeightPt = (float) (s.heightIn() * 72f);
        assertEquals(8.0, s.heightIn(), 0.0001);
        assertTrue(pageHeightPt > 432f,
                "page " + pageHeightPt + "pt must exceed the old fixed 432pt sheet");

        // 300-dpi label: the render is 1200px wide on a widthIn (≈4") page,
        // so the PDF embeds all 1200 dots at native density — no resample
        // onto a fixed 288pt (4") sheet that shrank the content before.
        ZebrashRenderer.RenderSpec hd =
                ZebrashRenderer.computeSpec(zpl("^XA^PW1200^LL1800^XZ"), 0);
        assertEquals(12, hd.dpmm());
        assertEquals(1200, canvasWidthPx(hd));
    }
}
