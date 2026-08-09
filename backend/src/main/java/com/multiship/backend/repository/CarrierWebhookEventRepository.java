package com.multiship.backend.repository;

import com.multiship.backend.model.CarrierWebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface CarrierWebhookEventRepository extends JpaRepository<CarrierWebhookEvent, Long> {

    List<CarrierWebhookEvent> findByTrackingNumberOrderByReceivedAtDesc(String trackingNumber);

    /**
     * Sprint 49 Tier 1 — replay-dedup lookup. Returns the most recent
     * verified row for this hash within the given window, so double
     * deliveries of the same event no-op.
     */
    Optional<CarrierWebhookEvent> findFirstByEventHashAndVerifiedTrueAndReceivedAtAfterOrderByReceivedAtDesc(
            String eventHash, LocalDateTime after);
}
