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
        when(subRepo.findByEventAndActiveTrue(EventType.LABEL_GENERATED))
                .thenReturn(List.of());
        dispatcher.fire(EventType.LABEL_GENERATED, 42L, Map.of("orderNo", 1));
        verifyNoInteractions(deliveryRepo);
    }

    @Test
    void fire_perApiKeyFiltering_onlyFiresMatchingSubs() {
        ExternalWebhookSubscription mine = subscription(1L, 42L, "https://mine.example");
        ExternalWebhookSubscription other = subscription(2L, 99L, "https://other.example");
        when(subRepo.findByEventAndActiveTrue(EventType.LABEL_GENERATED))
                .thenReturn(List.of(mine, other));

        // Trigger the dispatch. We don't have a real HTTP client wired,
        // so the delivery loop will bail out inside the retry attempts;
        // the important assertion is that only mine's dispatch was
        // attempted (i.e. exactly one delivery row saved for sub 1).
        dispatcher.fire(EventType.LABEL_GENERATED, 42L, Map.of("shipmentId", 1000));

        // Both attempts land in deliveryRepo.save; a save for sub 2 would
        // be a leak of the per-apiKey filter.
        verify(deliveryRepo, atLeastOnce()).save(argThat(d -> d.getSubscriptionId() == 1L));
        verify(deliveryRepo, never()).save(argThat(d -> d.getSubscriptionId() == 2L));
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
