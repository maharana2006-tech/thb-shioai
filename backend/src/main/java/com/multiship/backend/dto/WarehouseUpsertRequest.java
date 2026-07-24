package com.multiship.backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Payload for POST /warehouses and PUT /warehouses/{code}. The code is
 * immutable after creation — it links {@link com.multiship.backend.model.ClientWarehouse}
 * and {@link com.multiship.backend.model.ShipViaMapping} rows.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WarehouseUpsertRequest {

    @NotBlank
    @Size(max = 50)
    @Pattern(regexp = "[A-Za-z0-9_-]+",
            message = "Warehouse code may contain letters, digits, '-' and '_' only")
    private String code;

    @NotBlank
    @Size(max = 255)
    private String name;

    @Valid
    private AddressDTO address;

    /** PLATFORM | CLIENT (case-insensitive). */
    @NotBlank
    private String ownerType;

    /** Required when ownerType=CLIENT; must be blank/null for PLATFORM. */
    @Size(max = 50)
    private String ownerClientCode;

    /** Defaults to true on create. */
    private Boolean active;
}
