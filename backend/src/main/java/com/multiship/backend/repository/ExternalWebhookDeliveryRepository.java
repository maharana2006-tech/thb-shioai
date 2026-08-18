package com.multiship.backend.repository;

import com.multiship.backend.model.ExternalWebhookDelivery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ExternalWebhookDeliveryRepository
        extends JpaRepository<ExternalWebhookDelivery, Long> {

    List<ExternalWebhookDelivery> findTop50BySubscriptionIdOrderByAttemptedAtDesc(Long subscriptionId);

    /**
     * Audit R2 #335 — nightly retention: null the heavy {@code payload_json}
     * blob for rows attempted before {@code cutoff}. Returns count of
     * rows updated so the scheduler can log the reclaim. Metadata row
     * stays for correlation with the subscription.
     */
    @Modifying
    @Query("UPDATE ExternalWebhookDelivery d SET d.payloadJson = NULL "
            + "WHERE d.attemptedAt < :cutoff AND d.payloadJson IS NOT NULL")
    int nullifyPayloadOlderThan(@Param("cutoff") LocalDateTime cutoff);

    /**
     * Audit R2 #335 — nightly retention: hard-delete rows attempted
     * before {@code cutoff}. Returns count of rows deleted.
     */
    @Modifying
    @Query("DELETE FROM ExternalWebhookDelivery d WHERE d.attemptedAt < :cutoff")
    int deleteRowsOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
