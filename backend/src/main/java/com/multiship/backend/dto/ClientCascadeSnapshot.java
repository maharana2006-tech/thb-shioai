package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * What a client-disable cascade actually deactivated. Serialised into
 * the audit_log.changes JSON so the re-enable path can restore only
 * these rows (not touching rows that were already inactive before the
 * cascade).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClientCascadeSnapshot {
    /** IDs of carrier_account_ref rows that were flipped active=false. */
    private List<Long> carrierAccountIds;
    /** Codes of warehouses (ownerType=CLIENT) that were flipped active=false.
     *  Used on re-enable to flip them back to active. */
    private List<String> clientOwnedWarehouseCodes;
    /** IDs of the client_warehouse link rows we deleted. Dead once deleted —
     *  kept only for post-mortem tracing (which link the client had before). */
    private List<Long> clientWarehouseLinkIds;
    /**
     * Codes of every warehouse the client was ATTACHED to (regardless of
     * ownership — includes both CLIENT-owned and PLATFORM-owned). On
     * re-enable this drives the re-attach loop; without it, a PLATFORM
     * warehouse attachment would be silently lost across a disable/enable
     * cycle. Populated on every cascade after the DES-TH data-loss fix.
     */
    private List<String> detachedWarehouseCodes;
}
