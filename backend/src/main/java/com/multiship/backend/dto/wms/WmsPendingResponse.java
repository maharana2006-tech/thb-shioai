package com.multiship.backend.dto.wms;

import lombok.Data;

import java.util.List;

/**
 * Envelope for the WMS {@code /api/v1/shipping-label/pending-orders} response:
 * {@code { code, status, message, errorMessages, data:[…], totalPage, size }}.
 * Only {@code data} carries the shippable shipments we import.
 */
@Data
public class WmsPendingResponse {
    private Integer code;
    private String status;
    private String message;
    private List<String> errorMessages;
    private List<WmsPendingOrderDTO> data;
    private Integer totalPage;
    private Integer size;
}
