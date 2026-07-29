package com.multiship.backend.service.shipment;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.MultiWarehouseLabelRequest;
import com.multiship.backend.dto.MultiWarehouseLabelResponse;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Sprint 47 — generates one label per warehouse for a single split
 * shipment payload. The service groups the input lines by
 * {@code warehouseCode}, calls the existing single-shipment label
 * generator once per group, and persists a {@code ShipmentGroup} +
 * child {@code Shipment} rows so downstream reports / webhooks /
 * tracking can render the shipments as a set.
 *
 * <p>Rollback semantics — fail-all: if any child fails to generate,
 * the whole transaction rolls back so the operator gets a consistent
 * "no labels bought" state. Partial success would leave the split
 * half-shipped and confusing to reconcile.
 */
public interface MultiWarehouseLabelService {

    /**
     * @param request the split-shipment payload; lines are grouped by
     *                {@code warehouseCode}.
     * @param user    the calling principal (recorded on ShipmentGroup for
     *                audit).
     * @return the group + per-child shipment result. Any child failure
     *         rolls back the whole call and returns an error envelope.
     */
    ApiResponse<MultiWarehouseLabelResponse> generate(
            MultiWarehouseLabelRequest request, UserDetails user);
}
