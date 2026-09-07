package com.multiship.backend.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reduces a printable label PDF to its MAIN label on a single 4×6" page —
 * what a thermal printer (or Labelary) shows for the ZPL.
 *
 * <p>Carrier PDFs are the problem this solves: FedEx returns the 4×6 label
 * drawn on a Letter page, and international shipments come as 3–4 such
 * pages (label + consignee/agent copies + doc pages). Printing that as-is
 * spools four Letter sheets with a small label on each. Here page 1 is
 * rasterised, its content bounding box found (everything that isn't paper
 * white), and that region alone is placed on a 4×6 page — no knowledge of
 * where each carrier positions its label is needed.
 *
 * <p>Pages that are already a single 4×6 (zebrash output, the PDFBox
 * facsimile) pass through untouched.
 */
@Service
public class LabelMainPageCropper {

    /** 4×6 inches in PDF points. */
    static final float LABEL_W_PT = 288f;
    static final float LABEL_H_PT = 432f;
    /** Rasterisation density for the crop. 300 keeps carrier barcodes crisp. */
    private static final int RENDER_DPI = 300;
    /** Luminance above which a pixel counts as paper (0–255). */
    private static final int PAPER_THRESHOLD = 235;
    /** Breathing room kept around the detected content, in rendered pixels. */
    private static final int MARGIN_PX = 12;

    /** Page 1 only, cropped to its content and fitted onto a 4×6 page. */
    public byte[] mainLabelOnly(byte[] pdf) {
        try (PDDocument src = Loader.loadPDF(pdf)) {
            if (src.getNumberOfPages() == 0) return pdf;
            if (src.getNumberOfPages() == 1 && isLabelSized(src.getPage(0))) return pdf;

            BufferedImage page = new PDFRenderer(src).renderImageWithDPI(0, RENDER_DPI, ImageType.RGB);
            BufferedImage content = cropToContent(page);
            return onLabelPage(content);
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not isolate the main label page", ex);
        }
    }

    /** {@link #mainLabelOnly(byte[])} for each piece of a multi-package shipment. */
    public List<byte[]> mainLabelOnlyEach(List<byte[]> pdfs) {
        List<byte[]> out = new ArrayList<>(pdfs.size());
        for (byte[] p : pdfs) out.add(mainLabelOnly(p));
        return out;
    }

    private static boolean isLabelSized(PDPage page) {
        PDRectangle box = page.getMediaBox();
        float w = box.getWidth(), h = box.getHeight();
        // Tolerate rounding from renderers that size the page from pixels.
        return Math.abs(w - LABEL_W_PT) < 6f && Math.abs(h - LABEL_H_PT) < 6f;
    }

    private static BufferedImage cropToContent(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        int minX = w, minY = h, maxX = -1, maxY = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = img.getRGB(x, y);
                int lum = (((rgb >> 16) & 0xff) * 299 + ((rgb >> 8) & 0xff) * 587 + (rgb & 0xff) * 114) / 1000;
                if (lum < PAPER_THRESHOLD) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        if (maxX < 0) return img; // blank page — keep it whole rather than crop to nothing
        int x0 = Math.max(0, minX - MARGIN_PX), y0 = Math.max(0, minY - MARGIN_PX);
        int x1 = Math.min(w, maxX + 1 + MARGIN_PX), y1 = Math.min(h, maxY + 1 + MARGIN_PX);
        return img.getSubimage(x0, y0, x1 - x0, y1 - y0);
    }

    /** One 4×6 page with the image scaled to fit (aspect preserved, centred). */
    private static byte[] onLabelPage(BufferedImage content) throws IOException {
        try (PDDocument out = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(LABEL_W_PT, LABEL_H_PT));
            out.addPage(page);
            PDImageXObject image = LosslessFactory.createFromImage(out, content);
            float scale = Math.min(LABEL_W_PT / content.getWidth(), LABEL_H_PT / content.getHeight());
            float drawW = content.getWidth() * scale, drawH = content.getHeight() * scale;
            float x = (LABEL_W_PT - drawW) / 2f, y = (LABEL_H_PT - drawH) / 2f;
            try (PDPageContentStream cs = new PDPageContentStream(out, page)) {
                cs.drawImage(image, x, y, drawW, drawH);
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            out.save(bytes);
            return bytes.toByteArray();
        }
    }
}
