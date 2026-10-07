package com.multiship.backend.service;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.VoidLabelResponseDTO;
import com.multiship.backend.model.CarrierAccountRef;
import com.multiship.backend.model.OrderTracking;
import com.multiship.backend.repository.CarrierAccountRefRepository;
import com.multiship.backend.repository.OrderRepository;
import com.multiship.backend.repository.OrderTrackingRepository;
import com.multiship.backend.service.carriers.CarrierConnector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * Sprint 30 implementation of {@link VoidService}. Credential
 * resolution reuses the {@link TrackingServiceImpl} precedent —
 * customer's own account first, then platform (house).
 *
 * <p>DB update on success: {@code status = VOIDED},
 * {@code isLabelGenerated = false}, {@code updatedAt = now()}. The
 * tracking number and label path are kept for audit ("we voided X").
 * When the carrier returns {@code NOT_SUPPORTED} we do NOT update the
 * DB — the label is still live at the carrier.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VoidServiceImpl implements VoidService {

    private final OrderTrackingRepository orderTrackingRepository;
    private final CarrierAccountRefRepository carrierAccountRefRepository;
    private final CarrierService carrierService;
    private final com.multiship.backend.repository.ShipmentBatchRepository shipmentBatchRepository;
    private final com.multiship.backend.config.CarrierProperties carrierProperties;
    /**
     * Sprint 50 Tier 0.5 PR E - tenant guard so a scoped USER cannot void
     * a label that belongs to a foreign tenant. The tenant discriminator
     * (tenant_id / cust_no) lives on label_batch (Order), not on the
     * tracking table.
     */
    private final OrderRepository orderRepository;
    private final TenantScopeEnforcer tenantScope;

    /** Logs page: LABEL_VOIDED events. Optional for hand-built tests. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AuditService auditService;

    /** V89 — external-system writeback on void. Fire-and-forget. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.multiship.backend.service.externalsystems.writeback.ExternalSystemWritebackDispatcher writebackDispatcher;

    /** Perf P3 — REQUIRES_NEW programmatic tx for phases A + C of the
     *  split path, and for the legacy path (semantically identical to
     *  @Transactional at method level since voidLabel is only called
     *  from a controller — no caller tx to join). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.transaction.support.TransactionTemplate requiresNewTransactionTemplate;

    /** Perf P3 feature flag — see {@code carrier.tx-split-phase-c} in
     *  application.properties. FALSE preserves the pre-P3 single-tx
     *  behaviour; TRUE runs the three-phase split. */
    @org.springframework.beans.factory.annotation.Value("${carrier.tx-split-phase-c:false}")
    private boolean phaseSplitEnabled;

    /**
     * Sprint 51 R1 (audit finding #1) — the void path used to read
     * {@link OrderTracking} without a row lock and without a transaction
     * boundary. Two concurrent voids (double-click, retry-on-timeout, or a
     * malicious repeat) both saw {@code status != "VOIDED"}, both called
     * the carrier, and some carriers (UPS, FedEx) charge a re-attempt fee
     * on the second call or corrupt the audit trail.
     *
     * <p>The {@link Transactional} boundary + {@link
     * com.multiship.backend.repository.OrderTrackingRepository#findByOrderNoForUpdate}
     * pessimistic-write lookup serialise concurrent voids on the DB row:
     * the loser sees {@code status = "VOIDED"} and returns
     * {@code ALREADY_VOIDED} without a carrier round-trip. The tx is
     * intentionally scoped to hold across the carrier HTTP call so the
     * status flip and the carrier call happen atomically — see the
     * follow-up note in {@code CarrierServiceImpl} at line 280 about
     * splitting label-generation into A) reserve IN_FLIGHT + release
     * lock, B) carrier call, C) persist result. Same trade-off applies
     * here; deferring for the same reason (needs a new "IN_FLIGHT" tri-state).
     */
    @Override
    public ApiResponse<VoidLabelResponseDTO> voidLabel(Integer orderNo) {
        if (orderNo == null) {
            return failure(HttpStatus.BAD_REQUEST, "Order number is required.");
        }
        if (phaseSplitEnabled && requiresNewTransactionTemplate != null) {
            return voidLabelSplit(orderNo);
        }
        // Legacy path — single @Transactional-equivalent block spanning
        // row-lock + carrier HTTP + persist. requiresNewTransactionTemplate
        // may be null in bare-ctor tests; fall back to a direct call so
        // existing Mockito tests that don't wire the template still pass.
        // (voidLabelLegacyBody mutates DB; in those tests the repos are
        // mocked, so tx semantics don't matter.)
        if (requiresNewTransactionTemplate != null) {
            return requiresNewTransactionTemplate.execute(status -> voidLabelLegacyBody(orderNo));
        }
        return voidLabelLegacyBody(orderNo);
    }

    private ApiResponse<VoidLabelResponseDTO> voidLabelLegacyBody(Integer orderNo) {
        // Sprint 50 Tier 0.5 PR E - belt-and-braces tenant guard. The
        // controller SpEL restricts foreign-tenant access, but any
        // internal caller bypassing method security lands here first.
        orderRepository.findByOrderNo(orderNo).ifPresent(o ->
                tenantScope.requireTenantMatch(
                        StringUtils.hasText(o.getTenantId()) ? o.getTenantId() : o.getCustNo()));

        OrderTracking tracking = orderTrackingRepository.findByOrderNoForUpdate(orderNo).orElse(null);
        if (tracking == null || !StringUtils.hasText(tracking.getTrackingNumber())) {
            return failure(HttpStatus.NOT_FOUND,
                    "Order " + orderNo + " has no tracking number to void.");
        }
        // Tag every carrier HTTP round-trip this void path triggers so the
        // carrier_api_log row ties back to this order + tracking. See
        // CarrierCallContext javadoc for the MDC contract.
        try (var ignored = com.multiship.backend.service.observability
                .CarrierCallContext.forOrder(Long.valueOf(orderNo), tracking.getTrackingNumber())) {
            return voidLabelInner(orderNo, tracking);
        }
    }

    private ApiResponse<VoidLabelResponseDTO> voidLabelInner(Integer orderNo, OrderTracking tracking) {

        // Idempotent short-circuit — now safe from the concurrent-void
        // race because we hold PESSIMISTIC_WRITE on the tracking row.
        if ("VOIDED".equalsIgnoreCase(tracking.getStatus())) {
            return success(dto(orderNo, tracking, true, "ALREADY_VOIDED",
                    "Order " + orderNo + " was already voided."));
        }

        String canonicalCarrier = TrackingServiceImpl.canonicalizeCarrierCode(tracking.getShipViaCd());
        // The stored ship-via is a SERVICE code; when it doesn't name a known
        // carrier, the account the label was billed on does.
        if (!java.util.Set.of("UPS", "FEDEX", "USPS", "DHL", "STAMPS").contains(
                canonicalCarrier == null ? "" : canonicalCarrier.toUpperCase(java.util.Locale.ROOT))
                && StringUtils.hasText(tracking.getAccountNumber())) {
            String fromAccount = carrierAccountRefRepository
                    .findFirstByAccountNumberIgnoreCaseOrderByUpdatedAtDesc(tracking.getAccountNumber().trim())
                    .map(CarrierAccountRef::getCarrierCode).orElse(null);
            if (StringUtils.hasText(fromAccount)) canonicalCarrier = fromAccount.trim().toUpperCase(java.util.Locale.ROOT);
        }
        if (!StringUtils.hasText(canonicalCarrier)) {
            return failure(HttpStatus.UNPROCESSABLE_CONTENT,
                    "Order " + orderNo + " has no carrier code; can't resolve credentials.");
        }

        CarrierConnector connector;
        try {
            connector = carrierService.getCarrierConnector(canonicalCarrier);
        } catch (Exception ex) {
            return failure(HttpStatus.UNPROCESSABLE_CONTENT,
                    "Carrier " + canonicalCarrier + " isn't configured on this instance.");
        }

        CarrierAccountRef account = resolveAccount(canonicalCarrier, tracking.getAccountNumber());
        if (account == null || !StringUtils.hasText(account.getClientId())
                || !StringUtils.hasText(account.getClientSecret())) {
            // Distinguish "the billed account is gone" from "no credentials at
            // all" — the carrier only accepts a cancel from the account that
            // bought the label, so substituting another account can't work.
            if (StringUtils.hasText(tracking.getAccountNumber())) {
                return failure(HttpStatus.UNPROCESSABLE_CONTENT,
                        "This label was billed to " + canonicalCarrier + " account "
                                + tracking.getAccountNumber().trim() + ", which is no longer "
                                + "registered (or has no credentials). Re-add that account in "
                                + "Settings → Carriers to void " + tracking.getTrackingNumber() + ".");
            }
            return failure(HttpStatus.UNPROCESSABLE_CONTENT,
                    "No live credentials for " + canonicalCarrier
                            + " — cannot void " + tracking.getTrackingNumber() + ".");
        }

        String accessToken;
        try {
            accessToken = connector.getAccessToken(account.getClientId(), account.getClientSecret(),
                    account.getAccountNumber(), account.getEnvironment());
        } catch (Exception ex) {
            log.warn("Void {} — token acquisition for {} failed: {}",
                    tracking.getTrackingNumber(), canonicalCarrier, ex.getMessage());
            return failure(HttpStatus.BAD_GATEWAY,
                    canonicalCarrier + " token acquisition failed: " + ex.getMessage());
        }

        // Multi-batch void — an order that was auto-split into N carrier
        // shipments has N master tracking numbers in shipment_batch. Void
        // every batch and treat the overall void as successful only when
        // every batch voided. Any failure surfaces the first failing batch's
        // message; partial voids are persisted (order status flips only when
        // ALL batches voided).
        java.util.List<com.multiship.backend.model.ShipmentBatch> batches =
                shipmentBatchRepository.findByOrderNoOrderByBatchSeqAsc(orderNo);
        java.util.List<String> trackingNumbersToVoid = new java.util.ArrayList<>();
        if (batches.isEmpty()) {
            // Legacy path — no batches persisted; void the order-level master.
            trackingNumbersToVoid.add(tracking.getTrackingNumber());
        } else {
            for (com.multiship.backend.model.ShipmentBatch b : batches) {
                if (StringUtils.hasText(b.getMasterTrackingNumber())) {
                    trackingNumbersToVoid.add(b.getMasterTrackingNumber());
                }
            }
        }

        // Sprint 49 Tier 2: pass the real account number + platform shipper
        // country to the connector. FedEx cancel rejects any label whose
        // shipperAccountNumber doesn't match; the placeholder "ACCOUNT" it
        // used to receive made every FedEx void fail silently.
        String voidAccountNumber = account.getAccountNumber();
        String senderCountryCode = carrierProperties.getShipper() != null
                ? carrierProperties.getShipper().getCountryCode()
                : null;

        CarrierConnector.VoidResult result = null;
        java.util.List<CarrierConnector.VoidResult> perBatchResults = new java.util.ArrayList<>();
        for (String trackNo : trackingNumbersToVoid) {
            try {
                CarrierConnector.VoidResult r = connector.voidShipment(trackNo, accessToken,
                        account.getEnvironment(), voidAccountNumber, senderCountryCode);
                perBatchResults.add(r);
                if (result == null) result = r;
            } catch (Exception ex) {
                log.warn("Void {} — carrier call failed at {}: {}", trackNo, canonicalCarrier, ex.getMessage());
                return failure(HttpStatus.BAD_GATEWAY,
                        canonicalCarrier + " void call failed for " + trackNo + ": " + ex.getMessage());
            }
        }
        // If any batch failed to void, surface that. Order status only flips
        // when every batch reports voided=true.
        boolean allVoided = !perBatchResults.isEmpty()
                && perBatchResults.stream().allMatch(CarrierConnector.VoidResult::voided);
        if (!allVoided) {
            CarrierConnector.VoidResult firstFail = perBatchResults.stream()
                    .filter(r -> !r.voided()).findFirst().orElse(result);
            result = firstFail != null ? firstFail : result;
        } else {
            // Reuse a synthetic aggregate so downstream persistence sees success.
            result = new CarrierConnector.VoidResult(
                    tracking.getTrackingNumber(), true, "VOIDED",
                    perBatchResults.size() + " batch(es) voided.", null);
        }

        // A refusal keeps the label live, but the attempt itself is part of the
        // label's story: record it on the tracking row and in Logs so "did anyone
        // try to cancel this?" has an answer. (Sandbox UPS refuses every void.)
        if (!result.voided()) {
            try {
                com.multiship.backend.util.LabelHistory.append(tracking, "VOID_REFUSED",
                        tracking.getTrackingNumber(), null, LocalDateTime.now());
                orderTrackingRepository.save(tracking);
            } catch (Exception ex) {
                log.warn("Void {} — could not record the refusal on label history: {}",
                        tracking.getTrackingNumber(), ex.getMessage());
            }
            if (auditService != null) {
                auditService.logShipment(AuditService.LABEL_VOID_REFUSED, orderNo, null,
                        tracking.getTrackingNumber(),
                        canonicalCarrier + " refused the void: " + (result.message() == null ? result.status() : result.message()));
            }
        }
        // Persist the successful void so the order is no longer treated
        // as GENERATED. NOT_SUPPORTED / ERROR leaves the DB untouched —
        // the label is still live at the carrier.
        if (result.voided()) {
            tracking.setStatus("VOIDED");
            tracking.setIsLabelGenerated(false);
            tracking.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
            // Local clock, like labelGeneratedAt and the REISSUED entry — a UTC stamp
            // here printed the void hours away from the reissue that followed it.
            com.multiship.backend.util.LabelHistory.append(tracking, "VOIDED", tracking.getTrackingNumber(), null,
                    LocalDateTime.now());
            orderTrackingRepository.save(tracking);
            // Logs page: shipment-lifecycle trail. Carry the money figures so
            // the void reads as the reversal of the LABEL_GENERATED charge —
            // both numbers, labelled, because "billable" (carrier + markup)
            // and the raw carrier cost are different quantities and showing
            // only one caused two surfaces to "disagree" about the amount.
            if (auditService != null) {
                String money = "";
                if (tracking.getBillableAmount() != null || tracking.getCarrierAmount() != null) {
                    String ccy = tracking.getMarkupCurrency() == null ? "USD" : tracking.getMarkupCurrency();
                    money = " · reversed"
                            + (tracking.getCarrierAmount() != null ? " carrier " + tracking.getCarrierAmount() + " " + ccy : "")
                            + (tracking.getBillableAmount() != null ? " / billable " + tracking.getBillableAmount() + " " + ccy : "");
                }
                auditService.logShipment(AuditService.LABEL_VOIDED, orderNo, null,
                        tracking.getTrackingNumber(),
                        canonicalCarrier + " label voided (" + perBatchResults.size() + " batch(es))" + money);
            }
            // V89 — external-system writeback clear. Resolve the order's
            // clientCode from the persisted Order row so per-tenant
            // routing works. Fire-and-forget; a writeback failure here
            // must NOT surface as a void failure (the label IS voided).
            try {
                if (writebackDispatcher != null) {
                    java.util.Optional<com.multiship.backend.model.Order> ord =
                            orderRepository.findByOrderNo(orderNo);
                    String clientCode = ord.map(com.multiship.backend.model.Order::getCustNo).orElse(null);
                    String source     = ord.map(com.multiship.backend.model.Order::getSource).orElse(null);
                    String channel    = ord.map(com.multiship.backend.model.Order::getOrderChannel).orElse(null);
                    writebackDispatcher.dispatchOnClear(
                            com.multiship.backend.service.externalsystems.writeback.WritebackClearRequest
                                    .of(null, tracking.getTrackingNumber(), orderNo, clientCode, source, channel));
                }
            } catch (RuntimeException wbFail) {
                log.warn("V89 writeback clear failed for voided order {} (void succeeded): {}",
                        orderNo, wbFail.getMessage());
            }
        }

        VoidLabelResponseDTO body = VoidLabelResponseDTO.builder()
                .orderNo(orderNo)
                .trackingNumber(tracking.getTrackingNumber())
                .carrierCode(canonicalCarrier)
                .voided(result.voided())
                .status(result.status())
                .message(result.message())
                .build();
        return success(body);
    }

    // ===== Perf P3 — three-phase split (gated by phaseSplitEnabled) =====
    //
    // Phase A reserves the row (sets in_flight_since) in a short tx and
    // releases the lock. Phase B does the token acquisition + per-batch
    // carrier HTTP calls with NO tx held. Phase C persists the final
    // state (VOIDED or VOID_REFUSED) + nulls in_flight_since in a new tx.
    //
    // A process crash between B and C leaves in_flight_since set; the
    // InFlightTrackingSweeper surfaces the stuck row (P5 will resolve by
    // querying tracking state at the carrier).

    private record Reservation(
            ApiResponse<VoidLabelResponseDTO> earlyReturn,  // non-null = short-circuit; skip B+C
            Integer orderNo,
            String trackingNumber,
            String canonicalCarrier,
            CarrierConnector connector,
            CarrierAccountRef account,
            List<String> trackingNumbersToVoid,
            String voidAccountNumber,
            String senderCountryCode) {}

    private record CarrierCallOutcome(
            CarrierConnector.VoidResult aggregateResult,
            List<CarrierConnector.VoidResult> perBatchResults,
            ApiResponse<VoidLabelResponseDTO> earlyFailure) {}  // non-null = BAD_GATEWAY return

    private ApiResponse<VoidLabelResponseDTO> voidLabelSplit(Integer orderNo) {
        try (var ignored = com.multiship.backend.service.observability
                .CarrierCallContext.forOrder(Long.valueOf(orderNo))) {
            Reservation reservation = requiresNewTransactionTemplate.execute(
                    status -> runReservationPhase(orderNo));
            if (reservation == null) {
                return failure(HttpStatus.INTERNAL_SERVER_ERROR,
                        "Phase A returned no reservation for order " + orderNo + ".");
            }
            if (reservation.earlyReturn() != null) {
                return reservation.earlyReturn();
            }
            // Phase B — no tx held across the carrier HTTP. MDC enrichment
            // with trackingNumber picked up by CarrierApiLoggingInterceptor
            // on every HTTP round-trip inside this block.
            CarrierCallOutcome outcome;
            try (var tracked = com.multiship.backend.service.observability
                    .CarrierCallContext.forOrder(Long.valueOf(orderNo), reservation.trackingNumber())) {
                outcome = runCarrierCallPhase(reservation);
            }
            // Phase C — short tx, persist + null in_flight_since. On
            // outcome.earlyFailure() we still need to null in_flight_since
            // so the sweeper doesn't flag the row; use a tiny tx just for
            // that reset and return the BAD_GATEWAY response.
            return requiresNewTransactionTemplate.execute(
                    status -> runPersistPhase(reservation, outcome));
        }
    }

    private Reservation runReservationPhase(Integer orderNo) {
        // Belt-and-braces tenant guard (same as legacy path).
        orderRepository.findByOrderNo(orderNo).ifPresent(o ->
                tenantScope.requireTenantMatch(
                        StringUtils.hasText(o.getTenantId()) ? o.getTenantId() : o.getCustNo()));

        OrderTracking tracking = orderTrackingRepository.findByOrderNoForUpdate(orderNo).orElse(null);
        if (tracking == null || !StringUtils.hasText(tracking.getTrackingNumber())) {
            return new Reservation(failure(HttpStatus.NOT_FOUND,
                    "Order " + orderNo + " has no tracking number to void."),
                    orderNo, null, null, null, null, null, null, null);
        }
        if ("VOIDED".equalsIgnoreCase(tracking.getStatus())) {
            return new Reservation(success(dto(orderNo, tracking, true, "ALREADY_VOIDED",
                    "Order " + orderNo + " was already voided.")),
                    orderNo, null, null, null, null, null, null, null);
        }
        // Already-in-flight guard: another concurrent void saw this row
        // first and is waiting on the carrier. Pre-P3 the pessimistic
        // lock serialized this; post-P3 the sentinel column does.
        if (tracking.getInFlightSince() != null) {
            return new Reservation(failure(HttpStatus.CONFLICT,
                    "Order " + orderNo + " already has a void in flight (started "
                            + tracking.getInFlightSince() + "). Wait for it to settle "
                            + "or let the sweeper reconcile it."),
                    orderNo, null, null, null, null, null, null, null);
        }

        String canonicalCarrier = TrackingServiceImpl.canonicalizeCarrierCode(tracking.getShipViaCd());
        if (!java.util.Set.of("UPS", "FEDEX", "USPS", "DHL", "STAMPS").contains(
                canonicalCarrier == null ? "" : canonicalCarrier.toUpperCase(java.util.Locale.ROOT))
                && StringUtils.hasText(tracking.getAccountNumber())) {
            String fromAccount = carrierAccountRefRepository
                    .findFirstByAccountNumberIgnoreCaseOrderByUpdatedAtDesc(tracking.getAccountNumber().trim())
                    .map(CarrierAccountRef::getCarrierCode).orElse(null);
            if (StringUtils.hasText(fromAccount)) canonicalCarrier = fromAccount.trim().toUpperCase(java.util.Locale.ROOT);
        }
        if (!StringUtils.hasText(canonicalCarrier)) {
            return new Reservation(failure(HttpStatus.UNPROCESSABLE_CONTENT,
                    "Order " + orderNo + " has no carrier code; can't resolve credentials."),
                    orderNo, null, null, null, null, null, null, null);
        }

        CarrierConnector connector;
        try {
            connector = carrierService.getCarrierConnector(canonicalCarrier);
        } catch (Exception ex) {
            return new Reservation(failure(HttpStatus.UNPROCESSABLE_CONTENT,
                    "Carrier " + canonicalCarrier + " isn't configured on this instance."),
                    orderNo, null, null, null, null, null, null, null);
        }

        CarrierAccountRef account = resolveAccount(canonicalCarrier, tracking.getAccountNumber());
        if (account == null || !StringUtils.hasText(account.getClientId())
                || !StringUtils.hasText(account.getClientSecret())) {
            if (StringUtils.hasText(tracking.getAccountNumber())) {
                return new Reservation(failure(HttpStatus.UNPROCESSABLE_CONTENT,
                        "This label was billed to " + canonicalCarrier + " account "
                                + tracking.getAccountNumber().trim() + ", which is no longer "
                                + "registered (or has no credentials). Re-add that account in "
                                + "Settings → Carriers to void " + tracking.getTrackingNumber() + "."),
                        orderNo, null, null, null, null, null, null, null);
            }
            return new Reservation(failure(HttpStatus.UNPROCESSABLE_CONTENT,
                    "No live credentials for " + canonicalCarrier
                            + " — cannot void " + tracking.getTrackingNumber() + "."),
                    orderNo, null, null, null, null, null, null, null);
        }

        java.util.List<com.multiship.backend.model.ShipmentBatch> batches =
                shipmentBatchRepository.findByOrderNoOrderByBatchSeqAsc(orderNo);
        java.util.List<String> trackingNumbersToVoid = new java.util.ArrayList<>();
        if (batches.isEmpty()) {
            trackingNumbersToVoid.add(tracking.getTrackingNumber());
        } else {
            for (com.multiship.backend.model.ShipmentBatch b : batches) {
                if (StringUtils.hasText(b.getMasterTrackingNumber())) {
                    trackingNumbersToVoid.add(b.getMasterTrackingNumber());
                }
            }
        }

        String senderCountryCode = carrierProperties.getShipper() != null
                ? carrierProperties.getShipper().getCountryCode()
                : null;

        // Reserve: set in_flight_since + save. Commit releases the lock.
        tracking.setInFlightSince(Instant.now());
        orderTrackingRepository.save(tracking);

        return new Reservation(null, orderNo,
                tracking.getTrackingNumber(), canonicalCarrier, connector, account,
                trackingNumbersToVoid, account.getAccountNumber(), senderCountryCode);
    }

    private CarrierCallOutcome runCarrierCallPhase(Reservation r) {
        String accessToken;
        try {
            accessToken = r.connector().getAccessToken(
                    r.account().getClientId(), r.account().getClientSecret(),
                    r.account().getAccountNumber(), r.account().getEnvironment());
        } catch (Exception ex) {
            log.warn("Void {} — token acquisition for {} failed: {}",
                    r.trackingNumber(), r.canonicalCarrier(), ex.getMessage());
            return new CarrierCallOutcome(null, java.util.List.of(),
                    failure(HttpStatus.BAD_GATEWAY,
                            r.canonicalCarrier() + " token acquisition failed: " + ex.getMessage()));
        }

        CarrierConnector.VoidResult first = null;
        java.util.List<CarrierConnector.VoidResult> perBatch = new java.util.ArrayList<>();
        for (String trackNo : r.trackingNumbersToVoid()) {
            try {
                CarrierConnector.VoidResult vr = r.connector().voidShipment(trackNo, accessToken,
                        r.account().getEnvironment(), r.voidAccountNumber(), r.senderCountryCode());
                perBatch.add(vr);
                if (first == null) first = vr;
            } catch (Exception ex) {
                log.warn("Void {} — carrier call failed at {}: {}", trackNo, r.canonicalCarrier(), ex.getMessage());
                return new CarrierCallOutcome(null, perBatch,
                        failure(HttpStatus.BAD_GATEWAY,
                                r.canonicalCarrier() + " void call failed for " + trackNo + ": " + ex.getMessage()));
            }
        }

        boolean allVoided = !perBatch.isEmpty()
                && perBatch.stream().allMatch(CarrierConnector.VoidResult::voided);
        CarrierConnector.VoidResult aggregate;
        if (allVoided) {
            aggregate = new CarrierConnector.VoidResult(
                    r.trackingNumber(), true, "VOIDED",
                    perBatch.size() + " batch(es) voided.", null);
        } else {
            aggregate = perBatch.stream().filter(x -> !x.voided()).findFirst().orElse(first);
        }
        return new CarrierCallOutcome(aggregate, perBatch, null);
    }

    private ApiResponse<VoidLabelResponseDTO> runPersistPhase(Reservation r, CarrierCallOutcome outcome) {
        // Re-fetch under row lock. In_flight_since set by phase A guarantees
        // no other voidLabel path raced in; this lock just protects against
        // sibling writers (tracking updater, etc.) that touch the same row.
        OrderTracking tracking = orderTrackingRepository.findByOrderNoForUpdate(r.orderNo()).orElse(null);
        if (tracking == null) {
            // Should not happen — phase A found this row. Log + return the
            // phase B outcome anyway so the caller gets something.
            log.warn("Void {} — persist phase could not re-fetch tracking row (concurrent delete?)", r.orderNo());
            return outcome.earlyFailure() != null ? outcome.earlyFailure()
                    : failure(HttpStatus.NOT_FOUND, "Tracking row vanished between phases.");
        }
        tracking.setInFlightSince(null);

        if (outcome.earlyFailure() != null) {
            // Phase B failed before producing a result (token or carrier
            // exception). Clear the reservation sentinel and return.
            orderTrackingRepository.save(tracking);
            return outcome.earlyFailure();
        }

        CarrierConnector.VoidResult result = outcome.aggregateResult();
        if (!result.voided()) {
            try {
                com.multiship.backend.util.LabelHistory.append(tracking, "VOID_REFUSED",
                        tracking.getTrackingNumber(), null, LocalDateTime.now());
            } catch (Exception ex) {
                log.warn("Void {} — could not record the refusal on label history: {}",
                        tracking.getTrackingNumber(), ex.getMessage());
            }
            orderTrackingRepository.save(tracking);
            if (auditService != null) {
                auditService.logShipment(AuditService.LABEL_VOID_REFUSED, r.orderNo(), null,
                        tracking.getTrackingNumber(),
                        r.canonicalCarrier() + " refused the void: "
                                + (result.message() == null ? result.status() : result.message()));
            }
        } else {
            tracking.setStatus("VOIDED");
            tracking.setIsLabelGenerated(false);
            tracking.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
            com.multiship.backend.util.LabelHistory.append(tracking, "VOIDED",
                    tracking.getTrackingNumber(), null, LocalDateTime.now());
            orderTrackingRepository.save(tracking);
            if (auditService != null) {
                String money = "";
                if (tracking.getBillableAmount() != null || tracking.getCarrierAmount() != null) {
                    String ccy = tracking.getMarkupCurrency() == null ? "USD" : tracking.getMarkupCurrency();
                    money = " · reversed"
                            + (tracking.getCarrierAmount() != null ? " carrier " + tracking.getCarrierAmount() + " " + ccy : "")
                            + (tracking.getBillableAmount() != null ? " / billable " + tracking.getBillableAmount() + " " + ccy : "");
                }
                auditService.logShipment(AuditService.LABEL_VOIDED, r.orderNo(), null,
                        tracking.getTrackingNumber(),
                        r.canonicalCarrier() + " label voided (" + outcome.perBatchResults().size() + " batch(es))" + money);
            }
            try {
                if (writebackDispatcher != null) {
                    java.util.Optional<com.multiship.backend.model.Order> ord =
                            orderRepository.findByOrderNo(r.orderNo());
                    String clientCode = ord.map(com.multiship.backend.model.Order::getCustNo).orElse(null);
                    String source     = ord.map(com.multiship.backend.model.Order::getSource).orElse(null);
                    String channel    = ord.map(com.multiship.backend.model.Order::getOrderChannel).orElse(null);
                    writebackDispatcher.dispatchOnClear(
                            com.multiship.backend.service.externalsystems.writeback.WritebackClearRequest
                                    .of(null, tracking.getTrackingNumber(), r.orderNo(), clientCode, source, channel));
                }
            } catch (RuntimeException wbFail) {
                log.warn("V89 writeback clear failed for voided order {} (void succeeded): {}",
                        r.orderNo(), wbFail.getMessage());
            }
        }

        VoidLabelResponseDTO body = VoidLabelResponseDTO.builder()
                .orderNo(r.orderNo())
                .trackingNumber(tracking.getTrackingNumber())
                .carrierCode(r.canonicalCarrier())
                .voided(result.voided())
                .status(result.status())
                .message(result.message())
                .build();
        return success(body);
    }

    /**
     * Resolve the account to authenticate the void call. When the tracking
     * recorded the billing account, ONLY that exact (accountNumber, carrier)
     * row is acceptable:
     * <ul>
     *   <li>The old any-carrier fallback matched the same number under a
     *       DIFFERENT carrier — since (number, carrier) is the unique key,
     *       that row can belong to another tenant, handing this void a
     *       foreign tenant's credentials.</li>
     *   <li>The old silent platform substitution sent the platform number as
     *       {@code shipperAccountNumber} — exactly the mismatch FedEx rejects
     *       (see the Sprint 49 comment at the call site). Failing loudly so
     *       the operator re-registers the account beats a doomed carrier call.</li>
     * </ul>
     * Platform fallback remains only for legacy trackings that never recorded
     * an account number.
     */
    CarrierAccountRef resolveAccount(String carrierCode, String accountNumber) {
        if (StringUtils.hasText(accountNumber)) {
            return carrierAccountRefRepository
                    .findFirstByAccountNumberIgnoreCaseAndCarrierCodeIgnoreCase(accountNumber, carrierCode)
                    .orElse(null);
        }
        List<CarrierAccountRef> platform = carrierAccountRefRepository
                .findPlatformAccountsByCarrier(carrierCode);
        return platform.isEmpty() ? null : platform.get(0);
    }

    private static VoidLabelResponseDTO dto(Integer orderNo, OrderTracking t,
                                              boolean voided, String status, String message) {
        return VoidLabelResponseDTO.builder()
                .orderNo(orderNo)
                .trackingNumber(t.getTrackingNumber())
                .carrierCode(t.getShipViaCd())
                .voided(voided)
                .status(status)
                .message(message)
                .build();
    }

    private static ApiResponse<VoidLabelResponseDTO> success(VoidLabelResponseDTO data) {
        return ApiResponse.<VoidLabelResponseDTO>builder()
                .status("success").code(200)
                .message(data.getMessage()).data(data).build();
    }

    private static ApiResponse<VoidLabelResponseDTO> failure(HttpStatus status, String message) {
        return ApiResponse.<VoidLabelResponseDTO>builder()
                .status("error").code(status.value())
                .errorCode(ErrorCode.VALIDATION_ERROR.name())
                .message(message).data(null).build();
    }
}
