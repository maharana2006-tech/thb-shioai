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
}
