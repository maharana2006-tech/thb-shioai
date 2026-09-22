package com.multiship.backend.service.ndsshipment;

/**
 * Applies the NDS prefill phone-rule (from the ShipX port):
 * <ol>
 *   <li>Strip whitespace characters from the raw NDS value.</li>
 *   <li>If the remaining length is 10–15 characters, keep the stripped value.</li>
 *   <li>Otherwise substitute {@code +1 616 772 3513} (the ops-provided default
 *       so labels don't get rejected upstream) and flag the field as
 *       {@code defaulted} on the prefill DTO.</li>
 * </ol>
 * "Whitespace" here strips spaces, tabs, newlines, and non-breaking
 * spaces the ERP occasionally injects.
 */
final class NdsPhoneNormalizer {

    /** Ops-provided fallback used when the NDS phone can't be salvaged. */
    static final String DEFAULT_PHONE = "+1 616 772 3513";

    private NdsPhoneNormalizer() {}

    /** Value shape so callers get both the phone + the "defaulted" flag in one call. */
    record Result(String phone, boolean defaulted) {}

    static Result normalize(String rawPhone) {
        if (rawPhone == null || rawPhone.isBlank()) {
            return new Result(DEFAULT_PHONE, true);
        }
        String stripped = rawPhone.replaceAll("[\\s\\u00A0]", "");
        int len = stripped.length();
        if (len >= 10 && len <= 15) {
            return new Result(stripped, false);
        }
        return new Result(DEFAULT_PHONE, true);
    }
}
