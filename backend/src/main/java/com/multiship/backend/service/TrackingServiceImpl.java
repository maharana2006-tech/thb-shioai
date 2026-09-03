package com.multiship.backend.service;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.TrackingResponseDTO;
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
import org.springframework.util.StringUtils;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Sprint 16 implementation of {@link TrackingService}. Injects the same
 * {@link CarrierService} the label flow uses so the connector lookup and
 * carrier code canonicalization stay in one place.
 *
 * <p>Cache design: Caffeine cache keyed by trackingNumber, capped at
 * {@link #MAX_ENTRIES} with expireAfterWrite bounded at
 * {@link #CACHE_TTL_DELIVERED} (24h). Per-entry TTLs are enforced by
 * the {@link CacheEntry#isExpired()} check the callers already use, so
 * active-vs-delivered semantics are unchanged. Sprint 49 Tier 3 Fix 6 —
 * previously an unbounded ConcurrentHashMap that grew forever.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrackingServiceImpl implements TrackingService {

    /** In-flight shipment: refresh every 5 minutes at most. */
    private static final Duration CACHE_TTL_ACTIVE = Duration.ofMinutes(5);
    /** Delivered shipment: 24-hour cache — carriers rarely reopen a delivered scan. */
    private static final Duration CACHE_TTL_DELIVERED = Duration.ofHours(24);
    /** Sprint 49 Tier 3 Fix 6 — hard cap on tracking cache size. */
    private static final long MAX_ENTRIES = 10_000;

    private final OrderTrackingRepository orderTrackingRepository;
    private final CarrierAccountRefRepository carrierAccountRefRepository;
    private final CarrierService carrierService;
    /**
     * Sprint 50 Tier 0.5 PR E — tenant guard. TrackingServiceImpl needs the
     * Order (not just OrderTracking) to reach the tenant discriminator
     * (tenant_id / cust_no lives on label_batch, mirrored on Order).
     */
    private final OrderRepository orderRepository;
    private final TenantScopeEnforcer tenantScope;

    /**
     * Audit L2 — populate masterTrackings[] + childTrackings[] on the
     * response, matching the external v2 shape from PR #548. Optional
     * (@Autowired required=false) so pure-Mockito tests that don't wire
     * these deps still construct — helpers null-check and degrade to
     * empty lists (matches pre-audit shape). See
     * feedback_lombok_constructor_arg_order.md for why this isn't a
     * constructor arg.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.multiship.backend.repository.ShipmentBatchRepository shipmentBatchRepositoryForTracking;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.multiship.backend.repository.LabelPackageRepository labelPackageRepositoryForTracking;

    private final Cache<String, CacheEntry> cache = Caffeine.newBuilder()
            .maximumSize(MAX_ENTRIES)
            .expireAfterWrite(CACHE_TTL_DELIVERED)
            .build();

    @Override
    public void invalidate(String trackingNumber) {
        if (StringUtils.hasText(trackingNumber)) {
            cache.invalidate(trackingNumber);
        }
    }

    @Override
    public ApiResponse<TrackingResponseDTO> getLiveTracking(Integer orderNo) {
        if (orderNo == null) {
            return failure(HttpStatus.BAD_REQUEST, "Order number is required.");
        }

        // Sprint 50 Tier 0.5 PR E — belt-and-braces post-load tenant guard.
        // Controller SpEL (@orderAccess.canViewOrder) already scopes the read,
        // but any internal caller bypassing method security lands here first.
        orderRepository.findByOrderNo(orderNo).ifPresent(o ->
                tenantScope.requireTenantMatch(
                        StringUtils.hasText(o.getTenantId()) ? o.getTenantId() : o.getCustNo()));

        OrderTracking tracking = orderTrackingRepository.findByOrderNo(orderNo).orElse(null);
        if (tracking == null || !StringUtils.hasText(tracking.getTrackingNumber())) {
            return failure(HttpStatus.NOT_FOUND, "Order " + orderNo + " has no tracking number yet.");
        }

        String trackingNumber = tracking.getTrackingNumber();
        String canonicalCarrier = canonicalizeCarrierCode(tracking.getShipViaCd());
        if (!StringUtils.hasText(canonicalCarrier)) {
            return failure(HttpStatus.UNPROCESSABLE_CONTENT,
                    "Order " + orderNo + " has no carrier code; can't resolve credentials.");
        }

        // Cache probe. A LIVE result with delivered=true stays for 24h;
        // anything else re-checks after CACHE_TTL_ACTIVE.
        CacheEntry cached = cache.getIfPresent(trackingNumber);
        if (cached != null && !cached.isExpired()) {
            return success(cached.dto().toBuilder().source("CACHE").build(),
                    "Cached tracking (checked " + cached.ageSeconds() + "s ago).");
        }

        CarrierConnector connector;
        try {
            connector = carrierService.getCarrierConnector(canonicalCarrier);
        } catch (Exception ex) {
            return failure(HttpStatus.UNPROCESSABLE_CONTENT,
                    "Carrier " + canonicalCarrier + " isn't configured on this instance.");
        }

        // Resolve the credential row that generated the label. When the
        // shipment's account number isn't on the book we fall back to the
        // carrier's platform account so the tracking call still authenticates.
        CarrierAccountRef account = resolveAccount(canonicalCarrier, tracking.getAccountNumber());
        if (account == null || !StringUtils.hasText(account.getClientId())
                || !StringUtils.hasText(account.getClientSecret())) {
            // No credentials available — hand back the URL-only stub from
            // the 1-arg trackShipment. The UI can still show the tracking
            // link even without live events.
            CarrierConnector.TrackingResult stub = connector.trackShipment(trackingNumber);
            TrackingResponseDTO dto = withMpsTopology(toDto(stub, canonicalCarrier, "STUB"), orderNo);
            return success(dto, "No live credentials for " + canonicalCarrier
                    + " — returning the URL-only stub.");
        }

        String accessToken;
        try {
            accessToken = connector.getAccessToken(account.getClientId(), account.getClientSecret(),
                    account.getAccountNumber(), account.getEnvironment());
        } catch (Exception ex) {
            log.warn("Token acquisition for {} failed while tracking {}: {}",
                    canonicalCarrier, trackingNumber, ex.getMessage());
            CarrierConnector.TrackingResult stub = connector.trackShipment(trackingNumber);
            TrackingResponseDTO dto = withMpsTopology(toDto(stub, canonicalCarrier, "STUB"), orderNo);
            return success(dto, "Live token unavailable — URL-only stub returned.");
        }

        CarrierConnector.TrackingResult result;
        try {
            result = connector.trackShipment(trackingNumber, accessToken, account.getEnvironment());
        } catch (com.multiship.backend.service.carriers.exceptions.CarrierRateLimitException rle) {
            // Audit L1 — distinguish "carrier is throttling me" from "carrier
            // is unavailable". Pre-fix this fell into the generic catch and
            // silently degraded to STUB — partners could not tell backoff
            // was required. Now emits source=RATE_LIMITED with the carrier's
            // Retry-After hint so the FE / partner can schedule its next
            // poll properly.
            log.info("Live tracking for {} rate-limited at {} (retry-after={}s)",
                    trackingNumber, canonicalCarrier, rle.getRetryAfterSeconds());
            CarrierConnector.TrackingResult stub = connector.trackShipment(trackingNumber);
            TrackingResponseDTO dto = withMpsTopology(toDto(stub, canonicalCarrier, "RATE_LIMITED"), orderNo)
                    .toBuilder()
                    .retryAfterSeconds(rle.getRetryAfterSeconds())
                    .build();
            return success(dto, "Carrier is rate-limiting requests"
                    + (rle.getRetryAfterSeconds() != null
                            ? " — retry after " + rle.getRetryAfterSeconds() + "s." : "."));
        } catch (Exception ex) {
            log.warn("Live tracking for {} failed at {}: {}",
                    trackingNumber, canonicalCarrier, ex.getMessage());
            CarrierConnector.TrackingResult stub = connector.trackShipment(trackingNumber);
            return success(withMpsTopology(toDto(stub, canonicalCarrier, "STUB"), orderNo),
                    "Live tracking call failed.");
        }

        TrackingResponseDTO dto = withMpsTopology(toDto(result, canonicalCarrier, "LIVE"), orderNo);
        // Cache LIVE results only — STUB / RATE_LIMITED responses are already cheap.
        cache.put(trackingNumber, new CacheEntry(dto,
                dto.getDelivered() != null && dto.getDelivered()
                        ? CACHE_TTL_DELIVERED : CACHE_TTL_ACTIVE));
        return success(dto, "Live tracking checked.");
    }

    /**
     * Audit L2 — enrich the DTO with per-batch master + per-piece child
     * arrays. Matches the external v2 shape from PR #548 so internal FE
     * (TrackingTimelineModal) can show per-piece context. No-op when the
     * repos aren't wired (pure-Mockito tests) or the order isn't multi-
     * package (empty lists preserve the pre-audit shape).
     */
    private TrackingResponseDTO withMpsTopology(TrackingResponseDTO dto, Integer orderNo) {
        if (dto == null || orderNo == null) return dto;
        java.util.List<TrackingResponseDTO.MasterTracking> masters = java.util.List.of();
        java.util.List<TrackingResponseDTO.ChildTracking> children = java.util.List.of();
        if (shipmentBatchRepositoryForTracking != null) {
            masters = shipmentBatchRepositoryForTracking.findByOrderNoOrderByBatchSeqAsc(orderNo).stream()
                    .map(b -> TrackingResponseDTO.MasterTracking.builder()
                            .batchSeq(b.getBatchSeq())
                            .carrierCode(b.getCarrierCode())
                            .masterTrackingNumber(b.getMasterTrackingNumber())
                            .masterTrackingUrl(b.getMasterTrackingUrl())
                            .packageCountInBatch(b.getPackageCountInBatch())
                            .build())
                    .toList();
        }
        if (labelPackageRepositoryForTracking != null) {
            children = labelPackageRepositoryForTracking.findByOrderNoOrderBySequenceNumberAsc(orderNo).stream()
                    .map(p -> TrackingResponseDTO.ChildTracking.builder()
                            .sequenceNumber(p.getSequenceNumber())
                            .trackingNumber(p.getTrackingNumber())
                            .trackingUrl(p.getTrackingUrl())
                            .build())
                    .toList();
        }
        return dto.toBuilder()
                .masterTrackings(masters)
                .childTrackings(children)
                .build();
    }

    /**
     * Resolve the account for the tracking call. Preferred order:
     * <ol>
     *   <li>Exact match on (carrier, accountNumber) — the row that generated
     *       the label.</li>
     *   <li>Any account with that number, any carrier — handles legacy rows
     *       with mislabeled carrier codes.</li>
     *   <li>The carrier's platform account — falls back to house credentials
     *       so tracking still authenticates.</li>
     * </ol>
     * Returns null when even the platform account is missing (or when
     * credentials on the found row are blank).
     */
    private CarrierAccountRef resolveAccount(String canonicalCarrier, String accountNumber) {
        if (StringUtils.hasText(accountNumber)) {
            Optional<CarrierAccountRef> exact = carrierAccountRefRepository
                    .findFirstByAccountNumberIgnoreCaseAndCarrierCodeIgnoreCase(accountNumber, canonicalCarrier);
            if (exact.isPresent()) return exact.get();
            // NOTE: no any-carrier fallback here. (number, carrier) is the
            // unique key, so the same number under a different carrier can be
            // a DIFFERENT tenant's row — matching it would authenticate this
            // track call with a foreign tenant's credentials. Tracking works
            // fine on the platform account, so fall through to that instead.
        }
        List<CarrierAccountRef> platform = carrierAccountRefRepository
                .findPlatformAccountsByCarrier(canonicalCarrier);
        return platform.isEmpty() ? null : platform.get(0);
    }

    /**
     * Canonicalize the ERP-side ship_via code (P80/F77/L01) into our carrier
     * enum (UPS/FEDEX/USPS). Anything already canonical passes through
     * unchanged; DHL, being a newer addition, is only ever stored canonically.
     */
    static String canonicalizeCarrierCode(String shipViaCd) {
        if (!StringUtils.hasText(shipViaCd)) return null;
        // Delegate to the shared canonicalizer so carrier-prefixed SERVICE codes
        // resolve too — a manual shipment stores its service code (e.g.
        // FEDEX_2_DAY, FEDEX_GROUND) in ship_via_cd, and the old local map
        // passed those through unchanged, so getCarrierConnector("FEDEX_2_DAY")
        // failed with "carrier isn't configured" and no order could be tracked.
        String canonical = ShippingConfigService.canonicalCarrierFor(shipViaCd);
        return StringUtils.hasText(canonical) ? canonical : null;
    }

    /** Copy a connector TrackingResult onto the wire-shape DTO. */
    private TrackingResponseDTO toDto(CarrierConnector.TrackingResult r, String carrierCode, String source) {
        List<TrackingResponseDTO.TrackingEventDTO> events = r.events() == null
                ? List.of()
                : r.events().stream()
                        .map(e -> TrackingResponseDTO.TrackingEventDTO.builder()
                                .timestamp(e.timestamp())
                                .status(e.status())
                                .description(e.description())
                                .location(e.location())
                                .build())
                        .toList();
        return TrackingResponseDTO.builder()
                .trackingNumber(r.trackingNumber())
                .carrierCode(carrierCode)
                .status(r.status())
                .delivered(r.delivered())
                .trackingUrl(r.trackingUrl())
                .currentLocation(r.currentLocation())
                .estimatedDelivery(r.estimatedDelivery())
                .events(events)
                .source(source)
                .build();
    }

    private static ApiResponse<TrackingResponseDTO> success(TrackingResponseDTO data, String message) {
        return ApiResponse.<TrackingResponseDTO>builder()
                .status("success").code(200).message(message).data(data).build();
    }

    private static ApiResponse<TrackingResponseDTO> failure(HttpStatus status, String message) {
        return ApiResponse.<TrackingResponseDTO>builder()
                .status("error").code(status.value())
                .errorCode(ErrorCode.VALIDATION_ERROR.name())
                .message(message).data(null).build();
    }

    /** Cached tracking response with an absolute expiry timestamp. */
    private record CacheEntry(TrackingResponseDTO dto, Instant expiresAt) {
        CacheEntry(TrackingResponseDTO dto, Duration ttl) {
            this(dto, Instant.now().plus(ttl));
        }
        boolean isExpired() { return Instant.now().isAfter(expiresAt); }
        long ageSeconds() {
            long total = Duration.between(Instant.now(), expiresAt).getSeconds();
            long ttlSeconds = CACHE_TTL_ACTIVE.getSeconds();
            return Math.max(0, ttlSeconds - total);
        }
    }
}
