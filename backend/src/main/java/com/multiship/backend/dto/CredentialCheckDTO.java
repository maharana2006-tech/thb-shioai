package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** Outcome of a live credential check against the carrier API. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CredentialCheckDTO {
    private Boolean verified;
    private String message;
    private LocalDateTime checkedAt;

    /**
     * Stamps.com SERA (3-legged OAuth): when true, the credentials themselves
     * couldn't be verified server-to-server because the account is provisioned
     * for {@code authorization_code} grant, not {@code client_credentials}.
     * The frontend should open {@link #authorizeUrl} in a popup/redirect so
     * the operator can consent — on success the SERA server 302s back to our
     * callback endpoint which stores the refresh_token on the account and
     * marks it verified.
     */
    private Boolean needsAuthorization;

    /**
     * Fully-formed authorize URL (including {@code client_id}, {@code state},
     * {@code redirect_uri}, and {@code scope}) the frontend opens when
     * {@link #needsAuthorization} is true. Only populated when
     * {@link #needsAuthorization} is true.
     */
    private String authorizeUrl;
}
