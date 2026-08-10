package com.multiship.backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClientUpsertRequest {

    /** Unique uppercase code linking orders to this client (immutable after creation). */
    @NotBlank
    @Size(max = 50)
    @Pattern(regexp = "[A-Za-z0-9_-]+", message = "Client code may contain letters, digits, '-' and '_' only")
    private String clientCode;

    @NotBlank
    @Size(max = 255)
    private String name;

    @Size(max = 255)
    private String email;

    @Size(max = 50)
    private String phone;

    // ===== Sprint 50 Tier 1 finding #4 — per-tenant defaults =====
    // All optional (nullable); the client admin form sets them post-create.
    // Length caps mirror the V6 CHECK constraints on the DB side.
    @Size(min = 3, max = 3, message = "defaultCurrency must be a 3-letter ISO 4217 code")
    @Pattern(regexp = "[A-Za-z]{3}|", message = "defaultCurrency must be alphabetic")
    private String defaultCurrency;

    @Size(max = 4)
    private String defaultWeightUnit;

    @Size(max = 4)
    private String defaultDimUnit;

    @Size(max = 50)
    private String timezone;

    @Size(min = 2, max = 2, message = "defaultOriginCountry must be a 2-letter ISO-3166 code")
    @Pattern(regexp = "[A-Za-z]{2}|", message = "defaultOriginCountry must be alphabetic")
    private String defaultOriginCountry;

    @Valid
    private AddressDTO shipFrom;

    @Valid
    private AddressDTO returnAddress;

    private Boolean returnSameAsShipFrom;
}
