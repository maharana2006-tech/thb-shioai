package com.multiship.backend.service;

import com.multiship.backend.config.WebhookProperties;
import com.multiship.backend.model.CarrierWebhookEvent;
import com.multiship.backend.repository.CarrierWebhookEventRepository;
import com.multiship.backend.repository.LabelPackageRepository;
import com.multiship.backend.repository.OrderTrackingRepository;
import com.multiship.backend.service.carriers.CarrierConnector;
import com.multiship.backend.service.carriers.CarrierConnector.TrackingWebhookEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.task.SyncTaskExecutor;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sprint 49 Tier 0 — signature-bypass fix regression guard.
 *
 * <p>Prior behavior: a blank {@code webhook.secrets.{carrier}} caused
 * the receiver to set {@code sigOk=true} and mutate order state.
 * Anyone POSTing to {@code /api/v1/webhooks/carrier/{c}} could rewrite
 * any shipment's status.
 *
 * <p>New behavior:
 * <ul>
 *   <li>secret present → verify (existing)</li>
 *   <li>secret blank AND {@code webhook.unsigned.{c}=true} → accept as
 *       unverified, audit row saved, order state NOT touched</li>
 *   <li>secret blank AND opt-in absent → REJECT with audit row marked
 *       {@code rejected=true}; controller returns 401</li>
 * </ul>
 */
class WebhookServiceImplTest {

    private CarrierService carrierService;
    private CarrierWebhookEventRepository eventRepo;
    private OrderTrackingRepository trackingRepo;
    private LabelPackageRepository labelPackageRepo;
    private WebhookProperties props;
    private CarrierConnector connector;
    private TrackingService trackingService;
    private ObjectProvider<TrackingService> trackingServiceProvider;
    private WebhookServiceImpl service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        carrierService = mock(CarrierService.class);
        eventRepo = mock(CarrierWebhookEventRepository.class);
        trackingRepo = mock(OrderTrackingRepository.class);
        labelPackageRepo = mock(LabelPackageRepository.class);
        props = new WebhookProperties();
        connector = mock(CarrierConnector.class);
        // Sprint 50 Tier 1 finding #12 — WebhookServiceImpl now invalidates
        // the tracking cache after a verified state mutation. Wired via an
        // ObjectProvider so tests can supply a mock TrackingService (or an
        // empty provider) without spinning up a real cache.
        trackingService = mock(TrackingService.class);
        trackingServiceProvider = mock(ObjectProvider.class);
        when(trackingServiceProvider.getIfAvailable()).thenReturn(trackingService);

        // Sprint 50 Tier 1 finding #9 — SyncTaskExecutor keeps the state-
        // mutation branch on the caller thread in tests, so verifyables
        // (trackingRepo.save, trackingService.invalidate) fire before the
        // assertion runs. Production wiring uses a bounded pool from
        // WebhookAsyncConfig.
        service = new WebhookServiceImpl(
                carrierService, eventRepo, trackingRepo, props, labelPackageRepo,
                trackingServiceProvider, new SyncTaskExecutor());

        when(carrierService.getCarrierConnector(anyString())).thenReturn(connector);
        when(eventRepo.save(any(CarrierWebhookEvent.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private TrackingWebhookEvent sampleEvent() {
        return new TrackingWebhookEvent(
                "1Z9999999999999999",
                "DL",
                "DELIVERED",
                LocalDateTime.now(),
                "Bengaluru, KA IN",
                true,
                "Delivered to recipient");
    }

    @Test
    void blankSecretWithoutOptInRejects() {
        // Attacker POSTs to /webhooks/carrier/ups with no secret configured
        // and no unsigned opt-in. Prior bug: this succeeded and rewrote state.
        // Now: audit row marked rejected, no order state touched.
        CarrierWebhookEvent result = service.receive("UPS", "raw-body", Map.of());

        assertTrue(Boolean.TRUE.equals(result.getRejected()),
                "rejected must be true so controller returns 401");
        assertFalse(Boolean.TRUE.equals(result.getVerified()));
        // Never even tried to parse the payload
        verify(connector, never()).parseWebhookEvent(anyString(), any());
        // Never touched OrderTracking
        verify(trackingRepo, never()).save(any());
    }

    @Test
    void blankSecretWithOptInAcceptsButDoesNotMutateState() {
        // Deployment that never had an HMAC secret (IP allowlist) opts in.
        props.getUnsigned().setUps(true);
        when(connector.parseWebhookEvent(anyString(), any())).thenReturn(sampleEvent());
        when(trackingRepo.findByTrackingNumberIgnoreCase(anyString())).thenReturn(Optional.empty());
        when(labelPackageRepo.findByTrackingNumber(anyString())).thenReturn(Optional.empty());

        CarrierWebhookEvent result = service.receive("UPS", "raw-body", Map.of());

        assertFalse(Boolean.TRUE.equals(result.getRejected()));
        assertFalse(Boolean.TRUE.equals(result.getVerified()),
                "opt-in unsigned is unverified — auditor sees false");
        // Parsed for audit
        verify(connector, times(1)).parseWebhookEvent(anyString(), any());
        // But order state stays untouched
        verify(trackingRepo, never()).save(any());
    }

    @Test
    void validSignatureMutatesOrderState() {
        props.getSecrets().setUps("shared-secret");
        when(connector.verifyWebhookSignature(anyString(), any(), anyString())).thenReturn(true);
        when(connector.parseWebhookEvent(anyString(), any())).thenReturn(sampleEvent());
        when(trackingRepo.findByTrackingNumberIgnoreCase(anyString()))
                .thenReturn(Optional.of(new com.multiship.backend.model.OrderTracking()));

        CarrierWebhookEvent result = service.receive("UPS", "raw-body", Map.of());

        assertFalse(Boolean.TRUE.equals(result.getRejected()));
        assertTrue(Boolean.TRUE.equals(result.getVerified()));
        verify(trackingRepo, times(1)).save(any());
    }

    /* -------- Sprint 50 Tier 1 finding #12 — cache invalidation -------- */

    @Test
    void verifiedEventInvalidatesTrackingCache() {
        // Prior bug: a "delivered" webhook updated the DB but the in-memory
        // tracking cache still served the stale "in transit" entry for up to
        // 24h. Fix: WebhookServiceImpl calls TrackingService.invalidate() on
        // the event's tracking number after mutation, so the next
        // getLiveTracking re-fetches from the carrier.
        props.getSecrets().setUps("shared-secret");
        when(connector.verifyWebhookSignature(anyString(), any(), anyString())).thenReturn(true);
        TrackingWebhookEvent evt = sampleEvent();
        when(connector.parseWebhookEvent(anyString(), any())).thenReturn(evt);
        when(trackingRepo.findByTrackingNumberIgnoreCase(anyString()))
                .thenReturn(Optional.of(new com.multiship.backend.model.OrderTracking()));

        service.receive("UPS", "raw-body", Map.of());

        verify(trackingService, times(1)).invalidate(evt.trackingNumber());
    }

    @Test
    void unsignedOptInDoesNotInvalidateCache() {
        // Unsigned opt-in is audit-only — no state change, no cache eviction.
        // Guarantees a hostile actor with no HMAC can't blast the cache empty.
        props.getUnsigned().setUps(true);
        when(connector.parseWebhookEvent(anyString(), any())).thenReturn(sampleEvent());

        service.receive("UPS", "raw-body", Map.of());

        verify(trackingService, never()).invalidate(anyString());
    }

    @Test
    void secretPresentButSignatureFailsAuditsWithoutMutation() {
        props.getSecrets().setUps("shared-secret");
        when(connector.verifyWebhookSignature(anyString(), any(), anyString())).thenReturn(false);
        when(connector.parseWebhookEvent(anyString(), any())).thenReturn(sampleEvent());

        CarrierWebhookEvent result = service.receive("UPS", "raw-body", Map.of());

        assertFalse(Boolean.TRUE.equals(result.getRejected()),
                "signature-fail is auditable, not rejected — carrier retry semantics preserved");
        assertFalse(Boolean.TRUE.equals(result.getVerified()));
        verify(trackingRepo, never()).save(any());
    }

    @Test
    void unknownCarrierPersistsUnverifiedAudit() {
        when(carrierService.getCarrierConnector(anyString()))
                .thenThrow(new IllegalArgumentException("unknown carrier"));

        CarrierWebhookEvent result = service.receive("BOGUS", "raw-body", Map.of());

        // Existing behavior — audit-only, not rejected (unknown-carrier is
        // separate from the signature-bypass concern).
        assertFalse(Boolean.TRUE.equals(result.getVerified()));
        verify(trackingRepo, never()).save(any());
    }

    @Test
    void carrierCodeIsCaseInsensitiveForUnsignedOptIn() {
        // The switch normalizes to upper — confirm lower-case carrier still hits the flag.
        props.getUnsigned().setFedex(true);
        when(connector.parseWebhookEvent(anyString(), any())).thenReturn(sampleEvent());

        CarrierWebhookEvent result = service.receive("fedex", "raw-body", Map.of());

        assertFalse(Boolean.TRUE.equals(result.getRejected()));
        assertEquals("FEDEX", result.getCarrierCode());
    }

    /* -------- Sprint 49 Tier 1 — replay dedup -------- */

    @Test
    void duplicateVerifiedEventInWindowIsDedupedNoStateChange() {
        props.getSecrets().setUps("shared-secret");
        when(connector.verifyWebhookSignature(anyString(), any(), anyString())).thenReturn(true);
        TrackingWebhookEvent evt = sampleEvent();
        when(connector.parseWebhookEvent(anyString(), any())).thenReturn(evt);

        // Repo returns a prior verified event → dedup gate fires
        CarrierWebhookEvent prior = new CarrierWebhookEvent();
        prior.setId(999L);
        prior.setVerified(true);
        prior.setEventHash("dummy");
        when(eventRepo.findFirstByEventHashAndVerifiedTrueAndReceivedAtAfterOrderByReceivedAtDesc(
                anyString(), any())).thenReturn(Optional.of(prior));

        CarrierWebhookEvent result = service.receive("UPS", "raw-body", Map.of());

        assertTrue(Boolean.TRUE.equals(result.getDuplicate()),
                "second delivery must be flagged duplicate");
        verify(trackingRepo, never()).save(any());
    }

    @Test
    void firstVerifiedEventPersistsAndMutatesState() {
        props.getSecrets().setUps("shared-secret");
        when(connector.verifyWebhookSignature(anyString(), any(), anyString())).thenReturn(true);
        when(connector.parseWebhookEvent(anyString(), any())).thenReturn(sampleEvent());
        when(eventRepo.findFirstByEventHashAndVerifiedTrueAndReceivedAtAfterOrderByReceivedAtDesc(
                anyString(), any())).thenReturn(Optional.empty());
        when(trackingRepo.findByTrackingNumberIgnoreCase(anyString()))
                .thenReturn(Optional.of(new com.multiship.backend.model.OrderTracking()));

        CarrierWebhookEvent result = service.receive("UPS", "raw-body", Map.of());

        assertFalse(Boolean.TRUE.equals(result.getDuplicate()));
        assertTrue(Boolean.TRUE.equals(result.getVerified()));
        verify(trackingRepo, times(1)).save(any());
    }

    @Test
    void eventHashIsStableAcrossDuplicateDeliveries() {
        // Two calls with the same parsed event → identical hash → dedup lookup uses same key.
        LocalDateTime fixed = LocalDateTime.of(2026, 8, 9, 10, 0);
        TrackingWebhookEvent evt = new TrackingWebhookEvent(
                "1Z9999", "DL", "DELIVERED", fixed, "Loc", true, "Delivered");
        String h1 = WebhookServiceImpl.computeEventHash("UPS", evt, "body-a");
        String h2 = WebhookServiceImpl.computeEventHash("UPS", evt, "body-b");
        assertEquals(h1, h2, "hash must depend only on parsed fields, not raw body");
        assertEquals(64, h1.length(), "SHA-256 hex should be 64 chars");
    }

    @Test
    void eventHashFallsBackToPayloadHashWhenTrackingMissing() {
        // Malformed event with null tracking → fall back to raw payload hash.
        TrackingWebhookEvent evt = new TrackingWebhookEvent(
                null, "DL", "DELIVERED", LocalDateTime.now(), "Loc", true, "Delivered");
        String h = WebhookServiceImpl.computeEventHash("UPS", evt, "raw-body-content");
        assertEquals(64, h.length());
        // Different payload → different hash even though event fields match
        String h2 = WebhookServiceImpl.computeEventHash("UPS", evt, "different-body");
        assertNotEquals(h, h2);
    }

    @Test
    void eventHashDiffersByCarrier() {
        // Same event fields but delivered by two different carriers must not collide.
        LocalDateTime fixed = LocalDateTime.of(2026, 8, 9, 10, 0);
        TrackingWebhookEvent evt = new TrackingWebhookEvent(
                "1Z9999", "DL", "DELIVERED", fixed, "Loc", true, "Delivered");
        assertNotEquals(
                WebhookServiceImpl.computeEventHash("UPS", evt, ""),
                WebhookServiceImpl.computeEventHash("FEDEX", evt, ""));
    }
}
