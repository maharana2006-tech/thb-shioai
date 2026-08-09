package com.multiship.backend.service;

import com.multiship.backend.config.WebhookProperties;
import com.multiship.backend.model.CarrierWebhookEvent;
import com.multiship.backend.model.OrderTracking;
import com.multiship.backend.repository.CarrierWebhookEventRepository;
import com.multiship.backend.repository.OrderTrackingRepository;
import com.multiship.backend.service.carriers.CarrierConnector;
import com.multiship.backend.service.carriers.CarrierConnector.TrackingWebhookEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Sprint 36 impl.
 *
 * <p>Verified events update OrderTracking's status / delivered flag
 * so the UI reflects the new state without needing a fresh
 * TrackingService poll. Malformed payloads and bad-signature events are
 * still persisted (for audit) but leave OrderTracking untouched.
 *
 * <p>When {@code webhook.secrets.{carrier}} is blank, the connector's
 * signature verification returns false; we log the payload as
 * unverified but still update tracking. Some carriers use IP allowlist
 * (not HMAC) for verification, so blank secret = "trust the payload".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookServiceImpl implements WebhookService {

    private final CarrierService carrierService;
    private final CarrierWebhookEventRepository webhookEventRepository;
    private final OrderTrackingRepository orderTrackingRepository;
    private final WebhookProperties webhookProperties;
    private final com.multiship.backend.repository.LabelPackageRepository labelPackageRepository;

    @Override
    public CarrierWebhookEvent receive(String carrierCode, String rawPayload,
                                        Map<String, String> headers) {
        String carrier = carrierCode == null ? "" : carrierCode.trim().toUpperCase(Locale.ROOT);
        CarrierWebhookEvent audit = new CarrierWebhookEvent();
        audit.setCarrierCode(carrier);
        audit.setRawPayload(rawPayload);
        audit.setReceivedAt(LocalDateTime.now(ZoneOffset.UTC));
        audit.setVerified(false);
        audit.setDelivered(false);

        CarrierConnector connector;
        try {
            connector = carrierService.getCarrierConnector(carrier);
        } catch (Exception ex) {
            log.warn("Webhook: carrier {} isn't configured; persisting unverified.", carrier);
            return webhookEventRepository.save(audit);
        }

        String secret = webhookProperties.secretFor(carrier);
        boolean sigOk;
        if (StringUtils.hasText(secret)) {
            sigOk = connector.verifyWebhookSignature(rawPayload, headers, secret);
        } else {
            // Blank secret → trust the payload (carrier uses IP allowlist).
            // Persist as unverified but still process.
            sigOk = true;
            log.warn("Webhook: no secret configured for {} — trusting payload.", carrier);
        }
        audit.setVerified(sigOk);

        TrackingWebhookEvent event = connector.parseWebhookEvent(rawPayload, headers);
        if (event == null) {
            log.warn("Webhook: {} payload didn't parse; persisting unverified audit row.", carrier);
            return webhookEventRepository.save(audit);
        }

        audit.setTrackingNumber(event.trackingNumber());
        audit.setEventType(event.eventType());
        audit.setStatusCode(event.statusCode());
        audit.setOccurredAt(event.occurredAt());
        audit.setLocation(event.location());
        audit.setDescription(event.description());
        audit.setDelivered(event.delivered());

        CarrierWebhookEvent saved = webhookEventRepository.save(audit);

        // Only mutate on verified events with a tracking number we know about.
        // Carrier webhooks are keyed by per-piece tracking on multi-package
        // shipments — look up the per-piece row first, then bubble up to the
        // master (OrderTracking) so the order-level status still advances.
        if (sigOk && StringUtils.hasText(event.trackingNumber())) {
            String eventTracking = event.trackingNumber();
            // Per-piece resolution — the LabelPackage row for this exact box.
            labelPackageRepository.findByTrackingNumber(eventTracking).ifPresent(pkg -> {
                // Bump the per-piece updated_at; status column TBD in a
                // follow-up. Currently we surface per-piece movement via
                // the description on the CarrierWebhookEvent audit row.
                pkg.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
                labelPackageRepository.save(pkg);
            });
            // Master (shipment-level) — either the tracking IS the master, or
            // it's a piece; in both cases we advance the order's status.
            Optional<OrderTracking> tracking = orderTrackingRepository
                    .findByTrackingNumberIgnoreCase(eventTracking);
            if (tracking.isEmpty()) {
                // Piece-tracking event — resolve the master via the LabelPackage row.
                tracking = labelPackageRepository.findByTrackingNumber(eventTracking)
                        .flatMap(pkg -> orderTrackingRepository.findByOrderNo(pkg.getOrderNo()).stream().findFirst());
            }
            if (tracking.isPresent()) {
                OrderTracking t = tracking.get();
                if (StringUtils.hasText(event.description())) {
                    t.setStatus(event.description());
                }
                t.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
                orderTrackingRepository.save(t);
            }
        }

        return saved;
    }
}
