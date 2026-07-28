package com.multiship.backend.repository;

import com.multiship.backend.model.ExternalWebhookDelivery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ExternalWebhookDeliveryRepository
        extends JpaRepository<ExternalWebhookDelivery, Long> {

    List<ExternalWebhookDelivery> findTop50BySubscriptionIdOrderByAttemptedAtDesc(Long subscriptionId);
}
