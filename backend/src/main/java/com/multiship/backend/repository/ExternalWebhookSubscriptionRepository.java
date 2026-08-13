package com.multiship.backend.repository;

import com.multiship.backend.model.ExternalWebhookSubscription;
import com.multiship.backend.model.ExternalWebhookSubscription.EventType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ExternalWebhookSubscriptionRepository
        extends JpaRepository<ExternalWebhookSubscription, Long> {

    List<ExternalWebhookSubscription> findByApiKeyIdOrderByEventAsc(Long apiKeyId);

    /** All active subscriptions for an event — the dispatcher iterates. */
    List<ExternalWebhookSubscription> findByEventAndActiveTrue(EventType event);

    /**
     * Sprint 51 M-Perf (BP-M1) — filtered variant used when
     * {@code apiKeyIdFilter} is non-null. Pre-M-Perf the dispatcher
     * called {@link #findByEventAndActiveTrue} and Java-side-filtered
     * by apiKeyId, allocating the full active-subscription list every
     * time. At 100 events/min/tenant × N tenants with a growing
     * subscription table this was allocating tens of thousands of
     * short-lived instances per second. Pushing the filter into the
     * DB WHERE clause narrows the result set at the source.
     */
    List<ExternalWebhookSubscription> findByEventAndApiKeyIdAndActiveTrue(EventType event, Long apiKeyId);
}
