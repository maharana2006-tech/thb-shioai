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
public class CarrierAccountDTO {
    private Long id;
    private String tenantId;
    private String carrierCode;
    private String carrierName;
    private String accountNumber;
    private String accountCode;
    private Boolean isDefault;
    private Boolean active;
    private String environment;
    private String shipViaCd;
    private String shipViaDescription;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
