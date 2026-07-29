package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Warehouse-gate on a {@link com.multiship.backend.model.ClientAllowedService}
 * row: the set of warehouse ids the client may ship this service FROM.
 * Empty = unrestricted (any warehouse attached to the client).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClientAllowedServiceWarehousesDTO {
    private String clientCode;
    private Long serviceId;
    /** Warehouse ids sorted ascending. */
    private List<Long> warehouseIds;
}
