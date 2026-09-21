package com.multiship.backend.service.printing;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Office lasers speak PCL, not PDF: a Samsung K2200 lists PCL, PCL XL, QPDL
 * and plain text. Sent anything else it prints the bytes as text — a label's
 * ZPL came out as pages of hex. These pin what a PCL printer receives.
 */
class PclRasterizerTest {

    private static byte[] pdf(int pages, boolean blackBox) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) {
                PDPage page = new PDPage(PDRectangle.LETTER);
                doc.addPage(page);
                if (blackBox) {
                    try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                        cs.addRect(72, 600, 144, 72);   // a solid 2" x 1" block
                        cs.fill();
                    }
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static int count(byte[] haystack, String needle) {
        String s = new String(haystack, StandardCharsets.ISO_8859_1);
        int n = 0;
        for (int i = s.indexOf(needle); i >= 0; i = s.indexOf(needle, i + 1)) n++;
        return n;
    }

    @Test
    void oneSheetPerPdfPage_notPagesOfText() throws Exception {
        byte[] pcl = PclRasterizer.fromPdf(pdf(2, true), "LETTER");
        String head = new String(pcl, 0, 40, StandardCharsets.ISO_8859_1);
        assertTrue(head.startsWith("E"), "the job starts with a printer reset");
        assertTrue(head.contains("&l2A"), "Letter page size");
        assertEquals(2, count(pcl, "*r1A"), "one raster graphic per page");
        assertEquals(2, count(pcl, "\f"), "one form feed per page — two pages in, two sheets out");
        assertEquals(1, count(pcl, "*b2M") / 2 + 0, "PackBits compression is selected for each page");
        assertTrue(new String(pcl, StandardCharsets.ISO_8859_1).endsWith("E"), "and ends with a reset");
    }

    @Test
    void a4GetsTheA4PageSize() throws Exception {
        byte[] pcl = PclRasterizer.fromPdf(pdf(1, false), "A4");
        assertTrue(new String(pcl, 0, 40, StandardCharsets.ISO_8859_1).contains("&l26A"));
    }

    @Test
    void aMostlyWhitePageStaysSmall() throws Exception {
        byte[] blank = PclRasterizer.fromPdf(pdf(1, false), "LETTER");
        byte[] boxed = PclRasterizer.fromPdf(pdf(1, true), "LETTER");
        // 3,300 rows of 319 bytes would be ~1 MB uncompressed; blank rows cost
        // five bytes each, and a black block adds only its own rows.
        assertTrue(blank.length < 25_000, "blank page: " + blank.length + " bytes");
        assertTrue(boxed.length > blank.length, "the block's rows carry ink");
        assertTrue(boxed.length < 60_000, "block page: " + boxed.length + " bytes");
    }

    @Test
    void theBlockIsInkAndThePageEdgeIsNot() throws Exception {
        byte[] pcl = PclRasterizer.fromPdf(pdf(1, true), "LETTER");
        String s = new String(pcl, StandardCharsets.ISO_8859_1);
        // Find every data row and check that some carry ink (0xFF runs) —
        // a polarity bug would make the WHITE page black instead.
        int inkRows = 0, rows = 0;
        for (int i = s.indexOf("*b"); i >= 0; i = s.indexOf("*b", i + 1)) {
            int end = i + 3;
            while (end < s.length() && Character.isDigit(s.charAt(end))) end++;
            if (end >= s.length() || s.charAt(end) != 'W') continue;  // "*b2M" selects compression, not a row
            int len = Integer.parseInt(s.substring(i + 3, end));
            rows++;
            if (len > 0) inkRows++;
            i = end + len;                                           // skip the row's data bytes
        }
        // 11in at 300 dpi is 3300 lines; PDFBox floors 792 x (300/72f) =
        // 3299.9998, so the rendered page is 3299 lines tall. One row each.
        assertTrue(rows == 3299 || rows == 3300, "one row per scan line, got " + rows);
        // A 1in-tall block at 300 dpi is about 300 rows; allow a little anti-aliasing.
        assertTrue(inkRows > 280 && inkRows < 320, "rows carrying ink: " + inkRows);
    }

    @Test
    void packBitsRoundTrips() {
        for (byte[] row : List.of(
                new byte[]{0, 0, 0, 0},
                new byte[]{1, 2, 3, 4, 5},
                new byte[]{(byte) 0xFF, (byte) 0xFF, 7, 8, 8, 8, 9},
                new byte[300])) {
            assertArrayEquals(row, unpack(PclRasterizer.packBits(row)));
        }
        assertFalse(PclRasterizer.packBits(new byte[319]).length > 8, "an empty row packs to a few bytes");
    }

    private static byte[] unpack(byte[] packed) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int i = 0;
        while (i < packed.length) {
            int n = packed[i++];
            if (n >= 0) {
                out.write(packed, i, n + 1);
                i += n + 1;
            } else if (n != -128) {
                for (int k = 0; k < 1 - n; k++) out.write(packed[i]);
                i++;
            }
        }
        return out.toByteArray();
    }

    // ===== which setup a printer gets, from what it reports =====

    @Test
    void aSamsungThatOnlySpeaksPclIsToldToUsePcl() {
        var caps = PrinterService.capabilities(new PrinterTransport.PrinterAttributes("Samsung K2200 Series",
                List.of("application/octet-stream", "application/PCL", "application/vnd.hp-PCL",
                        "application/vnd.hp-PCLXL", "application/x-QPDL", "text/plain"), "ipp/print"), 631);
        assertFalse(caps.pdf(), "the K2200 does not print PDF");
        assertTrue(caps.pcl());
        assertFalse(caps.zpl());
        assertEquals("IPP", caps.suggestedConnection());
        assertEquals("PCL", caps.suggestedFormat());
        assertTrue(caps.summary().contains("doesn't print PDF or ZPL"), caps.summary());
    }

    @Test
    void aPrinterThatTakesPdfIsLeftOnPdf_andAZebraOnZpl() {
        var office = PrinterService.capabilities(new PrinterTransport.PrinterAttributes("HP LaserJet Pro",
                List.of("application/pdf", "application/vnd.hp-PCL"), "ipp/print"), 631);
        assertEquals("PDF", office.suggestedFormat(), "PDF wins when the printer reads it");
        var zebra = PrinterService.capabilities(new PrinterTransport.PrinterAttributes("Zebra ZD421",
                List.of("application/octet-stream"), "ipp/print"), 631);
        assertEquals("ZPL", zebra.suggestedFormat());
        assertEquals("RAW_9100", zebra.suggestedConnection());
    }
}
