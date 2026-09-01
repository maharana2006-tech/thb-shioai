package com.multiship.backend.service;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.RateShopRequestDTO;
import com.multiship.backend.dto.RateShopResponseDTO;
import com.multiship.backend.dto.RateShopResponseDTO.CarrierRateStatus;
import com.multiship.backend.dto.RateShopResponseDTO.RateOptionDTO;
import com.multiship.backend.dto.RoutingEvaluationRequest;
import com.multiship.backend.dto.RoutingEvaluationResult;
import com.multiship.backend.dto.ShipmentRequestDTO;
import com.multiship.backend.model.CarrierAccountRef;
import com.multiship.backend.model.RoutingRule.ActionType;
import com.multiship.backend.model.ShippingService;
import com.multiship.backend.repository.CarrierAccountRefRepository;
import com.multiship.backend.repository.ShippingServiceRepository;
import com.multiship.backend.service.carriers.CarrierConnector;
import com.multiship.backend.service.fx.FxRateService;
import com.multiship.backend.util.UnitConverter;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * Fan-out implementation of {@link RateShopService}. Runs the four
 * connector rate calls IN PARALLEL — carrier RTTs are usually 1-2 s each,
 * so a serial fan-out would take 4-8 s while parallel finishes in the
 * slowest carrier's time.
 *
 * <p>Credential resolution reuses the {@link TrackingServiceImpl}
 * precedent: prefer the customer's own account, fall back to the platform
 * (house) account. Missing credentials → STUB entry with an empty option
 * list; connector exception → ERROR entry.
 *
 * <p>Sort: cheapest first by {@code totalAmount}. Currency-mixed sort is a
 * known caveat — see {@link #compareRateOptions}. Follow-up sprint will
 * normalise via {@code FxRateService} so cross-currency options sort
 * correctly.
 */
@Slf4j
@Service
public class RateShopServiceImpl implements RateShopService {

    /**
     * Longest we'll wait for any one connector before giving up on it and
     * marking it ERROR. Bounded so a hung carrier can't block the whole
     * response.
     */
    private static final long PER_CARRIER_TIMEOUT_SECONDS = 15;

    /** Every carrier this instance knows about, in the order they appear
     *  in the UI matrix (UPS first because it's the most-common tenant
     *  choice on this platform). */
    static final List<String> CARRIER_ORDER = List.of("UPS", "FEDEX", "USPS", "DHL");

    /** Sort currency — every priced option is USD-normalised through
     *  {@link FxRateService} before the comparator runs, so the merged list
     *  is truly cheapest-first regardless of what each carrier quoted in. */
    private static final String SORT_CURRENCY = "USD";

    private final CarrierService carrierService;
    private final CarrierAccountRefRepository carrierAccountRefRepository;
    private final FxRateService fxRateService;
    private final RateCacheService rateCacheService;
    private final ExecutorService executor;

    /** Sprint 48 B5 — optional so existing tests don't need to plumb it.
     *  When absent (unit tests), the packaging pre-flight is a no-op. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ShippingConfigService shippingConfigService;

    /** Sprint 48 freight/LTL — optional; supplies the per-shipment weight
     *  cap the parcel-eligibility check needs. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private CarrierLimitService carrierLimitService;

    /** Sprint 48 B5 — same kill-switch as CarrierServiceImpl; when false
     *  the rate-shop skips packaging pre-flight and just fires every carrier. */
    @org.springframework.beans.factory.annotation.Value("${packaging.validation-enabled:true}")
    private boolean packagingValidationEnabled;

    /** Sprint 45 — optional so existing tests don't need to plumb it. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.multiship.backend.repository.RateShopHistoryRepository rateShopHistoryRepository;

    /** G4 — optional so existing tests don't need to plumb routing.
     *  When absent, rate-shop options aren't tagged with routing outcome. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private RoutingRuleService routingRuleService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ShippingServiceRepository shippingServiceRepository;

    /**
     * Sprint 50 Tier 0.5 PR H - clamp caller-supplied {@code customerNo} so
     * a scoped USER cannot rate-shop through another tenant's default
     * carrier account (which would reveal that tenant's negotiated pricing).
     * Optional so pre-PR-H tests that build the service directly still
     * compile without plumbing another dep.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private TenantScopeEnforcer tenantScope;

    @Autowired
    public RateShopServiceImpl(CarrierService carrierService,
                                CarrierAccountRefRepository carrierAccountRefRepository,
                                FxRateService fxRateService,
                                RateCacheService rateCacheService) {
        this.carrierService = carrierService;
        this.carrierAccountRefRepository = carrierAccountRefRepository;
        this.fxRateService = fxRateService;
        this.rateCacheService = rateCacheService;
        // Bounded pool — fan-out is at most CARRIER_ORDER.size() calls, so
        // 8 threads is comfortably enough to run every carrier concurrently
        // for a couple of overlapping requests.
        this.executor = Executors.newFixedThreadPool(8, r -> {
            Thread t = new Thread(r, "rate-shop-fanout");
            t.setDaemon(true);
            return t;
        });
    }

    /** Package-private constructor for tests — inject a custom executor
     *  (e.g. a synchronous same-thread runner), FX service (usually an
     *  inert stub), and RateCacheService (usually an inert no-op stub). */
    RateShopServiceImpl(CarrierService carrierService,
                        CarrierAccountRefRepository carrierAccountRefRepository,
                        FxRateService fxRateService,
                        RateCacheService rateCacheService,
                        ExecutorService executor) {
        this.carrierService = carrierService;
        this.carrierAccountRefRepository = carrierAccountRefRepository;
        this.fxRateService = fxRateService;
        this.rateCacheService = rateCacheService;
        this.executor = executor;
    }

    /**
     * Sprint 51 BP-M2 — drain the fan-out pool on shutdown so an in-flight
     * rate-shop (four parallel carrier calls) has up to 30s to finish
     * before the JVM exits.
     */
    @PreDestroy
    void shutdownExecutor() {
        if (executor == null) return;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                log.warn("rate-shop-fanout did not drain within 30s — forcing shutdownNow()");
                executor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    @Override
    public ApiResponse<RateShopResponseDTO> rateShop(RateShopRequestDTO request) {
        if (request == null || request.getShipment() == null) {
            return failure(HttpStatus.BAD_REQUEST, "shipment is required.");
        }

        // Sprint 50 Tier 0.5 PR H - clamp caller-supplied customerNo BEFORE
        // it's used to resolve a carrier account. A scoped USER posting
        // {customerNo: OTHER} would otherwise rate-shop through OTHER's
        // default account and see their negotiated pricing. Clamping here
        // also covers the routing-preview branch (enrichWithRoutingPreview
        // reads request.getCustomerNo() later in this same call).
        if (tenantScope != null) {
            request.setCustomerNo(tenantScope.clampClientCode(request.getCustomerNo()));
        }

        List<String> carriers = resolveCarriers(request.getCarriers());
        if (carriers.isEmpty()) {
            return failure(HttpStatus.BAD_REQUEST,
                    "No carriers to shop against (whitelist is empty and no defaults are configured).");
        }

        List<CompletableFuture<CarrierFanoutResult>> futures = carriers.stream()
                .map(carrier -> CompletableFuture.supplyAsync(
                        () -> quoteOne(carrier, request.getShipment(), request.getCustomerNo()),
                        executor))
                .toList();

        List<CarrierFanoutResult> results = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            String carrier = carriers.get(i);
            CompletableFuture<CarrierFanoutResult> f = futures.get(i);
            try {
                results.add(f.get(PER_CARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            } catch (TimeoutException ex) {
                f.cancel(true);
                log.warn("Rate shop {} timed out after {}s", carrier, PER_CARRIER_TIMEOUT_SECONDS);
                results.add(new CarrierFanoutResult(carrier, List.of(),
                        "ERROR", carrier + " timed out after " + PER_CARRIER_TIMEOUT_SECONDS + "s"));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                results.add(new CarrierFanoutResult(carrier, List.of(),
                        "ERROR", carrier + " fan-out was interrupted"));
            } catch (ExecutionException ex) {
                results.add(new CarrierFanoutResult(carrier, List.of(),
                        "ERROR", carrier + " rate call failed: "
                                + rootCauseMessage(ex.getCause())));
            }
        }

        RateShopResponseDTO merged = mergeResults(results);

        // G4 — enrich each option with the internal serviceId and the
        // routing-engine preview. Best-effort: any resolution failure just
        // leaves the option untagged rather than failing the whole rate shop.
        enrichWithRoutingPreview(merged, request);

        // Sprint 45 — best-effort history persistence for the reporting
        // pipeline. Never fails the rate shop itself.
        if (rateShopHistoryRepository != null) {
            try {
                persistHistory(request, results, merged);
            } catch (Exception ignored) {
                // swallow: rate-shop success is more important than history.
            }
        }

        return success(merged, summary(results));
    }

    private void persistHistory(RateShopRequestDTO request,
                                 List<CarrierFanoutResult> results,
                                 RateShopResponseDTO merged) {
        com.multiship.backend.model.RateShopHistory row =
                new com.multiship.backend.model.RateShopHistory();
        row.setCustomerNo(request.getCustomerNo());
        ShipmentRequestDTO ship = request.getShipment();
        if (ship != null) {
            row.setDestCountry(ship.getRecipientCountryCode());
            row.setWeight(ship.getWeight());
            row.setWeightUnit(ship.getWeightUnit());
        }
        row.setCarriersQueried(results.size());
        row.setCarriersReturned((int) results.stream()
                .filter(r -> !r.options().isEmpty()).count());
        if (merged != null && merged.getOptions() != null && !merged.getOptions().isEmpty()) {
            RateOptionDTO cheapest = merged.getOptions().get(0);
            row.setCheapestCarrier(cheapest.getCarrierCode());
            row.setCheapestService(cheapest.getServiceCode());
            row.setCheapestAmount(cheapest.getTotalAmount());
            row.setCheapestCurrency(cheapest.getCurrency());
        }
        row.setCreatedAt(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
        rateShopHistoryRepository.save(row);
    }

    /**
     * Fan-out for one carrier: resolve credentials, get a token, call
     * {@code getRates}. Never throws — every failure path returns a
     * {@link CarrierFanoutResult} with the reason so the caller can surface
     * it in the per-carrier status list.
     */
    CarrierFanoutResult quoteOne(String carrierCode, ShipmentRequestDTO shipment, String customerNo) {
        CarrierConnector connector;
        try {
            connector = carrierService.getCarrierConnector(carrierCode);
        } catch (Exception ex) {
            return new CarrierFanoutResult(carrierCode, List.of(), "ERROR",
                    carrierCode + " is not configured on this instance");
        }

        // Resolve the billing account BEFORE the cache lookup (one cheap repo
        // read, no network I/O) so the cache key carries the account whose
        // negotiated pricing the quote reflects. Keying on the bare lane let
        // tenant A's negotiated rates be served verbatim to tenant B within
        // TTL — the exact leak the customerNo clamp upstream exists to stop.
        CarrierAccountRef account = resolveAccount(carrierCode, customerNo);
        if (account == null || !StringUtils.hasText(account.getClientId())
                || !StringUtils.hasText(account.getClientSecret())) {
            return new CarrierFanoutResult(carrierCode, List.of(), "STUB",
                    "no credentials configured for " + carrierCode);
        }
        // Overlay the account number now — it scopes both the cache key and
        // the carrier rate call (connectors use it for negotiated pricing).
        ShipmentRequestDTO scoped = withAccountNumber(shipment, account.getAccountNumber());

        // Sprint 39 — cache lookup before token acquisition / network I/O. On
        // hit we return LIVE options tagged CACHE for the "cached Xs ago" badge.
        java.util.Optional<RateCacheService.CachedRates> cached =
                rateCacheService.lookup(carrierCode, scoped);
        if (cached.isPresent()) {
            long seconds = java.time.Duration.between(
                    cached.get().cachedAt(),
                    java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)).getSeconds();
            return new CarrierFanoutResult(carrierCode, cached.get().options(), "CACHE",
                    cached.get().options().size() + " option"
                            + (cached.get().options().size() == 1 ? "" : "s")
                            + " from " + carrierCode + " (cached " + seconds + "s ago)");
        }

        String accessToken;
        try {
            accessToken = connector.getAccessToken(
                    account.getClientId(), account.getClientSecret(),
                    account.getAccountNumber(), account.getEnvironment());
        } catch (Exception ex) {
            log.warn("Rate shop token acquisition for {} failed: {}",
                    carrierCode, ex.getMessage());
            return new CarrierFanoutResult(carrierCode, List.of(), "ERROR",
                    carrierCode + " token acquisition failed: " + ex.getMessage());
        }

        // Sprint 48 B5 — packaging pre-flight before the carrier call.
        // Resolves the same preset a real label-generation would pick for
        // this shipment. Any HARD violation → skip this carrier and record
        // the reason so the response tells ops WHY that carrier is missing
        // ("FEDEX skipped: Box 3: Weight 20 lb exceeds FedEx Envelope
        // limit of 1 lb"). Other carriers still get rate-shopped.
        if (packagingValidationEnabled && shippingConfigService != null) {
            com.multiship.backend.model.PackagePreset preset = shippingConfigService
                    .pickPackage(null, scoped.getWeight())
                    .map(ShippingConfigService.PickedPackage::preset)
                    .orElse(null);
            com.multiship.backend.util.PackagingValidator.Outcome outcome =
                    preset == null
                            ? new com.multiship.backend.util.PackagingValidator.Outcome(List.of())
                            : com.multiship.backend.util.PackagingValidator.validateAll(
                                    preset,
                                    scoped.getWeight(), scoped.getWeightUnit(),
                                    scoped.getLength(), scoped.getWidth(),
                                    scoped.getHeight(), scoped.getDimUnit(),
                                    scoped.getPackages());
            // Sprint 48 freight/LTL: parcel-eligibility for THIS carrier.
            // Skips the carrier if a piece exceeds the parcel service caps
            // or the shipment total exceeds the carrier per-shipment cap.
            String svcCode = scoped.getServiceType();
            boolean intl = scoped.getRecipientCountryCode() != null
                    && scoped.getShipperCountryCode() != null
                    && !scoped.getRecipientCountryCode().trim().equalsIgnoreCase(
                            scoped.getShipperCountryCode().trim());
            com.multiship.backend.model.CarrierShippingLimit svcLimit = carrierLimitService == null
                    ? null : carrierLimitService.resolveLimit(carrierCode, svcCode, intl);
            outcome = outcome.merge(com.multiship.backend.util.PackagingValidator.validateParcelLimits(
                    carrierCode, svcCode, scoped.getPackages(),
                    scoped.getWeight(), scoped.getWeightUnit(),
                    scoped.getLength(), scoped.getWidth(), scoped.getHeight(), scoped.getDimUnit(),
                    svcLimit == null ? null : svcLimit.getMaxTotalWeightLb()));
            if (outcome.hardBlock()) {
                return new CarrierFanoutResult(carrierCode, List.of(), "SKIPPED_PACKAGING",
                        carrierCode + " skipped: " + outcome.combinedMessage());
            }
        }

        try {
            List<CarrierConnector.RateOption> raw = connector.getRates(scoped, accessToken, account.getEnvironment());
            if (raw == null) raw = List.of();
            // Sprint 39 — cache non-empty results. Empty lists skip the
            // cache so a transient miss retries the carrier next time.
            if (!raw.isEmpty()) {
                // Store under the ACCOUNT-scoped envelope so the cached entry
                // is keyed to the tenant/account whose pricing it reflects.
                rateCacheService.store(carrierCode, scoped, raw);
            }
            return new CarrierFanoutResult(carrierCode, raw,
                    raw.isEmpty() ? "STUB" : "LIVE",
                    raw.isEmpty()
                            ? carrierCode + " returned no rates for this lane"
                            : raw.size() + " option" + (raw.size() == 1 ? "" : "s") + " from " + carrierCode);
        } catch (Exception ex) {
            log.warn("Rate shop {} call failed: {}", carrierCode, ex.getMessage());
            return new CarrierFanoutResult(carrierCode, List.of(), "ERROR",
                    carrierCode + " rate call failed: " + ex.getMessage());
        }
    }

    /**
     * Resolve a credentials row for {@code carrierCode}. Prefers the
     * customer's default carrier account when a customer number is
     * supplied; falls back to any active platform (house) account.
     * Matches the TrackingServiceImpl fallback chain.
     */
    CarrierAccountRef resolveAccount(String carrierCode, String customerNo) {
        if (StringUtils.hasText(customerNo)) {
            // Canonical cascade: default-flagged first, then the client's sole
            // account on this carrier, active rows only. Previously a client
            // whose only account wasn't flagged default was quoted HOUSE rates
            // instead of their own negotiated rates, and a deactivated default
            // still matched (the default query has no active filter).
            List<CarrierAccountRef> usable = carrierAccountRefRepository
                    .findByCustomerNoIgnoreCaseOrderByClientDefaultDescUpdatedAtDesc(customerNo.trim())
                    .stream()
                    .filter(a -> !Boolean.FALSE.equals(a.getActive()))
                    .filter(a -> carrierCode.equalsIgnoreCase(
                            a.getCarrierCode() == null ? "" : a.getCarrierCode().trim()))
                    .filter(a -> StringUtils.hasText(a.getClientId())
                            && StringUtils.hasText(a.getClientSecret()))
                    .toList();
            for (CarrierAccountRef ref : usable) {
                if (Boolean.TRUE.equals(ref.getClientDefault())) return ref;
            }
            if (usable.size() == 1) return usable.get(0);
        }
        List<CarrierAccountRef> platform = carrierAccountRefRepository
                .findPlatformAccountsByCarrier(carrierCode);
        return platform.isEmpty() ? null : platform.get(0);
    }

    /** Return a shallow copy of {@code shipment} with {@code accountNumber}
     *  overlaid — connectors rate against the account whose credentials
     *  we're using, not the account in the incoming envelope. */
    private static ShipmentRequestDTO withAccountNumber(ShipmentRequestDTO shipment, String accountNumber) {
        ShipmentRequestDTO out = new ShipmentRequestDTO();
        out.setCarrierCode(shipment.getCarrierCode());
        out.setAccountNumber(accountNumber);
        out.setServiceType(shipment.getServiceType());
        out.setPackageType(shipment.getPackageType());
        out.setWeight(shipment.getWeight());
        out.setLength(shipment.getLength());
        out.setWidth(shipment.getWidth());
        out.setHeight(shipment.getHeight());
        out.setShipperName(shipment.getShipperName());
        out.setShipperPhone(shipment.getShipperPhone());
        out.setShipperAddressLine1(shipment.getShipperAddressLine1());
        out.setShipperAddressLine2(shipment.getShipperAddressLine2());
        out.setShipperCity(shipment.getShipperCity());
        out.setShipperState(shipment.getShipperState());
        out.setShipperPostalCode(shipment.getShipperPostalCode());
        out.setShipperCountryCode(shipment.getShipperCountryCode());
        out.setRecipientName(shipment.getRecipientName());
        out.setRecipientPhone(shipment.getRecipientPhone());
        out.setRecipientAddressLine1(shipment.getRecipientAddressLine1());
        out.setRecipientAddressLine2(shipment.getRecipientAddressLine2());
        out.setRecipientAddressLine3(shipment.getRecipientAddressLine3());
        out.setRecipientCity(shipment.getRecipientCity());
        out.setRecipientState(shipment.getRecipientState());
        out.setRecipientPostalCode(shipment.getRecipientPostalCode());
        out.setRecipientCountryCode(shipment.getRecipientCountryCode());
        out.setReferenceNumber(shipment.getReferenceNumber());
        out.setSpecialInstructions(shipment.getSpecialInstructions());
        out.setDeclaredValue(shipment.getDeclaredValue());
        out.setDeclaredValueCurrency(shipment.getDeclaredValueCurrency());
        out.setWeightUnit(shipment.getWeightUnit());
        out.setDimUnit(shipment.getDimUnit());
        out.setIntl(shipment.getIntl());
        out.setRecipientResidential(shipment.getRecipientResidential());
        out.setRecipientPhoneCountryCode(shipment.getRecipientPhoneCountryCode());
        return out;
    }

    /**
     * Normalise + de-dup the requested carriers list. Null / empty →
     * default {@link #CARRIER_ORDER}. Everything upper-cased so the
     * connector lookup and account query match.
     */
    List<String> resolveCarriers(List<String> requested) {
        if (requested == null || requested.isEmpty()) return CARRIER_ORDER;
        Set<String> valid = Set.copyOf(CARRIER_ORDER);
        return requested.stream()
                .filter(StringUtils::hasText)
                .map(c -> c.trim().toUpperCase(Locale.ROOT))
                .distinct()
                .filter(valid::contains)
                .collect(Collectors.toList());
    }

    /**
     * Merge per-carrier fan-out results into the wire response — sorted
     * options list + per-carrier status list.
     */
    private RateShopResponseDTO mergeResults(List<CarrierFanoutResult> results) {
        List<RateOptionDTO> options = results.stream()
                .flatMap(r -> r.options().stream().map(RateShopServiceImpl::toDto))
                .sorted(this::compareRateOptions)
                .collect(Collectors.toList());
        List<CarrierRateStatus> statuses = results.stream()
                .map(r -> CarrierRateStatus.builder()
                        .carrierCode(r.carrierCode())
                        .optionCount(r.options().size())
                        .source(r.source())
                        .message(r.message())
                        .build())
                .collect(Collectors.toList());
        return RateShopResponseDTO.builder()
                .options(options)
                .carrierResults(statuses)
                .build();
    }

    /**
     * G4 — for each merged option, resolve the internal ShippingService.id
     * from (carrier, serviceCode) and ask the routing engine what would
     * happen at label time. Silent no-op when the caller didn't scope by
     * client, or when the routing service isn't wired.
     */
    private void enrichWithRoutingPreview(RateShopResponseDTO merged, RateShopRequestDTO request) {
        if (merged == null || merged.getOptions() == null || merged.getOptions().isEmpty()) return;
        if (routingRuleService == null || shippingServiceRepository == null) return;
        String clientCode = request == null ? null : request.getCustomerNo();
        if (clientCode == null || clientCode.isBlank()) return;

        ShipmentRequestDTO shipment = request.getShipment();
        BigDecimal weightLb = shipment == null ? null
                : UnitConverter.toPounds(shipment.getWeight(), shipment.getWeightUnit());
        String destCountry = shipment == null ? null : shipment.getRecipientCountryCode();
        String destRegion = destCountry == null ? null
                : com.multiship.backend.util.CountryRegions.regionOf(destCountry);
        BigDecimal declaredValue = shipment == null ? null : shipment.getDeclaredValue();

        for (RateOptionDTO option : merged.getOptions()) {
            Long serviceId = resolveServiceId(option.getCarrierCode(), option.getServiceCode());
            option.setServiceId(serviceId);
            if (serviceId == null) continue; // can't ask the engine without an id

            RoutingEvaluationRequest evalReq = RoutingEvaluationRequest.builder()
                    .weightLb(weightLb)
                    .destCountry(destCountry)
                    .destRegion(destRegion)
                    .currentCarrier(option.getCarrierCode())
                    .currentServiceId(serviceId)
                    // Rate-shop has no resolved warehouse — null skips warehouse-scoped rules.
                    .currentWarehouseId(null)
                    .declaredValue(declaredValue)
                    .orderSource(null)
                    .build();
            RoutingEvaluationResult result = safeEvaluate(clientCode, evalReq);
            if (result == null || !"MATCH".equals(result.getStatus())) {
                option.setRoutingOutcome("KEEP");
                continue;
            }
            option.setRoutingRuleName(result.getMatchedRuleName());
            if (result.getActionType() == ActionType.BLOCK) {
                option.setRoutingOutcome("BLOCK");
                option.setRoutingBlockReason(result.getBlockReason());
            } else if (result.getActionType() == ActionType.REROUTE) {
                option.setRoutingOutcome("REROUTE");
                option.setRoutingTargetCarrier(result.getTargetCarrier());
                option.setRoutingTargetWarehouseId(result.getTargetWarehouseId());
                if (result.getTargetServiceId() != null) {
                    ShippingService target = shippingServiceRepository
                            .findById(result.getTargetServiceId()).orElse(null);
                    if (target != null) {
                        option.setRoutingTargetServiceCode(target.getServiceCode());
                        if (option.getRoutingTargetCarrier() == null) {
                            option.setRoutingTargetCarrier(target.getCarrier());
                        }
                    }
                }
            }
        }
    }

    private Long resolveServiceId(String carrier, String serviceCode) {
        if (shippingServiceRepository == null) return null;
        if (carrier == null || carrier.isBlank() || serviceCode == null || serviceCode.isBlank()) return null;
        return shippingServiceRepository
                .findByCarrierIgnoreCaseAndServiceCodeIgnoreCase(carrier, serviceCode)
                .map(ShippingService::getId)
                .orElse(null);
    }

    private RoutingEvaluationResult safeEvaluate(String clientCode, RoutingEvaluationRequest req) {
        try {
            return routingRuleService.evaluate(clientCode, req);
        } catch (Exception ex) {
            // A rate-shop preview must never fail the whole call — swallow
            // any evaluator exception and leave the option untagged.
            log.debug("Routing preview failed for client={}: {}", clientCode, ex.getMessage());
            return null;
        }
    }

    /**
     * Sort by USD-normalised {@code totalAmount} ascending — cheapest first
     * regardless of the native currency each carrier quoted in. Nulls sink
     * to the bottom (a null price shouldn't be highlighted as the deal).
     *
     * <p>Normalisation goes through {@link FxRateService}; when the rate
     * is unavailable (unsupported currency, feed outage, transient failure)
     * we fall through to the RAW amount so the option still sorts against
     * anything else at its face value. This is slightly wrong when mixed
     * currencies land in the fallback path, but the option still shows the
     * carrier's native currency + amount so operators can spot the mix and
     * decide manually.
     *
     * <p>The DTO's {@code totalAmount} + {@code currency} are the carrier's
     * native values — the USD conversion is used ONLY for the sort key,
     * not surfaced back to the caller.
     */
    int compareRateOptions(RateOptionDTO a, RateOptionDTO b) {
        BigDecimal aKey = sortKey(a);
        BigDecimal bKey = sortKey(b);
        if (aKey == null && bKey == null) return 0;
        if (aKey == null) return 1;
        if (bKey == null) return -1;
        return aKey.compareTo(bKey);
    }

    /**
     * USD-normalised sort key for one option. USD passes through as-is; any
     * other currency is converted via the FX service. When conversion isn't
     * possible (blank currency, feed outage) we return the raw amount as
     * the fallback — see {@link #compareRateOptions} for the trade-off.
     */
    private BigDecimal sortKey(RateOptionDTO option) {
        BigDecimal amount = option.getTotalAmount();
        if (amount == null) return null;
        String currency = option.getCurrency();
        if (!StringUtils.hasText(currency) || SORT_CURRENCY.equalsIgnoreCase(currency)) {
            return amount;
        }
        Optional<BigDecimal> converted = fxRateService.convert(amount, currency, SORT_CURRENCY);
        return converted.orElse(amount);
    }

    private static RateOptionDTO toDto(CarrierConnector.RateOption r) {
        return RateOptionDTO.builder()
                .carrierCode(r.carrierCode())
                .serviceCode(r.serviceCode())
                .serviceName(r.serviceName())
                .totalAmount(r.totalAmount())
                .currency(r.currency())
                .estimatedDelivery(r.estimatedDelivery())
                .transitDays(r.transitDays())
                .build();
    }

    private static String summary(List<CarrierFanoutResult> results) {
        long live = results.stream().filter(r -> "LIVE".equals(r.source())).count();
        int total = results.stream().mapToInt(r -> r.options().size()).sum();
        return total + " option" + (total == 1 ? "" : "s") + " from "
                + live + " carrier" + (live == 1 ? "" : "s");
    }

    private static String rootCauseMessage(Throwable t) {
        if (t == null) return "unknown";
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
        return cur.getMessage() == null ? cur.getClass().getSimpleName() : cur.getMessage();
    }

    private static ApiResponse<RateShopResponseDTO> success(RateShopResponseDTO data, String message) {
        return ApiResponse.<RateShopResponseDTO>builder()
                .status("success").code(200).message(message).data(data).build();
    }

    private static ApiResponse<RateShopResponseDTO> failure(HttpStatus status, String message) {
        return ApiResponse.<RateShopResponseDTO>builder()
                .status("error").code(status.value())
                .errorCode(ErrorCode.VALIDATION_ERROR.name())
                .message(message).data(null).build();
    }

    /** Internal transport shape — one carrier's fan-out output. */
    record CarrierFanoutResult(String carrierCode,
                                List<CarrierConnector.RateOption> options,
                                String source,
                                String message) {}
}
