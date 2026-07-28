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
}
