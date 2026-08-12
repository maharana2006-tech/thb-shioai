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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
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
 * <p>Sprint 49 Tier 0 — signature bypass fixed. A blank
 * {@code webhook.secrets.{carrier}} no longer implies "trust". The
 * per-carrier {@code webhook.unsigned.{carrier}=true} opt-in is now
 * required to accept unsigned webhooks (for the IP-allowlist deployments
 * that never had an HMAC secret to begin with). Without the opt-in,
 * unsigned webhooks are marked {@code rejected=true} and the controller
 * returns 401 to the caller.
 *
 * <p>Sprint 49 Tier 1 — replay dedup. Verified events compute a
 * {@code eventHash} (SHA-256 of carrier + tracking + type + status +
 * occurredAt, falling back to hash of rawPayload). If a matching
 * verified row exists within the last 24h, the new event is persisted
 * as {@code duplicate=true} and order state is NOT mutated.
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
    /**
     * Sprint 50 Tier 1 finding #12 — evict the tracking cache after a
     * verified state mutation so the UI stops serving the stale "in transit"
     * entry. Optional so tests that spin up a partial WebhookServiceImpl
     * without a tracking cache still work.
     */
    private final org.springframework.beans.factory.ObjectProvider<TrackingService> trackingServiceProvider;
    /**
     * Sprint 50 Tier 1 finding #9 — bounded executor for the state-mutation
     * branch of receive(). Sync path (parse + verify + persist audit) stays
     * on the Tomcat thread so the controller can respond immediately; the
     * heavy tail (order lookup + save + cache invalidation) runs here so
     * high-rate carrier bursts don't starve Tomcat's thread pool.
     */
    private final org.springframework.core.task.TaskExecutor webhookExecutor;

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
        boolean mutateState;  // only true on verified events; unsigned-opt-in accepts + audits without touching order state
        if (StringUtils.hasText(secret)) {
            sigOk = connector.verifyWebhookSignature(rawPayload, headers, secret);
            mutateState = sigOk;
        } else if (webhookProperties.allowsUnsigned(carrier)) {
            // Blank secret + explicit opt-in → accept but never mutate; auditor still gets the row.
            sigOk = false;
            mutateState = false;
            log.warn("Webhook: {} accepted unsigned (opt-in); audit only, no state change.", carrier);
        } else {
            // Blank secret + no opt-in → REJECT. Persist audit row with rejected=true;
            // the controller returns 401 to the caller.
            audit.setVerified(false);
            audit.setRejected(true);
            log.warn("Webhook: {} rejected — no secret configured and unsigned opt-in disabled.", carrier);
            return webhookEventRepository.save(audit);
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
        audit.setEventHash(computeEventHash(carrier, event, rawPayload));

        // Sprint 49 Tier 1 — dedup gate. If a prior verified row with the
        // same event_hash landed in the last 24h, persist this row with
        // duplicate=true and do NOT mutate order state.
        if (mutateState && audit.getEventHash() != null) {
            LocalDateTime windowStart = LocalDateTime.now(ZoneOffset.UTC).minusHours(24);
            Optional<CarrierWebhookEvent> prior = webhookEventRepository
                    .findFirstByEventHashAndVerifiedTrueAndReceivedAtAfterOrderByReceivedAtDesc(
                            audit.getEventHash(), windowStart);
            if (prior.isPresent()) {
                audit.setDuplicate(true);
                log.info("Webhook: {} duplicate event {} (prior id={}) — audit only, no state change.",
                        carrier, audit.getEventHash(), prior.get().getId());
                return webhookEventRepository.save(audit);
            }
        }

        CarrierWebhookEvent saved = webhookEventRepository.save(audit);

        // Only mutate on verified events with a tracking number we know about.
        // Carrier webhooks are keyed by per-piece tracking on multi-package
        // shipments — look up the per-piece row first, then bubble up to the
        // master (OrderTracking) so the order-level status still advances.
        //
        // Sprint 49 Tier 0: `mutateState` (not raw `sigOk`) drives this — the
        // unsigned-opt-in path is intentionally audit-only.
        //
        // Sprint 50 Tier 1 finding #9: the heavy tail (per-piece bump,
        // master lookup, save, cache invalidation) now runs on
        // webhookExecutor. Sync path returns the saved audit row to the
        // controller immediately; state advances a few ms later on a
        // worker thread. CallerRunsPolicy on the executor pushes back on
        // the carrier under sustained overload.
        if (mutateState && StringUtils.hasText(event.trackingNumber())) {
            final String eventTracking = event.trackingNumber();
            final String eventDescription = event.description();
            webhookExecutor.execute(() ->
                    mutateStateForVerifiedEvent(eventTracking, eventDescription));
        }
        return saved;
    }

    // Sprint 50 Tier 1 finding #9 — deliberately NOT @Transactional. The two
    // saves (label_packages, order_trackings) target different aggregate
    // roots and a partial update is recoverable: any missed row is healed by
    // the carrier's redelivery through Sprint 49 Tier 1's dedup. Adding a
    // transaction here would need a separate proxy bean because the executor
    // lambda bypasses this method's own proxy, and the cascade of extracting
    // a WebhookStateMutator @Component wasn't worth the correctness win over
    // the redelivery safety net.
    /**
     * Sprint 50 Tier 1 finding #9 — the async tail of {@link #receive}. Runs
     * on {@code webhookExecutor}. Kept package-private for test override
     * (a test can inject a SyncTaskExecutor + still cover this method).
     */
    void mutateStateForVerifiedEvent(String eventTracking, String eventDescription) {
        try {
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
                if (StringUtils.hasText(eventDescription)) {
                    t.setStatus(eventDescription);
                }
                t.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
                orderTrackingRepository.save(t);
            }
            // Sprint 50 Tier 1 finding #12 — evict the tracking cache so the
            // next /orders/{id}/tracking/live re-fetches the fresh state from
            // the carrier instead of serving the stale entry for up to 24h.
            TrackingService trackingService = trackingServiceProvider.getIfAvailable();
            if (trackingService != null) {
                trackingService.invalidate(eventTracking);
            }
        } catch (Exception ex) {
            // Sprint 50 Tier 1 finding #9 — the async worker never rethrows.
            // The carrier already got a 200 for this event; a downstream save
            // failure is only ever ops-visible via this WARN. Re-delivery
            // dedup (Sprint 49 Tier 1) means the carrier's retry heals the
            // missed state advance without us needing durable retries here.
            log.warn("Webhook async state-mutation failed for tracking={}: {}",
                    eventTracking, ex.getMessage());
        }
    }

    /**
     * Sprint 49 Tier 1 — SHA-256 event fingerprint for dedup. Combines the
     * fields that MUST all match for two events to represent "the same
     * carrier scan"; falls back to hashing the raw payload if any of those
     * are missing (e.g. malformed parser output that still somehow
     * verified).
     */
    static String computeEventHash(String carrier, TrackingWebhookEvent event, String rawPayload) {
        if (event == null) return sha256(rawPayload == null ? "" : rawPayload);
        String tracking = event.trackingNumber();
        String type = event.eventType();
        String status = event.statusCode();
        var occurred = event.occurredAt();
        if (tracking == null || tracking.isBlank() || occurred == null) {
            return sha256(rawPayload == null ? "" : rawPayload);
        }
        String base = String.join("|",
                carrier == null ? "" : carrier,
                tracking,
                type == null ? "" : type,
                status == null ? "" : status,
                occurred.toString());
        return sha256(base);
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
