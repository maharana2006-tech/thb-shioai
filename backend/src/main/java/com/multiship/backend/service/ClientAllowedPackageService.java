package com.multiship.backend.service;

import com.multiship.backend.dto.AllowPackageRequest;
import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ClientAllowedPackageDTO;

import java.util.List;

public interface ClientAllowedPackageService {

    ApiResponse<List<ClientAllowedPackageDTO>> listForClient(String clientCode);

    ApiResponse<ClientAllowedPackageDTO> allow(String clientCode, AllowPackageRequest request);

    ApiResponse<Void> remove(String clientCode, Long presetId);

    ApiResponse<ClientAllowedPackageDTO> setDefault(String clientCode, Long presetId);
}
