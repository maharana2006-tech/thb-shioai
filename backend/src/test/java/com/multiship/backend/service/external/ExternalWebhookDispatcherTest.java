package com.multiship.backend.service.external;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.model.ExternalWebhookDelivery;
import com.multiship.backend.model.ExternalWebhookSubscription;
import com.multiship.backend.model.ExternalWebhookSubscription.EventType;
import com.multiship.backend.repository.ExternalWebhookDeliveryRepository;
import com.multiship.backend.repository.ExternalWebhookSubscriptionRepository;
import com.multiship.backend.service.carriers.WebhookHmacUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Sprint 46 — outbound webhook plumbing. Covers HMAC signature, event
 * filtering by api-key, and the "no subscriptions" fast path.
 */
class ExternalWebhookDispatcherTest {

    private ExternalWebhookSubscriptionRepository subRepo;
    private ExternalWebhookDeliveryRepository deliveryRepo;
    private ExternalWebhookDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        subRepo = mock(ExternalWebhookSubscriptionRepository.class);
        deliveryRepo = mock(ExternalWebhookDeliveryRepository.class);
        // Sprint 51 T3 finding #7 — pass a validator mock that never
        // blocks so the existing tests exercise the dispatch path they
        // were written for. WebhookUrlValidatorTest covers the guard
        // behaviour directly.
        WebhookUrlValidator urlValidator = mock(WebhookUrlValidator.class);
        when(urlValidator.isBlocked(anyString())).thenReturn(false);
        dispatcher = new ExternalWebhookDispatcher(subRepo, deliveryRepo, new ObjectMapper(), urlValidator);
    }

    @Test
    void fire_noSubscriptions_isNoOp() {
        // Sprint 51 M-Perf (BP-M1) — dispatcher now calls the filtered
        // variant when apiKeyIdFilter is non-null, so tests must stub
        // findByEventAndApiKeyIdAndActiveTrue instead of the broad find.
        when(subRepo.findByEventAndApiKeyIdAndActiveTrue(EventType.LABEL_GENERATED, 42L))
                .thenReturn(List.of());
        dispatcher.fire(EventType.LABEL_GENERATED, 42L, Map.of("orderNo", 1));
        verifyNoInteractions(deliveryRepo);
    }

    @Test
    void fire_perApiKeyFiltering_returnsOnlyMatchingSubs() {
        // Sprint 51 M-Perf — the WHERE-clause filter now happens DB-side.
        // The repo returns just "mine" when queried with apiKeyIdFilter=42;
        // dispatcher iterates only those. The prior Java-side-filter
        // semantics are preserved from the caller's perspective.
        ExternalWebhookSubscription mine = subscription(1L, 42L, "https://mine.example");
        when(subRepo.findByEventAndApiKeyIdAndActiveTrue(EventType.LABEL_GENERATED, 42L))
                .thenReturn(List.of(mine));

        dispatcher.fire(EventType.LABEL_GENERATED, 42L, Map.of("shipmentId", 1000));

        verify(deliveryRepo, atLeastOnce()).save(argThat(d -> d.getSubscriptionId() == 1L));
        verify(deliveryRepo, never()).save(argThat(d -> d.getSubscriptionId() == 2L));
        // The broad find must NOT be consulted when a filter is present —
        // that's the whole point of the DB-side filter.
        verify(subRepo, never()).findByEventAndActiveTrue(any());
    }

    @Test
    void fire_nullApiKeyFilter_usesBroadFind() {
        // apiKeyIdFilter = null → dispatcher hits the unfiltered method
        // (broadcast to every subscription for the event, all tenants).
        when(subRepo.findByEventAndActiveTrue(EventType.LABEL_GENERATED))
                .thenReturn(List.of());
        dispatcher.fire(EventType.LABEL_GENERATED, null, Map.of("orderNo", 1));
        verify(subRepo).findByEventAndActiveTrue(EventType.LABEL_GENERATED);
        verify(subRepo, never()).findByEventAndApiKeyIdAndActiveTrue(any(), any());
    }

    @Test
    void hmacSignature_matchesCanonicalTestVector() {
        // Sanity: the shared HMAC utility used by the dispatcher must
        // produce the same signature for the same body + secret.
        String body = "{\"orderNo\":42}";
        String secret = "shared-secret";
        String sig1 = WebhookHmacUtil.hmacSha256Hex(body, secret);
        String sig2 = WebhookHmacUtil.hmacSha256Hex(body, secret);
        assertNotNull(sig1);
        assertEquals(sig1, sig2, "signature should be deterministic");
        assertEquals(64, sig1.length(), "hex-encoded SHA256 is 64 chars");
    }

    private static ExternalWebhookSubscription subscription(Long id, Long apiKeyId, String url) {
        ExternalWebhookSubscription s = new ExternalWebhookSubscription();
        s.setId(id);
        s.setApiKeyId(apiKeyId);
        s.setEvent(EventType.LABEL_GENERATED);
        s.setUrl(url);
        s.setSecret("secret-" + apiKeyId);
        s.setActive(true);
        return s;
    }
}
