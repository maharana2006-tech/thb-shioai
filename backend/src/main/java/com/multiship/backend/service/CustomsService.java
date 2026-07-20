package com.multiship.backend.service;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.OrderCustomsDTO;
import com.multiship.backend.dto.OrderCustomsUpsertRequest;

public interface CustomsService {

    /** The order's customs declaration; success with null data when none exists yet. */
    ApiResponse<OrderCustomsDTO> getCustoms(String orderNo);

    /** Create or replace the order's customs declaration (items replaced wholesale). */
    ApiResponse<OrderCustomsDTO> upsertCustoms(String orderNo, OrderCustomsUpsertRequest request);
}
