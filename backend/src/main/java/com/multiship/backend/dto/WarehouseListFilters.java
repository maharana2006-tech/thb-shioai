package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Filters for GET /warehouses. Blank/null = not applied. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WarehouseListFilters {
    /** Global keyword: code, name, or city. */
    private String search;
    /** PLATFORM | CLIENT. */
    private String ownerType;
    /** Restrict to warehouses owned by one client. */
    private String ownerClientCode;
    /** YES | NO — active flag. */
    private String active;
    /** code | name | owner | created */
    private String sortBy;
    private String sortDirection;
    private int page;
    private int size;
}
