package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * One entry in the client's attached-warehouses list. Wraps the underlying
 * warehouse summary so the UI can render everything in one round-trip.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClientWarehouseDTO {
    private Long id;
    private String clientCode;
    private WarehouseDTO warehouse;
    /** True on the row that the resolution service picks when no warehouse is named. */
    private Boolean isDefault;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
