package com.multiship.backend.service.carriers;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Shared HMAC-SHA256 utilities for Sprint 36 webhook signature
 * verification. Every carrier signs its webhook body with HMAC-SHA256
 * using a secret we registered — same algorithm, different header + hex
 * encoding + case conventions.
 */
final class WebhookHmacUtil {

    private WebhookHmacUtil() {}

    /**
     * Compute the lowercase hex HMAC-SHA256 of {@code body} using
     * {@code secret}. Returns null when either input is blank OR the
     * MAC init fails (bad algorithm or key spec).
     */
    static String hmacSha256Hex(String body, String secret) {
        if (secret == null || secret.isBlank() || body == null) return null;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * Constant-time comparison of two signatures — prevents timing
     * attacks that would leak the correct signature by measuring how
     * long the comparison takes.
     */
    static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        // Normalise: trim + lowercase before comparing. Every carrier
        // sends the sig in lowercase hex, but tolerating either case
        // keeps future-you unblocked.
        byte[] aBytes = a.trim().toLowerCase().getBytes(StandardCharsets.UTF_8);
        byte[] bBytes = b.trim().toLowerCase().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(aBytes, bBytes);
    }
}
