package com.multiship.backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Wrapper request for the rate-shop endpoint. Bundles the shipment envelope
 * with two optional filters — a customer number so the service can prefer
 * the customer's own carrier credentials over the platform account, and an
 * explicit carriers list so callers can constrain the fan-out to a subset
 * (e.g. "just quote UPS and FedEx for this lane").
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RateShopRequestDTO {

    @NotNull
    @Valid
    private ShipmentRequestDTO shipment;

    /**
     * Customer number for credential resolution. When present, the service
     * looks up the customer's default carrier accounts first; when absent
     * it falls back to platform (house) accounts. Null on internal /
     * platform-side rate quotes.
     */
    private String customerNo;

    /**
     * Whitelist of carrier codes to fan out to. Null or empty = "all
     * configured carriers on this instance". Case-insensitive.
     */
    private List<String> carriers;
}
