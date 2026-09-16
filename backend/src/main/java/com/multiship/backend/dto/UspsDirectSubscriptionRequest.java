package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Write-shape for {@code POST /api/v1/admin/usps-direct/subscriptions}.
 * The service mints the HMAC secret server-side (32 random bytes,
 * base64) so the caller doesn't provide one — that's a POLA choice
 * (caller can't accidentally register the same subscription twice with
 * the same weak secret) plus a security choice (secret material never
 * flows over any inbound request body).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UspsDirectSubscriptionRequest {

    /** MID | TRACKING_NUMBERS | STID — validated against
     *  {@link com.multiship.backend.model.UspsDirectSubscription.FilterType}
     *  at controller boundary. */
    private String filterType;

    /** Scalar (MID / STID) or CSV list (tracking numbers). Non-blank. */
    private String filterValue;

    /** URL USPS POSTs push events to. HTTPS-only. */
    private String listenerURL;

    /** Optional CSV of USPS eventTypes[] — omit to subscribe to every
     *  event USPS ships for the filter. */
    private String eventTypes;

    /** {@code PRODUCTION} | {@code SANDBOX}. Defaults to {@code PRODUCTION}
     *  server-side when null / blank. */
    private String environment;
}
