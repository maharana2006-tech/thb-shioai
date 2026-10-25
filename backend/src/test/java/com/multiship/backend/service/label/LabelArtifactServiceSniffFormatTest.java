package com.multiship.backend.service.label;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * BUG-019 / BUG-024 — pre-"label-package change" UPS rows had
 * {@code format=UNKNOWN} on the label_package table so every
 * artifact endpoint 404'd even though the stored bytes were legit.
 * {@link LabelArtifactService#sniffFormat} now self-heals these on
 * view by detecting the magic bytes and dispatching via the known-
 * format switch. These pin the sniffer's contract.
 */
class LabelArtifactServiceSniffFormatTest {

    @Test
    void detectsZplFromLeadingXa() {
        byte[] zpl = "^XA^FO50,50^A0N,40,40^FDHello^FS^XZ".getBytes(StandardCharsets.ISO_8859_1);
        assertEquals("ZPL", LabelArtifactService.sniffFormat(zpl));
    }

    @Test
    void detectsZplEvenWhenUspsPrependsFxComment() {
        // USPS writer prepends `^FX ... ^FS` before `^XA` — magic byte
        // scan has to look past the first comment to find `^XA`.
        byte[] zpl = "^FX written by USPS at 2026-10-20^FS^XA^FO50,50^FDHi^FS^XZ"
                .getBytes(StandardCharsets.ISO_8859_1);
        assertEquals("ZPL", LabelArtifactService.sniffFormat(zpl));
    }

    @Test
    void detectsZplAfterLeadingWhitespace() {
        // UPS' GraphicImage sometimes base64-decodes to a leading newline
        // before `^XA`; skip whitespace before matching.
        byte[] zpl = "\n\r  ^XA^FO50,50^FDHi^FS^XZ".getBytes(StandardCharsets.ISO_8859_1);
        assertEquals("ZPL", LabelArtifactService.sniffFormat(zpl));
    }

    @Test
    void detectsPdf() {
        byte[] pdf = "%PDF-1.4\n%EOF".getBytes(StandardCharsets.ISO_8859_1);
        assertEquals("PDF", LabelArtifactService.sniffFormat(pdf));
    }

    @Test
    void detectsGif() {
        byte[] gif = "GIF89a".getBytes(StandardCharsets.ISO_8859_1);
        assertEquals("GIF", LabelArtifactService.sniffFormat(gif));
    }

    @Test
    void detectsPng() {
        byte[] png = { (byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a };
        assertEquals("PNG", LabelArtifactService.sniffFormat(png));
    }

    @Test
    void unknownBytesReturnNull() {
        byte[] garbage = { 0x01, 0x02, 0x03, 0x04 };
        assertNull(LabelArtifactService.sniffFormat(garbage));
    }

    @Test
    void emptyAndNullInputReturnNull() {
        assertNull(LabelArtifactService.sniffFormat(null));
        assertNull(LabelArtifactService.sniffFormat(new byte[0]));
    }
}
