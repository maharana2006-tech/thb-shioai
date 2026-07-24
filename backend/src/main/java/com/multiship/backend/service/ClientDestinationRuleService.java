package com.multiship.backend.service;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ClientDestinationRulesDTO;
import com.multiship.backend.dto.ReplaceDestinationRulesRequest;

public interface ClientDestinationRuleService {

    ApiResponse<ClientDestinationRulesDTO> get(String clientCode);

    ApiResponse<ClientDestinationRulesDTO> replace(String clientCode, ReplaceDestinationRulesRequest request);

    ApiResponse<Void> clear(String clientCode);
}
