package com.multiship.backend.service.label;

import com.multiship.backend.model.LabelPackage;
import com.multiship.backend.service.PdfMerger;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Phase 3 (Shipment &amp; Label re-architecture) — the printable form of an
 * order's label, for every surface that prints: the {@code /labels/{n}/pdf}
 * download, the document bundle, and the printer queue. Four call sites,
 * one composition, so there is exactly one answer to "what prints".
 *
 * <p>Native ZPL packages render through the one engine; a raster package is
 * already a page (its PDF cropped to the main label, or its image placed on
 * a 4×6 page). Pages are merged in package order, so a mixed order prints
 * its boxes in sequence.
 */
@Service
@RequiredArgsConstructor
public class LabelPrintableService {

    private final LabelArtifactService artifacts;
    private final LabelRenderService render;
    private final PdfMerger pdfMerger;

    /** Spoolable ZPL for every package: native verbatim, rasters as {@code ^GFA}. */
    public Optional<String> zpl(Integer orderNo) {
        return artifacts.zplAll(orderNo).map(b -> new String(b, StandardCharsets.ISO_8859_1));
    }

    /**
     * Printable PDF — one 4×6 page per package in sequence order, or just
     * {@code seq}. Empty when nothing printable is stored (the caller answers
     * 404 or skips the order; nothing synthetic is ever substituted).
     */
    public Optional<byte[]> pdf(Integer orderNo, Integer seq) {
        List<LabelPackage> pkgs = seq != null
                ? artifacts.pkg(orderNo, seq).map(List::of).orElse(List.of())
                : artifacts.packages(orderNo);

        List<byte[]> pages = new ArrayList<>();
        // Consecutive native packages batch into one render call; a raster
        // package breaks the run so page order still follows package order.
        List<byte[]> nativeRun = new ArrayList<>();
        for (LabelPackage p : pkgs) {
            if (LabelArtifactService.isRaster(p)) {
                flush(nativeRun, pages);
                Optional<byte[]> raster = artifacts.storedBytes(p);
                if (raster.isPresent()) {
                    pages.add(render.renderRasterPdf(raster.get(), p.getFormat()));
                }
            } else {
                artifacts.zpl(orderNo, p.getSequenceNumber()).ifPresent(nativeRun::add);
            }
        }
        flush(nativeRun, pages);

        if (pages.isEmpty()) return Optional.empty();
        return Optional.of(pages.size() == 1 ? pages.get(0) : pdfMerger.mergeToOne(pages));
    }

    private void flush(List<byte[]> nativeRun, List<byte[]> pages) {
        if (nativeRun.isEmpty()) return;
        pages.add(render.renderPdf(new ArrayList<>(nativeRun)));
        nativeRun.clear();
    }
}
