package com.multiship.backend.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * Wraps raster label artifacts (GIF/PNG — what UPS returns by default) into
 * a 4×6" PDF, one page per image, so the same /label/pdf consumers (section
 * preview, print modal, Download PDF) work for image-returning carriers
 * exactly as they do for PDF and ZPL ones.
 *
 * <p>UPS ships its 4×6 GIF rotated: 1400×800 landscape with the label text
 * running bottom-to-top and the shipper block along the left edge. A 90°
 * clockwise turn puts it upright. Any landscape image gets that treatment;
 * portrait images are placed as-is. The image is scaled to fit the page
 * preserving aspect and centred.
 */
@Service
public class LabelImagePdfService {

    /** 4in × 6in at 72 pt/in. */
    static final float PAGE_W = 288f;
    static final float PAGE_H = 432f;

    public byte[] imagesToPdf(List<byte[]> images) {
        try (PDDocument doc = new PDDocument()) {
            for (byte[] raw : images) {
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(raw));
                if (img == null) {
                    throw new IllegalArgumentException("Label artifact is not a decodable image");
                }
                if (img.getWidth() > img.getHeight()) {
                    img = rotateClockwise(img);
                }
                PDPage page = new PDPage(new PDRectangle(PAGE_W, PAGE_H));
                doc.addPage(page);
                PDImageXObject x = LosslessFactory.createFromImage(doc, img);
                float scale = Math.min(PAGE_W / img.getWidth(), PAGE_H / img.getHeight());
                float w = img.getWidth() * scale;
                float h = img.getHeight() * scale;
                float left = (PAGE_W - w) / 2f;
                float bottom = (PAGE_H - h) / 2f;
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.drawImage(x, left, bottom, w, h);
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not build the label PDF from image artifact(s)", e);
        }
    }

    private static BufferedImage rotateClockwise(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        BufferedImage dst = new BufferedImage(h, w, BufferedImage.TYPE_INT_RGB);
        // Drawn through Graphics2D rather than AffineTransformOp: the op
        // mishandles GIF's indexed colour model (clipped output over a black
        // unfilled area). White fill so any letterboxing prints as paper.
        java.awt.Graphics2D g = dst.createGraphics();
        try {
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, h, w);
            // Rotate 90° CW about the origin, then shift right by the new width.
            AffineTransform at = new AffineTransform();
            at.translate(h, 0);
            at.rotate(Math.PI / 2);
            g.drawImage(src, at, null);
        } finally {
            g.dispose();
        }
        return dst;
    }
}
