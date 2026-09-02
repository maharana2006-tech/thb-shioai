package com.multiship.backend.controller;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Locks in the pure helpers that back {@code GET /orders/{n}/label-state}:
 * the artifact-format sniffer and the matrix-state classifier. Both are
 * package-private static — no controller wiring / no HTTP roundtrip
 * needed to prove they do the right thing.
 *
 * <p>Sibling of {@link OrderControllerEffectivePkgCountTest} which locks
 * in the composite loop-bound helper (issue #545 Part A). This one covers
 * the diagnostic that answers "which matrix row is this order in".
 */
class LabelStateEndpointHelpersTest {

    // ─── sniffStoredArtifactFormat ──────────────────────────────────

    @Test
    void sniff_null_and_blank_return_NONE() throws Exception {
        assertEquals("NONE", invokeSniff(null));
        assertEquals("NONE", invokeSniff(""));
        assertEquals("NONE", invokeSniff("   "));
    }

    @Test
    void sniff_http_urls_return_URL() throws Exception {
        assertEquals("URL", invokeSniff("http://labels.example.com/abc.pdf"));
        assertEquals("URL", invokeSniff("https://labels.example.com/abc.pdf"));
        assertEquals("URL", invokeSniff("HTTPS://labels.example.com/abc.pdf"));
    }

    @Test
    void sniff_raw_zpl_returns_ZPL() throws Exception {
        assertEquals("ZPL", invokeSniff("^XA^FO50,50^A0N,40,40^FDTest^FS^XZ"));
        assertEquals("ZPL", invokeSniff("^XA^XZ"));
    }

    @Test
    void sniff_base64_encoded_zpl_returns_ZPL() throws Exception {
        String stored = Base64.getEncoder().encodeToString("^XA^XZ".getBytes());
        assertEquals("ZPL", invokeSniff(stored));
    }

    @Test
    void sniff_base64_encoded_pdf_returns_PDF() throws Exception {
        String stored = Base64.getEncoder().encodeToString("%PDF-1.4 stuff".getBytes());
        assertEquals("PDF", invokeSniff(stored));
    }

    @Test
    void sniff_base64_encoded_png_returns_BASE64_UNKNOWN() throws Exception {
        // PNG magic isn't in our passthrough vocab; the diagnostic reports
        // BASE64_UNKNOWN so ops know the artifact IS a valid decoded blob,
        // it's just not a format the resolver serves inline.
        String stored = Base64.getEncoder().encodeToString(
                new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n'});
        assertEquals("BASE64_UNKNOWN", invokeSniff(stored));
    }

    @Test
    void sniff_plain_non_base64_text_returns_BASE64_UNKNOWN() throws Exception {
        // Legacy rows that stashed a file path (not a URL, not base64) fall
        // here — the resolver's fetchBytes ultimately returns raw bytes but
        // the sniff won't recognise them, matching the diagnostic contract.
        assertEquals("BASE64_UNKNOWN", invokeSniff("just some plain text nothing base64"));
    }

    // ─── classifyMatrixState ────────────────────────────────────────

    @Test
    void classify_single_pkg_is_STATE_6() throws Exception {
        assertEquals("STATE_6_SINGLE_PKG_NO_BUG", invokeClassify(1, 0, true));
        assertEquals("STATE_6_SINGLE_PKG_NO_BUG", invokeClassify(1, 1, true));
    }

    @Test
    void classify_multi_pkg_with_zero_rows_is_STATE_5() throws Exception {
        // packageCount=2, label_package empty (state 5 — carrier's per-piece
        // labels were never persisted → composite 404s, FE falls back to
        // facsimile of pkg 1 only).
        assertEquals("STATE_5_NO_ROWS_FACSIMILE_ONLY", invokeClassify(2, 0, true));
    }

    @Test
    void classify_multi_pkg_with_missing_rows_is_STATE_4() throws Exception {
        // effective=2 but only 1 row present.
        assertEquals("STATE_4_MISSING_ROWS", invokeClassify(2, 1, true));
    }

    @Test
    void classify_all_rows_present_and_resolvable_is_STATE_1_OR_2() throws Exception {
        assertEquals("STATE_1_OR_2_OK", invokeClassify(2, 2, true));
        // rowCount > effective is also fine — the loop iterates rowCount
        // rows and effective was really the min. In practice they should
        // match; this test ensures we don't spurious-fail.
        assertEquals("STATE_1_OR_2_OK", invokeClassify(2, 3, true));
    }

    @Test
    void classify_rows_present_but_bytes_unresolvable_is_STATE_3() throws Exception {
        // Row exists but label_file_path is blank / a stored URL that 404s
        // / a format the sniffer doesn't recognise. Composite silently
        // skips the panel.
        assertEquals("STATE_3_ROW_PRESENT_BUT_BYTES_UNRESOLVABLE",
                invokeClassify(2, 2, false));
    }

    // ─── reflection helpers ─────────────────────────────────────────

    private static String invokeSniff(String stored) throws Exception {
        Method m = OrderController.class.getDeclaredMethod("sniffStoredArtifactFormat", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, stored);
    }

    private static String invokeClassify(int effective, int rowCount, boolean allResolvable) throws Exception {
        Method m = OrderController.class.getDeclaredMethod("classifyMatrixState",
                int.class, int.class, boolean.class);
        m.setAccessible(true);
        return (String) m.invoke(null, effective, rowCount, allResolvable);
    }
}
