package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Filters for GET /customs-profiles. Blank/null/empty = not applied.
 *
 * <p>Region filtering is done on the frontend by expanding a selected region
 * into its country codes and passing them here as {@code countries}. The
 * server just filters for profiles that cover at least one country in the
 * given list.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomsProfileFilters {
    /** Global keyword: client code, client name, importer/broker, or country. */
    private String search;
    /** Restrict to one client. */
    private String clientCode;
    /** UPS | FEDEX | USPS — profile's account carrier. */
    private String carrier;
    /** YES = profile has own broker; NO = carrier default. */
    private String broker;
    /** Restrict to profiles that cover any of these ISO-2 country codes. */
    private List<String> countries;
    /** client | destinations | importer | broker */
    private String sortBy;
    /** ASC | DESC */
    private String sortDirection;
    private int page;
    private int size;
}
