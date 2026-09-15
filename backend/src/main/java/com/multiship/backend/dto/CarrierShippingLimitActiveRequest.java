package com.multiship.backend.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Audit L4 #376 — request body for
 * {@code PATCH /admin/carrier-shipping-limits/{id}/active}.
 *
 * <p>Splitting the toggle out of the full-payload {@link CarrierShippingLimitRequest}
 * removes the concurrent-edit race: the previous FE flow read the row, flipped
 * {@code active}, and PUT every field back — so a second admin editing
 * {@code maxPackages} mid-toggle got their edit silently overwritten by the
 * toggler's stale copy. A dedicated PATCH sends only the flag, so nothing
 * else can be clobbered.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CarrierShippingLimitActiveRequest {

    @NotNull(message = "active is required")
    private Boolean active;
}
