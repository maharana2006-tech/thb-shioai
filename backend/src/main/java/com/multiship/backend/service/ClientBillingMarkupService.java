package com.multiship.backend.service;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ClientBillingMarkupDTO;
import com.multiship.backend.dto.UpdateClientMarkupRequest;

public interface ClientBillingMarkupService {

    ApiResponse<ClientBillingMarkupDTO> get(String clientCode);

    ApiResponse<ClientBillingMarkupDTO> update(String clientCode, UpdateClientMarkupRequest request);
}
