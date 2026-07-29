package com.multiship.backend.service;

import com.multiship.backend.dto.AllowServiceRequest;
import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ClientAllowedServiceDTO;
import com.multiship.backend.dto.ClientAllowedServiceDestinationsDTO;
import com.multiship.backend.dto.ClientAllowedServiceWarehousesDTO;
import com.multiship.backend.dto.ReplaceAllowedServiceDestinationsRequest;
import com.multiship.backend.dto.ReplaceAllowedServiceWarehousesRequest;

import java.util.List;

public interface ClientAllowedServiceService {

    ApiResponse<List<ClientAllowedServiceDTO>> listForClient(String clientCode);

    ApiResponse<ClientAllowedServiceDTO> allow(String clientCode, AllowServiceRequest request);

    ApiResponse<Void> remove(String clientCode, Long serviceId);

    ApiResponse<ClientAllowedServiceDTO> setDefault(String clientCode, Long serviceId);

    /**
     * Every service-allowlist row across all clients. Consumed by the
     * Shipping Services settings page to render an "Assigned to (n)" column
     * grouped by service id.
     */
    ApiResponse<List<ClientAllowedServiceDTO>> listAllAssignments();

    /** Destination-gate on a single allowlist row. Empty = unrestricted. */
    ApiResponse<ClientAllowedServiceDestinationsDTO> getDestinations(String clientCode, Long serviceId);

    /** Atomic replace of the destination set (delete-all then insert). */
    ApiResponse<ClientAllowedServiceDestinationsDTO> replaceDestinations(
            String clientCode, Long serviceId, ReplaceAllowedServiceDestinationsRequest request);

    /** Clear every destination row for the allowlist entry (back to unrestricted). */
    ApiResponse<Void> clearDestinations(String clientCode, Long serviceId);

    // ===== Warehouse gate (G1) =====

    /** Warehouse-gate on a single allowlist row. Empty = unrestricted. */
    ApiResponse<ClientAllowedServiceWarehousesDTO> getWarehouses(String clientCode, Long serviceId);

    /** Atomic replace of the warehouse set (delete-all then insert). Only
     *  warehouses actually attached to the client are accepted; others are
     *  silently dropped so the caller can't gate a service to a warehouse
     *  the client can't ship from. */
    ApiResponse<ClientAllowedServiceWarehousesDTO> replaceWarehouses(
            String clientCode, Long serviceId, ReplaceAllowedServiceWarehousesRequest request);

    /** Clear every warehouse row for the allowlist entry (back to unrestricted). */
    ApiResponse<Void> clearWarehouses(String clientCode, Long serviceId);
}
