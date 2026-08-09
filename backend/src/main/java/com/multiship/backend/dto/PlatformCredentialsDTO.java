package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Platform-account carrier metadata for the Add-account drawer prefill.
 *
 * <p>Sprint 49 Tier 1 — {@code clientSecret} is NEVER returned in the
 * response. Previously the plaintext secret went to the browser (and
 * anywhere a response could be logged / screenshot / cached). The
 * drawer now shows {@link #clientSecretMasked} for display and offers a
 * "use platform credentials" server-side copy flow so the value never
 * transits the browser.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlatformCredentialsDTO {
    private String carrierCode;
    private String clientId;
    /** "****" + last 4 chars, or empty when no secret is stored. Never plaintext. */
    private String clientSecretMasked;
    /** True when a secret exists; the UI can gate the prefill button on this. */
    private boolean hasClientSecret;
    private boolean found;
}
