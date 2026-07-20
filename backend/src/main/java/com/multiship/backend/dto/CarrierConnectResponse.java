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
public class CarrierConnectResponse {

    private String carrierCode;
    private String carrierName;
    private Boolean connected;
    private String message;
    private String accountNumber;
    private String environment;
    private LocalDateTime connectedAt;
    private LocalDateTime tokenExpiresAt;
    private Boolean tokenExpired;
    private String accessTokenPreview;
}
