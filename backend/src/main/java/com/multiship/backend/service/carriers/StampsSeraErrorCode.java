package com.multiship.backend.service.carriers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Optional;

/**
 * PR-T1 — SERA v1 error-code taxonomy. Per
 * {@code developer.stamps.com/rest-api/reference/serav1.html} SERA emits
 * {@code {"error_code":"800010","error_message":"..."}} on failure. The
 * numeric {@code error_code} is the retry / classification signal; the
 * old string-only {@code extractSeraError} path threw it away.
 *
 * <p>Known codes (families):
 * <ul>
 *   <li>{@code 800000} — validation (bad request body)</li>
 *   <li>{@code 800001} — invalid Idempotency-Key</li>
 *   <li>{@code 800002} — missing Idempotency-Key on enforced endpoint</li>
 *   <li>{@code 800010} — invalid {@code label_id}</li>
 *   <li>{@code 800100}–{@code 800106} — pickup errors</li>
 *   <li>{@code 800200} — manifest not supported</li>
 *   <li>{@code 899999} — generic / unhandled server error</li>
 * </ul>
 *
 * <p>{@link #UNKNOWN} is returned when the body is unparseable or carries
 * no {@code error_code} field — the caller then falls back to the
 * free-form {@code error_message} / HTTP-status string.
 */
public enum StampsSeraErrorCode {

    VALIDATION("800000"),
    IDEMPOTENCY_INVALID("800001"),
    IDEMPOTENCY_MISSING("800002"),
    LABEL_INVALID("800010"),
    PICKUP_NOT_SUPPORTED("800100"),
    PICKUP_MULTI_CARRIER("800101"),
    PICKUP_INELIGIBLE("800102"),
    PICKUP_USPS_SUNDAY("800103"),
    PICKUP_USPS_HOLIDAY("800104"),
    PICKUP_USPS_NOT_NEXT_BUSINESS_DAY("800105"),
    PICKUP_ADDRESS_MISMATCH("800106"),
    MANIFEST_NOT_SUPPORTED("800200"),
    GENERIC("899999"),
    UNKNOWN("");

    /** Raw numeric string SERA returns on the wire. Empty for {@link #UNKNOWN}. */
    public final String rawCode;

    StampsSeraErrorCode(String rawCode) {
        this.rawCode = rawCode;
    }

    /** Reverse lookup. Null / blank / unrecognised → {@link #UNKNOWN}. */
    public static StampsSeraErrorCode fromCode(String raw) {
        if (raw == null) return UNKNOWN;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return UNKNOWN;
        for (StampsSeraErrorCode c : values()) {
            if (c != UNKNOWN && c.rawCode.equals(trimmed)) return c;
        }
        return UNKNOWN;
    }

    /** True for 800100-800106 — pickup-flow errors. The caller usually
     *  wants to surface these verbatim to the operator, not retry. */
    public boolean isPickup() {
        return name().startsWith("PICKUP_");
    }

    /** True for 800001 + 800002 — Idempotency-Key problems. These are
     *  caller bugs (missing or malformed key); never auto-retry. */
    public boolean isIdempotency() {
        return this == IDEMPOTENCY_INVALID || this == IDEMPOTENCY_MISSING;
    }

    /** True for 800000 — generic validation failure. Caller should
     *  surface the free-form message to the operator and not retry. */
    public boolean isValidation() {
        return this == VALIDATION;
    }

    /** True for 800010 — invalid label_id on void / reprint. The caller
     *  should treat as {@code IllegalArgumentException}, not a transient
     *  failure. */
    public boolean isLabelInvalid() {
        return this == LABEL_INVALID;
    }

    /** True when SERA gave us no error_code at all — the free-form
     *  message is the only signal. */
    public boolean isUnknown() {
        return this == UNKNOWN;
    }

    /**
     * Parsed SERA error envelope. {@link #code} is the classified enum;
     * {@link #rawCode} is the untouched wire string (useful in logs when
     * SERA adds a new code we don't recognise yet); {@link #message} is
     * {@code error_message} / {@code detail} / {@code message} / {@code error}
     * / {@code errors[0].message} depending on which shape SERA sent.
     */
    public record SeraError(StampsSeraErrorCode code, String rawCode, String message) {

        /** Convenience: short human-readable classification + message, for logs. */
        public String describe() {
            if (code == UNKNOWN) {
                return message == null ? "unknown SERA error" : message;
            }
            return code.name() + " (" + rawCode + "): " + (message == null ? "(no message)" : message);
        }
    }

    /**
     * Parse a SERA error response body. Order of precedence for the
     * free-form message: {@code error_message}, {@code detail},
     * {@code message}, {@code error}, {@code errors[0].message}. The
     * numeric {@code error_code} is read verbatim and classified via
     * {@link #fromCode}.
     *
     * @param body    the raw response body (may be null / blank / malformed)
     * @param mapper  shared Jackson ObjectMapper
     * @return parsed envelope; falls back to {@link #UNKNOWN} + a truncated
     *         raw-body message when nothing matches
     */
    public static SeraError parse(String body, ObjectMapper mapper) {
        if (body == null || body.isBlank()) {
            return new SeraError(UNKNOWN, null, "empty response");
        }
        try {
            JsonNode j = mapper.readTree(body);
            String rawCode = firstText(j, "error_code");
            String msg = firstText(j, "error_message", "detail", "message", "error");
            if (msg == null) {
                JsonNode errors = j.path("errors");
                if (errors.isArray() && !errors.isEmpty()) {
                    JsonNode first = errors.get(0);
                    msg = firstText(first, "error_message", "message", "detail");
                    if (rawCode == null) rawCode = firstText(first, "error_code");
                }
            }
            StampsSeraErrorCode code = fromCode(rawCode);
            String message = msg != null ? msg : (rawCode != null ? "error " + rawCode : safeHead(body));
            return new SeraError(code, rawCode, message);
        } catch (Exception parseFailed) {
            return new SeraError(UNKNOWN, null, safeHead(body));
        }
    }

    /** Convenience string-only overload for existing callers that only
     *  need the human-readable message (what the legacy
     *  {@code extractSeraError(String)} returned). */
    public static String parseMessage(String body, ObjectMapper mapper) {
        return parse(body, mapper).message();
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String f : fields) {
            String v = node.path(f).asText(null);
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    /** Match the pre-existing {@code safeHead} truncation length (200). */
    private static String safeHead(String s) {
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    /** Try to parse an {@link Optional#empty} on parse failure — used by
     *  retry classifiers that only care when there's a definite code. */
    public static Optional<StampsSeraErrorCode> tryCode(String body, ObjectMapper mapper) {
        SeraError e = parse(body, mapper);
        return e.code() == UNKNOWN ? Optional.empty() : Optional.of(e.code());
    }
}
