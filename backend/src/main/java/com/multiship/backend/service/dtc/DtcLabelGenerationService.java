package com.multiship.backend.service.dtc;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.LabelGenerationResponse;
import com.multiship.backend.dto.ManualShipmentRequest;
import com.multiship.backend.model.CarrierAccountRef;
import com.multiship.backend.model.Client;
import com.multiship.backend.model.ClientShipviaCodeMap;
import com.multiship.backend.model.DtcGenerationJob;
import com.multiship.backend.model.DtcOrder;
import com.multiship.backend.model.Order;
import com.multiship.backend.model.OrderTracking;
import com.multiship.backend.model.ShipmentBatch;
import com.multiship.backend.model.ShippingService;
import com.multiship.backend.repository.CarrierAccountRefRepository;
import com.multiship.backend.repository.ClientRepository;
import com.multiship.backend.repository.ClientShipviaCodeMapRepository;
import com.multiship.backend.repository.ClientWarehouseRepository;
import com.multiship.backend.repository.DtcGenerationJobRepository;
import com.multiship.backend.repository.DtcOrderRepository;
import com.multiship.backend.repository.OrderRepository;
import com.multiship.backend.repository.OrderTrackingRepository;
import com.multiship.backend.repository.ShipmentBatchRepository;
import com.multiship.backend.repository.ShippingServiceRepository;
import com.multiship.backend.repository.WarehouseRepository;
import com.multiship.backend.service.CarrierService;
import com.multiship.backend.service.ShippingConfigService;
import com.multiship.backend.service.StdShipMethodResolver;
import com.multiship.backend.service.carriers.usps.queue.IdempotencyKeys;
import com.multiship.backend.service.ndsshipment.NdsAddressSanitizer;
import com.multiship.backend.service.ndsshipment.NdsPhoneNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * DTC "Automatic label" batch generation (V102). Enqueues a
 * {@link DtcGenerationJob} over one (tenant, batch) and, once the worker
 * claims it, mints (or regenerates) a label order for every dtc_orders row.
 *
 * <p>Per-row pipeline mirrors OrderImportServiceImpl's background path so a
 * DTC row behaves exactly like an imported bulk row:
 * <ol>
 *   <li>Resolve the service — "STD" via {@link StdShipMethodResolver};
 *       otherwise client_shipvia_code_map (exact) then ship_via_mapping
 *       (client-narrowed → any-client → first), hold codes rejected. The pick
 *       is then aligned to the row's border crossing.</li>
 *   <li>Build a {@link ManualShipmentRequest}: recipient from the row's
 *       ship-to columns (NDS sanitizer/normalizer), sender from the client's
 *       shipFrom, default warehouse, bill-to account cascade
 *       (client default/single → platform), weight defaulting to 1 lb.</li>
 *   <li>{@code carrierService.generateManualLabel(req, null, existingOrderNo)}
 *       with the order-anchored idempotency key and
 *       {@code system:dtc-worker/{jobId}} audit actor — the exact stamps the
 *       import path uses, so retries from any surface dedup on
 *       order_tracking.idempotency_key.</li>
 *   <li>Stamp the row's generated_* columns: GENERATED + tracking, or
 *       QUEUED_USPS when the USPS_DIRECT router parked the label on the
 *       queue, or FAILED + message.</li>
 * </ol>
 *
 * <p>Rows already carrying a generated label are skipped (a re-run after a partial
 * batch must not re-buy labels); {@link #syncFromLabel} first re-reads the label order
 * so a line repaired on the shipment form is realigned and then skipped rather than
 * re-attempted. FAILED rows retry in place via
 * {@code existingOrderNo = generatedOrderNo}, which flips the minted order
 * ERROR → GENERATED instead of inserting a duplicate.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DtcLabelGenerationService {

    private static final String STATUS_GENERATED = "GENERATED";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_QUEUED_USPS = "QUEUED_USPS";
    /** The width of dtc_orders.generated_message — see {@link #abbreviate}. */
    private static final int MESSAGE_MAX_LEN = 255;
    private static final BigDecimal DEFAULT_WEIGHT_LB = BigDecimal.ONE;
    private static final String CURRENCY_USD = "USD";

    private static final int PROGRESS_FLUSH_EVERY_N = 5;
    private static final long PROGRESS_FLUSH_EVERY_MS = 2_000;

    private final DtcGenerationJobRepository jobRepository;
    private final DtcOrderRepository dtcOrderRepository;
    private final OrderRepository orderRepository;
    private final OrderTrackingRepository orderTrackingRepository;
    private final ShipmentBatchRepository shipmentBatchRepository;
    private final CarrierService carrierService;
    private final ClientRepository clientRepository;
    private final ClientShipviaCodeMapRepository clientShipviaCodeMapRepository;
    private final ShippingServiceRepository shippingServiceRepository;
    private final StdShipMethodResolver stdShipMethodResolver;
    private final ShippingConfigService shippingConfigService;
    private final CarrierAccountRefRepository accountRefRepository;
    private final ClientWarehouseRepository clientWarehouseRepository;
    private final WarehouseRepository warehouseRepository;

    @Value("${dtc.generation.row-concurrency:3}")
    private int rowConcurrency;

    private volatile ExecutorService rowPool;

    /**
     * Enqueue a generation run for (tenant, batch). Refuses when an active
     * job already exists so a double-click can't buy the batch twice.
     *
     * @return the queued job, or empty when one is already active
     */
    @Transactional
    public Optional<DtcGenerationJob> enqueue(String tenantId, BigDecimal batchId, String requestedBy) {
        boolean active = jobRepository.existsByTenantIdAndBatchIdAndStatusIn(
                tenantId, batchId, List.of(DtcGenerationJob.QUEUED, DtcGenerationJob.RUNNING));
        if (active) {
            return Optional.empty();
        }
        DtcGenerationJob job = new DtcGenerationJob();
        job.setTenantId(tenantId);
        job.setBatchId(batchId);
        job.setStatus(DtcGenerationJob.QUEUED);
        job.setRequestedBy(requestedBy);
        job.setTotalRows((int) dtcOrderRepository
                .findByTenantIdAndBatchId(tenantId, batchId,
                        org.springframework.data.domain.PageRequest.of(0, 1))
                .getTotalElements());
        return Optional.of(jobRepository.save(job));
    }

    /**
     * Job body — claimed RUNNING by the worker before this is called.
     * Fan out rows over {@value #rowConcurrency} threads, flush progress
     * throttled, mark DONE (or FAILED on a crash outside row handling).
     */
    public void executeJob(Long jobId) {
        DtcGenerationJob job = jobRepository.findById(jobId).orElse(null);
        if (job == null) {
            log.warn("DTC generation job {} disappeared before execution", jobId);
            return;
        }
        List<DtcOrder> rows = dtcOrderRepository.findByTenantIdAndBatchIdOrderByIdAsc(
                job.getTenantId(), job.getBatchId());

        AtomicInteger processed = new AtomicInteger();
        AtomicInteger generated = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();
        AtomicLong lastFlushMs = new AtomicLong(System.currentTimeMillis());

        try {
            List<Callable<Void>> tasks = rows.stream()
                    .<Callable<Void>>map(row -> () -> {
                        RowOutcome outcome = generateOneRow(job, row.getId());
                        processed.incrementAndGet();
                        switch (outcome) {
                            case GENERATED -> generated.incrementAndGet();
                            case FAILED -> failed.incrementAndGet();
                            case SKIPPED -> skipped.incrementAndGet();
                        }
                        flushProgressIfDue(job, processed.get(), generated.get(), failed.get(),
                                skipped.get(), lastFlushMs);
                        return null;
                    })
                    .toList();

            ensureRowPool();
            List<Future<Void>> futures = rowPool.invokeAll(tasks);
            for (Future<Void> f : futures) {
                try {
                    f.get();
                } catch (Exception e) {
                    // generateOneRow never throws (it catches internally);
                    // guard so one rogue future still lands the job DONE.
                    log.warn("DTC generation job {} row future threw: {}", jobId, e.toString());
                }
            }

            job.setProcessedRows(processed.get());
            job.setGeneratedCount(generated.get());
            job.setFailedCount(failed.get());
            job.setSkippedCount(skipped.get());
            job.setStatus(DtcGenerationJob.DONE);
            job.setFinishedAt(LocalDateTime.now());
            jobRepository.save(job);
            log.info("DTC generation job {} done: {}/{} generated, {} failed, {} skipped",
                    jobId, generated.get(), rows.size(), failed.get(), skipped.get());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failJob(job, "interrupted: " + e.getMessage());
        } catch (RuntimeException e) {
            failJob(job, e.getMessage());
        }
    }

    /** Per-row label mint. Never throws — every outcome lands on the row. */
    private RowOutcome generateOneRow(DtcGenerationJob job, Long dtcOrderId) {
        DtcOrder row = dtcOrderRepository.findById(dtcOrderId).orElse(null);
        if (row == null) {
            return RowOutcome.SKIPPED;
        }
        try {
            if (syncFromLabel(row)) {
                return RowOutcome.SKIPPED; // already labelled — don't re-buy
            }

            ManualShipmentRequest req = buildRequest(job, row);
            Integer existingOrderNo = row.getGeneratedOrderNo();
            if (existingOrderNo != null) {
                req.setInternalIdempotencyKey(IdempotencyKeys.forUspsOrder(existingOrderNo.longValue()));
            }
            req.setInternalAuditActor("system:dtc-worker/" + job.getId());

            ApiResponse<LabelGenerationResponse> resp =
                    carrierService.generateManualLabel(req, null, existingOrderNo);
            LabelGenerationResponse data = resp == null ? null : resp.getData();

            if (resp != null && "success".equalsIgnoreCase(resp.getStatus()) && data != null
                    && StringUtils.hasText(data.getTrackingNumber())) {
                stamp(row, STATUS_GENERATED, data.getTrackingNumber(), data.getCarrierCode(),
                        data.getOrderNo() == null ? null : data.getOrderNo().intValue(), data.getMessage());
                return RowOutcome.GENERATED;
            }
            if (data != null && ("QUEUED".equalsIgnoreCase(data.getStatus())
                    || "QUEUED_MPS".equalsIgnoreCase(data.getStatus()))) {
                // Both branches must stay Integer — a primitive-int branch would
                // unbox a null existingOrderNo and NPE exactly on this path.
                stamp(row, STATUS_QUEUED_USPS, data.getTrackingNumber(), data.getCarrierCode(),
                        data.getOrderNo() == null ? existingOrderNo
                                : Integer.valueOf(data.getOrderNo().intValue()), data.getMessage());
                return RowOutcome.GENERATED; // accepted by the USPS queue — counts as handled
            }

            String msg = resp == null || !StringUtils.hasText(resp.getMessage())
                    ? "label generation returned no tracking number"
                    : resp.getMessage();
            // Same ternary trap as above — keep both branches Integer.
            Integer failedNo = data != null && data.getOrderNo() != null
                    ? Integer.valueOf(data.getOrderNo().intValue()) : existingOrderNo;
            stamp(row, STATUS_FAILED, null, null, failedNo, abbreviate(msg));
            return RowOutcome.FAILED;
        } catch (Exception e) {
            log.warn("DTC row {} (batch {}, tenant {}) generation failed: {}",
                    row.getId(), row.getBatchId(), row.getTenantId(), e.toString(), e);
            try {
                stamp(row, STATUS_FAILED, null, null, row.getGeneratedOrderNo(),
                        abbreviate(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            } catch (Exception stampEx) {
                log.warn("DTC row {}: could not stamp failure: {}", row.getId(), stampEx.toString());
            }
            return RowOutcome.FAILED;
        }
    }

    /**
     * Row → request. Throws {@link IllegalArgumentException} with an
     * operator-actionable message when the row can't be honoured — the
     * caller stamps FAILED with that message.
     */
    private ManualShipmentRequest buildRequest(DtcGenerationJob job, DtcOrder row) {
        String tenantId = row.getTenantId() == null ? job.getTenantId() : row.getTenantId();
        String shipVia = firstNonBlank(row.getShipViaCode(), row.getShipVia());
        String originCountry = originOf(tenantId);
        String destCountry = firstNonBlank(row.getShipToCountryCode(), "US")
                .trim().toUpperCase(Locale.ROOT);

        ShippingService service = resolveService(tenantId, shipVia, originCountry, destCountry);
        CarrierAccountRef account = resolveAccount(tenantId, service.getCarrier());

        ManualShipmentRequest req = new ManualShipmentRequest();
        req.setClientCode(tenantId);
        req.setSource("DTC");
        req.setChannel("D2C");
        req.setServiceId(service.getId());
        req.setCarrierCode(service.getCarrier());
        req.setAccountNumber(account.getAccountNumber());
        // Pass the account's identity, not just its number: several tenants
        // can share one bill-to number (e.g. house 746W05), and the number-only
        // lookup in CarrierServiceImpl picks the first matching row regardless
        // of owner, failing the ownership gate with "belongs to client X".
        req.setAccountId(account.getId());
        req.setIsReturn(false);
        req.setWeight(row.getWeight() != null && row.getWeight().signum() > 0
                ? row.getWeight() : DEFAULT_WEIGHT_LB);
        req.setWeightUnit("LB");
        req.setReference("DTC batch " + row.getBatchId()
                + (StringUtils.hasText(row.getToteNumber()) ? " / tote " + row.getToteNumber() : ""));
        if (StringUtils.hasText(row.getGoodsDesc())) {
            req.setGoodsDescription(NdsAddressSanitizer.sanitize(row.getGoodsDesc()));
        }
        applyInvoiceLine(req, row, originCountry);

        req.setRecipient(buildRecipient(row, destCountry));
        applyClientOrigin(req, tenantId);
        applyDefaultWarehouse(req, tenantId);
        return req;
    }

    /**
     * The ERP feed has no item grid — one goods_desc and one unit_value per tote
     * — so the commercial invoice line is built from those two columns. Without
     * a line a cross-border label carries no monetary value and UPS answers
     * "InvoiceLineTotal MonetaryValue must be greater than 0".
     *
     * <p>HS code is deliberately left unset: the feed does not carry it, and a
     * guessed harmonized code is a false customs declaration. A cross-border row
     * that needs one is corrected by the operator through Edit on the shipment
     * form. A row with no positive unit value gets no line for the same reason.
     */
    private void applyInvoiceLine(ManualShipmentRequest req, DtcOrder row, String originCountry) {
        String description = NdsAddressSanitizer.sanitize(row.getGoodsDesc());
        if (!StringUtils.hasText(description)
                || row.getUnitValue() == null || row.getUnitValue().signum() <= 0) {
            return;
        }
        ManualShipmentRequest.Item item = new ManualShipmentRequest.Item();
        item.setDescription(description);
        item.setQuantity(1);
        item.setUnitValue(row.getUnitValue());
        item.setWeight(req.getWeight());
        item.setCountryOfOrigin(originCountry);

        req.setItems(List.of(item));
        req.setCurrency(CURRENCY_USD);
    }

    /** Where this client ships from — the same cascade applyClientOrigin uses for the sender. */
    private String originOf(String tenantId) {
        Client client = clientRepository.findByClientCodeIgnoreCase(tenantId.trim()).orElse(null);
        if (client == null) return "US";
        com.multiship.backend.model.Address sf = client.getShipFrom();
        String country = sf != null && StringUtils.hasText(sf.getCountry())
                ? sf.getCountry() : client.getDefaultOriginCountry();
        return StringUtils.hasText(country) ? country.trim().toUpperCase(Locale.ROOT) : "US";
    }

    private ManualShipmentRequest.Address buildRecipient(DtcOrder row, String destCountry) {
        ManualShipmentRequest.Address to = new ManualShipmentRequest.Address();
        String name = firstNonBlank(row.getShipName(), row.getShipAttn());
        to.setName(NdsAddressSanitizer.sanitize(name));
        if (StringUtils.hasText(row.getShipAttn())
                && (name == null || !row.getShipAttn().trim().equalsIgnoreCase(name.trim()))) {
            to.setCompany(NdsAddressSanitizer.sanitize(row.getShipAttn()));
        }
        to.setAddressLine1(NdsAddressSanitizer.sanitize(row.getShipAddr1()));
        to.setAddressLine2(NdsAddressSanitizer.sanitize(row.getShipAddr2()));
        to.setAddressLine3(NdsAddressSanitizer.sanitize(row.getShipAddr3()));
        to.setCity(NdsAddressSanitizer.sanitize(row.getShipToCity()));
        to.setState(NdsAddressSanitizer.sanitize(row.getShipToState()));
        to.setPostalCode(row.getShipToZip() == null ? null : row.getShipToZip().trim());
        to.setCountryCode(destCountry);
        to.setResidential(true); // DTC ships to consumers
        String clientPhone = clientRepository
                .findByClientCodeIgnoreCase(row.getTenantId() == null ? "" : row.getTenantId().trim())
                .map(Client::getPhone).orElse(null);
        to.setPhone(NdsPhoneNormalizer.normalize(row.getPhone(), clientPhone).phone());
        to.setEmail(StringUtils.hasText(row.getEmail()) ? row.getEmail().trim() : null);
        if (!StringUtils.hasText(to.getAddressLine1()) || !StringUtils.hasText(to.getCity())
                || !StringUtils.hasText(to.getPostalCode())) {
            throw new IllegalArgumentException(
                    "row is missing a usable ship-to address (addr1/city/postal required)");
        }
        return to;
    }

    /**
     * ship-via → service. Same precedence as the NDS lookup
     * (NdsShipmentLookupService.resolveServiceId): the client's exact
     * client_shipvia_code_map row, then ship_via_mapping client-narrowed →
     * any-client → first. "STD" defers to StdShipMethodResolver. Hold codes
     * (HLD literal or is_hold mapping) reject the row outright. The mapped
     * service is finally checked against the row's own border crossing —
     * {@link #alignToLane}.
     */
    private ShippingService resolveService(String tenantId, String shipVia,
                                           String originCountry, String destCountry) {
        if (!StringUtils.hasText(shipVia)) {
            throw new IllegalArgumentException("row has no ship-via code");
        }
        String code = shipVia.trim();
        if ("HLD".equalsIgnoreCase(code)
                || clientShipviaCodeMapRepository
                        .findByClientCodeIgnoreCaseAndErpCodeIgnoreCase(tenantId, code)
                        .map(m -> Boolean.TRUE.equals(m.getIsHold()))
                        .orElse(false)) {
            throw new IllegalArgumentException("ship-via " + code + " is on hold");
        }

        ShippingService service;
        if (StdShipMethodResolver.STD.equalsIgnoreCase(code)) {
            service = stdShipMethodResolver.resolveStdForClient(tenantId)
                    .map(StdShipMethodResolver.Result::service)
                    .orElse(null);
            if (service == null) {
                throw new IllegalArgumentException("no STD shipping-service mapping configured for "
                        + tenantId + " (Settings → Shipping Service Mapping)");
            }
        } else {
            // V126 merge — single specificity-ordered lookup. The finder's
            // ORDER BY puts per-client rows ahead of platform-wide (null
            // client) rows, so the pre-merge fallback chain is one call.
            List<ClientShipviaCodeMap> matches = clientShipviaCodeMapRepository
                    .findMatches(tenantId, code, null, null, null);
            if (matches.isEmpty()) {
                throw new IllegalArgumentException("no shipping service mapped for ship-via " + code
                        + " — add a Code Map for " + tenantId);
            }
            service = shippingServiceRepository.findById(matches.get(0).getServiceId()).orElse(null);
        }

        if (service == null) {
            throw new IllegalArgumentException("mapped shipping service for ship-via " + code
                    + " no longer exists");
        }
        if (!service.isEnabled()) {
            throw new IllegalArgumentException("service " + service.getName() + " ("
                    + service.getCarrier() + ") is disabled in the catalog");
        }
        return alignToLane(service, tenantId, code, originCountry, destCountry);
    }

    /**
     * An ERP ship-via code names a service, not a lane: a code mapped to UPS
     * Ground (scope DOMESTIC) reaches a US→CA tote unchanged and UPS answers
     * "121100 The requested service is invalid for the shipment origin" — a
     * carrier 400 the operator cannot act on from the batch list. When the
     * mapped service's scope contradicts the row's own crossing, the resolution
     * is re-run destination-aware through
     * {@link ShippingConfigService#resolveService}, so the client's
     * cross-border ship-method rule wins and, failing that, the carrier's
     * international catalog entry for this origin. A lane with no service at
     * all fails with the mapping to add.
     */
    private ShippingService alignToLane(ShippingService service, String tenantId, String code,
                                        String originCountry, String destCountry) {
        boolean crossBorder = !originCountry.equalsIgnoreCase(destCountry);
        if (fitsLane(service, crossBorder)) {
            return service;
        }
        ShippingService aligned = shippingConfigService
                .resolveService(service.getCarrier(), tenantId, code, destCountry,
                        crossBorder, originCountry)
                .filter(s -> fitsLane(s, crossBorder))
                .orElseThrow(() -> new IllegalArgumentException("ship-via " + code + " maps to "
                        + service.getName() + " (" + service.getCarrier() + ", " + service.getScope()
                        + "), which is not offered " + originCountry + "→" + destCountry
                        + "; map " + code + " to a service on that lane under"
                        + " Settings → Shipping Service Mapping"));
        log.info("DTC ship-via {} maps to {} ({}), which cannot serve {}→{}: generating on {}",
                code, service.getName(), service.getScope(), originCountry, destCountry,
                aligned.getName());
        return aligned;
    }

    /** A service buys this lane when its catalog scope covers it. Unknown scope is taken at face value. */
    private static boolean fitsLane(ShippingService service, boolean crossBorder) {
        String scope = service.getScope() == null
                ? "" : service.getScope().trim().toUpperCase(Locale.ROOT);
        return scope.isEmpty() || "BOTH".equals(scope)
                || (crossBorder ? "INTERNATIONAL" : "DOMESTIC").equals(scope);
    }

    /**
     * Bill-to account cascade, mirroring the import path: the client's own
     * complete, active accounts on the carrier — clientDefault wins, a sole
     * account is unambiguous, several without a default fail loudly — then
     * the platform (house) account.
     */
    private CarrierAccountRef resolveAccount(String tenantId, String carrier) {
        String canon = carrier == null ? "" : carrier.trim().toUpperCase(Locale.ROOT);
        List<CarrierAccountRef> clientAccounts = StringUtils.hasText(tenantId)
                ? accountRefRepository
                        .findByCustomerNoIgnoreCaseOrderByClientDefaultDescUpdatedAtDesc(tenantId.trim())
                        .stream()
                        .filter(a -> !Boolean.FALSE.equals(a.getActive()))
                        .filter(CarrierAccountRef::isComplete)
                        .filter(a -> canon.equalsIgnoreCase(
                                a.getCarrierCode() == null ? "" : a.getCarrierCode().trim()))
                        .toList()
                : List.of();
        if (!clientAccounts.isEmpty()) {
            return clientAccounts.stream()
                    .filter(a -> Boolean.TRUE.equals(a.getClientDefault()))
                    .findFirst()
                    .or(() -> clientAccounts.size() == 1
                            ? Optional.of(clientAccounts.get(0)) : Optional.empty())
                    .orElseThrow(() -> new IllegalArgumentException(tenantId + " has "
                            + clientAccounts.size() + " " + canon
                            + " accounts and no default; set a default under Settings → Carrier Accounts"));
        }
        return accountRefRepository.findPlatformAccountsByCarrier(canon).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no active " + canon
                        + " account for " + tenantId + " and no platform account to fall back to"));
    }

    /** Sender = the client's shipFrom; no-op when absent (platform default applies downstream). */
    private void applyClientOrigin(ManualShipmentRequest req, String tenantId) {
        if (req.getSender() != null || !StringUtils.hasText(tenantId)) return;
        Client client = clientRepository.findByClientCodeIgnoreCase(tenantId.trim()).orElse(null);
        if (client == null) return;
        com.multiship.backend.model.Address sf = client.getShipFrom();
        String country = sf != null && StringUtils.hasText(sf.getCountry())
                ? sf.getCountry() : client.getDefaultOriginCountry();
        if (!StringUtils.hasText(country)) return;
        ManualShipmentRequest.Address sender = new ManualShipmentRequest.Address();
        sender.setCountryCode(country.trim());
        if (sf != null) {
            sender.setName(sf.getName());
            sender.setAddressLine1(sf.getLine1());
            sender.setAddressLine2(sf.getLine2());
            sender.setCity(sf.getCity());
            sender.setState(sf.getState());
            sender.setPostalCode(sf.getZip());
            sender.setPhone(sf.getPhone());
        }
        req.setSender(sender);
    }

    /** Blank warehouseCode → the client's default warehouse (New Shipment pre-select behaviour). */
    private void applyDefaultWarehouse(ManualShipmentRequest req, String tenantId) {
        if (StringUtils.hasText(req.getWarehouseCode()) || !StringUtils.hasText(tenantId)) return;
        try {
            clientWarehouseRepository.findByClientCodeIgnoreCaseAndIsDefaultTrue(tenantId.trim())
                    .flatMap(link -> warehouseRepository.findById(link.getWarehouseId()))
                    .filter(w -> !Boolean.FALSE.equals(w.getActive())
                            && StringUtils.hasText(w.getCode()))
                    .ifPresent(w -> req.setWarehouseCode(w.getCode()));
        } catch (RuntimeException ignore) {
            // client's registered address stands (applyClientOrigin)
        }
    }

    /**
     * A repair made on the manual shipment form mints its label through a path that
     * knows nothing about {@code dtc_orders}, so the line keeps the carrier error the
     * worker last saw: the history screen shows FAILED next to a printed label, and the
     * next batch run re-attempts the line from the ERP row and overwrites what the
     * operator fixed. Re-read the label order the line points at and stamp it to match.
     *
     * <p>One-way on purpose: a line is only ever lifted to GENERATED, never dropped
     * from it — a voided or re-errored order still had a label bought for it, and
     * downgrading the line would make the next batch run buy a second one.
     *
     * @return true when the line carries a generated label, so callers leave it alone
     */
    public boolean syncFromLabel(DtcOrder row) {
        Integer orderNo = row.getGeneratedOrderNo();
        if (orderNo == null) {
            return false;
        }
        if (STATUS_GENERATED.equalsIgnoreCase(row.getGeneratedStatus() == null ? "" : row.getGeneratedStatus())) {
            return true;
        }
        Order order = orderRepository.findByOrderNo(orderNo).orElse(null);
        if (order == null || Boolean.TRUE.equals(order.getIsError())
                || !STATUS_GENERATED.equalsIgnoreCase(order.getOrderStatus() == null ? "" : order.getOrderStatus())) {
            return false;
        }
        OrderTracking tracking = orderTrackingRepository.findByOrderNo(orderNo).orElse(null);
        if (tracking == null || !StringUtils.hasText(tracking.getTrackingNumber())) {
            return false; // order generated but no label on it yet
        }
        String carrier = shipmentBatchRepository.findByOrderNoOrderByBatchSeqAsc(orderNo).stream()
                .map(ShipmentBatch::getCarrierCode)
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse(row.getGeneratedCarrierCode());
        String was = row.getGeneratedStatus();
        stamp(row, STATUS_GENERATED, tracking.getTrackingNumber(), carrier, orderNo,
                labelledMessage(orderNo, tracking));
        log.info("DTC line {} (batch {}) realigned with label order {}: {} → GENERATED",
                row.getId(), row.getBatchId(), orderNo, was);
        return true;
    }

    /** Same wording the label path reports, so a realigned line reads like a generated one. */
    private static String labelledMessage(Integer orderNo, OrderTracking tracking) {
        return "Manual shipment #" + orderNo + " labelled"
                + (StringUtils.hasText(tracking.getWarehouseCode()) ? " on " + tracking.getWarehouseCode() : "")
                + ".";
    }

    private void stamp(DtcOrder row, String status, String tracking, String carrierCode,
                       Integer orderNo, String message) {
        row.setGeneratedStatus(status);
        row.setGeneratedTrackingNumber(tracking);
        row.setGeneratedCarrierCode(carrierCode);
        if (orderNo != null) {
            row.setGeneratedOrderNo(orderNo);
        }
        row.setGeneratedMessage(abbreviate(message));
        row.setGeneratedAt(LocalDateTime.now());
        dtcOrderRepository.save(row);
    }

    private void flushProgressIfDue(DtcGenerationJob job, int processed, int generated, int failed,
                                    int skipped, AtomicLong lastFlushMs) {
        long now = System.currentTimeMillis();
        boolean dueByCount = processed % PROGRESS_FLUSH_EVERY_N == 0;
        boolean dueByTime = now - lastFlushMs.get() >= PROGRESS_FLUSH_EVERY_MS;
        if (!dueByCount && !dueByTime) return;
        synchronized (job) {
            job.setProcessedRows(processed);
            job.setGeneratedCount(generated);
            job.setFailedCount(failed);
            job.setSkippedCount(skipped);
            job.setHeartbeatAt(LocalDateTime.now());
            jobRepository.save(job);
            lastFlushMs.set(now);
        }
    }

    private void failJob(DtcGenerationJob job, String message) {
        job.setStatus(DtcGenerationJob.FAILED);
        job.setErrorMessage(abbreviate(message));
        job.setFinishedAt(LocalDateTime.now());
        jobRepository.save(job);
        log.warn("DTC generation job {} FAILED: {}", job.getId(), message);
    }

    private synchronized void ensureRowPool() {
        if (rowPool == null) {
            int n = Math.max(1, rowConcurrency);
            rowPool = Executors.newFixedThreadPool(n, r -> {
                Thread t = new Thread(r, "dtc-generation-row");
                t.setDaemon(true);
                return t;
            });
        }
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (StringUtils.hasText(v)) return v.trim();
        }
        return null;
    }

    /**
     * A carrier HTTP-400 body runs longer than the column, and an over-long stamp fails
     * the UPDATE that is meant to record the failure — the line then shows no status at
     * all, with no error for the operator to act on.
     */
    private static String abbreviate(String message) {
        if (message == null) return null;
        String trimmed = message.trim();
        return trimmed.length() <= MESSAGE_MAX_LEN ? trimmed : trimmed.substring(0, MESSAGE_MAX_LEN);
    }

    private enum RowOutcome {
        GENERATED, FAILED, SKIPPED
    }
}
