package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Platform-account carrier credentials, returned so the Add-account drawer
 * can pre-fill Client ID / Secret for a new account on the same carrier.
 * ADMIN-only; the shipper can override the pre-filled values.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlatformCredentialsDTO {
    private String carrierCode;
    private String clientId;
    private String clientSecret;
    private boolean found;
}
