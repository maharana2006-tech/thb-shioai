package com.multiship.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * PR #544 — Composes multiple ZebrashRenderer-produced per-package PNGs
 * into a single vertically-stacked PNG for the
 * {@code GET /orders/{n}/label/preview.png} endpoint when no
 * {@code ?pkg=} parameter is supplied on a multi-package order.
 *
 * <p>Layout: N PNGs stacked top-to-bottom with a 20 px white gap between
 * each. Composite width = max of individual widths; height = sum of
 * individual heights + gaps. Preserves the aspect ratio of each panel.
 *
 * <p>Why not multi-page PDF here: the endpoint contract is
 * {@code image/png}. Returning multi-page anything requires a different
 * content type; sibling {@code /label/pdf} already covers that.
 * Composite PNG keeps the {@code <img src=…>} FE path working
 * unchanged (no `<iframe>` swap needed for multi-pkg orders).
 */
@Slf4j
@Service
public class ZebrashCompositor {

    /** White band between stacked panels — matches thermal-label bleed. */
    private static final int GAP_PX = 20;

    /**
     * Stack the given PNG byte-buffers vertically. Never returns null;
     * an empty input list is a caller bug and throws.
     */
    public byte[] stackVertically(List<byte[]> pngs) {
        if (pngs == null || pngs.isEmpty()) {
            throw new IllegalArgumentException("at least one PNG required");
        }
        // Single-panel fast path — no composition needed.
        if (pngs.size() == 1) return pngs.get(0);

        List<BufferedImage> panels = new java.util.ArrayList<>(pngs.size());
        int maxWidth = 0;
        int totalHeight = 0;
        try {
            for (byte[] png : pngs) {
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
                if (img == null) {
                    throw new IllegalStateException("panel is not a valid PNG");
                }
                panels.add(img);
                maxWidth = Math.max(maxWidth, img.getWidth());
                totalHeight += img.getHeight();
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to decode per-package PNG: " + ex.getMessage(), ex);
        }
        totalHeight += GAP_PX * (panels.size() - 1);

        BufferedImage canvas = new BufferedImage(maxWidth, totalHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        try {
            // Fill with white so the gaps between panels aren't
            // transparent (thermal labels are white paper; visual parity
            // matters when the operator scrolls through the composite).
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, maxWidth, totalHeight);
            int y = 0;
            for (BufferedImage panel : panels) {
                // Center each panel horizontally when widths differ (rare
                // but possible if per-package dpmm ever varies).
                int x = (maxWidth - panel.getWidth()) / 2;
                g.drawImage(panel, x, y, null);
                y += panel.getHeight() + GAP_PX;
            }
        } finally {
            g.dispose();
        }

        try (ByteArrayOutputStream out = new ByteArrayOutputStream(16384)) {
            if (!ImageIO.write(canvas, "PNG", out)) {
                throw new IllegalStateException("ImageIO refused to encode the composite PNG");
            }
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to encode composite PNG: " + ex.getMessage(), ex);
        }
    }
}
