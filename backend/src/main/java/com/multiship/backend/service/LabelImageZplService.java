package com.multiship.backend.service;

import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * Converts a raster carrier label (UPS's default GIF, or a PNG) into ZPL by
 * embedding it as a {@code ^GFA} graphic on a 4×6" 8-dpmm canvas
 * (812×1218 dots) — the same thing a Zebra driver does when handed an image.
 * Copy ZPL / Download .zpl for an image-returning carrier therefore yield
 * ZPL that prints (and previews on Labelary) as the carrier's real label,
 * instead of falling through to the platform facsimile.
 *
 * <p>Landscape images (UPS ships 1400×800 with the text running bottom-to-
 * top) are turned 90° clockwise first, matching {@link LabelImagePdfService}.
 * The bitmap is written as plain hex — universally accepted by printers and
 * viewers; ~250 KB for a full 4×6, fine for a clipboard/file payload.
 */
@Service
public class LabelImageZplService {

    static final int DOTS_W = 812;
    static final int DOTS_H = 1218;
    private static final int BYTES_PER_ROW = (DOTS_W + 7) / 8; // 102
    private static final int LUMA_THRESHOLD = 160;
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    public String imageToZpl(byte[] raw) {
        BufferedImage src;
        try {
            src = ImageIO.read(new ByteArrayInputStream(raw));
        } catch (IOException e) {
            throw new IllegalStateException("Could not decode the label image", e);
        }
        if (src == null) {
            throw new IllegalArgumentException("Label artifact is not a decodable image");
        }
        BufferedImage mono = fitToCanvas(src);
        byte[] bits = packBits(mono);
        StringBuilder zpl = new StringBuilder(bits.length * 2 + 128);
        zpl.append("^XA\n^PW").append(DOTS_W).append("\n^LL").append(DOTS_H).append("\n^LH0,0\n")
           .append("^FO0,0^GFA,").append(bits.length).append(',').append(bits.length).append(',')
           .append(BYTES_PER_ROW).append(',');
        for (byte b : bits) {
            zpl.append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
        }
        zpl.append("^FS\n^XZ\n");
        return zpl.toString();
    }

    /** Rotate landscape → portrait, scale to fit 812×1218 preserving aspect, centre on white. */
    private static BufferedImage fitToCanvas(BufferedImage src) {
        BufferedImage upright = src.getWidth() > src.getHeight() ? rotateClockwise(src) : src;
        double scale = Math.min((double) DOTS_W / upright.getWidth(), (double) DOTS_H / upright.getHeight());
        int w = (int) Math.round(upright.getWidth() * scale);
        int h = (int) Math.round(upright.getHeight() * scale);
        int left = (DOTS_W - w) / 2;
        int top = (DOTS_H - h) / 2;
        BufferedImage canvas = new BufferedImage(DOTS_W, DOTS_H, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = canvas.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, DOTS_W, DOTS_H);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(upright, left, top, w, h, null);
        } finally {
            g.dispose();
        }
        return canvas;
    }

    private static BufferedImage rotateClockwise(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        BufferedImage dst = new BufferedImage(h, w, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, h, w);
            AffineTransform at = new AffineTransform();
            at.translate(h, 0);
            at.rotate(Math.PI / 2);
            g.drawImage(src, at, null);
        } finally {
            g.dispose();
        }
        return dst;
    }

    /** 1 bit per dot, MSB first, rows padded to BYTES_PER_ROW; 1 = black (ZPL convention). */
    private static byte[] packBits(BufferedImage gray) {
        byte[] out = new byte[BYTES_PER_ROW * DOTS_H];
        for (int y = 0; y < DOTS_H; y++) {
            int rowBase = y * BYTES_PER_ROW;
            for (int x = 0; x < DOTS_W; x++) {
                int luma = gray.getRaster().getSample(x, y, 0);
                if (luma < LUMA_THRESHOLD) {
                    out[rowBase + (x >> 3)] |= (byte) (0x80 >> (x & 7));
                }
            }
        }
        return out;
    }
}
