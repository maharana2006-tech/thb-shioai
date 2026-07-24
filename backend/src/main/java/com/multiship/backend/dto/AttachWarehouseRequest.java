package com.multiship.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * POST /clients/{code}/warehouses body. Attaches an existing warehouse to
 * the client and optionally marks it as the new default (which clears the
 * old one atomically in the service).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AttachWarehouseRequest {

    @NotBlank
    private String warehouseCode;

    @NotNull
    private Boolean makeDefault;
}
