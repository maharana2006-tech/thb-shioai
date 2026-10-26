package com.multiship.backend.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * UPS production labels carry a MaxiCode ({@code ^BD2,1,1} with a ^FH
 * hex-escaped structured carrier message), a Code 128 routing barcode and
 * the 1Z tracking barcode. The UPS test environment prints "PRINTS HERE"
 * placeholders instead, so nothing in dev exercises the real thing. This
 * pins that the bundled renderer draws all three — the preview and PDF
 * paths must not go blank on the first production UPS label.
 *
 * <p>Skipped when the native binary is not bundled (dev builds with
 * {@code -Dzebrash.skip=true} on an unsupported platform).
 */
class ZebrashRendererUpsLabelTest {

    @Test
    void rendersMaxicodeRoutingAndTrackingBarcodes() throws IOException {
        ZebrashRenderer renderer = new ZebrashRenderer();
        ReflectionTestUtils.setField(renderer, "timeoutSeconds", 10);
        ReflectionTestUtils.setField(renderer, "requireZebrash", false);
        renderer.init();

        byte[] zpl;
        try (InputStream in = Objects.requireNonNull(
                getClass().getResourceAsStream("/labels/ups-production-style.zpl"))) {
            zpl = in.readAllBytes();
        }
        byte[] png;
        try {
            png = renderer.renderPng(zpl);
        } catch (ZebrashRenderer.RendererUnavailableException unavailable) {
            assumeTrue(false, "zebrash binary not bundled on this platform: " + unavailable.getMessage());
            return;
        }

        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        assertTrue(img.getWidth() >= 812 && img.getWidth() <= 816, "4in at 8dpmm, got " + img.getWidth());
        // Each region must actually contain ink: a skipped ^BD / ^BC leaves it blank.
        assertTrue(darkPixels(img, 50, 400, 230, 220) > 2_000, "MaxiCode region is blank");
        assertTrue(darkPixels(img, 300, 460, 260, 90) > 2_000, "routing barcode region is blank");
        assertTrue(darkPixels(img, 60, 720, 700, 190) > 20_000, "1Z tracking barcode region is blank");
    }

    private static int darkPixels(BufferedImage img, int x0, int y0, int w, int h) {
        int n = 0;
        for (int y = y0; y < Math.min(img.getHeight(), y0 + h); y++) {
            for (int x = x0; x < Math.min(img.getWidth(), x0 + w); x++) {
                if ((img.getRGB(x, y) & 0xFF) < 128) n++;
            }
        }
        return n;
    }
}
