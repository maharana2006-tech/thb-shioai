package com.multiship.backend.service;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ClientShippingPolicyDTO;
import com.multiship.backend.dto.UpdateClientPolicyRequest;

public interface ClientShippingPolicyService {

    ApiResponse<ClientShippingPolicyDTO> get(String clientCode);

    ApiResponse<ClientShippingPolicyDTO> update(String clientCode, UpdateClientPolicyRequest request);
}
