package com.multiship.backend.service;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * PR #548 — ZebrashCompositor now overlays a "PKG N of M" badge on each
 * per-panel PNG BEFORE stacking. These tests exercise the badge overlay
 * without asserting on rendered pixel content (that's fragile against
 * font metrics differences across JDK / OS). Focus:
 *
 *   - single-panel fast path preserved when NO badge (byte-identical)
 *   - single-panel WITH badge re-encodes (bytes differ from input)
 *   - multi-panel composite dimensions match sum-of-panels + gap
 *   - null / empty / mismatched-length badges are tolerated fail-open
 */
class ZebrashCompositorBadgeTest {

    private final ZebrashCompositor compositor = new ZebrashCompositor();

    @Test
    void empty_input_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> compositor.stackVerticallyWithBadges(List.of(), null));
        assertThrows(IllegalArgumentException.class,
                () -> compositor.stackVerticallyWithBadges(null, null));
    }

    @Test
    void single_panel_no_badge_passthrough() {
        byte[] panel = solidPng(800, 1200, Color.WHITE);
        byte[] result = compositor.stackVertically(List.of(panel));
        // Fast path — same bytes returned, no re-encode cost.
        assertEquals(panel.length, result.length);
        for (int i = 0; i < panel.length; i++) {
            assertEquals(panel[i], result[i]);
        }
    }

    @Test
    void single_panel_with_badge_reencodes() throws IOException {
        byte[] panel = solidPng(800, 1200, Color.WHITE);
        byte[] result = compositor.stackVerticallyWithBadges(
                List.of(panel), List.of("PKG 1 OF 3\n1Z999AA10123456784"));
        // Fast path skipped — new PNG allocated with badge drawn on top.
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(result));
        assertNotNull(img);
        assertEquals(800, img.getWidth());
        assertEquals(1200, img.getHeight());
    }

    @Test
    void three_panels_compose_stacked_vertically_with_gaps() throws IOException {
        byte[] panel = solidPng(800, 1200, Color.WHITE);
        List<byte[]> panels = List.of(panel, panel, panel);
        List<String> badges = List.of(
                "PKG 1 OF 3",
                "PKG 2 OF 3",
                "PKG 3 OF 3");
        byte[] result = compositor.stackVerticallyWithBadges(panels, badges);
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(result));
        assertNotNull(img);
        assertEquals(800, img.getWidth());
        // 3 × 1200 + 2 × 20-px gap.
        assertEquals(1200 * 3 + 20 * 2, img.getHeight());
    }

    @Test
    void null_badges_skips_overlay_and_falls_back_to_plain_composite() throws IOException {
        // Behaviour parity check: stackVertically(pngs) delegates to
        // stackVerticallyWithBadges(pngs, null) and must produce a valid
        // composite.
        byte[] panel = solidPng(800, 1200, Color.WHITE);
        byte[] result = compositor.stackVerticallyWithBadges(
                List.of(panel, panel), null);
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(result));
        assertEquals(1200 * 2 + 20, img.getHeight());
    }

    @Test
    void shorter_badges_list_skips_missing_indices_but_still_composites() throws IOException {
        // Composite loop only fills badges up to min(panels.size, badges.size).
        // Fail-open: no crash, just no overlay on the tail panels.
        byte[] panel = solidPng(800, 1200, Color.WHITE);
        byte[] result = compositor.stackVerticallyWithBadges(
                List.of(panel, panel, panel), List.of("PKG 1 OF 3"));
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(result));
        assertEquals(1200 * 3 + 20 * 2, img.getHeight());
    }

    @Test
    void empty_or_null_badge_entry_skips_overlay_for_that_panel() throws IOException {
        // Middle entry blank — should still composite the same as if no
        // badges at all for that panel.
        byte[] panel = solidPng(800, 1200, Color.WHITE);
        java.util.List<String> badges = new java.util.ArrayList<>();
        badges.add("PKG 1 OF 3");
        badges.add("");
        badges.add(null);
        byte[] result = compositor.stackVerticallyWithBadges(
                List.of(panel, panel, panel), badges);
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(result));
        assertEquals(1200 * 3 + 20 * 2, img.getHeight());
    }

    /** Solid-color test PNG — cheap alternative to hitting the zebrash binary. */
    private static byte[] solidPng(int width, int height, Color color) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(color);
            g.fillRect(0, 0, width, height);
        } finally {
            g.dispose();
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream(4096)) {
            ImageIO.write(img, "PNG", out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
