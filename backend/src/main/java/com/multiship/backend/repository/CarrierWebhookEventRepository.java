package com.multiship.backend.repository;

import com.multiship.backend.model.CarrierWebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CarrierWebhookEventRepository extends JpaRepository<CarrierWebhookEvent, Long> {

    List<CarrierWebhookEvent> findByTrackingNumberOrderByReceivedAtDesc(String trackingNumber);
}
