package com.multiship.backend.service;

import com.multiship.backend.model.CarrierWebhookEvent;

import java.util.Map;

/**
 * Sprint 36 — receive push-tracking webhooks from any carrier. Verifies
 * the signature, parses the payload into a normalised event, and
 * persists the audit row.
 */
public interface WebhookService {

    /**
     * Handle a webhook POST. Never throws — malformed payloads and bad
     * signatures are persisted as unverified audit rows and returned
     * with {@code verified=false}. Verified rows also update the
     * corresponding OrderTracking and bust the tracking cache.
     */
    CarrierWebhookEvent receive(String carrierCode, String rawPayload, Map<String, String> headers);
}
