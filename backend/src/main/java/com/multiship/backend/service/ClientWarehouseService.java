package com.multiship.backend.service;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.AttachWarehouseRequest;
import com.multiship.backend.dto.ClientWarehouseDTO;

import java.util.List;

public interface ClientWarehouseService {

    ApiResponse<List<ClientWarehouseDTO>> listForClient(String clientCode);

    ApiResponse<ClientWarehouseDTO> attach(String clientCode, AttachWarehouseRequest request);

    ApiResponse<Void> detach(String clientCode, String warehouseCode);

    ApiResponse<ClientWarehouseDTO> setDefault(String clientCode, String warehouseCode);
}
