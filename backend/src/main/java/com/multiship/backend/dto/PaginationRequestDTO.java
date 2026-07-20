package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaginationRequestDTO {
    @Builder.Default
    private int page = 0;

    @Builder.Default
    private int size = 20;

    @Builder.Default
    private String sortBy = "orderNo";

    @Builder.Default
    private String sortDirection = "ASC";

    // Valid sort fields
    public static final String SORT_BY_ORDER_NO = "orderNo";
    public static final String SORT_BY_CITY = "city";
    public static final String SORT_BY_WEIGHT = "weight";
    public static final String SORT_BY_STATUS = "status";
    public static final String SORT_BY_CREATED_DATE = "createdDate";

    // Valid sort directions
    public static final String SORT_ASC = "ASC";
    public static final String SORT_DESC = "DESC";
}