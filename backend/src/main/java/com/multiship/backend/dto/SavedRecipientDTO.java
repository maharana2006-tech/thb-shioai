package com.multiship.backend.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Sprint 38 — wire DTO for saved recipients. Round-trips both ways:
 * clients POST it to create/update, and the search endpoint returns
 * it.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SavedRecipientDTO {

    /** Populated on responses; ignored on create. */
    private Long id;

    /** Owner customer number. Null = platform-wide. */
    private String ownerCustomerNo;

    @NotBlank
    private String name;

    private String company;
    private String phone;
    private String phoneCountryCode;
    private String email;

    @NotBlank
    private String addressLine1;
    private String addressLine2;
    private String addressLine3;

    @NotBlank
    private String city;
    private String state;

    @NotBlank
    private String postalCode;

    @NotBlank
    private String countryCode;

    private Boolean residential;
    private String tag;

    /** Populated on responses. */
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
