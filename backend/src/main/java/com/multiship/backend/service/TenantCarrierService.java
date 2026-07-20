package com.multiship.backend.service;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.CarrierAccountDTO;
import com.multiship.backend.dto.OrderLineDTO;
import com.multiship.backend.dto.OrderWithLinesDTO;

import java.util.List;

public interface TenantCarrierService {

    ApiResponse<CarrierAccountDTO> getDefaultCarrierAccount(String tenantId);

    ApiResponse<List<CarrierAccountDTO>> getCarrierAccountsForTenant(String tenantId);

    ApiResponse<List<CarrierAccountDTO>> getAllTenantCarrierAccounts();

    ApiResponse<List<OrderLineDTO>> getOrderLines(Integer orderNo);

    ApiResponse<OrderWithLinesDTO> getOrderWithLines(Integer orderNo);
}
