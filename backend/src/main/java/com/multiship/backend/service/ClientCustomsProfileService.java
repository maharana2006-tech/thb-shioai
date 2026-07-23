package com.multiship.backend.service;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ClientCustomsProfileDTO;
import com.multiship.backend.dto.CustomsProfileFilters;
import com.multiship.backend.dto.PageResponseDTO;

import java.util.List;
import java.util.Map;

public interface ClientCustomsProfileService {

    /** Every profile across all clients (master list) with client name. */
    ApiResponse<List<ClientCustomsProfileDTO>> listAll();

    /**
     * Filtered + sorted + paginated list for the Importer/Broker settings page.
     * See {@link CustomsProfileFilters} for the recognised inputs.
     */
    ApiResponse<PageResponseDTO<ClientCustomsProfileDTO>> listPaginated(CustomsProfileFilters filters);

    /**
     * Cross-cutting counts for the page's health strip. Keys:
     * {@code profiles}, {@code destinationsCovered}, {@code clientsConfigured}.
     */
    ApiResponse<Map<String, Long>> getStats();

    /**
     * Build a CSV export of every profile that matches the given filters,
     * ignoring the page/size on {@link CustomsProfileFilters}.
     */
    String exportProfilesCsv(CustomsProfileFilters filters);

    /** All importer/broker profiles for a client. */
    ApiResponse<List<ClientCustomsProfileDTO>> list(String clientCode);

    /** One profile by its id (must belong to the client); data null when none exists. */
    ApiResponse<ClientCustomsProfileDTO> get(String clientCode, Long id);

    /**
     * Create (id null) or update (id set) a client's profile. The request's
     * countries set defines which destinations it covers; no country may belong
     * to another profile of the same client.
     */
    ApiResponse<ClientCustomsProfileDTO> upsert(String clientCode, ClientCustomsProfileDTO request);

    /** Remove a client's profile by id. */
    ApiResponse<Void> delete(String clientCode, Long id);
}
