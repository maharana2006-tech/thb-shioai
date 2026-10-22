package com.multiship.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * PR #536 — Wraps zebrash-rendered PNGs into single- or multi-page PDFs
 * for the {@code /orders/{n}/label/pdf} endpoint. Each page is sized to
 * the label's true physical dimensions (from {@link
 * ZebrashRenderer#renderSpecFor}) — a 203-dpi 4×6 label is a 288×432 pt
 * page, a 300-dpi label its own size — so the printed PDF matches the
 * thermal layout 1:1 when routed to a laser/inkjet instead of being
 * down-scaled onto a fixed sheet.
 *
 * <p>Multi-package shipments: concatenates one page per ZPL bytes[]
 * entry. Callers pass the list ordered by package sequence number.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ZebrashPdfService {

    private final ZebrashRenderer zebrashRenderer;

    /** PDF points per inch (72). Page size is derived per-label from the
     *  renderer's geometry so the PDF is the label's true physical size
     *  (a 300-dpi 4×6 prints as 4×6, not down-scaled onto a fixed sheet). */
    private static final float PT_PER_INCH = 72f;

    /**
     * Render one ZPL blob to a single-page PDF. Convenience wrapper
     * around {@link #renderZplsToPdf(List)}.
     */
    public byte[] renderZplToPdf(byte[] zpl) {
        return renderZplsToPdf(List.of(zpl));
    }

    /**
     * Render each ZPL blob to a page and concatenate into one PDF.
     * Each page is 4×6"; the PNG is scaled to fill.
     */
    public byte[] renderZplsToPdf(List<byte[]> zpls) {
        if (zpls == null || zpls.isEmpty()) {
            throw new IllegalArgumentException("at least one ZPL blob required");
        }
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < zpls.size(); i++) {
                byte[] zpl = zpls.get(i);
                byte[] png = zebrashRenderer.renderPng(zpl);
                PDImageXObject image = PDImageXObject.createFromByteArray(
                        doc, png, "label-" + (i + 1));
                // Page = the label's true physical size, so a correctly-
                // sized render isn't down-scaled onto a fixed 4×6 sheet.
                ZebrashRenderer.RenderSpec spec = zebrashRenderer.renderSpecFor(zpl);
                float pageW = (float) (spec.widthIn() * PT_PER_INCH);
                float pageH = (float) (spec.heightIn() * PT_PER_INCH);
                PDPage page = new PDPage(new PDRectangle(pageW, pageH));
                doc.addPage(page);
                // Fit preserving aspect (the render already matches the page
                // closely); a full-bleed stretch would squash barcode module
                // ratios.
                float scale = Math.min(pageW / image.getWidth(), pageH / image.getHeight());
                float w = image.getWidth() * scale;
                float h = image.getHeight() * scale;
                try (PDPageContentStream stream = new PDPageContentStream(doc, page)) {
                    stream.drawImage(image, (pageW - w) / 2f, pageH - h, w, h);
                }
            }
            try (ByteArrayOutputStream out = new ByteArrayOutputStream(16384)) {
                doc.save(out);
                return out.toByteArray();
            }
        } catch (IOException ex) {
            throw new ZebrashRenderer.ZplRenderException("PDF assembly failed: " + ex.getMessage());
        }
    }
}
