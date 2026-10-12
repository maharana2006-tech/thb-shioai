package com.multiship.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.List;

/**
 * V143 (ZPL-first Stage 2) — render a single carrier label artifact
 * into ZPL + PDF + PNG so {@code /label/{zpl,pdf,png}} endpoints are
 * zero-conversion reads.
 *
 * <p>Six source→target directions covered by delegating to existing
 * single-purpose services:
 * <pre>
 *   ZPL → PDF    : ZebrashPdfService.renderZplToPdf
 *   ZPL → PNG    : ZebrashRenderer.renderPng
 *   PDF → PNG    : PDFBox PDFRenderer (page 1 @ 203 DPI, thermal-equivalent)
 *   PDF → ZPL    : PDF → PNG → LabelImageZplService.imageToZpl
 *   GIF/PNG → ZPL: LabelImageZplService.imageToZpl
 *   GIF/PNG → PDF: LabelImagePdfService.imagesToPdf
 * </pre>
 *
 * <p>Any single conversion failure is logged and that format is left
 * null — the caller stores what succeeded. The serve-time endpoints
 * fall back to on-demand conversion when a column is null.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LabelFormatConverter {

    /** 203 DPI matches a Zebra ZT230 / common thermal label printer. */
    private static final int PDF_RENDER_DPI = 203;

    private final ZebrashPdfService zebrashPdfService;
    private final ZebrashRenderer zebrashRenderer;
    private final LabelImageZplService labelImageZplService;
    private final LabelImagePdfService labelImagePdfService;

    /** Three base64 strings — raw text for ZPL, base64 for PDF/PNG.
     *  Any field may be null when the conversion failed. */
    public record RenderedFormats(String zpl, String pdf, String png) {}

    /**
     * Convert a single carrier artifact (any supported source format)
     * into all three target formats. {@code sourceFormat} should be
     * {@code "ZPL"}, {@code "PDF"}, {@code "PNG"}, or {@code "GIF"} —
     * sniffed upstream via {@link LabelArtifactResolver} or similar.
     *
     * @param bytes raw bytes of the source artifact
     * @param sourceFormat uppercase format tag as returned by sniffer
     * @return rendered formats; any field null = conversion failed
     */
    public RenderedFormats renderAll(byte[] bytes, String sourceFormat) {
        if (bytes == null || bytes.length == 0 || sourceFormat == null) {
            return new RenderedFormats(null, null, null);
        }
        return switch (sourceFormat.toUpperCase()) {
            case "ZPL" -> fromZpl(bytes);
            case "PDF" -> fromPdf(bytes);
            case "GIF", "PNG", "JPG", "JPEG" -> fromImage(bytes);
            default -> {
                log.debug("LabelFormatConverter: unknown source format {}, leaving all three null", sourceFormat);
                yield new RenderedFormats(null, null, null);
            }
        };
    }

    // ─── Source branches ────────────────────────────────────────────────────

    private RenderedFormats fromZpl(byte[] zplBytes) {
        String zpl = new String(zplBytes);
        String pdf = safeToBase64("ZPL→PDF", () -> zebrashPdfService.renderZplToPdf(zplBytes));
        String png = safeToBase64("ZPL→PNG", () -> zebrashRenderer.renderPng(zplBytes));
        return new RenderedFormats(zpl, pdf, png);
    }

    private RenderedFormats fromPdf(byte[] pdfBytes) {
        String pdf = Base64.getEncoder().encodeToString(pdfBytes);
        byte[] pngBytes = safeBytes("PDF→PNG", () -> pdfToPng(pdfBytes));
        String png = pngBytes != null ? Base64.getEncoder().encodeToString(pngBytes) : null;
        String zpl = pngBytes != null
                ? safeString("PDF→ZPL (via PNG)", () -> labelImageZplService.imageToZpl(pngBytes))
                : null;
        return new RenderedFormats(zpl, pdf, png);
    }

    private RenderedFormats fromImage(byte[] imgBytes) {
        String png = Base64.getEncoder().encodeToString(imgBytes);
        String zpl = safeString("IMG→ZPL", () -> labelImageZplService.imageToZpl(imgBytes));
        String pdf = safeToBase64("IMG→PDF", () -> labelImagePdfService.imagesToPdf(List.of(imgBytes)));
        return new RenderedFormats(zpl, pdf, png);
    }

    // ─── Primitive conversions ──────────────────────────────────────────────

    private byte[] pdfToPng(byte[] pdfBytes) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            if (doc.getNumberOfPages() == 0) return null;
            PDFRenderer renderer = new PDFRenderer(doc);
            // Page 1 only — thermal labels are single-page by convention.
            // Multi-piece MPS stores ONE page per label_package row anyway.
            BufferedImage img = renderer.renderImageWithDPI(0, PDF_RENDER_DPI);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(img, "PNG", baos);
            return baos.toByteArray();
        }
    }

    // ─── Error-tolerant helpers ─────────────────────────────────────────────

    private interface BytesSupplier { byte[] get() throws Exception; }
    private interface StringSupplier { String get() throws Exception; }

    private String safeToBase64(String tag, BytesSupplier s) {
        byte[] out = safeBytes(tag, s);
        return out != null ? Base64.getEncoder().encodeToString(out) : null;
    }

    private byte[] safeBytes(String tag, BytesSupplier s) {
        try {
            byte[] out = s.get();
            return out != null && out.length > 0 ? out : null;
        } catch (Exception e) {
            log.warn("LabelFormatConverter {}: {}", tag, e.getMessage());
            return null;
        }
    }

    private String safeString(String tag, StringSupplier s) {
        try {
            String out = s.get();
            return out != null && !out.isBlank() ? out : null;
        } catch (Exception e) {
            log.warn("LabelFormatConverter {}: {}", tag, e.getMessage());
            return null;
        }
    }
}
