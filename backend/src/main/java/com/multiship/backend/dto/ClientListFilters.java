package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Filters for GET /clients. Blank/null = not applied. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClientListFilters {
    /** Global keyword: code, name, or city. */
    private String search;
    /** ACTIVE | INACTIVE */
    private String status;
    /** UPS | FEDEX | USPS — has an account with that carrier. */
    private String carrier;
    /** YES | NO — has any orders. */
    private String hasOrders;
    /** Column contains-filters. */
    private String code;
    private String name;
    private String city;
    /** code | name | orderCount | created */
    private String sortBy;
    private String sortDirection;
    private int page;
    private int size;
}
