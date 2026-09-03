package com.multiship.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * PR #552 — Concatenates several PDFs (byte-array form) into a single
 * multi-page PDF. Motivated by the {@code /orders/{n}/label/pdf}
 * passthrough on multi-package shipments: some carriers return one
 * PDF per piece (FedEx MPS PDF variant, UPS optional PDF response,
 * etc.), and the operator wants a single downloadable file.
 *
 * <p>Fast paths:
 * <ul>
 *   <li>empty input → {@link IllegalArgumentException} (caller bug)</li>
 *   <li>single input → returned verbatim (no re-encoding cost)</li>
 * </ul>
 *
 * <p>Uses PDFBox's {@link PDFMergerUtility} which is stream-safe and
 * happily merges heterogeneous page sizes / orientations (each source
 * PDF's pages keep their own dimensions in the output — no forced
 * re-flow). Fits the label-per-page use case where all sources are
 * typically the same 4x6" but we don't want to assume it.
 */
@Slf4j
@Service
public class PdfMerger {

    /**
     * Merge PDFs in the given order (index 0 → page 1, index 1 → next,
     * etc.). Individual inputs contribute their own page count; the
     * output page count = sum of all input page counts.
     */
    public byte[] mergeToOne(List<byte[]> pdfs) {
        if (pdfs == null || pdfs.isEmpty()) {
            throw new IllegalArgumentException("at least one PDF required");
        }
        // Single-input fast path — no merge overhead, no re-encode.
        if (pdfs.size() == 1) return pdfs.get(0);

        PDFMergerUtility merger = new PDFMergerUtility();
        try (ByteArrayOutputStream out = new ByteArrayOutputStream(pdfs.size() * 32_768)) {
            merger.setDestinationStream(out);
            for (byte[] pdf : pdfs) {
                if (pdf == null || pdf.length == 0) continue; // skip empties fail-open
                // RandomAccessReadBuffer is the PDFBox 3.x replacement for
                // the deprecated addSource(InputStream) — cheaper: PDFBox
                // wraps our byte[] without an extra copy internally.
                merger.addSource(new RandomAccessReadBuffer(new ByteArrayInputStream(pdf)));
            }
            // PDFBox 3.x — mergeDocuments takes a StreamCacheCreateFunction.
            // MemoryUsageSetting.setupMainMemoryOnly().streamCache is the
            // in-memory adapter (no temp files, fine for label-sized PDFs).
            merger.mergeDocuments(
                    org.apache.pdfbox.io.IOUtils.createMemoryOnlyStreamCache());
            byte[] merged = out.toByteArray();
            if (merged.length == 0) {
                throw new IllegalStateException(
                        "PDFMerger produced empty output — every input was null / empty?");
            }
            return merged;
        } catch (IOException ex) {
            throw new IllegalStateException("PDF merge failed: " + ex.getMessage(), ex);
        }
    }
}
