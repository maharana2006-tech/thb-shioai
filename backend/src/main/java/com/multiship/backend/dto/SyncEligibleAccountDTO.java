package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Lightweight projection of a {@link com.multiship.backend.model.CarrierAccountRef}
 * for the {@code /settings/shipping-catalog} sync menu. Carries only the
 * fields the picker needs — no client secret, no third-party billing, no
 * usage stats — so a compromise of this endpoint reveals nothing beyond
 * the account's carrier + env + display label.
 *
 * <p>{@code isPlatform} is derived server-side so the FE can group
 * accounts without re-implementing the customerNo-null convention.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SyncEligibleAccountDTO {
    private Long id;
    private String carrierCode;
    private String accountNumber;
    private String accountName;
    private String environment;
    /** True when customerNo is null/blank — a platform-scope account. */
    private Boolean isPlatform;
    /** The linked client's customerNo when this is a client account; null for platform. */
    private String customerNo;
}
