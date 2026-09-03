package com.multiship.backend.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PR #552 — locks in {@link PdfMerger} contract. The service is a thin
 * wrapper around PDFBox's PDFMergerUtility but the API surface + fast
 * paths matter for the {@code /orders/{n}/label/pdf} passthrough
 * behaviour when the operator asks for the "all pkgs" PDF.
 */
class PdfMergerTest {

    private final PdfMerger merger = new PdfMerger();

    @Test
    void null_input_throws() {
        assertThrows(IllegalArgumentException.class, () -> merger.mergeToOne(null));
    }

    @Test
    void empty_input_throws() {
        assertThrows(IllegalArgumentException.class, () -> merger.mergeToOne(List.of()));
    }

    @Test
    void single_input_returns_verbatim_fast_path() {
        byte[] input = onePagePdf("only page");
        byte[] out = merger.mergeToOne(List.of(input));
        // Same reference — fast path skips PDFMergerUtility entirely.
        assertEquals(input.length, out.length);
        for (int i = 0; i < input.length; i++) {
            assertEquals(input[i], out[i]);
        }
    }

    @Test
    void three_inputs_produce_three_page_pdf() throws IOException {
        byte[] p1 = onePagePdf("pkg 1");
        byte[] p2 = onePagePdf("pkg 2");
        byte[] p3 = onePagePdf("pkg 3");

        byte[] merged = merger.mergeToOne(List.of(p1, p2, p3));
        assertNotNull(merged);
        // Merged is a NEW document (larger than any single input, and
        // decodable to a 3-page PDF).
        try (PDDocument doc = Loader.loadPDF(merged)) {
            assertEquals(3, doc.getNumberOfPages());
        }
        assertNotEquals(p1.length, merged.length);
    }

    @Test
    void multi_page_inputs_page_counts_sum() throws IOException {
        // Two source PDFs: one 2 pages, one 3 pages → 5 pages out.
        byte[] twoP = multiPagePdf(2);
        byte[] threeP = multiPagePdf(3);
        byte[] merged = merger.mergeToOne(List.of(twoP, threeP));
        try (PDDocument doc = Loader.loadPDF(merged)) {
            assertEquals(5, doc.getNumberOfPages());
        }
    }

    @Test
    void null_or_empty_input_bytes_skipped_when_others_present() throws IOException {
        // Fail-open: one good PDF sandwiched by null/empty. Output = 1 page.
        byte[] good = onePagePdf("good");
        java.util.List<byte[]> ins = new java.util.ArrayList<>();
        ins.add(null);
        ins.add(new byte[0]);
        ins.add(good);
        ins.add(null);
        byte[] merged = merger.mergeToOne(ins);
        try (PDDocument doc = Loader.loadPDF(merged)) {
            assertEquals(1, doc.getNumberOfPages());
        }
    }

    @Test
    void all_inputs_null_or_empty_throws() {
        // Every source dropped → nothing merged → merger output would be empty.
        // We surface that as IllegalStateException so callers see a hard
        // signal instead of getting a "successful" 0-byte body.
        java.util.List<byte[]> ins = new java.util.ArrayList<>();
        ins.add(null);
        ins.add(new byte[0]);
        // Note: single-element fast path returns null verbatim for a 1-element
        // list; but for 2+ elements all null/empty, PDFBox merges 0 sources
        // and produces a 0-page document. The service throws in that case.
        assertThrows(IllegalStateException.class, () -> merger.mergeToOne(ins));
    }

    // ─── PDF factories ─────────────────────────────────────────────────

    /** Build a valid single-page 4x6 PDF with the given text on it. */
    private static byte[] onePagePdf(String text) {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(288f, 432f)); // 4x6"
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA
                        .getName().equals("Helvetica")
                        ? new org.apache.pdfbox.pdmodel.font.PDType1Font(
                                org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA)
                        : new org.apache.pdfbox.pdmodel.font.PDType1Font(
                                org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA),
                        12);
                cs.newLineAtOffset(50, 400);
                cs.showText(text);
                cs.endText();
            }
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                doc.save(out);
                byte[] bytes = out.toByteArray();
                assertTrue(bytes.length > 0);
                return bytes;
            }
        } catch (IOException ex) {
            throw new IllegalStateException("test fixture pdf build failed", ex);
        }
    }

    /** Build a valid N-page 4x6 PDF (empty pages, no text). */
    private static byte[] multiPagePdf(int pageCount) {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pageCount; i++) {
                doc.addPage(new PDPage(new PDRectangle(288f, 432f)));
            }
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                doc.save(out);
                return out.toByteArray();
            }
        } catch (IOException ex) {
            throw new IllegalStateException("test fixture pdf build failed", ex);
        }
    }
}
