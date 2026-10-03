package com.multiship.backend.dto;

import java.math.BigDecimal;

/**
 * Operator correction for one DTC shipment line — the body of
 * {@code PATCH /api/v1/dtc/batches/{batchId}/lines/{lineId}}, behind the batch page's
 * Edit on a line that has no label order yet.
 *
 * <p>Patch semantics per field: {@code null} leaves the column untouched, an
 * empty string clears it (numbers can only be replaced). Only the columns
 * "Automatic label" builds a shipment from are editable; batch, tote, tenant,
 * customer and ship date are the ERP's identity for the line.
 *
 * <p>{@code adoptOrderNo} is a different intent: it points the line at an order
 * the operator minted by hand on the manual shipment form, and stamps the row
 * GENERATED from that order's tracking. Sent with no data fields.
 */
public record DtcOrderEditRequest(
        String shipName,
        String shipAttn,
        String shipAddr1,
        String shipAddr2,
        String shipAddr3,
        String shipToCity,
        String shipToState,
        String shipToZip,
        String shipToCountryCode,
        String phone,
        String email,
        BigDecimal weight,
        BigDecimal unitValue,
        String goodsDesc,
        String shipViaCode,
        Integer adoptOrderNo) {
}
