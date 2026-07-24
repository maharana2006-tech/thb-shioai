package com.multiship.backend.service;

import com.multiship.backend.dto.AllowServiceRequest;
import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ClientAllowedServiceDTO;

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
}
