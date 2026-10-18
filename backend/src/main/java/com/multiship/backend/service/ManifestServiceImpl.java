package com.multiship.backend.service;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.EodEventDTO;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.ManifestRequestDTO;
import com.multiship.backend.dto.ManifestResponseDTO;
import com.multiship.backend.dto.ManifestResponseDTO.ManifestEntryDTO;
import com.multiship.backend.model.CarrierAccountRef;
import com.multiship.backend.model.Order;
import com.multiship.backend.model.OrderTracking;
import com.multiship.backend.model.ShippingService;
import com.multiship.backend.repository.CarrierAccountRefRepository;
import com.multiship.backend.repository.CarrierEodLogRepository;
import com.multiship.backend.repository.ClientShipviaCodeMapRepository;
import com.multiship.backend.repository.OrderRepository;
import com.multiship.backend.repository.OrderTrackingRepository;
import com.multiship.backend.repository.ShippingServiceRepository;
import com.multiship.backend.service.carriers.CarrierConnector;
import com.multiship.backend.service.carriers.CarrierConnector.AddressToValidate;
import com.multiship.backend.service.carriers.CarrierConnector.CloseOutRequest;
import com.multiship.backend.service.carriers.CarrierConnector.CloseOutResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Sprint 34 impl. Credential resolution mirrors {@link VoidServiceImpl}.
 * Never throws — carrier failures come back as {@code status=ERROR}.
 *
 * <p>FDX-G2 — classifies each tracking by driver fleet (Ground vs Express)
 * via the shipping-service-mapping chain, then calls the connector's
 * {@code closeOutDay} once per fleet group so FedEx Express labels don't
 * silently manifest-as-Ground (the pre-fix hardcode). Non-FedEx carriers
 * don't have a fleet-selector at manifest time so the split has no effect
 * on their body — one group per fleet is still called, so a mixed batch
 * on UPS produces 2 identical-shape calls. In practice UPS operators
 * typically batch by fleet already so this is rare.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ManifestServiceImpl implements ManifestService {

    private final CarrierService carrierService;
    private final CarrierAccountRefRepository carrierAccountRefRepository;
    /**
     * Sprint 50 Tier 0.5 PR E - clamp customerNo to the caller's tenant so
     * a scoped USER cannot close out a foreign tenant's manifest.
     */
    private final TenantScopeEnforcer tenantScope;
    // FDX-G2 — classification chain dependencies. Each tracking is looked up
    // through OrderTracking → Order → shipviaCd → ClientShipviaCodeMap
    // (specificity-ordered: per-client > platform-wide) → ShippingService.is_express.
    private final OrderTrackingRepository orderTrackingRepository;
    private final OrderRepository orderRepository;
    private final ClientShipviaCodeMapRepository clientShipviaCodeMapRepository;
    private final ShippingServiceRepository shippingServiceRepository;
    // V135 — audit log for pickup/close events (repo for reads, logger for writes).
    private final CarrierEodLogRepository carrierEodLogRepository;
    private final CarrierEodLogger eodLogger;

    @Override
    public ApiResponse<ManifestResponseDTO> closeOut(ManifestRequestDTO request) {
        ApiResponse<ManifestResponseDTO> resp = doCloseOut(request);
        recordCloseOutLog(request, resp, "MANUAL");
        return resp;
    }

    private ApiResponse<ManifestResponseDTO> doCloseOut(ManifestRequestDTO request) {
        if (request == null) {
            return failure(HttpStatus.BAD_REQUEST, "Request body is required.");
        }
        if (!StringUtils.hasText(request.getCarrierCode())) {
            return failure(HttpStatus.BAD_REQUEST, "carrierCode is required.");
        }
        if (request.getTrackingNumbers() == null || request.getTrackingNumbers().isEmpty()) {
            return failure(HttpStatus.BAD_REQUEST,
                    "At least one tracking number is required to close out a day.");
        }
        // Sprint 50 Tier 0.5 PR E - clamp caller-supplied customerNo to
        // caller's own tenant. Platform operators pass through unchanged.
        request.setCustomerNo(tenantScope.clampClientCode(request.getCustomerNo()));
        String carrier = request.getCarrierCode().trim().toUpperCase(Locale.ROOT);

        CarrierConnector connector;
        try {
            connector = carrierService.getCarrierConnector(carrier);
        } catch (Exception ex) {
            return failure(HttpStatus.UNPROCESSABLE_CONTENT,
                    "Carrier " + carrier + " isn't configured on this instance.");
        }

        // Dead-label gate — a VOIDED (or errored, never-generated) tracking is
        // cancelled at the carrier and must never reach a driver's manifest.
        // The frontend prefilter can drift; this is the hard guard.
        // BUG-019 — say plainly which numbers aren't this carrier's open labels,
        // instead of letting them fail later as "no live credentials" or an
        // internal "failedToClassify".
        List<String> unknown = new ArrayList<>();
        List<String> otherCarrier = new ArrayList<>();
        List<String> alreadyClosed = new ArrayList<>();
        // Each number is resolved once and reused by the FedEx fleet split below.
        java.util.Map<String, Optional<TrackingResolution>> resolved = new java.util.HashMap<>();
        for (String t : request.getTrackingNumbers()) {
            if (!StringUtils.hasText(t)) continue;
            OrderTracking tr = orderTrackingRepository.findFirstByTrackingNumberIgnoreCaseOrderByIdDesc(t).orElse(null);
            if (tr == null) {
                unknown.add(t.trim());
                continue;
            }
            if (tr.getManifestedAt() != null) {
                alreadyClosed.add(t.trim() + (StringUtils.hasText(tr.getManifestId()) ? " (" + tr.getManifestId() + ")" : ""));
            }
            resolved.computeIfAbsent(t, this::resolveForTracking).map(TrackingResolution::carrier)
                    .filter(c -> !carrier.equalsIgnoreCase(c))
                    .ifPresent(c -> otherCarrier.add(t.trim() + " (" + c.toUpperCase(Locale.ROOT) + ")"));
            String st = tr.getStatus() == null ? "" : tr.getStatus().trim().toUpperCase(Locale.ROOT);
            // Explicitly-dead labels only, keyed on STATUS — the generated
            // flag defaults to false on the entity, so it can't distinguish
            // a legacy row from a cancelled one. VOIDED = cancelled at the
            // carrier; ERROR = the label was never bought.
            if ("VOIDED".equals(st) || "ERROR".equals(st)) {
                return failure(HttpStatus.UNPROCESSABLE_CONTENT,
                        "Tracking " + t + " is " + ("VOIDED".equals(st) ? "voided" : "an errored label")
                                + " and cannot be manifested. Remove it and close out again.");
            }
        }
        if (!unknown.isEmpty()) {
            return failure(HttpStatus.UNPROCESSABLE_CONTENT, (unknown.size() == 1
                    ? "Tracking " + unknown.get(0) + " isn't"
                    : "These trackings aren't") + " an open " + carrier + " label in Multiship"
                    + (unknown.size() == 1 ? "" : ": " + String.join(", ", unknown))
                    + ". Check the number, or remove it and close out again.");
        }
        if (!alreadyClosed.isEmpty()) {
            return failure(HttpStatus.UNPROCESSABLE_CONTENT, "Already closed out: "
                    + String.join(", ", alreadyClosed) + ". Remove "
                    + (alreadyClosed.size() == 1 ? "it" : "them") + " and close out again.");
        }
        if (!otherCarrier.isEmpty()) {
            return failure(HttpStatus.UNPROCESSABLE_CONTENT, "Not " + carrier + " labels: "
                    + String.join(", ", otherCarrier) + ". Close those out under their own carrier.");
        }

        // Tenant gate on the SUBMITTED TRACKINGS — the customerNo clamp above
        // only steers credential choice; nothing stopped a scoped USER from
        // listing another tenant's tracking numbers and closing them out. Any
        // foreign tracking now rejects the whole request loudly.
        java.util.Optional<String> callerScope = tenantScope.resolveScope();
        if (callerScope.isPresent()) {
            for (String t : request.getTrackingNumbers()) {
                if (!StringUtils.hasText(t)) continue;
                Integer orderNo = orderTrackingRepository.findFirstByTrackingNumberIgnoreCaseOrderByIdDesc(t)
                        .map(OrderTracking::getOrderNo).orElse(null);
                String owner = orderNo == null ? null : orderRepository.findByOrderNo(orderNo)
                        .map(o -> StringUtils.hasText(o.getTenantId()) ? o.getTenantId() : o.getCustNo())
                        .orElse(null);
                if (owner != null && !owner.trim().equalsIgnoreCase(callerScope.get())) {
                    return failure(HttpStatus.UNPROCESSABLE_CONTENT,
                            "Tracking " + t + " belongs to another client and cannot be "
                                    + "closed out from this account.");
                }
            }
        }

        // Bill the close-out to the account the LABELS were actually generated
        // on. Re-resolving the client default here closed out labels billed to
        // account B under account A (or the house account when no default was
        // flagged) — the recorded account is on each tracking row. Mixed
        // accounts in one request can't share a manifest; split explicitly.
        java.util.Set<String> recordedAccounts = new java.util.LinkedHashSet<>();
        for (String t : request.getTrackingNumbers()) {
            if (!StringUtils.hasText(t)) continue;
            orderTrackingRepository.findFirstByTrackingNumberIgnoreCaseOrderByIdDesc(t)
                    .map(OrderTracking::getAccountNumber)
                    .filter(StringUtils::hasText)
                    .ifPresent(a -> recordedAccounts.add(a.trim()));
        }
        if (recordedAccounts.size() > 1) {
            return failure(HttpStatus.UNPROCESSABLE_CONTENT,
                    "These trackings were billed to " + recordedAccounts.size()
                            + " different accounts (" + String.join(", ", recordedAccounts)
                            + "). Close out one account's shipments at a time.");
        }
        CarrierAccountRef account = null;
        if (recordedAccounts.size() == 1) {
            account = carrierAccountRefRepository
                    .findFirstByAccountNumberIgnoreCaseAndCarrierCodeIgnoreCase(
                            recordedAccounts.iterator().next(), carrier)
                    .orElse(null);
        }
        // Legacy rows with no recorded account (or an account since deleted)
        // keep the old default/platform resolution as a fallback.
        if (account == null) {
            account = resolveAccount(carrier, request.getCustomerNo());
        }
        if (account == null || !StringUtils.hasText(account.getClientId())
                || !StringUtils.hasText(account.getClientSecret())) {
            return respond(singleFleetResponse(new CloseOutResult(carrier, null, null, null,
                    request.getTrackingNumbers().size(), "NOT_SUPPORTED",
                    "No live credentials for " + carrier + " — cannot close out.", null)));
        }

        String accessToken;
        try {
            accessToken = connector.getAccessToken(
                    account.getClientId(), account.getClientSecret(),
                    account.getAccountNumber(), account.getEnvironment());
        } catch (Exception ex) {
            log.warn("Close-out — token acquisition for {} failed: {}",
                    carrier, ex.getMessage());
            return respond(singleFleetResponse(new CloseOutResult(carrier, null, null, null,
                    request.getTrackingNumbers().size(), "ERROR",
                    carrier + " token acquisition failed: " + ex.getMessage(), null)));
        }

        AddressToValidate address = null;
        if (StringUtils.hasText(request.getAddressLine1())) {
            address = new AddressToValidate(
                    request.getAddressName(), null,
                    request.getAddressLine1(),
                    request.getAddressLine2(),
                    null,
                    request.getCity(),
                    request.getState(),
                    request.getPostalCode(),
                    request.getCountryCode());
        }
        LocalDate closeDate = request.getCloseDate() == null ? LocalDate.now() : request.getCloseDate();

        // FDX-G2 — the Ground/Express fleet split only applies to FedEx. Other
        // carriers (USPS SCAN form, UPS end-of-day, DHL) have no fleet selector,
        // and the classification chain can't resolve an express flag for them —
        // which previously dumped every tracking into failedToClassify and
        // manifested nothing (BUG-021). Classify only FedEx; manifest the other
        // carriers' trackings as a single group. Unresolvable FedEx trackings
        // still go into failedToClassify (fail-open, operator re-runs).
        ClassificationResult classify;
        if ("FEDEX".equals(carrier)) {
            classify = classifyTrackings(request.getTrackingNumbers(),
                    t -> resolved.computeIfAbsent(t, this::resolveForTracking));
        } else {
            List<String> all = request.getTrackingNumbers().stream()
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .toList();
            classify = new ClassificationResult(all, List.of(), List.of());
        }

        List<CloseOutResult> results = new ArrayList<>();
        // Preserve group order (GROUND first, EXPRESS second) so multi-fleet
        // responses always render in the same order for the operator.
        if (!classify.groundTrackings.isEmpty()) {
            results.add(markClosed(classify.groundTrackings, callCloseOut(connector, classify.groundTrackings,
                    closeDate, address, account, accessToken, false)));
        }
        if (!classify.expressTrackings.isEmpty()) {
            results.add(markClosed(classify.expressTrackings, callCloseOut(connector, classify.expressTrackings,
                    closeDate, address, account, accessToken, true)));
        }
        // Edge case — every tracking failed to classify. Nothing to submit;
        // return a NOT_SUPPORTED-shaped response so the operator sees the
        // failedToClassify list clearly rather than a confusing zero-manifest.
        if (results.isEmpty()) {
            return respond(ManifestResponseDTO.builder()
                    .carrierCode(carrier)
                    .trackingCount(0)
                    .status("ERROR")
                    .message("No trackings could be classified for fleet-split manifest. "
                            + "See failedToClassify for the offending trackings.")
                    .failedToClassify(nullIfEmpty(classify.failed))
                    .build());
        }

        return respond(buildResponse(carrier, results, classify.failed,
                classify.groundTrackings, classify.expressTrackings));
    }

    // ===== FDX-G2 classification =====

    /**
     * Bundle of the classification pass: two per-fleet lists + the trackings
     * we couldn't classify (missing OrderTracking, no shipvia alias, no
     * ShippingService for the resolved serviceId). Failed trackings are
     * EXCLUDED from the manifest per the user's locked design decision.
     */
    private record ClassificationResult(
            List<String> groundTrackings,
            List<String> expressTrackings,
            List<String> failed) {}

    /**
     * V157 — a manifested group's labels are closed: stamp them so they are
     * no longer offered as open, gathered by Close whole day, or accepted again.
     */
    private CloseOutResult markClosed(List<String> trackings, CloseOutResult result) {
        if (result == null || !"MANIFESTED".equals(result.status())) return result;
        LocalDateTime now = LocalDateTime.now();
        for (String t : trackings) {
            orderTrackingRepository.findFirstByTrackingNumberIgnoreCaseOrderByIdDesc(t).ifPresent(tr -> {
                tr.setManifestedAt(now);
                tr.setManifestId(result.manifestId());
                orderTrackingRepository.save(tr);
            });
        }
        return result;
    }

    ClassificationResult classifyTrackings(List<String> trackings) {
        return classifyTrackings(trackings, this::resolveForTracking);
    }

    private ClassificationResult classifyTrackings(List<String> trackings,
                                                   java.util.function.Function<String, Optional<TrackingResolution>> resolve) {
        List<String> ground = new ArrayList<>();
        List<String> express = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (String t : trackings) {
            if (!StringUtils.hasText(t)) {
                failed.add(t);
                continue;
            }
            Optional<Boolean> expressFlag = resolve.apply(t).map(TrackingResolution::express);
            if (expressFlag.isEmpty()) {
                failed.add(t);
            } else if (expressFlag.get()) {
                express.add(t);
            } else {
                ground.add(t);
            }
        }
        return new ClassificationResult(ground, express, failed);
    }


    /** One tracking's resolved carrier, fleet (express?) and owner (tenant /
     *  cust). Empty on any miss in the chain — callers treat that as
     *  "unclassifiable". V135 generalised the old express-flag lookup. */
    private record TrackingResolution(String carrier, boolean express, String owner) {}

    private Optional<TrackingResolution> resolveForTracking(String trackingNumber) {
        Optional<OrderTracking> tracking = orderTrackingRepository
                .findFirstByTrackingNumberIgnoreCaseOrderByIdDesc(trackingNumber);
        if (tracking.isEmpty() || tracking.get().getOrderNo() == null) return Optional.empty();

        Optional<Order> order = orderRepository.findByOrderNo(tracking.get().getOrderNo());
        if (order.isEmpty()) return Optional.empty();
        String shipviaCd = order.get().getShipviaCd();
        String tenantCode = StringUtils.hasText(order.get().getTenantId())
                ? order.get().getTenantId() : order.get().getCustNo();
        Optional<TrackingResolution> mapped = resolveByShipviaMap(shipviaCd, tenantCode);
        return mapped.isPresent() ? mapped : resolveByBilledAccount(tracking.get(), shipviaCd, tenantCode);
    }

    /**
     * BUG-022 — a manual shipment stores the catalog service code
     * (GROUND_ADVANTAGE) as its ship-via, which no client SHIPVIA map knows, so
     * its label was never gathered. The tracking row records the account the
     * label was billed to: its carrier is the label's carrier, and the service
     * (when it's a catalog code) says whether it's Express.
     */
    private Optional<TrackingResolution> resolveByBilledAccount(OrderTracking tracking, String shipviaCd,
                                                                String tenantCode) {
        if (!StringUtils.hasText(tracking.getAccountNumber())) return Optional.empty();
        return carrierAccountRefRepository.findFirstByAccountNumberIgnoreCaseOrderByUpdatedAtDesc(
                        tracking.getAccountNumber().trim())
                .map(account -> {
                    String carrier = account.getCarrierCode().trim().toUpperCase(Locale.ROOT);
                    boolean express = StringUtils.hasText(shipviaCd)
                            && shippingServiceRepository.findByCarrierIgnoreCaseAndServiceCodeIgnoreCase(
                                    carrier, shipviaCd.trim()).map(svc -> svc.isExpress()).orElse(false);
                    return new TrackingResolution(carrier, express, tenantCode == null ? null : tenantCode.trim());
                });
    }

    private Optional<TrackingResolution> resolveByShipviaMap(String shipviaCd, String tenantCode) {
        if (!StringUtils.hasText(shipviaCd)) return Optional.empty();

        // V126 merge — single specificity-ordered lookup. Per-client rows
        // beat platform-wide (null clientCode) in the finder's ORDER BY.
        List<com.multiship.backend.model.ClientShipviaCodeMap> matches =
                clientShipviaCodeMapRepository.findMatches(
                        tenantCode == null ? null : tenantCode.trim(),
                        shipviaCd.trim(),
                        null, null, null);
        if (matches.isEmpty()) return Optional.empty();
        Long serviceId = matches.get(0).getServiceId();

        return shippingServiceRepository.findById(serviceId)
                .map(svc -> new TrackingResolution(svc.getCarrier(), svc.isExpress(),
                        tenantCode == null ? null : tenantCode.trim()));
    }

    // ===== V135: close out a whole day for a carrier + audit log =====

    @Override
    public ApiResponse<ManifestResponseDTO> closeOutForDay(String carrierCode, String customerNo,
                                                           LocalDate closeDate, String warehouseCode,
                                                           String source) {
        if (!StringUtils.hasText(carrierCode)) {
            return failure(HttpStatus.BAD_REQUEST, "carrierCode is required.");
        }
        String carrier = carrierCode.trim().toUpperCase(Locale.ROOT);
        String client = tenantScope.clampClientCode(customerNo);
        LocalDate date = closeDate == null ? LocalDate.now() : closeDate;
        String wh = StringUtils.hasText(warehouseCode) ? warehouseCode.trim() : null;

        List<String> trackings = gatherTrackings(carrier, client, date, wh);
        if (trackings.isEmpty()) {
            ManifestResponseDTO empty = ManifestResponseDTO.builder()
                    .carrierCode(carrier).trackingCount(0).status("EMPTY")
                    .message("No open " + carrier + " labels to close for " + date + ".")
                    .build();
            logEod("CLOSEOUT", carrier, null, client, wh, date, null, 0, "EMPTY",
                    empty.getMessage(), source);
            return success(empty);
        }
        ManifestRequestDTO dto = ManifestRequestDTO.builder()
                .carrierCode(carrier).customerNo(client)
                .trackingNumbers(trackings).closeDate(date).build();
        ApiResponse<ManifestResponseDTO> resp = doCloseOut(dto);
        recordCloseOutLog(dto, resp, source, wh);
        return resp;
    }

    @Override
    public List<EodEventDTO> recentEvents(int limit) {
        int n = limit <= 0 || limit > 200 ? 50 : limit;
        var page = org.springframework.data.domain.PageRequest.of(0, n);
        // Tenant gate — a scoped USER sees only their own client's events;
        // platform operators (empty scope) still see all. Without this the
        // activity log leaked every tenant's close-out rows (account numbers,
        // customerNo, createdBy). Mirrors closeOut/closeDay's clamp.
        var rows = tenantScope.resolveScope()
                .map(scope -> carrierEodLogRepository.findByCustomerNoIgnoreCaseOrderByCreatedAtDesc(scope, page))
                .orElseGet(() -> carrierEodLogRepository.findAllByOrderByCreatedAtDesc(page));
        return rows.stream().map(EodEventDTO::from).toList();
    }

    /**
     * Gather the day's GENERATED, non-voided tracking numbers whose resolved
     * carrier matches, optionally scoped to one client / warehouse. Carrier is
     * resolved per tracking via the ship-via → ShippingService chain (same as
     * the fleet classifier) since it isn't stored on the tracking row.
     * ponytail: O(n) resolution (3 lookups/tracking) — fine for a daily batch;
     * add a joined query if volume ever makes it slow.
     */
    @Override
    public List<String> openTrackings(String carrierCode, String customerNo, LocalDate closeDate, String warehouseCode) {
        if (!StringUtils.hasText(carrierCode)) return List.of();
        return gatherTrackings(carrierCode.trim().toUpperCase(Locale.ROOT), tenantScope.clampClientCode(customerNo),
                closeDate == null ? LocalDate.now() : closeDate,
                StringUtils.hasText(warehouseCode) ? warehouseCode.trim() : null);
    }

    private List<String> gatherTrackings(String carrier, String customerNo,
                                         LocalDate date, String warehouseCode) {
        LocalDateTime start = date.atStartOfDay();
        LocalDateTime end = date.plusDays(1).atStartOfDay();
        List<OrderTracking> rows = orderTrackingRepository.findGeneratedBetween(start, end);
        List<String> out = new ArrayList<>();
        for (OrderTracking t : rows) {
            if (!StringUtils.hasText(t.getTrackingNumber())) continue;
            if (warehouseCode != null && !warehouseCode.equalsIgnoreCase(t.getWarehouseCode())) continue;
            Optional<TrackingResolution> r = resolveForTracking(t.getTrackingNumber());
            if (r.isEmpty()) continue;
            if (!carrier.equalsIgnoreCase(r.get().carrier())) continue;
            if (StringUtils.hasText(customerNo)
                    && (r.get().owner() == null || !customerNo.trim().equalsIgnoreCase(r.get().owner()))) {
                continue;
            }
            out.add(t.getTrackingNumber());
        }
        return out;
    }

    private void recordCloseOutLog(ManifestRequestDTO request,
                                   ApiResponse<ManifestResponseDTO> resp, String source) {
        recordCloseOutLog(request, resp, source, null);
    }

    private void recordCloseOutLog(ManifestRequestDTO request,
                                   ApiResponse<ManifestResponseDTO> resp,
                                   String source, String warehouseCode) {
        ManifestResponseDTO d = resp == null ? null : resp.getData();
        String status = d != null ? d.getStatus()
                : resp != null && resp.getCode() >= 400 ? "ERROR" : "ERROR";
        String message = d != null ? d.getMessage() : (resp == null ? null : resp.getMessage());
        String reference = d != null ? d.getManifestId() : null;
        int count = d != null ? d.getTrackingCount()
                : (request.getTrackingNumbers() == null ? 0 : request.getTrackingNumbers().size());
        logEod("CLOSEOUT", request.getCarrierCode(), null, request.getCustomerNo(),
                warehouseCode, request.getCloseDate(), reference, count, status, message, source);
    }

    private void logEod(String kind, String carrier, String accountNumber, String customerNo,
                        String warehouseCode, LocalDate eventDate, String reference, int count,
                        String status, String message, String source) {
        eodLogger.record(kind, carrier, accountNumber, customerNo, warehouseCode,
                eventDate, reference, count, status, message, source);
    }

    // ===== FDX-G2 close-out call + response assembly =====

    private CloseOutResult callCloseOut(CarrierConnector connector,
                                         List<String> trackingNumbers,
                                         LocalDate closeDate,
                                         AddressToValidate address,
                                         CarrierAccountRef account,
                                         String accessToken,
                                         boolean express) {
        CloseOutRequest req = new CloseOutRequest(
                List.copyOf(trackingNumbers), closeDate, address,
                account.getAccountNumber(), express);
        try {
            return connector.closeOutDay(req, accessToken, account.getEnvironment());
        } catch (Exception ex) {
            String carrier = connector.getCarrierCode();
            log.warn("Close-out {} call to {} failed: {}",
                    express ? "EXPRESS" : "GROUND", carrier, ex.getMessage());
            return new CloseOutResult(carrier, null, null, null,
                    trackingNumbers.size(), "ERROR",
                    carrier + " close-out call failed: " + ex.getMessage(), null);
        }
    }

    /**
     * FDX-G2 — collapse N per-fleet {@link CloseOutResult}s into one
     * {@link ManifestResponseDTO}. Single-fleet call → keeps the flat
     * top-level shape (back-compat with pre-FDX-G callers). Multi-fleet
     * call → populates {@code manifests[]} and sets top-level flat fields
     * to aggregated summary values so the response is self-describing.
     */
    private ManifestResponseDTO buildResponse(String carrier,
                                               List<CloseOutResult> results,
                                               List<String> failed,
                                               List<String> groundTrackings,
                                               List<String> expressTrackings) {
        if (results.size() == 1) {
            // Single-fleet case — flat shape, back-compat.
            CloseOutResult r = results.get(0);
            return ManifestResponseDTO.builder()
                    .carrierCode(r.carrierCode())
                    .manifestId(r.manifestId())
                    .manifestPdfUrl(r.manifestPdfUrl())
                    .manifestPdfBase64(r.manifestPdfBase64())
                    .trackingCount(r.trackingCount())
                    .status(r.status())
                    .message(r.message())
                    .failedToClassify(nullIfEmpty(failed))
                    .build();
        }
        // Multi-fleet case (typically FedEx Ground + Express mixed) —
        // manifests[] carries the breakdown; top-level fields aggregate.
        // Preserve the (ground, express) call order that closeOut() enqueued.
        List<ManifestEntryDTO> entries = new ArrayList<>(results.size());
        int totalCount = 0;
        int successCount = 0;
        for (int i = 0; i < results.size(); i++) {
            CloseOutResult r = results.get(i);
            // First result is GROUND if groundTrackings non-empty, otherwise
            // it's EXPRESS. Second (when present) is always EXPRESS.
            String fleet = (i == 0 && !groundTrackings.isEmpty()) ? "GROUND" : "EXPRESS";
            List<String> tns = "GROUND".equals(fleet) ? groundTrackings : expressTrackings;
            entries.add(ManifestEntryDTO.builder()
                    .fleet(fleet)
                    .manifestId(r.manifestId())
                    .manifestPdfUrl(r.manifestPdfUrl())
                    .manifestPdfBase64(r.manifestPdfBase64())
                    .trackingCount(r.trackingCount())
                    .status(r.status())
                    .message(r.message())
                    .trackingNumbers(List.copyOf(tns))
                    .build());
            totalCount += r.trackingCount();
            if ("MANIFESTED".equals(r.status())) successCount++;
        }
        String aggregatedStatus = successCount == results.size() ? "MANIFESTED"
                : successCount == 0 ? "ERROR" : "PARTIAL";
        String aggregatedMessage = aggregatedStatus.equals("MANIFESTED")
                ? "Manifested " + totalCount + " tracking(s) across " + results.size() + " fleets."
                : aggregatedStatus.equals("PARTIAL")
                    ? successCount + " of " + results.size() + " fleet manifests succeeded — see manifests[]."
                    : "All " + results.size() + " fleet manifests failed — see manifests[] for detail.";
        return ManifestResponseDTO.builder()
                .carrierCode(carrier)
                .trackingCount(totalCount)
                .status(aggregatedStatus)
                .message(aggregatedMessage)
                .manifests(entries)
                .failedToClassify(nullIfEmpty(failed))
                .build();
    }

    /** Legacy shape for pre-classification exit paths (no-credentials, token
     *  failure) — those don't go through the split so the flat shape is
     *  appropriate. */
    private static ManifestResponseDTO singleFleetResponse(CloseOutResult r) {
        return ManifestResponseDTO.builder()
                .carrierCode(r.carrierCode())
                .manifestId(r.manifestId())
                .manifestPdfUrl(r.manifestPdfUrl())
                .manifestPdfBase64(r.manifestPdfBase64())
                .trackingCount(r.trackingCount())
                .status(r.status())
                .message(r.message())
                .build();
    }

    private static <T> List<T> nullIfEmpty(List<T> list) {
        return list == null || list.isEmpty() ? null : List.copyOf(list);
    }

    // ===== resolve, toDto, response wrappers (unchanged from pre-FDX-G2) =====

    CarrierAccountRef resolveAccount(String carrierCode, String customerNo) {
        if (StringUtils.hasText(customerNo)) {
            // Canonical cascade: default-flagged first, then the client's sole
            // account on this carrier (previously a client whose only account
            // wasn't flagged default silently fell to the HOUSE account), and
            // never a deactivated row (the default-flag query has no active
            // filter, so a disabled client's stale default was still picked).
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

    private static ApiResponse<ManifestResponseDTO> success(ManifestResponseDTO data) {
        return ApiResponse.<ManifestResponseDTO>builder()
                .status("success").code(200).message(data.getMessage()).data(data).build();
    }

    /**
     * R2-BUG-003 (run-4 API hygiene) — pick the HTTP envelope based on the
     * aggregated carrier outcome inside the DTO instead of blindly wrapping
     * every reply as 200 "success". Pre-fix, {@code POST /manifests/close-day}
     * returned HTTP 200 + {@code status:"success"} even when every carrier
     * call failed and {@code data.status} was ERROR — API callers that
     * polled the HTTP status thought close-out succeeded.
     * <ul>
     *   <li>{@code MANIFESTED} / {@code PARTIAL} → 200 success (the operator
     *       got at least one real manifest; failures are itemised in {@code
     *       manifests[]} or {@code failedToClassify})</li>
     *   <li>{@code ERROR} → 502 CARRIER_FAILURE so callers see non-2xx</li>
     *   <li>{@code NOT_SUPPORTED} → 200 (informational — UPS Daily Pickup
     *       auto-manifests at the driver scan; not a server failure)</li>
     * </ul>
     */
    private static ApiResponse<ManifestResponseDTO> respond(ManifestResponseDTO data) {
        String s = data == null ? null : data.getStatus();
        if (s != null && "ERROR".equalsIgnoreCase(s)) {
            return ApiResponse.<ManifestResponseDTO>builder()
                    .status("error")
                    .code(HttpStatus.BAD_GATEWAY.value())
                    .errorCode(ErrorCode.CARRIER_FAILURE.name())
                    .message(data.getMessage())
                    .data(data)
                    .build();
        }
        return success(data);
    }

    private static ApiResponse<ManifestResponseDTO> failure(HttpStatus status, String message) {
        return ApiResponse.<ManifestResponseDTO>builder()
                .status("error").code(status.value())
                .errorCode(ErrorCode.VALIDATION_ERROR.name())
                .message(message).data(null).build();
    }
}
