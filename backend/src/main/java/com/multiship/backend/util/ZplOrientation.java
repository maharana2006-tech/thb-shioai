package com.multiship.backend.util;

import javax.imageio.ImageIO;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * {@code ^POI} (print orientation: inverted) tells a Zebra to print the
 * label rotated 180° so it reads upright as it comes off the feed. UPS
 * emits it on every ZPL label. The two engines differ: Labelary emulates
 * it (raw render upside down), zebrash ignores it (raw render upright).
 * This normalises Labelary's output so both engines, the preview and the
 * printable show the label the way an operator reads the peeled sheet.
 * The ZPL bytes stay exactly as the carrier sent them (the ETag/Copy
 * contract); only the Labelary picture is turned.
 */
public final class ZplOrientation {

    private static final Pattern INVERTED = Pattern.compile("\\^POI\\b");

    private ZplOrientation() {}

    /** True when the label asks the printer for inverted orientation. */
    public static boolean printsInverted(byte[] zpl) {
        if (zpl == null || zpl.length == 0) return false;
        return INVERTED.matcher(new String(zpl, StandardCharsets.ISO_8859_1)).find();
    }

    /** The PNG as a reader sees the printed label: rotated 180° when the
     *  ZPL prints inverted, otherwise returned as-is. */
    public static byte[] uprightPng(byte[] zpl, byte[] png) {
        return printsInverted(zpl) ? rotate180(png) : png;
    }

    static byte[] rotate180(byte[] png) {
        try {
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(png));
            if (src == null) return png;
            BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
            var g = out.createGraphics();
            g.drawImage(src, AffineTransform.getQuadrantRotateInstance(2, src.getWidth() / 2.0, src.getHeight() / 2.0), null);
            g.dispose();
            ByteArrayOutputStream bos = new ByteArrayOutputStream(png.length);
            ImageIO.write(out, "png", bos);
            return bos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("rotating label render", e);
        }
    }
}
