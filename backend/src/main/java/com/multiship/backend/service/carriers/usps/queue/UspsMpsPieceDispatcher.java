package com.multiship.backend.service.carriers.usps.queue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.dto.PackageDetailDTO;
import com.multiship.backend.dto.ShipmentRequestDTO;
import com.multiship.backend.model.CarrierAccountRef;
import com.multiship.backend.model.LabelPackage;
import com.multiship.backend.model.Order;
import com.multiship.backend.model.OrderTracking;
import com.multiship.backend.model.UspsLabelQueueItem;
import com.multiship.backend.repository.CarrierAccountRefRepository;
import com.multiship.backend.repository.LabelPackageRepository;
import com.multiship.backend.repository.OrderRepository;
import com.multiship.backend.repository.OrderTrackingRepository;
import com.multiship.backend.service.carriers.CarrierConnector;
import com.multiship.backend.service.carriers.UspsDirectConnector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * PR-F2.5 — per-piece runtime dispatch for MPS ("multi-piece shipment")
 * queue rows populated by {@link UspsMpsSplitterService} in PR-F2.
 *
 * <p>PR-F2's splitter enqueues N rows for an N-package USPS Direct
 * shipment; each row carries a synthetic negative {@code shipmentId}
 * plus first-class {@code parentOrderNo} + {@code sequenceNumber}
 * columns. The queue processor rate-limits at 55 label writes / hour and
 * hands each row to {@link UspsLabelQueueWiring}. The wiring's
 * pre-2026-09-15 single-label branch (which calls
 * {@code carrierService.generateLabel(orderNo, …)}) is unsuitable for
 * MPS pieces because {@code generateLabel} treats one order as one
 * shipment: calling it 5 times for a 5-piece order would either 409 on
 * the pre-existing tracking or attempt 5 fresh full-order labels — never
 * the 5 individual pieces the queue enqueued.
 *
 * <p>This service closes that gap. For every MPS piece the wiring hands
 * it:
 *
 * <ol>
 *   <li>Load the parent {@link Order} by {@code parentOrderNo}.</li>
 *   <li>Resolve the tenant's USPS {@link CarrierAccountRef}.</li>
 *   <li>Slice the parent's {@code packages_json} to isolate the
 *       {@code (sequenceNumber - 1)}-th package.</li>
 *   <li>Build a single-package {@link ShipmentRequestDTO} for that
 *       piece.</li>
 *   <li>Call {@link UspsDirectConnector#getAccessToken(String, String, String, String)}
 *       + {@link UspsDirectConnector#createShipment(ShipmentRequestDTO, String, String)}
 *       ONCE per piece — one HTTP label write per queue tick.</li>
 *   <li>Persist the returned tracking on a per-piece
 *       {@link LabelPackage} row; on piece 1 also stamp the master
 *       {@link OrderTracking} row so downstream reads (dashboard,
 *       webhook, refunds) find the "primary" tracking number.</li>
 *   <li>Return the tracking number so the queue processor records DONE
 *       + populates {@link UspsLabelQueueItem#getTrackingNumber()}.</li>
 * </ol>
 *
 * <p><b>Idempotency.</b> The queue's {@code UNIQUE(shipment_id)}
 * constraint plus per-piece {@link LabelPackage} rows keyed on
 * {@code (order_no, sequence_number)} together prevent duplicate charges
 * from a retried queue tick: a re-processed row for
 * {@code (parentOrderNo=42, sequenceNumber=3)} would upsert the same
 * label_package row rather than insert a second one. Failures propagate
 * verbatim so the queue processor marks the row FAILED +
 * increments {@code retry_count}.
 *
 * <p><b>Tenant resolution.</b> USPS uses a per-tenant account (matching
 * the design's per-tenant CRID/MID model — see
 * {@code docs/usps-direct-integration.md} §4.2). We prefer the tenant's
 * {@code client_default} row for the USPS carrier; the queue row's
 * {@code tenantCode} identifies the tenant. Absent a client-default row,
 * we fall back to any active USPS row for that tenant. Missing tenant
 * accounts throw {@link IllegalStateException} naming the tenant so ops
 * can populate the carrier account book.
 *
 * <p><b>REGULATORY_REFERENCE.</b> USPS APIs v3 have no batch-label
 * endpoint (see {@code docs/usps-direct-integration.md} §3 + §11 —
 * platform-owned OAuth app is capped at ~60 requests/hour, single-piece
 * per {@code POST /labels/v3/label} call). Every MPS piece therefore
 * costs one HTTP call, which is the reason the queue's 55/hour ceiling +
 * per-tenant fair-share slicing exist. Changing the "one call per piece"
 * shape requires USPS to ship a batch endpoint AND the platform OAuth
 * quota to move materially — coordinate with the USPS API team before
 * revisiting this design.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UspsMpsPieceDispatcher {

    /** Canonical carrier code the USPS Direct connector serves. */
    private static final String CARRIER_CODE_USPS = "USPS";

    /**
     * Shared JSON mapper for {@code label_batch.packages_json} round-trip.
     * A single instance is reused across ticks — Jackson's ObjectMapper is
     * thread-safe once configured, and we set no per-request options.
     */
    private static final ObjectMapper PACKAGES_JSON_MAPPER = new ObjectMapper();

    private final UspsDirectConnector uspsDirectConnector;
    private final OrderRepository orderRepository;
    private final OrderTrackingRepository orderTrackingRepository;
    private final LabelPackageRepository labelPackageRepository;
    private final CarrierAccountRefRepository carrierAccountRefRepository;
    private final CarrierProperties carrierProperties;

    /**
     * Dispatch one MPS piece to USPS Direct. Called by
     * {@link UspsLabelQueueWiring#processQueueItem(UspsLabelQueueItem)}
     * whenever {@code item.getParentOrderNo() != null}.
     *
     * @param item the queue row — must carry a non-null
     *             {@code parentOrderNo} + positive {@code sequenceNumber}.
     * @return the USPS-assigned tracking number for this piece.
     * @throws IllegalArgumentException validation failures (null/blank
     *         fields on the queue row, sequenceNumber out of range for
     *         the parent order's package count).
     * @throws IllegalStateException lookup failures (parent order not
     *         found, tenant's USPS account not registered).
     * @throws Exception connector-side failures propagate verbatim so
     *         the queue processor records FAILED + increments
     *         {@code retry_count}.
     */
    public String dispatchPiece(UspsLabelQueueItem item) throws Exception {
        // ---- Guard: caller (wiring) has already checked parentOrderNo != null,
        // but re-validate defensively so this class stays safe to reuse.
        if (item == null) {
            throw new IllegalArgumentException("UspsLabelQueueItem must not be null.");
        }
        Long parentOrderNo = item.getParentOrderNo();
        if (parentOrderNo == null) {
            throw new IllegalArgumentException(
                    "UspsMpsPieceDispatcher was called with a non-MPS queue item (parentOrderNo is null). "
                            + "The wiring-side branch that routes single-label rows to CarrierService.generateLabel "
                            + "must run before this dispatcher.");
        }
        Integer seq = item.getSequenceNumber();
        if (seq == null || seq < 1) {
            throw new IllegalArgumentException(
                    "MPS queue item " + item.getId() + " (parent " + parentOrderNo
                            + ") has invalid sequenceNumber=" + seq
                            + ". PR-F2 splitter enqueues 1-based sequences only.");
        }

        // ---- Load parent order.
        Order order = orderRepository.findByOrderNo(parentOrderNo.intValue())
                .orElseThrow(() -> new IllegalStateException(
                        "MPS parent order " + parentOrderNo + " not found for queue item "
                                + item.getId() + " (piece " + seq + "). "
                                + "The order may have been deleted after enqueue — cancel the queue row."));

        // ---- Slice the parent's packages_json to isolate this piece.
        List<PackageDetailDTO> parentPackages = deserializePackages(order.getPackagesJson());
        if (parentPackages.isEmpty()) {
            // Legacy single-package orders (pre-V33) have no packages_json.
            // Synthesise a 1-package list from Order-scalar fields so seq=1
            // still ships; seq > 1 on such an order is a data inconsistency.
            parentPackages = List.of(synthesiseSingletonPackage(order));
        }
        if (seq > parentPackages.size()) {
            throw new IllegalArgumentException(
                    "MPS piece sequenceNumber=" + seq + " exceeds parent order " + parentOrderNo
                            + " package count (" + parentPackages.size() + "). "
                            + "The enqueue side (PR-F2 splitter) fanned out more pieces than the order actually has — "
                            + "verify Order.packagesJson wasn't mutated after enqueue.");
        }
        PackageDetailDTO piece = parentPackages.get(seq - 1);

        // ---- Resolve the tenant's USPS account.
        String tenantCode = firstNonBlank(item.getTenantCode(), order.getTenantId(), order.getCustNo());
        CarrierAccountRef account = resolveUspsAccount(tenantCode, parentOrderNo, seq);

        // ---- Build the per-piece ShipmentRequestDTO.
        ShipmentRequestDTO pieceRequest = buildPieceRequest(order, piece, account, seq);

        // ---- Call USPS Direct: token → label.
        String environment = firstNonBlank(account.getEnvironment(),
                carrierProperties.getDefaultEnvironment());
        String accessToken = uspsDirectConnector.getAccessToken(
                account.getClientId(), account.getClientSecret(),
                account.getAccountNumber(), environment);

        log.info("USPS MPS piece dispatch: parent={} piece={}/{} account={} env={}",
                parentOrderNo, seq, parentPackages.size(),
                account.getAccountNumber(), environment);

        CarrierConnector.ShipmentResult result = uspsDirectConnector.createShipment(
                pieceRequest, accessToken, environment);

        if (result == null || !StringUtils.hasText(result.trackingNumber())) {
            throw new IllegalStateException(
                    "USPS Direct returned no tracking number for MPS piece parent=" + parentOrderNo
                            + " sequence=" + seq + " (queue item " + item.getId() + ").");
        }
        String tracking = result.trackingNumber();

        // ---- Persist the per-piece row.
        persistPieceTracking(order, piece, seq, result, account);

        // ---- On piece 1 also stamp the master OrderTracking row so
        // downstream single-tracking reads (dashboards, refund reconcile)
        // find the primary identifier for the shipment.
        if (seq == 1) {
            upsertMasterOrderTracking(order, result, account);
        }

        log.info("USPS MPS piece dispatch DONE: parent={} piece={}/{} tracking={}",
                parentOrderNo, seq, parentPackages.size(), tracking);
        return tracking;
    }

    /**
     * Locate the tenant's USPS carrier account. Preference order:
     * <ol>
     *   <li>{@code client_default = true} row for this tenant (USPS carrier only).</li>
     *   <li>First active USPS row for this tenant.</li>
     * </ol>
     * Throws {@link IllegalStateException} naming the tenant when no
     * usable USPS row exists — the queue processor surfaces the message
     * so ops can populate {@code /settings/carriers}.
     */
    private CarrierAccountRef resolveUspsAccount(String tenantCode, Long parentOrderNo, Integer seq) {
        if (!StringUtils.hasText(tenantCode)) {
            throw new IllegalStateException(
                    "MPS piece dispatch cannot resolve tenant for parent order " + parentOrderNo
                            + " (piece " + seq + "): queue item tenantCode + order.tenantId + order.custNo all blank.");
        }
        String tenant = tenantCode.trim();

        // 1) client_default = true for the tenant. May belong to any carrier;
        // filter to USPS.
        Optional<CarrierAccountRef> primary = carrierAccountRefRepository
                .findFirstByCustomerNoIgnoreCaseAndClientDefaultTrueAndActiveTrue(tenant)
                .filter(a -> CARRIER_CODE_USPS.equalsIgnoreCase(a.getCarrierCode()))
                .filter(CarrierAccountRef::isComplete);
        if (primary.isPresent()) {
            return primary.get();
        }

        // 2) Any active USPS row for the tenant.
        Optional<CarrierAccountRef> fallback = carrierAccountRefRepository
                .findByCustomerNoIgnoreCaseOrderByClientDefaultDescUpdatedAtDesc(tenant)
                .stream()
                .filter(a -> Boolean.TRUE.equals(a.getActive()))
                .filter(a -> CARRIER_CODE_USPS.equalsIgnoreCase(a.getCarrierCode()))
                .filter(CarrierAccountRef::isComplete)
                .findFirst();
        if (fallback.isPresent()) {
            return fallback.get();
        }

        throw new IllegalStateException(
                "MPS piece dispatch: tenant '" + tenant + "' has no active USPS carrier account "
                        + "(parent order " + parentOrderNo + ", piece " + seq + "). "
                        + "Add a USPS account for this tenant on /settings/carriers before retrying.");
    }

    /**
     * Assemble the single-package ShipmentRequestDTO USPS Direct sees on
     * the wire. Deliberately narrow: we copy the parent order's shipper /
     * recipient / account / service fields verbatim and place the sliced
     * piece as the sole entry on {@code packages}. USPS's own connector
     * body-builder ({@code buildLabelRequestBody}) reads
     * {@code effectivePackages()} which we ensure is length 1 for the
     * carrier call.
     *
     * <p>This is intentionally a leaner reconstruction than
     * {@code CarrierServiceImpl.buildShipmentRequest} — the private
     * method there resolves service catalog / package presets / customs
     * blocks, which are NOT required by USPS Direct for a domestic
     * per-piece label. If a future USPS_DIRECT feature (e.g. intl MPS)
     * needs the fuller cascade, the queue rate-limit itself is enough
     * runway to widen this builder later.
     */
    private ShipmentRequestDTO buildPieceRequest(Order order, PackageDetailDTO piece,
                                                  CarrierAccountRef account, Integer seq) {
        CarrierProperties.ShipperDefaults shipperDflt = carrierProperties.getShipper();

        // Shipper: order.shipFrom* (manual shipments write these) → platform defaults.
        String shipperName = firstNonBlank(order.getShipFromName(), shipperDflt.getName());
        String shipperPhone = firstNonBlank(order.getShipFromPhone(), shipperDflt.getPhone());
        String shipperLine1 = firstNonBlank(order.getShipFromAddr1(), shipperDflt.getAddressLine1());
        String shipperLine2 = firstNonBlank(order.getShipFromAddr2(), shipperDflt.getAddressLine2());
        String shipperCity = firstNonBlank(order.getShipFromCity(), shipperDflt.getCity());
        String shipperState = firstNonBlank(order.getShipFromState(), shipperDflt.getState());
        String shipperPostal = firstNonBlank(order.getShipFromZip(), shipperDflt.getPostalCode());
        String shipperCountry = firstNonBlank(order.getShipFromCountryCd(), shipperDflt.getCountryCode());
        String shipperCompany = order.getShipFromCompany();

        String pieceReference = "MPS-" + order.getOrderNo() + "-P" + seq;

        // Ensure the piece carries a sequenceNumber so the connector can
        // emit it verbatim (and so LabelPackage persistence is unambiguous).
        PackageDetailDTO pieceWithSeq = piece.getSequenceNumber() != null
                ? piece
                : cloneWithSeq(piece, seq);

        return ShipmentRequestDTO.builder()
                .carrierCode(CARRIER_CODE_USPS)
                .accountNumber(account.getAccountNumber())
                .serviceType(order.getShipviaCd())
                .packageType(firstNonBlank(pieceWithSeq.getPackageType(), "YOUR_PACKAGING"))
                .length(pieceWithSeq.getLength())
                .width(pieceWithSeq.getWidth())
                .height(pieceWithSeq.getHeight())
                .weight(pieceWithSeq.getWeight() != null ? pieceWithSeq.getWeight() : order.getWeight())
                .weightUnit(firstNonBlank(pieceWithSeq.getWeightUnit(), order.getWeightUnit()))
                .dimUnit(pieceWithSeq.getDimUnit())
                .shipperName(shipperName)
                .shipperPhone(shipperPhone)
                .shipperCompany(shipperCompany)
                .shipperAddressLine1(shipperLine1)
                .shipperAddressLine2(shipperLine2)
                .shipperCity(shipperCity)
                .shipperState(shipperState)
                .shipperPostalCode(shipperPostal)
                .shipperCountryCode(shipperCountry)
                .recipientName(firstNonBlank(order.getShipName(), order.getShipAttn(), order.getCustNo()))
                .recipientPhone(order.getPhone())
                .recipientAddressLine1(order.getShipAddr1())
                .recipientAddressLine2(order.getLocation())
                .recipientCity(order.getShiptoCity())
                .recipientState(order.getShiptoState())
                .recipientPostalCode(order.getShiptoZip())
                .recipientCountryCode(order.getShiptoCountryCd())
                .referenceNumber(pieceReference)
                .poNumber(String.valueOf(order.getOrderNo()))
                .departmentNumber(order.getCustNo())
                .declaredValue(pieceWithSeq.getDeclaredValue())
                .packages(List.of(pieceWithSeq))
                .build();
    }

    /**
     * Persist (or update) the {@link LabelPackage} row for this piece.
     * The unique constraint {@code (order_no, sequence_number)} makes
     * this an upsert: a retried queue tick lands on the same row rather
     * than a duplicate insert.
     */
    private void persistPieceTracking(Order order, PackageDetailDTO piece, Integer seq,
                                       CarrierConnector.ShipmentResult result,
                                       CarrierAccountRef account) {
        LocalDateTime now = LocalDateTime.now();
        LabelPackage row = labelPackageRepository
                .findByOrderNoAndSequenceNumber(order.getOrderNo(), seq)
                .orElseGet(LabelPackage::new);

        row.setOrderNo(order.getOrderNo());
        row.setSequenceNumber(seq);
        row.setTrackingNumber(result.trackingNumber());
        row.setTrackingUrl(result.trackingUrl());
        row.setLabelFilePath(firstNonBlank(result.labelPdf(), result.labelUrl()));
        row.setWeight(piece.getWeight());
        row.setWeightUnit(piece.getWeightUnit());
        row.setLength(piece.getLength());
        row.setWidth(piece.getWidth());
        row.setHeight(piece.getHeight());
        row.setDimUnit(piece.getDimUnit());
        row.setPackageType(piece.getPackageType());
        row.setDeclaredValue(piece.getDeclaredValue());
        row.setReference(piece.getReference());
        row.setDescription(piece.getDescription());
        if (row.getCreatedAt() == null) {
            row.setCreatedAt(now);
        }
        row.setUpdatedAt(now);
        labelPackageRepository.save(row);
    }

    /**
     * On piece 1: create or update the master {@link OrderTracking} row
     * so downstream single-tracking reads (dashboard, webhook, refund
     * reconcile) find the primary tracking number for the MPS shipment.
     * Matches the "master tracking = first piece's tracking" convention
     * used by every other multi-piece carrier path in this codebase
     * (see {@link com.multiship.backend.service.CarrierServiceImpl}
     * around line 653).
     */
    private void upsertMasterOrderTracking(Order order, CarrierConnector.ShipmentResult result,
                                            CarrierAccountRef account) {
        LocalDateTime now = LocalDateTime.now();
        OrderTracking tracking = orderTrackingRepository
                .findByOrderNo(order.getOrderNo())
                .orElseGet(OrderTracking::new);

        tracking.setOrderNo(order.getOrderNo());
        tracking.setOrderSuffix(order.getOrderSuffix());
        tracking.setTrackingNumber(result.trackingNumber());
        tracking.setTrackingUrl(result.trackingUrl());
        tracking.setShipViaCd(order.getShipviaCd());
        tracking.setAccountNumber(account.getAccountNumber());
        tracking.setIsLabelGenerated(true);
        tracking.setLabelGeneratedAt(now);
        tracking.setLabelFilePath(firstNonBlank(result.labelPdf(), result.labelUrl()));
        tracking.setStatus("GENERATED");
        tracking.setErrorMessage(null);
        if (tracking.getCreatedAt() == null) {
            tracking.setCreatedAt(now);
        }
        tracking.setUpdatedAt(now);
        orderTrackingRepository.save(tracking);
    }

    /**
     * Deserialise {@code label_batch.packages_json} back into per-box
     * DTOs. Mirrors {@code CarrierServiceImpl.deserializePackagesJson}
     * — kept inline here rather than delegated because that method is
     * package-private in a different package. Malformed JSON returns
     * empty (the caller synthesises a legacy single-package fallback).
     */
    private static List<PackageDetailDTO> deserializePackages(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            List<PackageDetailDTO> parsed = PACKAGES_JSON_MAPPER.readValue(json,
                    new TypeReference<List<PackageDetailDTO>>() {});
            return parsed == null ? List.of() : parsed;
        } catch (JsonProcessingException ex) {
            log.warn("MPS piece dispatch: packages_json deserialise failed ({}); "
                    + "falling back to single-package synthesis.", ex.getMessage());
            return List.of();
        }
    }

    /**
     * Legacy single-package orders (pre-V33 rows with null
     * {@code packages_json}) get a synthesised 1-package list built from
     * Order-scalar fields. Seq > 1 on such an order still fails the
     * bounds check upstream — we never silently ship the wrong piece.
     */
    private static PackageDetailDTO synthesiseSingletonPackage(Order order) {
        return PackageDetailDTO.builder()
                .sequenceNumber(1)
                .weight(order.getWeight())
                .weightUnit(order.getWeightUnit())
                .build();
    }

    /**
     * Clone a package DTO overriding only {@code sequenceNumber}. Used
     * when the packages_json entry has no explicit sequence — we stamp
     * the queue row's sequence so persistence + wire references stay
     * consistent.
     */
    private static PackageDetailDTO cloneWithSeq(PackageDetailDTO src, int seq) {
        return PackageDetailDTO.builder()
                .sequenceNumber(seq)
                .packageType(src.getPackageType())
                .weight(src.getWeight())
                .weightUnit(src.getWeightUnit())
                .length(src.getLength())
                .width(src.getWidth())
                .height(src.getHeight())
                .dimUnit(src.getDimUnit())
                .declaredValue(src.getDeclaredValue())
                .description(src.getDescription())
                .reference(src.getReference())
                .build();
    }

    /** First non-blank value in the supplied list; null when all are blank. */
    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }
}
