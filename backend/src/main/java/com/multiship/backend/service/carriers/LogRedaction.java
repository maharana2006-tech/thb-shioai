package com.multiship.backend.service.carriers;

/**
 * Sprint 51 BS-L1 — small util for scrubbing carrier credentials out of
 * response bodies before they hit the log.
 *
 * <p>Carrier auth failures (401/403) frequently echo the presented
 * {@code client_id} back in the JSON error body. When we log that body
 * verbatim the credentials land in operational logs (Splunk / CloudWatch)
 * where they can persist for weeks and be read by a wider audience than
 * ever legitimately needed access to the secret.
 *
 * <p>Kept intentionally minimal — no regex, no framework. Two string
 * replacements per invocation; skips work when either argument is blank.
 */
public final class LogRedaction {

    private static final String MASK = "***";

    private LogRedaction() {}

    /**
     * @return {@code body} with any occurrence of {@code clientId} or
     * {@code clientSecret} replaced by {@value #MASK}. Null-safe: returns
     * the input untouched when {@code body} is null; skips a given secret
     * when it is null or blank (otherwise we'd replace every empty string
     * in the body with the mask).
     */
    public static String redactSecrets(String body, String clientId, String clientSecret) {
        if (body == null) return null;
        String out = body;
        if (clientId != null && !clientId.isBlank()) {
            out = out.replace(clientId, MASK);
        }
        if (clientSecret != null && !clientSecret.isBlank()) {
            out = out.replace(clientSecret, MASK);
        }
        return out;
    }
}
