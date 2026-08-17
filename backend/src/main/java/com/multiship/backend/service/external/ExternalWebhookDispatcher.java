package com.multiship.backend.service.external;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.multiship.backend.model.ExternalWebhookDelivery;
import com.multiship.backend.model.ExternalWebhookDelivery.Status;
import com.multiship.backend.model.ExternalWebhookSubscription;
import com.multiship.backend.model.ExternalWebhookSubscription.EventType;
import com.multiship.backend.repository.ExternalWebhookDeliveryRepository;
import com.multiship.backend.repository.ExternalWebhookSubscriptionRepository;
import com.multiship.backend.service.carriers.HttpClients;
import com.multiship.backend.service.carriers.WebhookHmacUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Sprint 46 — outbound webhook dispatcher. Callers on the platform hot
 * path (label generation, tracking update, exception, rule block)
 * invoke {@link #fire(EventType, Long, Map)} to broadcast to every
 * subscription for that event scoped to the caller's client.
 *
 * <p>Delivery is fire-and-forget over a bounded thread pool via
 * {@code @Async}; the platform never blocks on a partner's webhook
 * receiver. Failed deliveries retry with exponential backoff up to
 * 3 attempts; the full attempt log is persisted in
 * {@link ExternalWebhookDelivery} for audit.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExternalWebhookDispatcher {

    private static final int MAX_ATTEMPTS = 3;
    private static final long[] BACKOFFS_MS = { 0L, 2_000L, 10_000L };

    private final ExternalWebhookSubscriptionRepository subscriptionRepo;
    private final ExternalWebhookDeliveryRepository deliveryRepo;
    private final ObjectMapper objectMapper;
    /** Sprint 51 T3 finding #7 — belt-and-braces SSRF guard. Save-time
     *  validation is authoritative, but stored rows that predate this
     *  validator (or slipped in via a bypass) still get dropped here
     *  before we POST to them. */
    private final WebhookUrlValidator urlValidator;

    /** Package-private RestClient injection point for tests. */
    @Autowired(required = false)
    private RestClient testRestClient;

    /**
     * Sprint 51 T4 finding #8 — shared {@link RestClient} with connect
     * + read timeouts. Pre-T4 the dispatcher called
     * {@code RestClient.create()} per delivery, which used the JDK
     * default (unbounded read timeout). One stuck partner receiver
     * pinned an {@code @Async} pool thread for the OS TCP default
     * (minutes to indefinite); at 100 events/min × N tenants the
     * pool drains + CallerRunsPolicy cascades into the caller's
     * label-generation thread.
     *
     * <p>Reuses {@link HttpClients#newBuilder()} so the webhook client
     * inherits the same 5s/30s policy already applied to every carrier
     * call, keeping one knob to tune.
     */
    private final RestClient sharedRestClient = HttpClients.newBuilder().build();

    /**
     * Sprint 51 BP-M1 — 60s cache on the subscription lookup. Every label
     * generation on the hot path fires a LABEL_GENERATED event; each event
     * previously hit the DB with {@code findByEventAndActiveTrue} — a full
     * table scan on a table that grows with subscription count × tenant
     * count. Cache keyed on (event, apiKeyId) folds duplicate lookups
     * inside the 60s window into one query. Subscription add / remove is
     * infrequent (admin action, rare) so the 60s eviction window is well
     * inside the operator's tolerance for propagation.
     */
    private static final Duration SUBSCRIPTION_CACHE_TTL = Duration.ofSeconds(60);
    private static final int SUBSCRIPTION_CACHE_MAX_SIZE = 10_000;
    private final Cache<SubscriptionCacheKey, List<ExternalWebhookSubscription>> subscriptionCache =
            Caffeine.newBuilder()
                    .maximumSize(SUBSCRIPTION_CACHE_MAX_SIZE)
                    .expireAfterWrite(SUBSCRIPTION_CACHE_TTL)
                    .build();

    private record SubscriptionCacheKey(EventType event, Long apiKeyId) {}

    /**
     * Audit W2 — public hook so controllers can invalidate the 60s
     * subscription cache the moment a sub is added / updated / deleted.
     * Without this, admin adds a sub and the dispatcher can miss up to
     * 60s of matching events (the cache was populated pre-add). Full
     * invalidation is cheap: at most a few thousand entries and only
     * ever touched off the hot path.
     */
    public void invalidateSubscriptionCache() {
        subscriptionCache.invalidateAll();
    }

    /**
     * Broadcast an event to every active subscription. When
     * {@code apiKeyIdFilter} is non-null, only subscriptions owned by
     * that API key fire — typically the caller who just generated a
     * label. When null, every active subscription for the event fires.
     *
     * <p>Sprint 51 BP-M1 — the api-key-scoped path uses the composite
     * (event, active, api_key_id) index via
     * {@link ExternalWebhookSubscriptionRepository#findByEventAndApiKeyIdAndActiveTrue}
     * instead of full-scanning the table and filtering in-JVM. The unscoped
     * fan-out (apiKeyIdFilter=null) still falls through to the platform-
     * wide query — those broadcasts are already rare (ops-triggered) so
     * they don't warrant the same optimisation.
     */
    @Async
    public void fire(EventType event, Long apiKeyIdFilter, Map<String, Object> payload) {
        try {
            String body = objectMapper.writeValueAsString(payload);
            List<ExternalWebhookSubscription> subs = subscriptionCache.get(
                    new SubscriptionCacheKey(event, apiKeyIdFilter),
                    k -> k.apiKeyId() == null
                            ? subscriptionRepo.findByEventAndActiveTrue(k.event())
                            : subscriptionRepo.findByEventAndApiKeyIdAndActiveTrue(k.event(), k.apiKeyId()));
            for (ExternalWebhookSubscription sub : subs) {
                deliverOne(sub, event, body);
            }
        } catch (Exception ex) {
            log.warn("Webhook dispatch for event {} failed: {}", event, ex.getMessage());
        }
    }

    private void deliverOne(ExternalWebhookSubscription sub, EventType event, String body) {
        // Sprint 51 T3 finding #7 — refuse to POST to a URL that the
        // validator flags as unsafe (private IP, cloud metadata, non-https
        // when strict). Persist a FAILED delivery so ops can see the
        // rejection instead of a silent skip.
        if (urlValidator.isBlocked(sub.getUrl())) {
            ExternalWebhookDelivery blocked = new ExternalWebhookDelivery();
            blocked.setSubscriptionId(sub.getId());
            blocked.setEvent(event.name());
            blocked.setPayloadJson(body);
            blocked.setAttemptedAt(LocalDateTime.now());
            blocked.setAttempts(0);
            blocked.setStatus(Status.FAILED);
            blocked.setErrorMessage("URL blocked by SSRF guard.");
            deliveryRepo.save(blocked);
            return;
        }

        ExternalWebhookDelivery delivery = new ExternalWebhookDelivery();
        delivery.setSubscriptionId(sub.getId());
        delivery.setEvent(event.name());
        delivery.setPayloadJson(body);
        delivery.setAttemptedAt(LocalDateTime.now());

        String signature = WebhookHmacUtil.hmacSha256Hex(body, sub.getSecret());
        RestClient client = testRestClient != null ? testRestClient : sharedRestClient;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            delivery.setAttempts(attempt);
            try {
                if (attempt > 1) {
                    try { Thread.sleep(BACKOFFS_MS[Math.min(attempt - 1, BACKOFFS_MS.length - 1)]); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                }
                var response = client.post()
                        .uri(sub.getUrl())
                        .header("Content-Type", "application/json")
                        .header("X-Multiship-Event", event.name())
                        .header("X-Multiship-Signature", signature == null ? "" : signature)
                        .body(body)
                        .retrieve()
                        .toBodilessEntity();
                delivery.setResponseStatus(response.getStatusCode().value());
                if (response.getStatusCode().is2xxSuccessful()) {
                    delivery.setStatus(Status.DELIVERED);
                    deliveryRepo.save(delivery);
                    return;
                }
                delivery.setErrorMessage("HTTP " + response.getStatusCode().value());
            } catch (Exception e) {
                delivery.setErrorMessage(e.getMessage());
                delivery.setResponseStatus(null);
            }
        }
        delivery.setStatus(Status.FAILED);
        deliveryRepo.save(delivery);
        log.warn("Webhook delivery failed after {} attempts to {}: {}",
                MAX_ATTEMPTS, sub.getUrl(), delivery.getErrorMessage());
    }
}
