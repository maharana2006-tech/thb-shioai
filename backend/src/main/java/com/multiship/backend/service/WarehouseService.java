package com.multiship.backend.service;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.PageResponseDTO;
import com.multiship.backend.dto.WarehouseDTO;
import com.multiship.backend.dto.WarehouseListFilters;
import com.multiship.backend.dto.WarehouseUpsertRequest;

public interface WarehouseService {

    ApiResponse<PageResponseDTO<WarehouseDTO>> listWarehouses(WarehouseListFilters filters);

    ApiResponse<WarehouseDTO> getWarehouse(String code);

    ApiResponse<WarehouseDTO> createWarehouse(WarehouseUpsertRequest request);

    ApiResponse<WarehouseDTO> updateWarehouse(String code, WarehouseUpsertRequest request);

    ApiResponse<WarehouseDTO> toggleActive(String code);

    ApiResponse<Void> deleteWarehouse(String code);
}
