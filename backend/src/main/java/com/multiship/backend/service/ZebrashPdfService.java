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
 * for the {@code /orders/{n}/label/pdf} endpoint. The Zebra thermal
 * label ships at 4×6" at 8dpmm (812×1218 px); PDFBox draws each PNG
 * into a 4×6" (288×432 point) page so the printed PDF matches thermal
 * layout when routed to a laser/inkjet.
 *
 * <p>Multi-package shipments: concatenates one page per ZPL bytes[]
 * entry. Callers pass the list ordered by package sequence number.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ZebrashPdfService {

    private final ZebrashRenderer zebrashRenderer;

    /** 4×6" at 72dpi = 288 × 432 pt. Matches the thermal label
     *  physical size so print scaling is 1:1. */
    private static final float PAGE_WIDTH_PT = 288f;
    private static final float PAGE_HEIGHT_PT = 432f;

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
                byte[] png = zebrashRenderer.renderPng(zpls.get(i));
                PDImageXObject image = PDImageXObject.createFromByteArray(
                        doc, png, "label-" + (i + 1));
                PDPage page = new PDPage(new PDRectangle(PAGE_WIDTH_PT, PAGE_HEIGHT_PT));
                doc.addPage(page);
                try (PDPageContentStream stream = new PDPageContentStream(doc, page)) {
                    stream.drawImage(image, 0, 0, PAGE_WIDTH_PT, PAGE_HEIGHT_PT);
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
