package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CarrierAccountRefDTO {
    private Long id;
    private String accountNumber;
    private String carrierCode;
    private String accountName;
    private String customerNo;
    private String environment;
    private Boolean isDefault;
    /** Default account for the linked client (customerNo). */
    private Boolean clientDefault;
    private Boolean active;
    /** True when credentials are filled and the account is usable for generation. */
    private Boolean complete;
    /** Masked hint of the client id, never the secret. */
    private String clientIdPreview;
    /** Result of the last credential check against the carrier API; null = never checked. */
    private Boolean verified;
    private LocalDateTime lastVerifiedAt;
    /** Labels generated with this account and when it last shipped. */
    private Long labelsGenerated;
    private LocalDateTime lastUsedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
