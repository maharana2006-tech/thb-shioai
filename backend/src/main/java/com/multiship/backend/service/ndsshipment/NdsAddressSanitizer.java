package com.multiship.backend.service.ndsshipment;

import java.util.regex.Pattern;

/**
 * Strips special characters from SHIP_NAME / SHIP_ATTN / SHIP_ADDR1
 * so downstream carrier APIs don't reject the wire payload. Whitelist
 * kept intentionally narrow — the point is safety, not preservation.
 *
 * <p><b>Kept characters:</b> ASCII letters + digits, space, {@code .},
 * {@code ,}, {@code -}, {@code #}, {@code /}, {@code &}, {@code '}.
 *
 * <p><b>Stripped:</b> everything else — quotes, angle brackets, brackets,
 * asterisks, backslashes, emoji, control chars. Consecutive-whitespace
 * collapse is a caller concern, not this helper's.
 *
 * <p><i>Note: ShipX's exact whitelist is unknown; this list is the
 * task's proposed baseline. Adjust if carrier rejects surface a
 * missing-character symptom.</i>
 */
final class NdsAddressSanitizer {

    /** Match any character NOT in the whitelist. */
    private static final Pattern DISALLOWED = Pattern.compile("[^A-Za-z0-9 .,\\-#/&']");

    private NdsAddressSanitizer() {}

    static String sanitize(String raw) {
        if (raw == null) return null;
        String stripped = DISALLOWED.matcher(raw).replaceAll("");
        // Trim so a leading `<` that got stripped doesn't leave a leading space.
        return stripped.trim();
    }
}
