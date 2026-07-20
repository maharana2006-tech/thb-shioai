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
public class CarrierStatusResponse {

    private String carrierCode;
    private String carrierName;
    private Boolean connected;
    private Boolean tokenExpired;
    private String accountNumber;
    private String environment;
    private LocalDateTime connectedAt;
    private LocalDateTime tokenExpiresAt;
    private String message;
    private String documentationUrl;
    private String connectionGuide;
}
