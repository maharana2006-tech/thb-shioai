package com.multiship.backend.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Stateless credential check used by the add-account drawer before saving. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerifyCredentialsRequest {

    @NotBlank
    private String carrierCode;

    @NotBlank
    private String clientId;

    @NotBlank
    private String clientSecret;

    /**
     * Optional shipper number — used as the {@code x-merchant-id} header on
     * UPS OAuth so quota/rate-limit tracking attaches to the merchant.
     * Ignored for carriers that don't need it.
     */
    private String accountNumber;
}
