package com.multiship.backend.util;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** UPS's {@code ^POI} must turn the picture, never the bytes. */
class ZplOrientationTest {

    private static byte[] zpl(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    @Test
    void detects_poi_only_as_a_command() {
        assertTrue(ZplOrientation.printsInverted(zpl("^XA^LRN^POI^PW812^FO0,0^FDx^FS^XZ")));
        assertFalse(ZplOrientation.printsInverted(zpl("^XA^PON^FO0,0^FDx^FS^XZ")));
        assertFalse(ZplOrientation.printsInverted(zpl("^XA^FO0,0^FDPOINT^FS^XZ")), "text is not a command");
        assertFalse(ZplOrientation.printsInverted(null));
    }

    @Test
    void non_inverted_png_is_returned_untouched() throws Exception {
        byte[] png = png(4, 2);
        assertSame(png, ZplOrientation.uprightPng(zpl("^XA^XZ"), png));
    }

    @Test
    void inverted_png_is_rotated_180_degrees() throws Exception {
        // 4×2: a single black pixel at top-left must land bottom-right.
        byte[] png = png(4, 2);
        BufferedImage out = ImageIO.read(new ByteArrayInputStream(ZplOrientation.uprightPng(zpl("^XA^POI^XZ"), png)));
        assertEquals(4, out.getWidth());
        assertEquals(2, out.getHeight());
        assertEquals(0, out.getRaster().getSample(3, 1, 0), "black moved to bottom-right");
        assertEquals(255, out.getRaster().getSample(0, 0, 0), "top-left is now white");
    }

    private static byte[] png(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) img.getRaster().setSample(x, y, 0, 255);
        img.getRaster().setSample(0, 0, 0, 0);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
        return bos.toByteArray();
    }
}
