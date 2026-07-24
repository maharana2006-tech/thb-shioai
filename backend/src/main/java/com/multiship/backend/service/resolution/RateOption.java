package com.multiship.backend.service.resolution;

import java.math.BigDecimal;

/**
 * One service-priced option in a rate-shop result set. Fed to
 * {@link ShipmentResolutionService#pickService} which returns the
 * option that matches the client's rate strategy (CHEAPEST | FASTEST |
 * FIXED).
 *
 * <p>{@code estimatedDeliveryDays} is nullable because carriers don't
 * always return an ETA; FASTEST falls back to CHEAPEST for options that
 * lack one so the picker never returns nothing when candidates exist.
 *
 * @param serviceId              platform service id (matches
 *                               {@code shipping_service.id}); nullable
 *                               when the option represents an unmapped
 *                               carrier response.
 * @param carrier                canonical carrier code (UPS | FEDEX | USPS).
 * @param serviceCode            carrier's own code (e.g. "03", "FEDEX_GROUND").
 * @param price                  carrier rate BEFORE markup.
 * @param currency               ISO-4217; used to validate markup currency.
 * @param estimatedDeliveryDays  business-day ETA when available.
 */
public record RateOption(
        Long serviceId,
        String carrier,
        String serviceCode,
        BigDecimal price,
        String currency,
        Integer estimatedDeliveryDays
) {
}
