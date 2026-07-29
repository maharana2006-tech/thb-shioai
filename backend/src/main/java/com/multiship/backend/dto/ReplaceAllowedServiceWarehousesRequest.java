package com.multiship.backend.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * PUT body for the service-warehouse gate. Atomic replace of the whole set.
 * Empty list = clear all warehouses (service becomes unrestricted).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReplaceAllowedServiceWarehousesRequest {

    @NotNull
    @Size(max = 100)
    private List<Long> warehouseIds;
}
