package com.multiship.backend.dto;

import java.time.LocalDateTime;

/**
 * PR-D — shape returned by
 * {@code GET /api/v1/admin/usps-direct/void-reconciliation/status?trackingNumber=...}.
 * Helps operators debug the "order shows CANCELLED but customer says
 * it was delivered" scenario: the reconciliation status tells them
 * whether USPS has confirmed the void (APPROVED), refused it (DENIED),
 * or hasn't yet weighed in.
 */
public record UspsVoidReconciliationStatusDTO(
        /** IMpb tracking number the operator queried. */
        String trackingNumber,

        /** OrderTracking.status — {@code VOIDED} or {@code VOID_FAILED}
         *  for reconciled voids; anything else means the row isn't in
         *  a void state at all. */
        String orderStatus,

        /** OrderTracking.voidReconciliationStatus — null when no
         *  reconciliation has run for this tracking number yet. */
        String reconciliationStatus,

        /** OrderTracking.voidReconciliationCheckedAt — null until the
         *  first reconciliation run touches this row. */
        LocalDateTime lastCheckedAt
) {
}
