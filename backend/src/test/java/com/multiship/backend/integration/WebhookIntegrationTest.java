package com.multiship.backend.integration;

import com.multiship.backend.config.WebhookProperties;
import com.multiship.backend.model.CarrierWebhookEvent;
import com.multiship.backend.repository.CarrierWebhookEventRepository;
import com.multiship.backend.service.WebhookService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Sprint 49 Tier 5 Fix 2 — end-to-end guard on the Tier 0 signature
 * fix + Tier 1 replay dedup.
 *
 * <p>Runs through the full service + real Postgres. Every case seeds
 * carrier config via {@link WebhookProperties} at runtime and
 * verifies the {@link CarrierWebhookEventRepository} state that
 * results.
 *
 * <p>Guarded by {@code INTEGRATION_TESTS=1} via
 * {@link AbstractIntegrationTest}.
 */
class WebhookIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private WebhookService webhookService;

    @Autowired
    private CarrierWebhookEventRepository eventRepository;

    @Autowired
    private WebhookProperties webhookProperties;

    @BeforeEach
    @Transactional
    void reset() {
        eventRepository.deleteAll();
        webhookProperties.getSecrets().setUps("");
        webhookProperties.getUnsigned().setUps(false);
    }

    /* -------- Tier 0: signature-bypass fix -------- */

    @Test
    void blankSecretWithoutOptInIsRejectedAndAudited() {
        // No secret configured for UPS + unsigned opt-in NOT set →
        // the receiver marks the row rejected=true; no state mutation.
        CarrierWebhookEvent audit = webhookService.receive("UPS", "raw-body", Map.of());

        assertTrue(Boolean.TRUE.equals(audit.getRejected()),
                "blank-secret + no opt-in must produce rejected=true");
        assertFalse(Boolean.TRUE.equals(audit.getVerified()));

        // Persisted for the audit trail.
        assertEquals(1, eventRepository.count());
    }

    @Test
    void blankSecretWithOptInIsAcceptedAudited() {
        // Deployment that uses IP allowlist rather than HMAC opts in
        // explicitly. Payload is accepted + persisted but never marked
        // verified — order state stays untouched.
        webhookProperties.getUnsigned().setUps(true);

        CarrierWebhookEvent audit = webhookService.receive("UPS", "unsigned-body", Map.of());

        assertFalse(Boolean.TRUE.equals(audit.getRejected()));
        assertFalse(Boolean.TRUE.equals(audit.getVerified()));
    }

    /* -------- Tier 1: replay dedup -------- */

    @Test
    void unknownCarrierIsAuditedWithoutRejecting() {
        // BOGUS carrier — connector lookup throws inside the service and
        // we persist an unverified audit row (existing behavior).
        CarrierWebhookEvent audit = webhookService.receive("BOGUS_CARRIER", "body", Map.of());

        assertFalse(Boolean.TRUE.equals(audit.getVerified()));
        // Not marked rejected — that's specifically the blank-secret path.
        assertFalse(Boolean.TRUE.equals(audit.getRejected()));
    }

    // Full valid-signature + duplicate tests would need a signed
    // payload the connector's verifyWebhookSignature accepts. That's
    // per-carrier HMAC and belongs in a dedicated test that mounts a
    // carrier-specific fixture — deferred; the unit-level tests in
    // WebhookServiceImplTest already cover those branches with mocks.
}
