package com.multiship.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
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
 *
 * <p>PR #548 (Sprint 52 follow-up) — {@link #stackVerticallyWithBadges}
 * overlays a "PKG N of M" badge (and optional tracking line) on the
 * top-right corner of each panel BEFORE stacking. Some carriers'
 * MPS ZPL omits the pkg counter (UPS in particular) — the operator
 * needs to know which physical box each printed label goes on.
 */
@Slf4j
@Service
public class ZebrashCompositor {

    /** White band between stacked panels — matches thermal-label bleed. */
    private static final int GAP_PX = 20;

    /**
     * Badge geometry — designed for a ~800 × 1200 px thermal label
     * rendered at 8dpmm from the standard 4×6" ZPL. Corner padding /
     * font size scale visually with real labels; if a caller ever passes
     * a much smaller PNG the badge will look proportionally large, but
     * over-large is safer than illegible.
     */
    private static final int BADGE_MARGIN_PX = 12;
    private static final int BADGE_PADDING_PX = 8;
    private static final int BADGE_FONT_SIZE = 22;
    private static final int BADGE_LINE_SPACING = 4;

    /**
     * Stack the given PNG byte-buffers vertically. Never returns null;
     * an empty input list is a caller bug and throws. Existing behavior
     * — no badge — preserved for callers that don't need it.
     */
    public byte[] stackVertically(List<byte[]> pngs) {
        return stackVerticallyWithBadges(pngs, null);
    }

    /**
     * PR #548 — Stack + overlay per-panel badges. {@code badges} is a
     * parallel list to {@code pngs}; each entry is the free-form label
     * text drawn on the top-right of that panel. Multi-line strings
     * (via {@code \n}) render each line stacked. When {@code badges} is
     * null OR shorter than {@code pngs}, missing indices skip the overlay
     * for that panel (fail-open — the rendered label just shows without
     * annotation rather than 500-ing).
     *
     * <p>Single-panel input with a badge still renders the badge (so a
     * per-package request with {@code ?pkg=2} can also show "PKG 2 OF
     * 3" if the caller opts in) — the pre-PR-#548 single-panel
     * fast-path passthrough only applies when there's no badge.
     */
    public byte[] stackVerticallyWithBadges(List<byte[]> pngs, List<String> badges) {
        if (pngs == null || pngs.isEmpty()) {
            throw new IllegalArgumentException("at least one PNG required");
        }
        boolean hasBadges = badges != null && !badges.isEmpty();
        // Single-panel fast path — passthrough ONLY when nothing to overlay.
        if (pngs.size() == 1 && !hasBadges) return pngs.get(0);

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
        // Overlay badges FIRST (mutates each panel in place). Doing this
        // before stacking keeps the composite loop below unchanged and
        // means single-panel-with-badge callers work through the same
        // code path.
        if (hasBadges) {
            for (int i = 0; i < panels.size(); i++) {
                String text = i < badges.size() ? badges.get(i) : null;
                if (text != null && !text.isBlank()) {
                    drawBadge(panels.get(i), text);
                }
            }
        }
        // For single-panel with badge — return that panel directly, no
        // GAP_PX or extra canvas.
        if (panels.size() == 1) return encodePng(panels.get(0));

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

        return encodePng(canvas);
    }

    /**
     * Draw a "PKG N OF M" badge (+ optional tracking) on the top-right
     * corner of {@code panel}. Mutates {@code panel} in place — cheaper
     * than allocating a fresh canvas for each panel.
     *
     * <p>Layout: white filled rectangle with 2 px black border, black
     * sans-serif text. Multi-line strings (via {@code \n}) render each
     * line stacked. Right-aligned to the panel's right edge minus
     * {@link #BADGE_MARGIN_PX}.
     */
    private static void drawBadge(BufferedImage panel, String text) {
        String[] lines = text.split("\n");
        Graphics2D g = panel.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            Font font = new Font(Font.SANS_SERIF, Font.BOLD, BADGE_FONT_SIZE);
            g.setFont(font);
            java.awt.FontMetrics fm = g.getFontMetrics();
            int lineHeight = fm.getAscent() + fm.getDescent();
            int textWidth = 0;
            for (String line : lines) textWidth = Math.max(textWidth, fm.stringWidth(line));

            int boxWidth = textWidth + 2 * BADGE_PADDING_PX;
            int boxHeight = lines.length * lineHeight + (lines.length - 1) * BADGE_LINE_SPACING
                    + 2 * BADGE_PADDING_PX;
            int boxX = panel.getWidth() - boxWidth - BADGE_MARGIN_PX;
            int boxY = BADGE_MARGIN_PX;
            // Clamp to on-canvas — some renderers produce narrower panels
            // (label size preset mismatch); a negative boxX would draw
            // off-screen and the operator would see no badge at all.
            if (boxX < BADGE_MARGIN_PX) boxX = BADGE_MARGIN_PX;

            g.setColor(Color.WHITE);
            g.fillRect(boxX, boxY, boxWidth, boxHeight);
            g.setColor(Color.BLACK);
            g.drawRect(boxX, boxY, boxWidth, boxHeight);

            int textY = boxY + BADGE_PADDING_PX + fm.getAscent();
            for (String line : lines) {
                int lineX = boxX + BADGE_PADDING_PX + (textWidth - fm.stringWidth(line)) / 2;
                g.drawString(line, lineX, textY);
                textY += lineHeight + BADGE_LINE_SPACING;
            }
        } finally {
            g.dispose();
        }
    }

    private static byte[] encodePng(BufferedImage image) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream(16384)) {
            if (!ImageIO.write(image, "PNG", out)) {
                throw new IllegalStateException("ImageIO refused to encode the composite PNG");
            }
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to encode composite PNG: " + ex.getMessage(), ex);
        }
    }
}
