package com.multiship.backend.service.ndsshipment;

/**
 * Applies the NDS prefill phone-rule (from the ShipX port):
 * <ol>
 *   <li>Strip whitespace characters from the raw NDS value.</li>
 *   <li>If the remaining length is 10–15 characters, keep the stripped value.</li>
 *   <li>Otherwise substitute the tenant-configured fallback (or the
 *       platform default) and flag the field as {@code defaulted} on
 *       the prefill DTO.</li>
 * </ol>
 * "Whitespace" here strips spaces, tabs, newlines, and non-breaking
 * spaces the ERP occasionally injects.
 */
public final class NdsPhoneNormalizer {

    /** Platform-default fallback when the tenant hasn't set one. */
    static final String DEFAULT_PHONE = "+1 616 772 3513";

    private NdsPhoneNormalizer() {}

    /** Value shape so callers get both the phone + the "defaulted" flag in one call. */
    public record Result(String phone, boolean defaulted) {}

    public static Result normalize(String rawPhone) {
        return normalize(rawPhone, null);
    }

    /**
     * @param fallbackPhone tenant-configured fallback; null / blank falls
     *                      through to {@link #DEFAULT_PHONE}.
     */
    public static Result normalize(String rawPhone, String fallbackPhone) {
        String fallback = fallbackPhone != null && !fallbackPhone.isBlank()
                ? fallbackPhone.trim()
                : DEFAULT_PHONE;
        if (rawPhone == null || rawPhone.isBlank()) {
            return new Result(fallback, true);
        }
        String stripped = rawPhone.replaceAll("[\\s\\u00A0]", "");
        int len = stripped.length();
        if (len >= 10 && len <= 15) {
            return new Result(stripped, false);
        }
        return new Result(fallback, true);
    }
}
