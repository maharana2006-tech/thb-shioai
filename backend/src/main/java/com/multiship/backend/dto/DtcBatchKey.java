package com.multiship.backend.dto;

import java.math.BigDecimal;

/**
 * Identity of one DTC batch in the summary list: the (tenant, Oracle batch)
 * pair. A batch spans many totes; uniqueness in dtc_orders is
 * (batch_id, tote_number, tenant_id).
 */
public record DtcBatchKey(String tenantId, BigDecimal batchId) {
}
