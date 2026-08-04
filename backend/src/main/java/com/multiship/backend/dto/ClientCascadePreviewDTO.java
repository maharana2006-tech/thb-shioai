package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Preview counts returned to the wizard's confirm dialog before
 * committing a client-disable cascade. Frontend uses `pendingOrderCount`
 * to hard-block (409-style) when non-zero, and the rest to render a
 * "N carrier accounts + M warehouses will be deactivated" summary.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClientCascadePreviewDTO {
    private String clientCode;
    /** Orders where is_label_generated=false — hard block on disable. */
    private long pendingOrderCount;
    /** Carrier accounts currently active with customerNo = client. */
    private long activeCarrierAccountCount;
    /** Warehouses OWNED by this client (ownerType=CLIENT) currently active. */
    private long clientOwnedWarehouseCount;
    /** Client-warehouse links (attachments) — always deactivated on cascade. */
    private long clientWarehouseLinkCount;
    /** Client currently active? Used by the frontend to render the right label. */
    private boolean clientCurrentlyActive;
}
