package com.multiship.backend.dto.external;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * Synchronous batch create — every item is the same body as
 * {@code POST /api/v2/external/shipments}. Items are validated one by one
 * (not via {@code @Valid} here) so a single bad item fails only itself.
 */
@Data
public class ExternalBatchShipmentRequest {
    public static final int MAX_ITEMS = 50;

    @NotEmpty
    @Size(max = MAX_ITEMS)
    private List<ExternalShipmentRequest> shipments;
}
