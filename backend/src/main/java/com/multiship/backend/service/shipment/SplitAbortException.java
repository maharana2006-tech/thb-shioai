package com.multiship.backend.service.shipment;

import lombok.Getter;

/**
 * Sprint 47 — thrown by {@link MultiWarehouseLabelService} when any child
 * shipment in a split fails to generate. Escaping the {@code @Transactional}
 * boundary triggers rollback so the operator gets a consistent
 * "no labels bought" state — never a half-split shipment.
 *
 * <p>The controller catches this and maps to a 422 with the failing
 * warehouse + underlying detail so the caller knows exactly which
 * warehouse aborted the batch.
 */
@Getter
public class SplitAbortException extends RuntimeException {

    private final String warehouseCode;
    private final String detail;

    public SplitAbortException(String message, String warehouseCode, String detail) {
        super(message);
        this.warehouseCode = warehouseCode;
        this.detail = detail;
    }
}
