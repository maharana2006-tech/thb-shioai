package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * A ship-from location surfaced to the client — read shape returned by
 * GET /warehouses and embedded in {@code ClientWarehouseDTO}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WarehouseDTO {
    private Long id;
    private String code;
    private String name;
    private AddressDTO address;
    /** PLATFORM | CLIENT — see {@code Warehouse.OWNER_*}. */
    private String ownerType;
    /** Populated only when ownerType=CLIENT. */
    private String ownerClientCode;
    private Boolean active;
    /** How many clients currently attach this warehouse (list view only). */
    private Long attachedClientCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
