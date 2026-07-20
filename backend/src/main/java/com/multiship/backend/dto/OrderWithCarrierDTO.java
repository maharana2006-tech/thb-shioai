package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderWithCarrierDTO {
    private OrderResponseDTO order;
    private CarrierAccountDTO carrierAccount;
}
