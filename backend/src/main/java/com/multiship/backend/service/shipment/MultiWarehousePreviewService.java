package com.multiship.backend.service.shipment;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.MultiWarehouseLabelRequest;
import com.multiship.backend.dto.MultiWarehousePreviewResponse;

/**
 * Sprint 47 PR4 — dry-run companion to {@link MultiWarehouseLabelService}.
 * Runs the same warehouse-assignment logic that the real endpoint would
 * (explicit warehouseCode on the line, else the G3 selector's pick) and
 * returns the resulting split plan without touching carriers or the DB.
 */
public interface MultiWarehousePreviewService {

    ApiResponse<MultiWarehousePreviewResponse> preview(MultiWarehouseLabelRequest request);
}
