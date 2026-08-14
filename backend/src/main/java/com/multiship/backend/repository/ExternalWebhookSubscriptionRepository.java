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
     * Sprint 51 BP-M1 — API-key-scoped variant. Pre-BP-M1 the dispatcher
     * pulled every active subscription for an event and filtered the
     * caller's api_key_id in Java; with N-of-1 subscriptions per event on
     * a shared platform, the SELECT scaled linearly with total tenants and
     * the filter discarded all-but-one. The composite index (event, active,
     * api_key_id) added in V13 turns this into an index-only scan.
     */
    List<ExternalWebhookSubscription> findByEventAndApiKeyIdAndActiveTrue(EventType event, Long apiKeyId);
}
