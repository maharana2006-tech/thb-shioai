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
    /** Codes of warehouses (ownerType=CLIENT) that were flipped active=false. */
    private List<String> clientOwnedWarehouseCodes;
    /** IDs of client_warehouse link rows deactivated (or removed — TBD). */
    private List<Long> clientWarehouseLinkIds;
}
