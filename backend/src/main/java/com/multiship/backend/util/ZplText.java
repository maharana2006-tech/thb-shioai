package com.multiship.backend.util;

import java.util.regex.Pattern;

/**
 * Text-level clean-ups for carrier-returned ZPL handed to operators (Copy
 * ZPL / Download .zpl). Printers ignore what they don't understand, but the
 * viewers people paste into (Labelary) lint it, and a warning on every UPS
 * label reads as a defect in our output.
 */
public final class ZplText {

    private ZplText() {}

    /**
     * Commands that are not ZPL II but that carriers emit anyway: UPS closes
     * every label with {@code ^DN} (undocumented; Zebra firmware ignores it,
     * Labelary reports "This ZPL command does not exist and was ignored").
     * Removed together with its trailing line break so the text stays tidy.
     */
    private static final Pattern UNSUPPORTED = Pattern.compile("\\^DN\\r?\\n?");

    public static String stripUnsupportedCommands(String zpl) {
        if (zpl == null || zpl.isEmpty()) return zpl;
        return UNSUPPORTED.matcher(zpl).replaceAll("");
    }

    private static final Pattern GFB = Pattern.compile("\\^GFB,(\\d+),(\\d+),(\\d+),");
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    /**
     * Recode binary graphics ({@code ^GFB}, raw bytes) as ASCII hex
     * ({@code ^GFA}) — the same bits, printer-equivalent. Stamps ships its
     * USPS logos and barcodes as {@code ^GFB}; raw bytes do not survive a
     * text/plain response, a clipboard or a web form (Labelary's viewer
     * rendered a blank label). The input is the label as ISO-8859-1 text,
     * one char per byte; the output is pure ASCII.
     */
    public static String asciiSafe(String zpl) {
        if (zpl == null || zpl.isEmpty()) return zpl;
        var m = GFB.matcher(zpl);
        StringBuilder out = new StringBuilder(zpl.length() * 2);
        int pos = 0;
        while (m.find(pos)) {
            int bytes = Integer.parseInt(m.group(1));
            int dataStart = m.end();
            int dataEnd = Math.min(zpl.length(), dataStart + bytes);
            out.append(zpl, pos, m.start())
               .append("^GFA,").append(m.group(1)).append(',').append(m.group(2)).append(',').append(m.group(3)).append(',');
            for (int i = dataStart; i < dataEnd; i++) {
                int b = zpl.charAt(i) & 0xff;
                out.append(HEX[b >>> 4]).append(HEX[b & 0xf]);
            }
            pos = dataEnd;
        }
        return out.append(zpl, pos, zpl.length()).toString();
    }
}
