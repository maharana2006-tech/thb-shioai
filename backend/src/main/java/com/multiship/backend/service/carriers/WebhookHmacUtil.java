package com.multiship.backend.service.carriers;

import lombok.extern.slf4j.Slf4j;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shared HMAC-SHA256 utilities for Sprint 36 webhook signature
 * verification. Every carrier signs its webhook body with HMAC-SHA256
 * using a secret we registered — same algorithm, different header + hex
 * encoding + case conventions.
 */
@Slf4j
public final class WebhookHmacUtil {

    private WebhookHmacUtil() {}

    /** Sprint 50 PR N post-audit #14 — log HMAC init failures once so
     *  a misconfigured JVM/policy (which strips HmacSHA256) doesn't
     *  silently reject every webhook. Fail-closed behaviour is correct
     *  but silence hides the ops-actionable root cause. */
    private static final AtomicBoolean loggedInitFailure = new AtomicBoolean(false);

    /**
     * Compute the lowercase hex HMAC-SHA256 of {@code body} using
     * {@code secret}. Returns null when either input is blank OR the
     * MAC init fails (bad algorithm or key spec).
     */
    public static String hmacSha256Hex(String body, String secret) {
        if (secret == null || secret.isBlank() || body == null) return null;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            // Sprint 50 PR N post-audit #14 — one-shot WARN on first failure.
            // Subsequent failures stay silent to avoid flooding the log if
            // the misconfiguration persists.
            if (loggedInitFailure.compareAndSet(false, true)) {
                log.warn("WebhookHmacUtil: HmacSHA256 init failed — every webhook "
                        + "signature verification will fail-closed. Check the JVM's "
                        + "crypto provider policy. Error: {}", ex.getMessage());
            }
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
