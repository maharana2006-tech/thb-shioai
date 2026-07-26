package com.multiship.backend.service;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.RateShopRequestDTO;
import com.multiship.backend.dto.RateShopResponseDTO;
import com.multiship.backend.dto.RateShopResponseDTO.CarrierRateStatus;
import com.multiship.backend.dto.RateShopResponseDTO.RateOptionDTO;
import com.multiship.backend.dto.ShipmentRequestDTO;
import com.multiship.backend.model.CarrierAccountRef;
import com.multiship.backend.repository.CarrierAccountRefRepository;
import com.multiship.backend.service.carriers.CarrierConnector;
import com.multiship.backend.service.fx.FxRateService;
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
    private final ExecutorService executor;

    @Autowired
    public RateShopServiceImpl(CarrierService carrierService,
                                CarrierAccountRefRepository carrierAccountRefRepository,
                                FxRateService fxRateService) {
        this.carrierService = carrierService;
        this.carrierAccountRefRepository = carrierAccountRefRepository;
        this.fxRateService = fxRateService;
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
     *  (e.g. a synchronous same-thread runner) and FX service (usually an
     *  inert stub that returns empty for every conversion). */
    RateShopServiceImpl(CarrierService carrierService,
                        CarrierAccountRefRepository carrierAccountRefRepository,
                        FxRateService fxRateService,
                        ExecutorService executor) {
        this.carrierService = carrierService;
        this.carrierAccountRefRepository = carrierAccountRefRepository;
        this.fxRateService = fxRateService;
        this.executor = executor;
    }

    @Override
    public ApiResponse<RateShopResponseDTO> rateShop(RateShopRequestDTO request) {
        if (request == null || request.getShipment() == null) {
            return failure(HttpStatus.BAD_REQUEST, "shipment is required.");
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

        return success(mergeResults(results), summary(results));
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

        CarrierAccountRef account = resolveAccount(carrierCode, customerNo);
        if (account == null || !StringUtils.hasText(account.getClientId())
                || !StringUtils.hasText(account.getClientSecret())) {
            return new CarrierFanoutResult(carrierCode, List.of(), "STUB",
                    "no credentials configured for " + carrierCode);
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

        // Overlay the account number for the rate call — connectors use it
        // to look up negotiated pricing, and the incoming shipment might
        // not carry the credentials owner's account number.
        ShipmentRequestDTO scoped = withAccountNumber(shipment, account.getAccountNumber());

        try {
            List<CarrierConnector.RateOption> raw = connector.getRates(scoped, accessToken);
            if (raw == null) raw = List.of();
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
            List<CarrierAccountRef> ownedDefaults = carrierAccountRefRepository
                    .findByCustomerNoIgnoreCaseAndClientDefaultTrue(customerNo);
            for (CarrierAccountRef ref : ownedDefaults) {
                if (carrierCode.equalsIgnoreCase(ref.getCarrierCode())
                        && StringUtils.hasText(ref.getClientId())
                        && StringUtils.hasText(ref.getClientSecret())) {
                    return ref;
                }
            }
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
