package com.multiship.backend.service;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ClientCodeMapDTO;
import com.multiship.backend.dto.UpsertClientCodeMapRequest;

import java.util.List;

/**
 * CRUD for the four per-client ERP alias tables. The {@code kind}
 * parameter selects which table each method operates on so callers
 * can share one path prefix in the controller.
 */
public interface ClientCodeMapService {

    ApiResponse<List<ClientCodeMapDTO>> list(String clientCode, ClientCodeMapDTO.Kind kind);

    ApiResponse<ClientCodeMapDTO> upsert(
            String clientCode, ClientCodeMapDTO.Kind kind, UpsertClientCodeMapRequest request);

    ApiResponse<Void> remove(String clientCode, ClientCodeMapDTO.Kind kind, Long id);
}
