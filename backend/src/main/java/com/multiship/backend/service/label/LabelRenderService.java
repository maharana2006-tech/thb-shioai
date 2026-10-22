package com.multiship.backend.service.label;

import com.multiship.backend.service.LabelImagePdfService;
import com.multiship.backend.service.LabelMainPageCropper;
import com.multiship.backend.service.LabelaryRenderer;
import com.multiship.backend.service.ZebrashPdfService;
import com.multiship.backend.service.ZebrashRenderer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * Phase 1 (Shipment &amp; Label re-architecture) — the one render pipeline.
 *
 * <p>Every visual output of a label (screen preview, PDF, print) is produced
 * here, from the same bytes the ZPL endpoint serves, by the same engine the
 * print path uses. There is no synthetic label, no rotation and no badge
 * compositing in this class, so there is nothing left to drift between
 * surfaces.
 *
 * <p>Engine order is fixed: zebrash (bundled, label content stays on-host),
 * then Labelary only when the binary is unavailable <em>and</em> Labelary is
 * enabled. In production {@code label.require-zebrash=true} fails boot on a
 * missing binary, so the fallback can never route content off-host there.
 * Geometry is 8 dpmm on 4×6 in (812×1218 dots) and rotation is 0: carrier
 * ZPL already encodes upright output.
 *
 * <p>The preview is always {@link #renderPng} of the ZPL bytes, raster
 * packages included: the one path that cannot disagree with Copy ZPL. A
 * carrier PDF (FedEx Letter page, DHL A4) is cropped to its main label
 * before it becomes {@code ^GFA} and before it becomes a printable page.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LabelRenderService {

    private final ZebrashRenderer zebrashRenderer;
    private final LabelaryRenderer labelaryRenderer;
    private final ZebrashPdfService zebrashPdfService;
    private final LabelImagePdfService labelImagePdfService;
    private final LabelMainPageCropper labelMainPageCropper;

    /** Native ZPL → PNG. Parse/render failures propagate; only a missing
     *  renderer binary falls through to Labelary. */
    public byte[] renderPng(byte[] zpl) {
        try {
            return zebrashRenderer.renderPng(zpl);
        } catch (ZebrashRenderer.RendererUnavailableException unavailable) {
            if (!labelaryRenderer.isAvailable()) throw unavailable;
            log.warn("zebrash unavailable for PNG render, falling back to Labelary: {}", unavailable.getMessage());
            return labelaryRenderer.renderPng(zpl);
        }
    }

    /** Native ZPL(s) → one 4×6 page per label, from the same engine as
     *  {@link #renderPng}. The Labelary fallback covers a single label only;
     *  Labelary is disabled in production, so a multi-label merge path would
     *  be dead code. */
    public byte[] renderPdf(List<byte[]> zpls) {
        if (zpls == null || zpls.isEmpty()) {
            throw new IllegalArgumentException("at least one ZPL is required");
        }
        try {
            return zebrashPdfService.renderZplsToPdf(zpls);
        } catch (ZebrashRenderer.RendererUnavailableException unavailable) {
            if (!labelaryRenderer.isAvailable() || zpls.size() != 1) throw unavailable;
            log.warn("zebrash unavailable for PDF render, falling back to Labelary: {}", unavailable.getMessage());
            return labelaryRenderer.renderPdf(zpls.get(0));
        }
    }

    /** A carrier raster → one 4×6 PDF page, the printable form of a raster
     *  label. Images go through {@code imagesToPdf} so UPS's landscape GIF is
     *  turned upright; a PDF is cropped to its main label. */
    public byte[] renderRasterPdf(byte[] raster, String format) {
        String f = format == null ? "" : format.trim().toUpperCase(Locale.ROOT);
        if ("PDF".equals(f)) return labelMainPageCropper.mainLabelOnly(raster);
        return labelImagePdfService.imagesToPdf(List.of(raster));
    }

    /**
     * Strong ETag for a label: the SHA-256 of the exact bytes the ZPL
     * endpoint returns. The preview is rendered from those bytes, so a client
     * holding this tag holds a render of what it will copy and print.
     */
    public static String etag(byte[] zpl) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return "\"" + HexFormat.of().formatHex(md.digest(zpl)) + "\"";
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static String etag(String zpl) {
        return etag(zpl.getBytes(StandardCharsets.ISO_8859_1));
    }
}
