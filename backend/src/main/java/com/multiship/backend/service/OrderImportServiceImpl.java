package com.multiship.backend.service;

import com.multiship.backend.dto.StagingUploadDTO;
import com.multiship.backend.model.ImportStagingRow;
import com.multiship.backend.model.ImportStagingUpload;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.OrderImportPreviewDTO;
import com.multiship.backend.dto.OrderImportRowDTO;
import com.multiship.backend.model.CarrierAccountRef;
import com.multiship.backend.model.Client;
import com.multiship.backend.model.Order;
import com.multiship.backend.model.ClientWarehouse;
import com.multiship.backend.model.Warehouse;
import com.multiship.backend.repository.CarrierAccountRefRepository;
import com.multiship.backend.repository.ClientRepository;
import com.multiship.backend.repository.ClientWarehouseRepository;
import com.multiship.backend.repository.OrderRepository;
import com.multiship.backend.repository.PackagePresetRepository;
import com.multiship.backend.repository.ShippingServiceRepository;
import com.multiship.backend.repository.WarehouseRepository;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Sprint 40 impl. Format detection by filename extension:
 * {@code .csv} → Apache Commons CSV; {@code .xlsx} → Apache POI XSSF.
 * Both formats normalise into the same {@link OrderImportRowDTO} shape.
 *
 * <p>Schema (columns in order — case-insensitive header row required):
 * <ul>
 *   <li>recipientName (required)</li>
 *   <li>recipientCompany, recipientPhone, recipientEmail</li>
 *   <li>addressLine1 (required), addressLine2</li>
 *   <li>city (required), state, postalCode (required), countryCode (required)</li>
 *   <li>carrierCode, serviceType, packageType</li>
 *   <li>weight (required, numeric > 0), weightUnit</li>
 *   <li>declaredValue, currency, reference, goodsDescription</li>
 * </ul>
 *
 * <p>Missing required fields, weight ≤ 0 or unparseable, or
 * declaredValue unparseable each produce a row-level error message.
 * The commit endpoint refuses to persist rows with errors.
 */
@Slf4j
@Service
public class OrderImportServiceImpl implements OrderImportService {

    private final CarrierService carrierService;

    /** Logs page: IMPORT_SAVED / IMPORT_GENERATED events. Optional so the
     *  hand-built test constructors don't have to plumb it. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AuditService auditService;
    /** Sprint 48 — used to bake per-client account dropdowns into the
     *  .xlsx template. Optional (null in the no-arg test constructor). */
    private final CarrierAccountRefRepository accountRefRepository;
    /** Sprint 48 — service catalog for the template's serviceType dropdown. */
    private final ShippingServiceRepository shippingServiceRepository;
    /** Sprint 48 — package presets for the template's packageType dropdown. */
    private final PackagePresetRepository packagePresetRepository;
    /** Sprint 48 — client list for the universal-template clientCode dropdown. */
    private final ClientRepository clientRepository;
    /** Sprint 48 — per-client warehouse attachments for the warehouseCode dropdown. */
    private final ClientWarehouseRepository clientWarehouseRepository;
    /** Sprint 48 — warehouse-code lookup for resolving ClientWarehouse.warehouseId → Warehouse.code. */
    private final WarehouseRepository warehouseRepository;
    /** Used to mint + stamp the shared import-batch id on every order generated
     *  from one commit() call (one CSV/XLSX file upload). */
    private final OrderRepository orderRepository;
    /** Sprint 48 — carrier address-validation service used by
     *  {@link #validateAddresses(List)}. Optional (null in test constructors). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AddressValidationService addressValidationService;

    /* --- Tier 3 rule tables. Optional so the existing test constructors
           (which pass nulls) keep working; each check no-ops when absent. --- */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.multiship.backend.repository.ServicePackageRepository servicePackageRepository;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.multiship.backend.repository.CarrierShippingLimitRepository carrierLimitRepository;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.multiship.backend.repository.ClientAllowedServiceRepository clientAllowedServiceRepository;
    /* --- Tier 4: tenant-defined custom-field definitions + value store. --- */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.multiship.backend.repository.CustomFieldDefinitionRepository customFieldDefinitionRepository;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private CustomFieldService customFieldService;

    /** Saved-import store for "Save to Data History" (commit-without-labels). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.multiship.backend.repository.ImportBatchRepository importBatchRepository;

    /** Live tracking for the retry sync (optional — unit-test constructors don't wire it). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.multiship.backend.repository.OrderTrackingRepository orderTrackingRepository;

    /**
     * SSE event bus — same publish pattern as BulkLabelServiceImpl.
     * Fire-and-forget; null when Redis is disabled. Publish sites are
     * every ImportBatch state transition (CAS gate flip, terminal in
     * generateLabelsForBatch's derive, cancellation, save).
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.multiship.backend.events.AppEventBus appEventBus;
    /** Catalog-aware service resolution (optional for the same reason). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ShippingConfigService shippingConfigService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.fasterxml.jackson.databind.ObjectMapper importObjectMapper;

    /** Sprint 50 Tier 0.5 PR G — tenant-scope clamp on every entry point.
     *  Optional so unit tests that construct the service via the no-arg
     *  or reduced-args constructor still compile. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.multiship.backend.service.TenantScopeEnforcer tenantScope;

    /** Null-safe wrapper around {@link TenantScopeEnforcer#clampClientCode(String)}.
     *  Returns the input unchanged when the enforcer isn't wired (tests). */
    private String clamp(String requested) {
        return tenantScope == null ? requested : tenantScope.clampClientCode(requested);
    }

    /** Null-safe wrapper around {@link TenantScopeEnforcer#requireTenantMatch(String)}.
     *  No-op when the enforcer isn't wired (tests). */
    private void requireMatch(String rowClientCode) {
        if (tenantScope != null) tenantScope.requireTenantMatch(rowClientCode);
    }

    /** Sprint 50 Tier 0.5 PR G — inspect the first non-blank clientCode across
     *  a batch's persisted rows. Used to enforce tenant match on batch-level
     *  operations (historyDetail, generateLabelsForBatch, generateLabelForRow)
     *  since {@link com.multiship.backend.model.ImportBatch} carries no direct
     *  tenant column — the tenant identity lives on each row of the payload. */
    private String firstClientCode(List<OrderImportRowDTO> rows) {
        if (rows == null) return null;
        for (OrderImportRowDTO r : rows) {
            String c = r.getClientCode();
            if (StringUtils.hasText(c)) return c;
        }
        return null;
    }

    /** Sprint 50 Tier 0.5 PR G — parse an ImportBatch's rowsJson to a list of
     *  OrderImportRowDTO. Empty list on any failure. */
    private List<OrderImportRowDTO> parseBatchRows(com.multiship.backend.model.ImportBatch batch) {
        if (batch == null || batch.getRowsJson() == null || importObjectMapper == null) {
            return java.util.List.of();
        }
        try {
            return importObjectMapper.readValue(
                    batch.getRowsJson(),
                    new com.fasterxml.jackson.core.type.TypeReference<List<OrderImportRowDTO>>() {});
        } catch (Exception e) {
            return java.util.List.of();
        }
    }

    /**
     * Sprint 50 Tier 1 finding #8 / Sprint 51 R4 (finding #4) — fan-out
     * executor for the commit loop. Pre-Sprint-50 the loop processed
     * groups serially on the request thread; a 500-row XLSX took 40-120 min.
     * Sprint 50 introduced the pool with a hardcoded 4 workers; Sprint 51
     * R4 externalises the size via application.properties (import.commit-
     * concurrency, import.max-per-tenant) with the same defaults chosen
     * for the bulk-label pool.
     */
    /**
     * Instance-field defaults kick in for pure-Mockito unit tests that
     * construct this service via {@code new} — Spring never resolves the
     * {@code @Value} annotations in that path, and {@link #initExecutors()}
     * won't fire either. {@link #ensureExecutors()} handles the lazy
     * fallback for the same reason.
     */
    @Value("${import.commit-concurrency:24}")
    private int importCommitConcurrency = 24;

    @Value("${import.max-per-tenant:8}")
    private int importMaxPerTenant = 8;

    private ExecutorService fanOutExecutor;

    /**
     * Live label-generation progress per batch id, so the UI can poll a real
     * "X of N" while a generate/retry runs. In-memory + concurrent: the
     * generate HTTP thread registers an entry and the fan-out workers increment
     * {@code done}; a separate poll request reads it; the entry is removed when
     * the run ends. No DB round-trip, so it's cheap to poll frequently.
     */
    private final Map<Long, GenProgress> generationProgressByBatch = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Import I-3 — cooperative cancellation flags for in-flight
     * generate-labels-for-batch runs. Same pattern as
     * {@code BulkLabelServiceImpl.cancelledJobIds}: presence of a batch
     * id means "operator asked to cancel". Workers gate on this BEFORE
     * calling the carrier inside {@code commit()}'s per-group loop so
     * queued groups are skipped; already-in-flight carrier calls run to
     * completion because we can't interrupt a paid label mid-request
     * without leaking it. Cleared in {@link #generateLabelsForBatch}'s
     * finally block regardless of outcome.
     */
    private final java.util.Set<Long> cancelledBatchIds =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Import I-11 — configurable cutoff for the startup housekeeper. Any
     * batch left in status IN_PROGRESS whose lastUpdatedAt is older than
     * this many minutes at boot is treated as a JVM-crash victim and
     * flipped back to a terminal state ({@code FAILED}) with a note in
     * the batch metadata. 60 min accommodates the worst-case commit
     * (large batches + slow carrier) with a huge safety margin.
     * Test-overridable via {@code import.stale-inprogress-cutoff-minutes}.
     */
    @Value("${import.stale-inprogress-cutoff-minutes:60}")
    private long staleInProgressCutoffMinutes = 60;

    /** Mutable counter behind {@link GenProgressView}. */
    private static final class GenProgress {
        final java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger();
        final int total;
        /** Shown under the progress bar while the run waits on a carrier; null otherwise. */
        volatile String note;
        GenProgress(int total) { this.total = total; }
    }

    /**
     * Sprint 50 PR K — commit() is called synchronously from the
     * OrderImportController HTTP handler, so the caller thread IS a
     * Tomcat worker. Bound the total time the HTTP thread can be blocked
     * on permit acquire to 30s; on overflow the batch aborts with a
     * TenantSaturatedException that the controller surfaces as 429.
     */
    // No total budget. A 1,000-order file cannot even be QUEUED in 30 s at
    // carrier speed (8 slots x ~3 s per label), and the old 30 s cap aborted
    // every large import after ~100 orders (2026-09-10 load test). A stalled
    // run is still caught: FairTenantExecutor gives each slot 60 s to free up.
    private static final long IMPORT_MAX_BATCH_WAIT_MS = Long.MAX_VALUE;
    private com.multiship.backend.service.fairness.FairTenantExecutor fairExecutor;

    @PostConstruct
    void initExecutors() {
        ensureExecutors();
        log.info("OrderImportServiceImpl fan-out ready: commitConcurrency={} maxPerTenant={}",
                importCommitConcurrency, importMaxPerTenant);
        reapStaleInProgressBatches();
    }

    /**
     * Import I-11 startup housekeeper. Sweeps import_batch rows left in
     * status=IN_PROGRESS from a prior JVM that crashed / was SIGKILL'd
     * past the graceful-shutdown window. Without this, a crash-victim
     * batch stays stuck in IN_PROGRESS forever AND — worse — the atomic
     * CAS gate on {@link #generateLabelsForBatch} would then reject any
     * retry with 409 IMPORT_BATCH_ALREADY_GENERATING because the row's
     * status still reads IN_PROGRESS.
     *
     * <p>Flips each stale row to FAILED with a diagnostic message so the
     * operator can see why the generation stopped and can retry from
     * Data History.
     *
     * <p>Package-private + returns count so tests can assert behavior.
     */
    int reapStaleInProgressBatches() {
        if (importBatchRepository == null) return 0;
        java.util.List<com.multiship.backend.model.ImportBatch> stale = importBatchRepository
                .findByStatusInOrderByIdAsc(java.util.List.of("IN_PROGRESS"));
        if (stale.isEmpty()) return 0;
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        int reaped = 0;
        for (com.multiship.backend.model.ImportBatch batch : stale) {
            // Extra guard: only reap batches genuinely older than the
            // cutoff; a fresh batch that legitimately started a moment
            // before the housekeeper fired must not be interrupted.
            // ImportBatch has no updated_at column so we use createdAt —
            // batches typically start generating within seconds of upload,
            // and the cutoff (default 60 min) is generous enough that a
            // legitimately running batch never trips.
            java.time.LocalDateTime lastActivity = batch.getCreatedAt();
            if (lastActivity != null
                    && lastActivity.isAfter(now.minusMinutes(staleInProgressCutoffMinutes))) {
                continue;
            }
            batch.setStatus("FAILED");
            String detail = "Marked FAILED by startup housekeeper: batch was IN_PROGRESS when the JVM "
                    + "restarted (created " + lastActivity + ", older than "
                    + staleInProgressCutoffMinutes + " min cutoff). Retry from Import history.";
            log.warn("Import batch {}: {}", batch.getId(), detail);
            importBatchRepository.save(batch);
            reaped++;
        }
        if (reaped > 0) {
            log.warn("Import startup housekeeper: reaped {} stale IN_PROGRESS import batch(es).", reaped);
        }
        return reaped;
    }

    /**
     * Sprint 51 BP-M2 — mirror BulkLabelServiceImpl. Give the in-flight
     * commit workers up to 30s to persist their orders before the JVM
     * exits so a rolling deploy doesn't strand half a batch with no
     * order row saved.
     */
    @PreDestroy
    void shutdownExecutors() {
        if (fanOutExecutor == null) return;
        fanOutExecutor.shutdown();
        try {
            if (!fanOutExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                log.warn("order-import-commit did not drain within 30s — forcing shutdownNow()");
                fanOutExecutor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            fanOutExecutor.shutdownNow();
        }
    }

    /** Lazy fallback — see the BulkLabelServiceImpl.ensureExecutors javadoc.
     *  Same rationale: pure-Mockito tests skip @PostConstruct, so we init
     *  on first use with whatever the compiled-in defaults are. */
    private synchronized void ensureExecutors() {
        if (fairExecutor != null) return;
        this.fanOutExecutor = Executors.newFixedThreadPool(importCommitConcurrency, r -> {
            Thread t = new Thread(r, "order-import-commit");
            t.setDaemon(true);
            return t;
        });
        this.fairExecutor = new com.multiship.backend.service.fairness.FairTenantExecutor(
                fanOutExecutor, importMaxPerTenant, 60, IMPORT_MAX_BATCH_WAIT_MS);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public OrderImportServiceImpl(CarrierService carrierService,
                                  CarrierAccountRefRepository accountRefRepository,
                                  ShippingServiceRepository shippingServiceRepository,
                                  PackagePresetRepository packagePresetRepository,
                                  ClientRepository clientRepository,
                                  ClientWarehouseRepository clientWarehouseRepository,
                                  WarehouseRepository warehouseRepository,
                                  OrderRepository orderRepository) {
        this.carrierService = carrierService;
        this.accountRefRepository = accountRefRepository;
        this.shippingServiceRepository = shippingServiceRepository;
        this.packagePresetRepository = packagePresetRepository;
        this.clientRepository = clientRepository;
        this.clientWarehouseRepository = clientWarehouseRepository;
        this.warehouseRepository = warehouseRepository;
        this.orderRepository = orderRepository;
    }

    public OrderImportServiceImpl() {
        this.carrierService = null;
        this.accountRefRepository = null;
        this.shippingServiceRepository = null;
        this.packagePresetRepository = null;
        this.clientRepository = null;
        this.clientWarehouseRepository = null;
        this.warehouseRepository = null;
        this.orderRepository = null;
    }

    /** Legacy Sprint-41 test constructor. */
    OrderImportServiceImpl(CarrierService carrierService) {
        this.carrierService = carrierService;
        this.accountRefRepository = null;
        this.shippingServiceRepository = null;
        this.packagePresetRepository = null;
        this.clientRepository = null;
        this.clientWarehouseRepository = null;
        this.warehouseRepository = null;
        this.orderRepository = null;
    }

    /** Canonical header ordering used for the template + parser column
     *  discovery. Column names are normalised to lowercase on match.
     *
     *  <p>Sprint 48 adds:
     *  <ul>
     *    <li>{@code orderRef} — order-group key. Rows sharing a non-blank
     *        orderRef fold into a single shipment; the first row supplies
     *        recipient / carrier / service, subsequent rows carry additional
     *        customs line-items.</li>
     *    <li>{@code itemDescription}, {@code itemSku}, {@code itemQuantity},
     *        {@code itemUnitValue}, {@code hsCode}, {@code countryOfOrigin}
     *        — per-line-item customs data. Optional; blank rows just skip
     *        the customs commodity block (domestic-only shipments).</li>
     *  </ul>
     */
    static final List<String> HEADERS = List.of(
            "orderRef",
            // Sprint 48 — clientCode + billTo + warehouseCode drive the
            // cascading dropdowns in the workbook. billTo unlocks
            // accountNumber free-text when THIRD_PARTY; warehouseCode
            // picks a specific origin (blank = client's default cascade).
            "clientCode", "billTo", "warehouseCode",
            "recipientName", "recipientCompany", "recipientPhone", "recipientEmail",
            "addressLine1", "addressLine2",
            "city", "state", "postalCode", "countryCode",
            "carrierCode", "accountNumber", "serviceType", "packageType",
            "weight", "weightUnit", "length", "width", "height", "dimUnit",
            "currency", "incoterms",
            "reference",
            // Sprint 48 revision — declaredValue derived at commit as
            // SUM(itemUnitValue × itemQuantity) so operators don't type
            // both; goodsDescription derived from the leader row's
            // itemDescription for the shipment-level description slot.
            "itemDescription", "itemSku", "itemQuantity", "itemUnitValue",
            "hsCode", "countryOfOrigin");

    /** Column names required for a valid row. */
    static final List<String> REQUIRED_COLUMNS = List.of(
            "recipientName", "addressLine1", "city", "postalCode",
            "countryCode", "weight");

    @Override
    public ApiResponse<OrderImportPreviewDTO> preview(String filename, InputStream body) {
        return preview(filename, body, null);
    }

    /**
     * Sprint 48 — reverse-lookup human names to wire codes on serviceType
     * and packageType. The universal template writes the user-friendly
     * name (e.g. "UPS Ground") into the cell, but every carrier connector
     * expects the wire code (e.g. "03"). We match on (carrier, name) via
     * the platform catalog. If the value already looks like a wire code
     * (uppercase alphanumeric, no spaces) or the lookup misses, we leave
     * the value untouched — operators overriding with a raw code still
     * work.
     */
    private void resolveNamesToCodes(List<OrderImportRowDTO> rows) {
        if (shippingServiceRepository == null || rows.isEmpty()) return;
        List<com.multiship.backend.model.ShippingService> services =
                shippingServiceRepository.findAllByOrderByCarrierAscSortOrderAsc();
        List<com.multiship.backend.model.PackagePreset> presets = packagePresetRepository == null
                ? List.of()
                : packagePresetRepository.findAllByOrderByIsDefaultDescNameAsc();
        for (OrderImportRowDTO row : rows) {
            String carrier = row.getCarrierCode();
            if (!StringUtils.hasText(carrier)) continue;
            String carrierU = carrier.toUpperCase(Locale.ROOT);
            // Service: (carrier, name) case-insensitive match. Skip lookup
            // when the value looks like a wire code already (no space,
            // ≤6 chars) so a raw "03" override stays untouched.
            String svcRaw = row.getServiceType();
            if (looksLikeName(svcRaw)) {
                for (com.multiship.backend.model.ShippingService s : services) {
                    if (!carrierU.equalsIgnoreCase(s.getCarrier())) continue;
                    if (svcRaw.equalsIgnoreCase(s.getName())) {
                        row.setServiceType(s.getServiceCode());
                        break;
                    }
                }
            }
            // Anything still not a catalog code ("GROUND" from a WMS,
            // "UPS_GROUND" from an ERP) → the carrier's own code, so the row
            // validates, the grid shows the real service, and the carrier is
            // never sent a word it doesn't know.
            if (shippingConfigService != null && StringUtils.hasText(row.getServiceType())) {
                String cur = row.getServiceType().trim();
                boolean isCode = services.stream().anyMatch(s -> carrierU.equalsIgnoreCase(s.getCarrier())
                        && s.getServiceCode() != null && s.getServiceCode().equalsIgnoreCase(cur));
                if (!isCode) {
                    shippingConfigService.resolveServiceCode(carrierU, cur, null)
                            .ifPresent(s -> row.setServiceType(s.getServiceCode()));
                }
            }
            // Package: (carrier, name) match against PackagePreset.name;
            // carrierPackageCode wins when present, else fall back to name.
            String pkgRaw = row.getPackageType();
            if (looksLikeName(pkgRaw)) {
                for (com.multiship.backend.model.PackagePreset p : presets) {
                    if (p.getCarrier() != null
                            && !carrierU.equalsIgnoreCase(p.getCarrier())) continue;
                    if (pkgRaw.equalsIgnoreCase(p.getName())) {
                        String code = p.getCarrierPackageCode();
                        row.setPackageType(code != null && !code.isBlank() ? code : p.getName());
                        break;
                    }
                }
            }
        }
    }

    /* ---------------- Tier 2: dynamic reference validation ---------------- */

    /** Carriers the platform can generate labels for. */
    private static final java.util.Set<String> KNOWN_CARRIERS =
            java.util.Set.of("UPS", "FEDEX", "USPS", "DHL");

    /**
     * Validate every code-bearing cell against the live reference data —
     * clients, per-client warehouse attachments, carrier accounts, and the
     * service/package catalogs. Rules are read fresh per call, so the
     * checks track whatever is registered at upload time (nothing is
     * hard-coded except the carrier set).
     *
     * <p>Severity follows the platform convention: a code that cannot
     * resolve at commit time (unknown client / detached warehouse /
     * unknown carrier) is an ERROR; a value the resolution cascade could
     * still legitimately handle (unknown account number, uncataloged
     * service or package code) is a WARNING.
     */
    void validateReferences(List<OrderImportRowDTO> rows) {
        if (clientRepository == null || rows.isEmpty()) return;

        // ---- Sprint 51 BP-M6: build the code set from the CSV rows so
        //      lookups stay bounded even on a mega-tenant install. Plus the
        //      caller's tenant scope (when scoped) so a partial match to
        //      the caller's clientCode always resolves.
        java.util.Set<String> rowClientCodes = new java.util.HashSet<>();
        for (OrderImportRowDTO row : rows) {
            if (StringUtils.hasText(row.getClientCode())) {
                rowClientCodes.add(row.getClientCode().trim().toUpperCase(Locale.ROOT));
            }
        }
        // A scoped USER always sees their own tenant + never sees another's.
        // Add the caller's own code so a blank clientCode row (already clamped
        // upstream) still validates against something.
        if (tenantScope != null) {
            tenantScope.resolveScope().ifPresent(s -> rowClientCodes.add(s.toUpperCase(Locale.ROOT)));
        }

        // ---- one snapshot per call: batched lookups, no per-row queries ----
        java.util.Set<String> activeClients = new java.util.HashSet<>();
        java.util.Set<String> inactiveClients = new java.util.HashSet<>();
        if (!rowClientCodes.isEmpty()) {
            for (Client c : clientRepository.findByClientCodeInIgnoreCase(rowClientCodes)) {
                String code = c.getClientCode() == null ? null : c.getClientCode().toUpperCase(Locale.ROOT);
                if (code == null) continue;
                if (c.isActive()) activeClients.add(code); else inactiveClients.add(code);
            }
        }
        // clientCode → set of attached warehouse codes. Sprint 51 BP-M6 —
        // resolve only the warehouse ids referenced by the caller's clients
        // instead of scanning every Warehouse row platform-wide.
        Map<String, java.util.Set<String>> attachedByClient = new LinkedHashMap<>();
        java.util.Map<Long, String> warehouseCodeById = new java.util.HashMap<>();
        if (clientWarehouseRepository != null && !rowClientCodes.isEmpty()) {
            // First pass: collect every warehouseId referenced by any of our
            // clients' attachments (single N-attachment fetch per client
            // reused for the per-row check below).
            java.util.Map<String, java.util.List<ClientWarehouse>> attachmentsByClient = new LinkedHashMap<>();
            java.util.Set<Long> warehouseIds = new java.util.HashSet<>();
            for (String key : rowClientCodes) {
                java.util.List<ClientWarehouse> attached = clientWarehouseRepository
                        .findByClientCodeIgnoreCaseOrderByIsDefaultDescCreatedAtAsc(key);
                attachmentsByClient.put(key, attached);
                for (ClientWarehouse cw : attached) {
                    if (cw.getWarehouseId() != null) warehouseIds.add(cw.getWarehouseId());
                }
            }
            if (warehouseRepository != null && !warehouseIds.isEmpty()) {
                for (Warehouse w : warehouseRepository.findByIdInAndActiveTrue(warehouseIds)) {
                    if (w.getCode() != null) {
                        warehouseCodeById.put(w.getId(), w.getCode().toUpperCase(Locale.ROOT));
                    }
                }
            }
            for (var entry : attachmentsByClient.entrySet()) {
                java.util.Set<String> codes = new java.util.HashSet<>();
                for (ClientWarehouse cw : entry.getValue()) {
                    String code = warehouseCodeById.get(cw.getWarehouseId());
                    if (code != null) codes.add(code);
                }
                attachedByClient.put(entry.getKey(), codes);
            }
        }
        // carrier → set of known account numbers (uppercased). Sprint 51
        // BP-M6 — only the caller's clients' accounts + platform accounts
        // (customerNo IS NULL / blank), never a competitor's numbers.
        // carrier -> (accountNumber -> owning client code; "" = platform account
        // usable by any client). Keyed by owner so a row billing to another
        // client's account is rejected at upload instead of at label time.
        Map<String, Map<String, String>> accountOwnerByCarrier = new LinkedHashMap<>();
        if (accountRefRepository != null && !rowClientCodes.isEmpty()) {
            for (CarrierAccountRef ref : accountRefRepository.findActiveByCustomerNoInOrPlatform(rowClientCodes)) {
                if (ref.getCarrierCode() == null || ref.getAccountNumber() == null) continue;
                String owner = StringUtils.hasText(ref.getCustomerNo())
                        ? ref.getCustomerNo().trim().toUpperCase(Locale.ROOT) : "";
                accountOwnerByCarrier
                        .computeIfAbsent(ref.getCarrierCode().toUpperCase(Locale.ROOT), k -> new java.util.HashMap<>())
                        .put(ref.getAccountNumber().trim().toUpperCase(Locale.ROOT), owner);
            }
        }
        // carrier → set of catalog service codes
        Map<String, java.util.Set<String>> servicesByCarrier = new LinkedHashMap<>();
        if (shippingServiceRepository != null) {
            for (com.multiship.backend.model.ShippingService s
                    : shippingServiceRepository.findAllByOrderByCarrierAscSortOrderAsc()) {
                if (s.getCarrier() == null || s.getServiceCode() == null) continue;
                servicesByCarrier
                        .computeIfAbsent(s.getCarrier().toUpperCase(Locale.ROOT), k -> new java.util.HashSet<>())
                        .add(s.getServiceCode().toUpperCase(Locale.ROOT));
            }
        }
        // package codes/names known to the catalog (any carrier)
        java.util.Set<String> knownPackages = new java.util.HashSet<>();
        if (packagePresetRepository != null) {
            for (com.multiship.backend.model.PackagePreset p
                    : packagePresetRepository.findAllByOrderByIsDefaultDescNameAsc()) {
                if (p.getName() != null) knownPackages.add(p.getName().toUpperCase(Locale.ROOT));
                if (p.getCarrierPackageCode() != null && !p.getCarrierPackageCode().isBlank()) {
                    knownPackages.add(p.getCarrierPackageCode().toUpperCase(Locale.ROOT));
                }
            }
        }

        // ---- per-row checks against the snapshot ----
        for (OrderImportRowDTO row : rows) {
            List<String> errors = new ArrayList<>(row.getErrors() == null ? List.of() : row.getErrors());
            List<String> warnings = new ArrayList<>(row.getWarnings() == null ? List.of() : row.getWarnings());

            String client = normalizeOrNull(row.getClientCode());
            if (client != null) {
                if (inactiveClients.contains(client)) {
                    errors.add("clientCode " + client + " is deactivated");
                } else if (!activeClients.contains(client)) {
                    errors.add("clientCode " + client + " is not registered");
                }
            }

            String warehouse = normalizeOrNull(row.getWarehouseCode());
            if (warehouse != null && client != null && activeClients.contains(client)) {
                java.util.Set<String> attached = attachedByClient.getOrDefault(client, java.util.Set.of());
                if (!attached.contains(warehouse)) {
                    errors.add("warehouseCode " + warehouse + " is not attached to client " + client);
                }
            }

            String carrier = normalizeOrNull(row.getCarrierCode());
            if (carrier != null && !KNOWN_CARRIERS.contains(carrier)) {
                errors.add("carrierCode '" + carrier + "' is not supported (UPS, FEDEX, USPS, DHL)");
            }

            String account = normalizeOrNull(row.getAccountNumber());
            boolean thirdParty = "THIRD_PARTY".equalsIgnoreCase(
                    row.getBillTo() == null ? "" : row.getBillTo().trim());
            if (account != null && carrier != null && KNOWN_CARRIERS.contains(carrier) && !thirdParty) {
                // The billing account must be registered AND either a platform
                // account or one owned by THIS row's client — otherwise it
                // validates clean but fails at label time ("not a registered
                // FEDEX account for this client"). THIRD_PARTY (external bill-to)
                // accounts are exempt — only format-checked in validateRow.
                Map<String, String> owners = accountOwnerByCarrier.getOrDefault(carrier, java.util.Map.of());
                String owner = owners.get(account);
                String rowClient = normalizeOrNull(row.getClientCode());
                if (owner == null) {
                    errors.add("accountNumber " + account + " is not a registered " + carrier
                            + " account for client " + (rowClient == null ? "(blank)" : rowClient)
                            + " or the platform");
                } else if (!owner.isEmpty() && rowClient != null && !owner.equals(rowClient)) {
                    errors.add("accountNumber " + account + " belongs to client " + owner
                            + ", not " + rowClient);
                }
            } else if (account == null && carrier != null && KNOWN_CARRIERS.contains(carrier)
                    && !thirdParty && client != null && activeClients.contains(client)
                    && accountRefRepository != null) {
                // Blank accountNumber → the row falls back to the client's
                // default/sole account at label time (resolveBulkAccount). If
                // that resolution can't pick one, generate fails at the carrier
                // with an order already minted (order 900100 sat in ERROR). Run
                // the SAME resolver here so the failure lands on the accountNumber
                // cell at upload/blur, exactly like every other reference check —
                // validation and generate stay in lockstep by calling one
                // resolver, never a re-implementation. THIRD_PARTY is exempt
                // (external bill-to, format-checked only in validateRow).
                BulkAccountPick pick = resolveBulkAccount(row.getClientCode(), carrier);
                if (pick.error != null) {
                    errors.add(pick.error);
                } else if (pick.accountNumber != null) {
                    // Resolution succeeded — say WHICH account will be billed when
                    // it isn't the client's own, so the platform-account fallback
                    // (client rule: blank account → house account, rebilled with
                    // markup) is visible at preview instead of a surprise on the
                    // invoice. Owner "" in the snapshot marks a platform account.
                    Map<String, String> owners = accountOwnerByCarrier.getOrDefault(carrier, java.util.Map.of());
                    String pickedOwner = owners.get(pick.accountNumber.trim().toUpperCase(Locale.ROOT));
                    if ("".equals(pickedOwner)) {
                        warnings.add("accountNumber is blank and " + client + " has no " + carrier
                                + " account — this shipment will bill the PLATFORM account "
                                + pick.accountNumber + " (rebilled to the client with markup)");
                    }
                }
            }

            String service = normalizeOrNull(row.getServiceType());
            if (service != null && carrier != null && KNOWN_CARRIERS.contains(carrier)) {
                java.util.Set<String> known = servicesByCarrier.getOrDefault(carrier, java.util.Set.of());
                if (!known.isEmpty() && !known.contains(service)) {
                    errors.add("serviceType '" + service + "' is not in the " + carrier
                            + " service catalog — use a code from Settings → Shipping services, "
                            + "or leave it blank for the client's default service");
                }
            }

            String pkg = normalizeOrNull(row.getPackageType());
            // YOUR_PACKAGING is the carrier-native "shipper's own box" code —
            // the label pipeline itself defaults to it, and the CSV template
            // ships it as the example value. It's always valid even when no
            // preset catalogs it (first-time installs hit exactly that: the
            // template's own sample row failed validation on first upload).
            if (pkg != null && !"YOUR_PACKAGING".equals(pkg)
                    && !knownPackages.isEmpty() && !knownPackages.contains(pkg)) {
                errors.add("packageType '" + pkg + "' is not a registered package preset");
            }

            row.setErrors(errors);
            row.setWarnings(warnings);
        }
    }

    private static String firstNonBlankStr(String... vals) {
        for (String v : vals) if (StringUtils.hasText(v)) return v.trim();
        return null;
    }

    private static String normalizeOrNull(String v) {
        return StringUtils.hasText(v) ? v.trim().toUpperCase(Locale.ROOT) : null;
    }

    /* ------------- Tier 4: tenant-defined custom-field columns ------------- */

    /**
     * Validate the non-schema columns against each client's active
     * {@link com.multiship.backend.model.CustomFieldDefinition}s.
     *
     * <p>A definition marked required must have a value on every row for
     * that client (ERROR — the order can't commit without it). Values that
     * are present are type-checked: NUMBER must parse, DATE must be
     * ISO {@code yyyy-MM-dd}, and SELECT must be one of the configured
     * options. Columns that match no definition are reported as a WARNING
     * and ignored, so a stray "Notes" column never blocks an import but
     * also never silently pretends to have been saved.
     *
     * <p>Row values are re-keyed to the definition's exact {@code fieldKey}
     * so the commit step can hand them straight to
     * {@code CustomFieldService.upsertValues}.
     */
    void validateCustomFields(List<OrderImportRowDTO> rows) {
        if (rows.isEmpty() || customFieldDefinitionRepository == null) return;

        // clientCode ("" = platform) → its applicable definitions
        Map<String, List<com.multiship.backend.model.CustomFieldDefinition>> defsByClient = new LinkedHashMap<>();
        for (OrderImportRowDTO row : rows) {
            String tenant = normalizeOrNull(row.getClientCode());
            String key = tenant == null ? "" : tenant;
            defsByClient.computeIfAbsent(key, k ->
                    customFieldDefinitionRepository.findApplicable(k.isEmpty() ? null : k));
        }

        for (OrderImportRowDTO row : rows) {
            String tenant = normalizeOrNull(row.getClientCode());
            List<com.multiship.backend.model.CustomFieldDefinition> defs =
                    defsByClient.getOrDefault(tenant == null ? "" : tenant, List.of());
            Map<String, String> provided = row.getCustomFields() == null
                    ? new LinkedHashMap<>() : new LinkedHashMap<>(row.getCustomFields());
            if (defs.isEmpty() && provided.isEmpty()) continue;

            Map<String, String> resolved = new LinkedHashMap<>();
            for (com.multiship.backend.model.CustomFieldDefinition def : defs) {
                if (def.getFieldKey() == null) continue;
                // Accept either the fieldKey or the human label as the header.
                String value = null;
                String matchedHeader = null;
                for (Map.Entry<String, String> e : provided.entrySet()) {
                    String h = e.getKey().trim();
                    if (h.equalsIgnoreCase(def.getFieldKey())
                            || (def.getLabel() != null && h.equalsIgnoreCase(def.getLabel()))) {
                        value = e.getValue();
                        matchedHeader = e.getKey();
                        break;
                    }
                }
                if (matchedHeader != null) provided.remove(matchedHeader);

                if (!StringUtils.hasText(value)) {
                    if (Boolean.TRUE.equals(def.getRequired())) {
                        addError(row, def.getFieldKey() + " is required (custom field"
                                + (def.getLabel() == null ? "" : " — " + def.getLabel()) + ")");
                    }
                    continue;
                }

                String trimmed = value.trim();
                switch (def.getFieldType()) {
                    case NUMBER -> {
                        try {
                            new BigDecimal(trimmed);
                        } catch (NumberFormatException ex) {
                            addError(row, def.getFieldKey() + " '" + trimmed + "' must be a number");
                        }
                    }
                    case DATE -> {
                        if (!trimmed.matches("\\d{4}-\\d{2}-\\d{2}")) {
                            addError(row, def.getFieldKey() + " '" + trimmed + "' must be a date (YYYY-MM-DD)");
                        }
                    }
                    case SELECT -> {
                        java.util.Set<String> options = new java.util.LinkedHashSet<>();
                        if (StringUtils.hasText(def.getSelectOptions())) {
                            for (String o : def.getSelectOptions().split(",")) {
                                if (StringUtils.hasText(o)) options.add(o.trim().toUpperCase(Locale.ROOT));
                            }
                        }
                        if (!options.isEmpty() && !options.contains(trimmed.toUpperCase(Locale.ROOT))) {
                            addError(row, def.getFieldKey() + " '" + trimmed + "' must be one of: "
                                    + def.getSelectOptions());
                        }
                    }
                    default -> { /* TEXT — sanitise() already ran at parse time */ }
                }
                // Keep the value even when it failed a check: the review UI
                // renders it under its own header so the operator can SEE and
                // correct it. The recorded error still blocks the commit.
                resolved.put(def.getFieldKey(), trimmed);
            }

            // Anything left over matched no definition for this client.
            for (String unknown : provided.keySet()) {
                addWarning(row, "column '" + unknown + "' is not a recognised field for "
                        + (tenant == null ? "the platform" : tenant) + " — it will be ignored");
            }
            row.setCustomFields(resolved.isEmpty() ? null : resolved);
        }
    }

    /* ------------- Tier 3: business rules from the rule tables ------------- */

    /** Normalise any accepted weight unit to pounds — every limit is stored in lb. */
    private static BigDecimal toPounds(BigDecimal weight, String unit) {
        if (weight == null) return null;
        String u = unit == null ? "LB" : unit.trim().toUpperCase(Locale.ROOT);
        return switch (u) {
            case "KG", "KGS" -> weight.multiply(new BigDecimal("2.20462"));
            case "OZ" -> weight.divide(new BigDecimal("16"), 4, java.math.RoundingMode.HALF_UP);
            default -> weight;
        };
    }

    /** Rows whose whole order (every line of its orderRef) passed validation — the rows that can be labelled. */
    private static int readyRowCount(List<OrderImportRowDTO> rows) {
        java.util.Set<String> broken = new java.util.HashSet<>();
        for (OrderImportRowDTO r : rows) {
            if (r.getErrors() != null && !r.getErrors().isEmpty()) broken.add(groupKeyOf(r));
        }
        int ready = 0;
        for (OrderImportRowDTO r : rows) {
            if (!broken.contains(groupKeyOf(r))) ready++;
        }
        return ready;
    }

    /**
     * A row-edit JSON body applied on top of a copy of the stored row, so only the
     * fields it names change. An empty body or JSON that isn't a row → 400.
     */
    private OrderImportRowDTO mergeRowJson(OrderImportRowDTO current, String json) {
        if (!StringUtils.hasText(json)) {
            throw new ImportBatchStateException(400,
                    "Send the row as JSON — the fields to change, or the whole row as the import returns it.");
        }
        com.fasterxml.jackson.databind.ObjectMapper mapper = importObjectMapper != null
                ? importObjectMapper : new com.fasterxml.jackson.databind.ObjectMapper();
        try {
            OrderImportRowDTO copy = mapper.readValue(mapper.writeValueAsString(current), OrderImportRowDTO.class);
            OrderImportRowDTO merged = mapper.readerForUpdating(copy)
                    .without(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(json);
            return merged == null ? copy : merged;
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            throw new ImportBatchStateException(400, "The row isn't valid JSON: " + e.getOriginalMessage());
        }
    }

    /**
     * Next label batch number. Taken from a sequence (V53): MAX(batch_id)+1 only
     * counts batches that already have orders, so two files uploaded before
     * either generated labels were both given the same number.
     */
    private Integer mintLabelBatchId() {
        if (orderRepository == null) return null;
        try {
            Long next = orderRepository.nextLabelBatchNumber();
            if (next != null) return next.intValue();
        } catch (Exception e) {
            log.warn("label_batch_number_seq unavailable — falling back to MAX(batch_id)+1: {}", e.getMessage());
        }
        Integer max = orderRepository.findMaxBatchId();
        return (max == null ? 0 : max) + 1;
    }

    /** "2 orders in this import are also in live imports: MT1-01 (#87), …" — or null when none are. */
    private String liveOverlapSummary(List<OrderImportRowDTO> rows, Long self) {
        if (importBatchRepository == null || rows == null) return null;
        java.util.Set<String> refs = new java.util.LinkedHashSet<>();
        for (OrderImportRowDTO r : rows) {
            if (StringUtils.hasText(r.getOrderRef())) refs.add(r.getOrderRef().trim().toUpperCase(Locale.ROOT));
        }
        if (refs.isEmpty()) return null;
        List<String> hits = new ArrayList<>();
        try {
            for (Object[] o : importBatchRepository.findBatchesHoldingOrderRefs(refs)) {
                if (o == null || o.length < 2 || o[0] == null || o[1] == null) continue;
                long other = ((Number) o[1]).longValue();
                if (self != null && other == self) continue;
                hits.add(o[0] + " (#" + other + ")");
            }
        } catch (Exception ex) {
            log.warn("Restore: duplicate-order check skipped: {}", ex.getMessage());
            return null;
        }
        if (hits.isEmpty()) return null;
        String list = String.join(", ", hits.subList(0, Math.min(5, hits.size())))
                + (hits.size() > 5 ? " and " + (hits.size() - 5) + " more" : "");
        return hits.size() + (hits.size() == 1 ? " order in this import is" : " orders in this import are")
                + " also in live imports: " + list + ".";
    }

    /** Group key used for order grouping — mirrors commit()/international rules. */
    private static String groupKeyOf(OrderImportRowDTO row) {
        return StringUtils.hasText(row.getOrderRef())
                ? row.getOrderRef().trim()
                : "__row_" + row.getRowNumber();
    }

    /** Shipment-level fields an order shares across its rows (item columns excluded). */
    private static final List<java.util.function.Function<OrderImportRowDTO, String>> GROUP_GETTERS = List.of(
            OrderImportRowDTO::getClientCode, OrderImportRowDTO::getBillTo, OrderImportRowDTO::getWarehouseCode,
            OrderImportRowDTO::getRecipientName, OrderImportRowDTO::getRecipientCompany, OrderImportRowDTO::getRecipientPhone,
            OrderImportRowDTO::getRecipientEmail, OrderImportRowDTO::getAddressLine1, OrderImportRowDTO::getAddressLine2,
            OrderImportRowDTO::getCity, OrderImportRowDTO::getState, OrderImportRowDTO::getPostalCode,
            OrderImportRowDTO::getCountryCode, OrderImportRowDTO::getCarrierCode, OrderImportRowDTO::getAccountNumber,
            OrderImportRowDTO::getServiceType, OrderImportRowDTO::getPackageType, OrderImportRowDTO::getWeightUnit,
            OrderImportRowDTO::getDimUnit, OrderImportRowDTO::getCurrency, OrderImportRowDTO::getIncoterms,
            OrderImportRowDTO::getReference);
    private static final List<java.util.function.BiConsumer<OrderImportRowDTO, String>> GROUP_SETTERS = List.of(
            OrderImportRowDTO::setClientCode, OrderImportRowDTO::setBillTo, OrderImportRowDTO::setWarehouseCode,
            OrderImportRowDTO::setRecipientName, OrderImportRowDTO::setRecipientCompany, OrderImportRowDTO::setRecipientPhone,
            OrderImportRowDTO::setRecipientEmail, OrderImportRowDTO::setAddressLine1, OrderImportRowDTO::setAddressLine2,
            OrderImportRowDTO::setCity, OrderImportRowDTO::setState, OrderImportRowDTO::setPostalCode,
            OrderImportRowDTO::setCountryCode, OrderImportRowDTO::setCarrierCode, OrderImportRowDTO::setAccountNumber,
            OrderImportRowDTO::setServiceType, OrderImportRowDTO::setPackageType, OrderImportRowDTO::setWeightUnit,
            OrderImportRowDTO::setDimUnit, OrderImportRowDTO::setCurrency, OrderImportRowDTO::setIncoterms,
            OrderImportRowDTO::setReference);

    private static boolean sameText(String a, String b) {
        return (a == null ? "" : a.trim()).equalsIgnoreCase(b == null ? "" : b.trim());
    }

    /** See updateBatchRow: changed shipment-level fields follow to the order's other, not-yet-shipped rows. */
    private static void propagateGroupEdit(List<OrderImportRowDTO> rows, OrderImportRowDTO before, OrderImportRowDTO after) {
        if (after == null || before == null || !StringUtils.hasText(after.getOrderRef())) return;
        for (OrderImportRowDTO other : rows) {
            if (other == after || other == before) continue;
            if (!StringUtils.hasText(other.getOrderRef()) || !sameText(other.getOrderRef(), after.getOrderRef())) continue;
            if ("GENERATED".equalsIgnoreCase(other.getGeneratedStatus())) continue;
            boolean touched = false;
            for (int i = 0; i < GROUP_GETTERS.size(); i++) {
                String was = GROUP_GETTERS.get(i).apply(before);
                String now = GROUP_GETTERS.get(i).apply(after);
                if (sameText(was, now)) continue;                       // field not edited
                String theirs = GROUP_GETTERS.get(i).apply(other);
                if (sameText(theirs, was)) {                            // they carried the old value
                    GROUP_SETTERS.get(i).accept(other, now);
                    touched = true;
                }
            }
            if (touched) {
                // weight follows the same rule (numeric)
                if (before.getWeight() != null && after.getWeight() != null && before.getWeight().compareTo(after.getWeight()) != 0
                        && other.getWeight() != null && other.getWeight().compareTo(before.getWeight()) == 0) {
                    other.setWeight(after.getWeight());
                }
                if (!"FAILED".equalsIgnoreCase(other.getGeneratedStatus())) other.setGeneratedStatus(null);
            }
        }
    }

    /** Same-order rows must agree on shipment-level fields; see validateBusinessRules. */
    private static void checkGroupField(OrderImportRowDTO r, OrderImportRowDTO leader, String field,
                                        String value, String leaderValue, boolean error) {
        if (!StringUtils.hasText(value) || !StringUtils.hasText(leaderValue)) return;
        if (value.trim().equalsIgnoreCase(leaderValue.trim())) return;
        String msg = field + " '" + value.trim() + "' differs from the first row of order "
                + leader.getOrderRef() + " ('" + leaderValue.trim() + "') — every row of one order must agree";
        if (error) addError(r, msg);
        else addWarning(r, msg + "; only the first row's value is used");
    }

    /**
     * An orderRef that already produced a label in an earlier import is
     * almost always a re-upload. Duplicate-file detection (name / content
     * hash) misses a file edited by one character; this catches the order.
     */
    public void flagOrderRefsAlreadyGenerated(List<OrderImportRowDTO> rows) {
        if (importBatchRepository == null || rows == null || rows.isEmpty()) return;
        Map<String, List<OrderImportRowDTO>> byRef = new LinkedHashMap<>();
        for (OrderImportRowDTO r : rows) {
            if (StringUtils.hasText(r.getOrderRef())) {
                byRef.computeIfAbsent(r.getOrderRef().trim().toUpperCase(Locale.ROOT), k -> new ArrayList<>()).add(r);
            }
        }
        if (byRef.isEmpty()) return;
        try {
            int scanned = 0;
            java.util.Set<String> flagged = new java.util.HashSet<>();
            for (com.multiship.backend.model.ImportBatch b : importBatchRepository.findAllByDeletedAtIsNullOrderByIdDesc()) {
                if (scanned++ >= 60 || flagged.size() == byRef.size()) break;
                for (OrderImportRowDTO prev : parseBatchRows(b)) {
                    if (!StringUtils.hasText(prev.getOrderRef()) || prev.getGeneratedOrderNo() == null) continue;
                    // The import row's status is frozen at generation time; an
                    // order repaired later via Fix & regenerate is GENERATED in the
                    // orders table while its row still says FAILED — ask the order.
                    if (!"GENERATED".equalsIgnoreCase(prev.getGeneratedStatus()) && !orderIsLabelled(prev.getGeneratedOrderNo())) continue;
                    String key = prev.getOrderRef().trim().toUpperCase(Locale.ROOT);
                    List<OrderImportRowDTO> mine = byRef.get(key);
                    if (mine == null || flagged.contains(key)) continue;
                    // Re-running the batch that created the order is not a duplicate.
                    if (mine.stream().anyMatch(m -> prev.getGeneratedOrderNo().equals(m.getGeneratedOrderNo()))) continue;
                    flagged.add(key);
                    for (OrderImportRowDTO m : mine) {
                        addWarning(m, "orderRef " + prev.getOrderRef().trim() + " was already generated as order "
                                + prev.getGeneratedOrderNo() + " in import #" + b.getId()
                                + " — generating again creates a duplicate shipment");
                    }
                }
            }
        } catch (Exception ignore) {
            // advisory only — never block a preview on history parsing
        }
        // Live orders as well: an import batch can be deleted from history
        // while its labelled orders live on, and an order repaired via Fix &
        // regenerate is GENERATED regardless of what its import row says.
        // The order stores the file's reference (else its orderRef) as
        // customer_ref, so match on either value.
        if (importBatchRepository == null) return;
        try {
            Map<String, List<OrderImportRowDTO>> byKey = new LinkedHashMap<>();
            for (OrderImportRowDTO r : rows) {
                if (!StringUtils.hasText(r.getOrderRef())) continue;
                String key = StringUtils.hasText(r.getReference()) ? r.getReference().trim() : r.getOrderRef().trim();
                byKey.computeIfAbsent(key.toUpperCase(Locale.ROOT), k -> new ArrayList<>()).add(r);
            }
            if (byKey.isEmpty()) return;
            List<Object[]> hits = importBatchRepository.findGeneratedOrdersByCustomerRefIn(byKey.keySet());
            log.debug("duplicate-orderRef advisory (live orders): refs={} hits={}", byKey.keySet(), hits.size());
            for (Object[] hit : hits) {
                Object orderNo = hit[0];
                String ref = hit[1] == null ? null : String.valueOf(hit[1]);
                if (!StringUtils.hasText(ref)) continue;
                List<OrderImportRowDTO> mine = byKey.remove(ref.trim().toUpperCase(Locale.ROOT));
                if (mine == null) continue;
                String msg = "reference " + ref.trim() + " already has a labelled order (#" + orderNo
                        + ") — generating again creates a duplicate shipment";
                for (OrderImportRowDTO m : mine) {
                    boolean already = m.getWarnings() != null && m.getWarnings().stream().anyMatch(w -> w.contains("already generated as order") || w.contains("already has a labelled order"));
                    if (!already) addWarning(m, msg);
                }
            }
        } catch (Exception ex) {
            // advisory only — but say why it could not run
            log.warn("duplicate-orderRef advisory (live orders) skipped: {}", ex.toString());
        }
    }

    /** See generateLabelsForBatch: rows whose order is already labelled become GENERATED and are left alone. */
    private void syncRowsWithLiveOrders(List<OrderImportRowDTO> rows) {
        if (orderRepository == null || rows == null) return;
        for (OrderImportRowDTO r : rows) {
            if (r.getGeneratedOrderNo() == null || "GENERATED".equalsIgnoreCase(r.getGeneratedStatus())) continue;
            try {
                Integer no = r.getGeneratedOrderNo();
                boolean live = orderRepository.findByOrderNo(no)
                        .map(o -> "GENERATED".equalsIgnoreCase(o.getOrderStatus())).orElse(false);
                if (!live) continue;
                r.setGeneratedStatus("GENERATED");
                if (orderTrackingRepository != null) {
                    orderTrackingRepository.findByOrderNo(no).ifPresent(t -> {
                        if (StringUtils.hasText(t.getTrackingNumber())) r.setGeneratedTrackingNumber(t.getTrackingNumber());
                    });
                }
                r.setGeneratedMessage("Labelled from the Orders grid (order #" + no + ") — not re-sent.");
            } catch (Exception ignore) {
                // best effort; the row keeps its stored status
            }
        }
    }

    /**
     * {@link #syncRowsWithLiveOrders(List)}, plus rows that carry NO order number
     * but whose order already exists in this import's label batch - labelled by a
     * worker that finished after the row set was saved. Matched by the customer
     * reference the order was created with (the row's reference, else its
     * orderRef). Without this a Retry labels those orders a second time.
     */
    void syncRowsWithLiveOrders(List<OrderImportRowDTO> rows, Integer labelBatchId) {
        syncRowsWithLiveOrders(rows);
        if (rows == null || labelBatchId == null || importBatchRepository == null) return;
        Map<String, List<OrderImportRowDTO>> byRef = new LinkedHashMap<>();
        for (OrderImportRowDTO r : rows) {
            if (r.getGeneratedOrderNo() != null || "GENERATED".equalsIgnoreCase(r.getGeneratedStatus())) continue;
            for (String k : new String[]{r.getReference(), r.getOrderRef()}) {
                if (StringUtils.hasText(k)) {
                    byRef.computeIfAbsent(k.trim().toUpperCase(Locale.ROOT), x -> new ArrayList<>()).add(r);
                }
            }
        }
        if (byRef.isEmpty()) return;
        try {
            // One order per reference: a GENERATED one wins, else the latest.
            Map<String, Object[]> best = new LinkedHashMap<>();
            for (Object[] o : importBatchRepository.findOrdersInLabelBatchByCustomerRefIn(labelBatchId, byRef.keySet())) {
                String ref = String.valueOf(o[1]).trim().toUpperCase(Locale.ROOT);
                Object[] cur = best.get(ref);
                boolean gen = "GENERATED".equalsIgnoreCase(String.valueOf(o[2]));
                boolean curGen = cur != null && "GENERATED".equalsIgnoreCase(String.valueOf(cur[2]));
                if (cur == null || gen || !curGen) best.put(ref, o);
            }
            for (Map.Entry<String, Object[]> e : best.entrySet()) {
                Integer no = ((Number) e.getValue()[0]).intValue();
                boolean gen = "GENERATED".equalsIgnoreCase(String.valueOf(e.getValue()[2]));
                for (OrderImportRowDTO r : byRef.getOrDefault(e.getKey(), List.of())) {
                    if (r.getGeneratedOrderNo() != null) continue;
                    r.setGeneratedOrderNo(no);
                    r.setBatchId(labelBatchId);
                    if (gen) {
                        r.setGeneratedStatus("GENERATED");
                        if (orderTrackingRepository != null) {
                            orderTrackingRepository.findByOrderNo(no).ifPresent(t -> {
                                if (StringUtils.hasText(t.getTrackingNumber())) r.setGeneratedTrackingNumber(t.getTrackingNumber());
                            });
                        }
                        r.setGeneratedMessage("Labelled in an earlier run (order #" + no + ") — not re-sent.");
                    }
                }
            }
        } catch (Exception ex) {
            log.warn("Import retry: could not match rows to orders in label batch {}: {}", labelBatchId, ex.getMessage());
        }
    }

    /** "108 of 1000 order(s) labelled · 2 failed": orders, not rows (item-line rows share their order's label). */
    static String ordersSummary(List<OrderImportRowDTO> rows) {
        Map<String, String> state = new LinkedHashMap<>();
        int pos = 0;
        for (OrderImportRowDTO r : rows == null ? List.<OrderImportRowDTO>of() : rows) {
            pos++;
            String key = StringUtils.hasText(r.getOrderRef()) ? r.getOrderRef().trim().toUpperCase(Locale.ROOT) : "#pos" + pos;
            String st = r.getGeneratedStatus() == null ? "" : r.getGeneratedStatus().trim().toUpperCase(Locale.ROOT);
            state.merge(key, st, (a, b) -> a.equals("GENERATED") || b.equals("GENERATED") ? "GENERATED"
                    : (a.equals("FAILED") || b.equals("FAILED") ? "FAILED" : a));
        }
        long gen = state.values().stream().filter("GENERATED"::equals).count();
        long failed = state.values().stream().filter("FAILED"::equals).count();
        return gen + " of " + state.size() + " order(s) labelled" + (failed > 0 ? " · " + failed + " failed" : "");
    }

    private boolean orderIsLabelled(Integer orderNo) {
        if (orderRepository == null || orderNo == null) return false;
        try {
            return orderRepository.findByOrderNo(orderNo)
                    .map(o -> "GENERATED".equalsIgnoreCase(o.getOrderStatus())).orElse(false);
        } catch (Exception e) {
            return false;
        }
    }

    /** Blank warehouseCode → the client's default warehouse (what the New Shipment form pre-selects). */
    private void applyDefaultWarehouse(com.multiship.backend.dto.ManualShipmentRequest req, String clientCode) {
        if (StringUtils.hasText(req.getWarehouseCode()) || !StringUtils.hasText(clientCode)) return;
        if (clientWarehouseRepository == null || warehouseRepository == null) return;
        try {
            clientWarehouseRepository.findByClientCodeIgnoreCaseAndIsDefaultTrue(clientCode.trim())
                    .flatMap(link -> warehouseRepository.findById(link.getWarehouseId()))
                    .filter(w -> !Boolean.FALSE.equals(w.getActive()) && StringUtils.hasText(w.getCode()))
                    .ifPresent(w -> req.setWarehouseCode(w.getCode()));
        } catch (Exception ignore) {
            // fall back to the client's registered address (applyClientOrigin)
        }
    }

    /**
     * Resolve the row's serviceType (a catalog code such as 03 / 08 /
     * INTERNATIONAL_PRIORITY, or a display name) to the catalog service and
     * put its id on the request. Returns an error message when the value
     * cannot be honoured; null when it was applied or left blank (blank →
     * the client / carrier default cascade, as before).
     */
    private String applyRequestedService(com.multiship.backend.dto.ManualShipmentRequest req, OrderImportRowDTO leader) {
        String code = normalizeOrNull(leader.getServiceType());
        if (code == null || shippingServiceRepository == null) return null;
        String carrier = normalizeOrNull(leader.getCarrierCode());
        if (carrier == null) return null;
        String canon = ShippingConfigService.canonicalCarrierFor(carrier);
        String origin = req.getSender() == null ? null : normalizeOrNull(req.getSender().getCountryCode());
        if (shippingConfigService == null) return null;
        java.util.Optional<com.multiship.backend.model.ShippingService> resolved =
                shippingConfigService.resolveServiceCode(canon, leader.getServiceType(), origin);
        if (resolved.isEmpty()) {
            return "serviceType '" + leader.getServiceType().trim() + "' is not in the " + canon
                    + " service catalog — use a code from Settings → Shipping services, or leave it blank for the default";
        }
        com.multiship.backend.model.ShippingService pick = resolved.get();
        if (!pick.isEnabled()) {
            return "serviceType '" + leader.getServiceType().trim() + "' (" + pick.getName() + ") is disabled in the "
                    + canon + " service catalog";
        }
        req.setServiceId(pick.getId());
        return null;
    }

    private static void addError(OrderImportRowDTO row, String message) {
        List<String> errs = new ArrayList<>(row.getErrors() == null ? List.of() : row.getErrors());
        if (!errs.contains(message)) errs.add(message);
        row.setErrors(errs);
    }

    private static void addWarning(OrderImportRowDTO row, String message) {
        List<String> warns = new ArrayList<>(row.getWarnings() == null ? List.of() : row.getWarnings());
        if (!warns.contains(message)) warns.add(message);
        row.setWarnings(warns);
    }

    /**
     * Validate each row against the configured shipping rules rather than
     * just its own shape:
     *
     * <ul>
     *   <li><b>Service ↔ package</b> — the package must be one the chosen
     *       service actually accepts ({@code service_package} links).</li>
     *   <li><b>Per-service weight cap</b> — {@code ShippingService.maxWeightLb}.</li>
     *   <li><b>Carrier limits</b> — {@code CarrierShippingLimit} max total
     *       weight and max pieces per shipment, plus a surcharge warning once
     *       declared value passes the free allowance.</li>
     *   <li><b>Client entitlement</b> — a client with an explicit allowed-service
     *       list may not use a service outside it.</li>
     *   <li><b>Group consistency</b> — rows sharing an orderRef must agree on
     *       recipient / carrier, because only the leader's values are used.</li>
     *   <li><b>Duplicates</b> — two groups shipping the same thing to the same
     *       address is nearly always a double-paste.</li>
     * </ul>
     *
     * Severity follows the platform convention: something the carrier will
     * certainly reject is an ERROR; something the resolution cascade can still
     * handle, or that merely costs money, is a WARNING.
     */
    void validateBusinessRules(List<OrderImportRowDTO> rows) {
        flagOrderRefsAlreadyGenerated(rows);
        if (rows.isEmpty() || shippingServiceRepository == null) return;

        // --- snapshot the rule tables once ---
        Map<String, com.multiship.backend.model.ShippingService> serviceByKey = new LinkedHashMap<>();
        for (com.multiship.backend.model.ShippingService s
                : shippingServiceRepository.findAllByOrderByCarrierAscSortOrderAsc()) {
            if (s.getCarrier() == null || s.getServiceCode() == null) continue;
            serviceByKey.putIfAbsent(
                    s.getCarrier().toUpperCase(Locale.ROOT) + '|' + s.getServiceCode().toUpperCase(Locale.ROOT), s);
        }
        Map<Long, com.multiship.backend.model.PackagePreset> presetById = new LinkedHashMap<>();
        if (packagePresetRepository != null) {
            for (com.multiship.backend.model.PackagePreset p
                    : packagePresetRepository.findAllByOrderByIsDefaultDescNameAsc()) {
                presetById.put(p.getId(), p);
            }
        }
        // clientCode → allowed serviceIds (empty set = no restriction configured)
        Map<String, java.util.Set<Long>> allowedByClient = new LinkedHashMap<>();
        if (clientAllowedServiceRepository != null) {
            for (OrderImportRowDTO row : rows) {
                String cc = normalizeOrNull(row.getClientCode());
                if (cc == null || allowedByClient.containsKey(cc)) continue;
                java.util.Set<Long> ids = new java.util.HashSet<>();
                for (com.multiship.backend.model.ClientAllowedService a
                        : clientAllowedServiceRepository
                        .findByClientCodeIgnoreCaseOrderByIsDefaultDescCreatedAtAsc(cc)) {
                    if (a.getServiceId() != null) ids.add(a.getServiceId());
                }
                allowedByClient.put(cc, ids);
            }
        }

        // --- per-row checks ---
        for (OrderImportRowDTO row : rows) {
            String carrier = normalizeOrNull(row.getCarrierCode());
            String serviceCode = normalizeOrNull(row.getServiceType());
            if (carrier == null) continue;

            com.multiship.backend.model.ShippingService service =
                    serviceCode == null ? null : serviceByKey.get(carrier + '|' + serviceCode);
            BigDecimal lb = toPounds(row.getWeight(), row.getWeightUnit());

            if (service != null) {
                // Per-service weight ceiling — the carrier rejects the label outright.
                if (lb != null && service.getMaxWeightLb() != null
                        && lb.compareTo(new BigDecimal(service.getMaxWeightLb())) > 0) {
                    addError(row, "weight " + lb.setScale(1, java.math.RoundingMode.HALF_UP)
                            + " lb exceeds the " + service.getMaxWeightLb() + " lb limit for " + serviceCode);
                }

                // Package must be one this service accepts.
                String pkg = normalizeOrNull(row.getPackageType());
                if (pkg != null && servicePackageRepository != null) {
                    java.util.Set<String> allowedPkgs = new java.util.HashSet<>();
                    for (com.multiship.backend.model.ServicePackage link
                            : servicePackageRepository.findByServiceId(service.getId())) {
                        com.multiship.backend.model.PackagePreset p = presetById.get(link.getPresetId());
                        if (p == null) continue;
                        if (p.getName() != null) allowedPkgs.add(p.getName().toUpperCase(Locale.ROOT));
                        if (StringUtils.hasText(p.getCarrierPackageCode())) {
                            allowedPkgs.add(p.getCarrierPackageCode().toUpperCase(Locale.ROOT));
                        }
                    }
                    if (!allowedPkgs.isEmpty() && !allowedPkgs.contains(pkg)) {
                        addWarning(row, "packageType '" + pkg + "' is not linked to service "
                                + serviceCode + "; the package cascade may pick a different one");
                    }
                }

                // Client entitlement — only enforced when the client has a list.
                String cc = normalizeOrNull(row.getClientCode());
                java.util.Set<Long> allowed = cc == null ? null : allowedByClient.get(cc);
                if (allowed != null && !allowed.isEmpty() && !allowed.contains(service.getId())) {
                    addError(row, "serviceType " + serviceCode + " is not in the allowed-service list for client " + cc);
                }
            }
        }

        // --- per-group checks (limits apply to the shipment, not the line) ---
        Map<String, List<OrderImportRowDTO>> groups = new LinkedHashMap<>();
        for (OrderImportRowDTO row : rows) {
            groups.computeIfAbsent(groupKeyOf(row), k -> new ArrayList<>()).add(row);
        }

        for (List<OrderImportRowDTO> group : groups.values()) {
            OrderImportRowDTO leader = group.get(0);
            String carrier = normalizeOrNull(leader.getCarrierCode());
            if (carrier == null) continue;
            String serviceCode = normalizeOrNull(leader.getServiceType());
            String country = normalizeOrNull(leader.getCountryCode());
            String scope = "US".equals(country) || country == null ? "DOMESTIC" : "INTERNATIONAL";

            // Group consistency — commit() uses ONLY the leader's recipient and
            // carrier, so a disagreeing row silently ships to the wrong place.
            for (int i = 1; i < group.size(); i++) {
                OrderImportRowDTO r = group.get(i);
                if (StringUtils.hasText(r.getRecipientName())
                        && !r.getRecipientName().equalsIgnoreCase(leader.getRecipientName())) {
                    addWarning(r, "recipientName differs from the first row of order " + leader.getOrderRef()
                            + "; only the first row's recipient is used");
                }
                String rc = normalizeOrNull(r.getCarrierCode());
                if (rc != null && !rc.equals(carrier)) {
                    addWarning(r, "carrierCode differs from the first row of order " + leader.getOrderRef()
                            + "; only the first row's carrier is used");
                }
                // Shipment-level fields must agree across every row of one
                // order — a USD row and a EUR row used to be summed as one
                // currency onto the customs value.
                checkGroupField(r, leader, "addressLine1", r.getAddressLine1(), leader.getAddressLine1(), true);
                checkGroupField(r, leader, "city", r.getCity(), leader.getCity(), true);
                checkGroupField(r, leader, "postalCode", r.getPostalCode(), leader.getPostalCode(), true);
                checkGroupField(r, leader, "countryCode", r.getCountryCode(), leader.getCountryCode(), true);
                checkGroupField(r, leader, "accountNumber", r.getAccountNumber(), leader.getAccountNumber(), true);
                checkGroupField(r, leader, "serviceType", r.getServiceType(), leader.getServiceType(), true);
                checkGroupField(r, leader, "currency", r.getCurrency(), leader.getCurrency(), true);
                checkGroupField(r, leader, "weightUnit", r.getWeightUnit(), leader.getWeightUnit(), true);
                checkGroupField(r, leader, "billTo", r.getBillTo(), leader.getBillTo(), true);
                checkGroupField(r, leader, "incoterms", r.getIncoterms(), leader.getIncoterms(), true);
                checkGroupField(r, leader, "clientCode", r.getClientCode(), leader.getClientCode(), true);
                checkGroupField(r, leader, "recipientCompany", r.getRecipientCompany(), leader.getRecipientCompany(), false);
                checkGroupField(r, leader, "recipientPhone", r.getRecipientPhone(), leader.getRecipientPhone(), false);
                checkGroupField(r, leader, "recipientEmail", r.getRecipientEmail(), leader.getRecipientEmail(), false);
            }

            if (carrierLimitRepository == null) continue;
            var limitOpt = carrierLimitRepository.resolve(carrier, serviceCode, scope);
            if (limitOpt.isEmpty()) continue;
            com.multiship.backend.model.CarrierShippingLimit limit = limitOpt.get();

            if (limit.getMaxPackages() != null && group.size() > limit.getMaxPackages()) {
                addError(leader, "order has " + group.size() + " pieces; " + carrier
                        + " allows " + limit.getMaxPackages() + " per shipment");
            }

            if (limit.getMaxTotalWeightLb() != null) {
                BigDecimal total = BigDecimal.ZERO;
                for (OrderImportRowDTO r : group) {
                    BigDecimal w = toPounds(r.getWeight(), r.getWeightUnit());
                    if (w != null) total = total.add(w);
                }
                if (total.compareTo(limit.getMaxTotalWeightLb()) > 0) {
                    addError(leader, "total weight " + total.setScale(1, java.math.RoundingMode.HALF_UP)
                            + " lb exceeds the " + limit.getMaxTotalWeightLb() + " lb limit for " + carrier);
                }
            }

            if (limit.getFreeDeclaredValue() != null) {
                BigDecimal declared = BigDecimal.ZERO;
                for (OrderImportRowDTO r : group) {
                    if (r.getItemUnitValue() == null) continue;
                    int qty = r.getItemQuantity() == null ? 1 : r.getItemQuantity();
                    declared = declared.add(r.getItemUnitValue().multiply(BigDecimal.valueOf(qty)));
                }
                if (declared.compareTo(limit.getFreeDeclaredValue()) > 0) {
                    addWarning(leader, "declared value " + declared.stripTrailingZeros().toPlainString()
                            + " is over " + carrier + "'s free allowance of "
                            + limit.getFreeDeclaredValue().stripTrailingZeros().toPlainString()
                            + "; an insurance surcharge will apply");
                }
            }
        }

        // --- duplicate shipments across groups ---
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (Map.Entry<String, List<OrderImportRowDTO>> entry : groups.entrySet()) {
            OrderImportRowDTO leader = entry.getValue().get(0);
            if (!StringUtils.hasText(leader.getRecipientName())
                    || !StringUtils.hasText(leader.getAddressLine1())) continue;
            String fingerprint = String.join("|",
                    leader.getRecipientName().trim().toUpperCase(Locale.ROOT),
                    leader.getAddressLine1().trim().toUpperCase(Locale.ROOT),
                    leader.getPostalCode() == null ? "" : leader.getPostalCode().trim().toUpperCase(Locale.ROOT),
                    leader.getReference() == null ? "" : leader.getReference().trim().toUpperCase(Locale.ROOT));
            Integer firstRow = seen.putIfAbsent(fingerprint, leader.getRowNumber());
            if (firstRow != null) {
                addWarning(leader, "same recipient, address and reference as row " + firstRow
                        + " — check this isn't a duplicate");
            }
        }
    }

    /** Heuristic — treat a value as a display name when it contains a
     *  space or a lowercase letter. Wire codes are UPPER + digits by
     *  convention across UPS / FedEx / DHL. */
    private static boolean looksLikeName(String v) {
        if (!StringUtils.hasText(v)) return false;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == ' ' || (c >= 'a' && c <= 'z')) return true;
        }
        return false;
    }

    @Override
    public ApiResponse<OrderImportPreviewDTO> preview(String filename, InputStream body, Long expectedAccountId) {
        return preview(filename, body, expectedAccountId, false);
    }

    @Override
    public ApiResponse<OrderImportPreviewDTO> preview(String filename, InputStream body, Long expectedAccountId,
                                                      boolean allowDuplicate) {
        String ext = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        try {
            List<OrderImportRowDTO> rows;
            if (ext.endsWith(".xlsx")) {
                rows = parseXlsx(body);
            } else if (ext.endsWith(".csv") || ext.endsWith(".txt")) {
                rows = parseCsv(body);
            } else {
                return failure(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                        "Only .csv, .txt, and .xlsx files are supported.");
            }
            // Sprint 50 Tier 0.5 PR G — clamp each row's clientCode to the
            // caller's tenant scope. For scoped USERs a blank code is forced
            // to their own tenant; a foreign code throws 403 immediately so
            // the upload never persists cross-tenant rows. Operators pass
            // through unchanged.
            for (OrderImportRowDTO row : rows) {
                row.setClientCode(clamp(row.getClientCode()));
            }
            // Reject at UPLOAD time when this file is already in Import history —
            // same file NAME, or same row CONTENT under a different name — so the
            // operator sees "already imported" immediately instead of only when
            // they hit Save. To re-import, delete the existing entry or edit its
            // rows in the saved grid (updateRow, a different path).
            // allowDuplicate — the operator confirmed "import anyway as a new
            // batch" (a re-sent daily file, the shipped template a second time).
            // Per-orderRef duplicate warnings still apply on the rows.
            if (!allowDuplicate && importBatchRepository != null && !rows.isEmpty()) {
                String normName = StringUtils.hasText(filename) ? filename.trim() : null;
                if (normName != null) {
                    com.multiship.backend.model.ImportBatch byName = importBatchRepository
                            .findFirstByFileNameIgnoreCaseAndDeletedAtIsNullOrderByIdDesc(normName).orElse(null);
                    if (byName != null) {
                        return failure(HttpStatus.CONFLICT,
                                "A file named \"" + normName + "\" is already imported as #" + byName.getId()
                                + (byName.getCreatedAt() != null ? " on " + byName.getCreatedAt().toLocalDate() : "")
                                + ". Delete it from Import history (or rename the file) before importing again.");
                    }
                }
                String hash = contentHash(rows);
                if (hash != null) {
                    com.multiship.backend.model.ImportBatch dup = importBatchRepository
                            .findFirstByContentHashAndDeletedAtIsNullOrderByIdDesc(hash).orElse(null);
                    if (dup != null) {
                        return failure(HttpStatus.CONFLICT,
                                "This file was already imported as #" + dup.getId()
                                + " (" + (StringUtils.hasText(dup.getFileName()) ? dup.getFileName() : "Untitled") + ")"
                                + ". Edit a value or upload a different file to import a changed version.");
                    }
                }
            }
            // Mint one batch id for this file upload right away and stamp
            // every row with it, so the whole sheet is grouped together
            // from the moment it's uploaded — commit() re-uses this same
            // id (rows round-trip it back) instead of minting a new one.
            if (orderRepository != null && !rows.isEmpty()) {
                Integer batchId = mintLabelBatchId();
                for (OrderImportRowDTO row : rows) {
                    row.setBatchId(batchId);
                }
            }
            // Sprint 48 — reverse-lookup human names ("UPS Ground") to
            // wire codes ("03") on service / package cells. The universal
            // template writes names; every carrier connector expects codes.
            resolveNamesToCodes(rows);
            // Dynamic rule validation — codes checked against the live
            // client / warehouse / account / catalog tables.
            validateReferences(rows);
            // Tier 3 — rules from the shipping catalog + limit tables.
            validateBusinessRules(rows);
            validateCustomFields(rows);
            // Sprint 48 — international shipments must carry customs
            // commodity data on at least one row in the group.
            validateInternationalItems(rows);
            // Sprint 48 — divergence warning. Resolve the expected account
            // once, then annotate every row whose accountNumber deviates.
            // Non-fatal: warnings never block commit; operators may edit
            // rows deliberately to bill a different account.
            if (expectedAccountId != null && accountRefRepository != null) {
                CarrierAccountRef expected = accountRefRepository.findById(expectedAccountId).orElse(null);
                if (expected != null && StringUtils.hasText(expected.getAccountNumber())) {
                    String expectedNumber = expected.getAccountNumber().trim();
                    for (OrderImportRowDTO row : rows) {
                        // Only warn when the row DOES carry an account and it
                        // differs — blank accountNumber inherits the template
                        // default at commit time, that's fine.
                        String rowNumber = row.getAccountNumber();
                        if (StringUtils.hasText(rowNumber)
                                && !rowNumber.trim().equalsIgnoreCase(expectedNumber)) {
                            List<String> warnings = new ArrayList<>(
                                    row.getWarnings() == null ? List.of() : row.getWarnings());
                            warnings.add("Template account = " + expectedNumber
                                    + " but row uses " + rowNumber + ". Row wins at commit.");
                            row.setWarnings(warnings);
                        }
                    }
                }
            }
            OrderImportPreviewDTO preview = buildPreview(rows);
            preview.setBatchId(rows.isEmpty() ? null : rows.get(0).getBatchId());
            return success(preview, rows.size() + " row(s) parsed.");
        } catch (Exception ex) {
            log.warn("Order import parse failed for {}: {}", filename, ex.getMessage());
            return failure(HttpStatus.BAD_REQUEST,
                    "Failed to parse " + filename + ": " + ex.getMessage());
        }
    }

    @Override
    public ApiResponse<OrderImportPreviewDTO> validate(List<OrderImportRowDTO> rows) {
        if (rows == null) rows = List.of();
        // Sprint 50 Tier 0.5 PR G — clamp before any validation so a
        // tenant-scoped USER re-submitting rows edited to a foreign
        // clientCode is rejected 403 before we spend cycles validating.
        for (OrderImportRowDTO row : rows) {
            row.setClientCode(clamp(row.getClientCode()));
        }
        // Re-run the sanitize → name-lookup → per-row → international
        // pipeline over the (possibly-edited) rows. We DON'T re-parse
        // strings through sanitise() here because the payload is JSON,
        // not raw CSV, and the frontend has already trimmed. But we
        // do re-run validateRow so any missing required fields the
        // operator introduced by editing get flagged.
        for (OrderImportRowDTO row : rows) {
            row.setErrors(validateRow(row));
            row.setWarnings(List.of()); // clear warnings; will be re-added by validators below
        }
        resolveNamesToCodes(rows);
        validateReferences(rows);
        validateBusinessRules(rows);
        validateCustomFields(rows);
        validateInternationalItems(rows);
        return success(buildPreview(rows), rows.size() + " row(s) validated.");
    }

    @Override
    public ApiResponse<OrderImportPreviewDTO> validateAddresses(List<OrderImportRowDTO> rows) {
        if (rows == null) rows = List.of();
        if (addressValidationService == null) {
            return failure(HttpStatus.SERVICE_UNAVAILABLE,
                    "Address validation service not wired.");
        }
        // Sprint 50 Tier 0.5 PR G — clamp before we call the carrier's
        // address validator (which is billed on the tenant's account) so
        // a scoped USER can't burn a foreign tenant's address-validation
        // quota by re-labeling a row.
        for (OrderImportRowDTO row : rows) {
            row.setClientCode(clamp(row.getClientCode()));
        }
        // Per-row: build an AddressValidationRequestDTO from the recipient
        // block, call the carrier's validateAddress, append a warning if
        // the carrier reports the address as invalid. Rows without a
        // picked carrier are skipped silently — no picked-carrier =
        // nothing to validate against.
        for (OrderImportRowDTO row : rows) {
            if (!StringUtils.hasText(row.getCarrierCode())) continue;
            if (!StringUtils.hasText(row.getAddressLine1())
                    || !StringUtils.hasText(row.getCity())
                    || !StringUtils.hasText(row.getPostalCode())
                    || !StringUtils.hasText(row.getCountryCode())) continue;
            com.multiship.backend.dto.AddressValidationRequestDTO req =
                    new com.multiship.backend.dto.AddressValidationRequestDTO();
            req.setCarrierCode(row.getCarrierCode());
            req.setCustomerNo(row.getClientCode());
            req.setName(row.getRecipientName());
            req.setCompany(row.getRecipientCompany());
            req.setAddressLine1(row.getAddressLine1());
            req.setAddressLine2(row.getAddressLine2());
            req.setCity(row.getCity());
            req.setState(row.getState());
            req.setPostalCode(row.getPostalCode());
            req.setCountryCode(row.getCountryCode());
            try {
                ApiResponse<com.multiship.backend.dto.AddressValidationResponseDTO> resp =
                        addressValidationService.validate(req);
                com.multiship.backend.dto.AddressValidationResponseDTO data =
                        resp == null ? null : resp.getData();
                if (data != null && !data.isValid()) {
                    List<String> warnings = new ArrayList<>(
                            row.getWarnings() == null ? List.of() : row.getWarnings());
                    String suggested = "";
                    if (data.getSuggested() != null
                            && data.getSuggested().getAddressLine1() != null) {
                        suggested = " Suggested: "
                                + data.getSuggested().getAddressLine1()
                                + " " + (data.getSuggested().getCity() == null ? "" : data.getSuggested().getCity())
                                + " " + (data.getSuggested().getPostalCode() == null ? "" : data.getSuggested().getPostalCode());
                    }
                    warnings.add("Address invalid ("
                            + row.getCarrierCode() + "): "
                            + (data.getMessage() == null ? "carrier rejected" : data.getMessage())
                            + suggested);
                    row.setWarnings(warnings);
                }
            } catch (Exception ex) {
                log.warn("Address validation failed for row {}: {}",
                        row.getRowNumber(), ex.getMessage());
                List<String> warnings = new ArrayList<>(
                        row.getWarnings() == null ? List.of() : row.getWarnings());
                warnings.add("Address validation call failed: " + ex.getMessage());
                row.setWarnings(warnings);
            }
        }
        return success(buildPreview(rows), rows.size() + " row(s) address-checked.");
    }

    @Override
    public ApiResponse<OrderImportPreviewDTO> commit(List<OrderImportRowDTO> rows, String requestedBy) {
        return commit(rows, requestedBy, false);
    }

    public ApiResponse<OrderImportPreviewDTO> commit(List<OrderImportRowDTO> rows, String requestedBy,
                                                     boolean usePlatformAccount) {
        return commit(rows, requestedBy, usePlatformAccount, null);
    }

    public ApiResponse<OrderImportPreviewDTO> commit(List<OrderImportRowDTO> rows, String requestedBy,
                                                     boolean usePlatformAccount, String sourceOverride) {
        return commit(rows, requestedBy, usePlatformAccount, sourceOverride, null);
    }

    /**
     * @param usePlatformAccount when true, every group bills to the platform
     *   (house) account for its carrier, overriding any row/client account.
     *   Used by the Data History "Use platform account" generate option.
     * @param sourceOverride stamps the persisted order's {@code source} (e.g.
     *   "API" for WMS-fetched batches). Null → the default "BULK".
     * @param onGroupComplete run once per group as it finishes (success OR
     *   failure), on the worker thread — drives the live "X of N" progress bar.
     *   Null when no progress reporting is needed.
     */
    public ApiResponse<OrderImportPreviewDTO> commit(List<OrderImportRowDTO> rows, String requestedBy,
                                                     boolean usePlatformAccount, String sourceOverride,
                                                     Runnable onGroupComplete) {
        return commit(rows, requestedBy, usePlatformAccount, sourceOverride, onGroupComplete, null);
    }

    /**
     * Import I-3 overload — additionally accepts {@code cancelCheck}, a
     * supplier a per-group worker polls just before invoking the carrier.
     * When it returns true, the worker records a "cancelled" outcome for
     * that group and skips the carrier call. Already-in-flight carrier
     * calls run to completion because we can't interrupt a paid label
     * mid-request without leaking it.
     *
     * <p>Passed as null from callers that don't support cancellation
     * (direct commit() from the manual paths); passed non-null from
     * {@link #generateLabelsForBatch} which owns the batch-level cancel
     * flag.
     */
    public ApiResponse<OrderImportPreviewDTO> commit(List<OrderImportRowDTO> rows, String requestedBy,
                                                     boolean usePlatformAccount, String sourceOverride,
                                                     Runnable onGroupComplete,
                                                     java.util.function.BooleanSupplier cancelCheck) {
        return commit(rows, requestedBy, usePlatformAccount, sourceOverride, onGroupComplete, cancelCheck, null);
    }

    /** As above; {@code onNote} gets a one-line status while the run waits on a carrier (null clears it). */
    public ApiResponse<OrderImportPreviewDTO> commit(List<OrderImportRowDTO> rows, String requestedBy,
                                                     boolean usePlatformAccount, String sourceOverride,
                                                     Runnable onGroupComplete,
                                                     java.util.function.BooleanSupplier cancelCheck,
                                                     java.util.function.Consumer<String> onNote) {
        if (rows == null || rows.isEmpty()) {
            return failure(HttpStatus.BAD_REQUEST, "No rows to commit.");
        }
        // Sprint 50 Tier 0.5 PR G — clamp before we mint labels / persist
        // orders. Frontend edits between preview and commit could otherwise
        // slip a foreign clientCode past preview's clamp; re-clamping here
        // ensures every persisted order + carrier call is tenant-correct.
        for (OrderImportRowDTO row : rows) {
            row.setClientCode(clamp(row.getClientCode()));
        }
        // One id per file upload — every order this commit() call generates a
        // label for gets stamped with the same batchId, so the whole sheet's
        // orders can be found/grouped together later. preview() already
        // minted + stamped one on every row when the file was first
        // uploaded; reuse that so preview and commit agree on the number.
        // Fall back to minting a fresh one only if rows arrive without it
        // (e.g. commit called directly, bypassing preview).
        Integer batchId = rows.stream()
                .map(OrderImportRowDTO::getBatchId)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElseGet(this::mintLabelBatchId);
        // Frontend may edit rows post-preview; re-run the name→code
        // reverse-lookup here so a value the operator pasted in
        // ("UPS Ground") still resolves to the wire code before the
        // carrier connector sees it.
        resolveNamesToCodes(rows);
        // Final server-side gate for the dynamic reference checks — start
        // from a clean slate (rows round-trip stale preview errors) so the
        // merge below sees only current failures. Warnings too: they were
        // never reset here, so every generate/retry pass re-APPENDED the
        // same advisory (rows showed the platform-billing note 3× after
        // three attempts).
        for (OrderImportRowDTO row : rows) {
            row.setErrors(List.of());
            row.setWarnings(List.of());
        }
        validateReferences(rows);
        validateBusinessRules(rows);
        validateCustomFields(rows);
        // Same for the international-item rule — operator edits could
        // have introduced a new international row without customs data.
        validateInternationalItems(rows);

        // Sprint 48 — group rows by orderRef so multi-row orders (one
        // shipment, N line-items) fold into a single label call. Rows
        // WITHOUT orderRef stay standalone (pre-Sprint-48 behaviour).
        // Ordering is preserved (LinkedHashMap) so preview and commit rows
        // line up 1:1 in the output.
        // The first row of each group is the "leader" — its recipient +
        // shipment fields drive the request; subsequent rows contribute
        // only customs line-items.
        Map<String, List<OrderImportRowDTO>> groups = new LinkedHashMap<>();
        for (OrderImportRowDTO row : rows) {
            String key = StringUtils.hasText(row.getOrderRef())
                    ? row.getOrderRef().trim()
                    : "__row_" + row.getRowNumber(); // unique standalone key
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }

        // Sprint 50 Tier 1 finding #8 — parallelize the commit loop across
        // fanOutExecutor. Each group's work is isolated: it only mutates
        // rows it owns, and generateManualLabel is already @Transactional
        // per-call (Spring wraps each carrierService call in its own tx),
        // so no per-thread JPA session leaks. invokeAll preserves task
        // order → per-group outcomes come back in input row order, so the
        // aggregate summary matches the pre-fix semantics.
        int groupCount = groups.size();
        // Sprint 50 PR M — L3: was WARN (log-spam risk under high-throughput
        // commits); this is informational fan-out telemetry, not an alert.
        log.info("Order import commit ({}): fanning {} groups across {} worker(s).",
                requestedBy, groupCount, importCommitConcurrency);

        List<List<OrderImportRowDTO>> groupList = new ArrayList<>(groups.values());
        List<Callable<GroupOutcome>> tasks = new ArrayList<>(groupCount);
        for (List<OrderImportRowDTO> group : groupList) {
            tasks.add(groupTask(group, batchId, usePlatformAccount, sourceOverride, onGroupComplete, cancelCheck));
        }

        // Sprint 50 Tier 1 finding #15 — tenant key for fair-share. Groups
        // in a single import file usually share a client; pick the first
        // group's leader clientCode. Blank means "no fairness" which is
        // fine for platform-owned imports.
        String tenantKey = groups.values().stream()
                .findFirst()
                .filter(g -> !g.isEmpty())
                .map(g -> g.get(0).getClientCode())
                .orElse(requestedBy);

        int valid = 0;
        int invalid = 0;
        int generated = 0;
        try {
            ensureExecutors();
            List<Future<GroupOutcome>> futures = fairExecutor.submitAll(tenantKey, tasks);
            List<List<OrderImportRowDTO>> deferred = new ArrayList<>();
            for (int i = 0; i < futures.size(); i++) {
                GroupOutcome outcome = futures.get(i).get();
                if (outcome.rateLimited()) { deferred.add(groupList.get(i)); continue; }
                valid += outcome.valid;
                invalid += outcome.invalid;
                generated += outcome.generated;
            }
            // Wait out the carrier's cool-down, then resend only the throttled
            // orders; up to MAX_RATE_LIMIT_PASSES times with a growing wait.
            for (int pass = 1; pass <= MAX_RATE_LIMIT_PASSES && !deferred.isEmpty(); pass++) {
                if (cancelCheck != null && cancelCheck.getAsBoolean()) break;
                long waitMs = waitForCarriers(deferred);
                String who = carrierLabel(carrierKey(deferred.get(0).get(0)));
                log.info("Order import commit ({}): {} order(s) rate-limited by {}; waiting {} s before retry {} of {}",
                        requestedBy, deferred.size(), who, waitMs / 1000, pass, MAX_RATE_LIMIT_PASSES);
                long end = System.currentTimeMillis() + waitMs;
                boolean cancelled = false;
                while (System.currentTimeMillis() < end) {
                    if (cancelCheck != null && cancelCheck.getAsBoolean()) { cancelled = true; break; }
                    long left = Math.max(0, end - System.currentTimeMillis());
                    if (onNote != null) onNote.accept(who + " asked us to slow down — retrying " + deferred.size()
                            + " order(s) in " + ((left + 999) / 1000) + " s (attempt " + pass + " of " + MAX_RATE_LIMIT_PASSES + ")");
                    Thread.sleep(Math.min(1_000L, Math.max(1L, left)));
                }
                if (cancelled) break;
                if (onNote != null) onNote.accept("Retrying " + deferred.size() + " rate-limited order(s) (attempt "
                        + pass + " of " + MAX_RATE_LIMIT_PASSES + ")");
                List<Callable<GroupOutcome>> retryTasks = new ArrayList<>(deferred.size());
                for (List<OrderImportRowDTO> g : deferred) {
                    retryTasks.add(groupTask(g, batchId, usePlatformAccount, sourceOverride, onGroupComplete, cancelCheck));
                }
                List<Future<GroupOutcome>> retryFutures = fairExecutor.submitAll(tenantKey, retryTasks);
                List<List<OrderImportRowDTO>> still = new ArrayList<>();
                for (int i = 0; i < retryFutures.size(); i++) {
                    GroupOutcome outcome = retryFutures.get(i).get();
                    if (outcome.rateLimited()) { still.add(deferred.get(i)); continue; }
                    valid += outcome.valid;
                    invalid += outcome.invalid;
                    generated += outcome.generated;
                }
                deferred = still;
            }
            for (List<OrderImportRowDTO> g : deferred) {
                String who = carrierLabel(carrierKey(g.get(0)));
                for (OrderImportRowDTO r : g) {
                    r.setGeneratedMessage(who + " is still rate-limiting after " + MAX_RATE_LIMIT_PASSES
                            + " automatic retries — click Retry in a few minutes.");
                }
                if (onGroupComplete != null) onGroupComplete.run();
            }
            if (onNote != null) onNote.accept(null);
        } catch (com.multiship.backend.service.fairness.FairTenantExecutor.TenantSaturatedException sat) {
            // No worker slot freed up for this client within the per-slot wait,
            // so the rest of the file was not dispatched. Orders already handed
            // to workers are still being labelled: WAIT for them. The caller
            // saves the row set right after this, and a row whose order lands
            // after that save reads "not generated" - a Retry then labels it a
            // second time (8 duplicates in the 2026-09-10 load test).
            for (Future<?> f : sat.getPartialFutures()) {
                try {
                    Object o = f.get();
                    if (o instanceof GroupOutcome g) {
                        valid += g.valid;
                        invalid += g.invalid;
                        generated += g.generated;
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (java.util.concurrent.ExecutionException ee) {
                    log.warn("Order import commit worker crashed: {}", ee.getMessage(), ee);
                }
            }
            log.warn("Order import commit for tenant {} stalled: {} of {} groups dispatched (all finished)",
                    tenantKey, sat.getSubmittedTasks(), sat.getTotalTasks());
            return ApiResponse.<OrderImportPreviewDTO>builder()
                    .status("error").code(HttpStatus.TOO_MANY_REQUESTS.value())
                    .errorCode(ErrorCode.TENANT_RATE_LIMITED.name())
                    .message("Label generation stalled: no worker slot freed up for this client for 60 s. "
                            + sat.getSubmittedTasks() + " of " + sat.getTotalTasks()
                            + " orders were sent; click Retry to send the rest.")
                    .build();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("Order import commit interrupted; partial results may be present.");
        } catch (java.util.concurrent.ExecutionException ee) {
            // invokeAll's Future.get only throws when a task itself threw an
            // unchecked exception it didn't catch; processGroup catches every
            // per-group failure and stamps the row, so this branch means a
            // bug in the outer plumbing (e.g. an NPE in the aggregator). Log
            // it and let the caller see a partial summary rather than 500.
            log.warn("Order import commit worker crashed: {}", ee.getMessage(), ee);
        }

        log.info("Order import commit ({}): {} valid, {} invalid, {} labels generated",
                requestedBy, valid, invalid, generated);
        return success(OrderImportPreviewDTO.builder()
                .totalRows(rows.size())
                .validRows(valid)
                .invalidRows(invalid)
                .batchId(generated > 0 ? batchId : null)
                .rows(rows)
                .build(),
                generated > 0
                        ? generated + " label(s) generated"
                                + (invalid > 0 ? " · " + invalid + " row(s) skipped" : "")
                        : (invalid > 0
                                ? invalid + " row(s) skipped — none committed"
                                : "0 label(s) generated"));
    }

    // ── Save to Data History (persist the imported data, no labels) ──────────
    @Override
    public ApiResponse<OrderImportPreviewDTO> save(List<OrderImportRowDTO> rows, String requestedBy,
                                                   String fileName, boolean draft) {
        return save(rows, requestedBy, fileName, draft, false);
    }

    @Override
    public ApiResponse<OrderImportPreviewDTO> save(List<OrderImportRowDTO> rows, String requestedBy,
                                                   String fileName, boolean draft, boolean allowDuplicate) {
        List<OrderImportRowDTO> safe = rows == null ? java.util.List.of() : rows;
        // Sprint 50 Tier 0.5 PR G — clamp before we persist rowsJson so a
        // scoped USER can't seed the import_batch table with foreign
        // clientCodes that later history() calls would surface.
        for (OrderImportRowDTO row : safe) {
            row.setClientCode(clamp(row.getClientCode()));
        }
        int total = safe.size();

        // Re-run the full validation pipeline server-side (never trust the
        // client-sent errors) so the persisted per-row state is authoritative.
        for (OrderImportRowDTO row : safe) {
            row.setErrors(validateRow(row));
            row.setWarnings(List.of());
        }
        resolveNamesToCodes(safe);
        validateReferences(safe);
        validateBusinessRules(safe);
        validateCustomFields(safe);
        validateInternationalItems(safe);

        int invalid = (int) safe.stream()
                .filter(r -> r.getErrors() != null && !r.getErrors().isEmpty())
                .count();
        // Ready = rows whose whole order is clean (an order is labelled as one shipment).
        int saved = readyRowCount(safe);

        // Sprint 51 — two save modes:
        //   final (draft=false) — REFUSE (422) unless every row is valid.
        //   draft (draft=true)  — park the whole batch even with bad rows so
        //                         the operator never loses work-in-progress.
        if (!draft && invalid > 0) {
            return failure(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Can't save — " + invalid + " of " + total
                    + " row(s) still have errors. Fix every row, or use \"Save as draft\".");
        }

        // A draft that still has bad rows is parked as DRAFT; a clean batch
        // (final save, or a draft that happens to be error-free) starts its
        // label lifecycle at INITIATE. Each row carries its own fate:
        //   SAVED     — clean, ready to generate a label.
        //   NEEDS_FIX — has errors; held in a draft until corrected.
        String status = (draft && invalid > 0) ? "DRAFT" : "INITIATE";
        for (OrderImportRowDTO r : safe) {
            boolean clean = r.getErrors() == null || r.getErrors().isEmpty();
            r.setGeneratedStatus(clean ? "SAVED" : "NEEDS_FIX");
        }

        // De-dup Import history — a save / save-as-draft is REJECTED when the
        // same file is already imported, so the operator can't pile up duplicate
        // entries. Checked two ways:
        //   (1) same file NAME (an already-imported file), and
        //   (2) same row CONTENT under a different name (a renamed re-upload).
        // To re-import, delete/trash the existing entry (or edit its rows in the
        // saved grid, which updates in place via updateRow — a different path).
        String normName = StringUtils.hasText(fileName) ? fileName.trim() : "Untitled import";
        String contentHash = contentHash(safe);
        // allowDuplicate — operator confirmed "import anyway as a new batch".
        if (!allowDuplicate && importBatchRepository != null) {
            com.multiship.backend.model.ImportBatch byName = importBatchRepository
                    .findFirstByFileNameIgnoreCaseAndDeletedAtIsNullOrderByIdDesc(normName).orElse(null);
            if (byName != null) {
                return failure(HttpStatus.CONFLICT,
                        "A file named \"" + normName + "\" is already imported as #" + byName.getId()
                        + (byName.getCreatedAt() != null ? " on " + byName.getCreatedAt().toLocalDate() : "")
                        + ". Delete it from Import history (or rename the file) before importing again.");
            }
            if (contentHash != null) {
                com.multiship.backend.model.ImportBatch dup = importBatchRepository
                        .findFirstByContentHashAndDeletedAtIsNullOrderByIdDesc(contentHash).orElse(null);
                if (dup != null) {
                    String dupName = StringUtils.hasText(dup.getFileName()) ? dup.getFileName() : "Untitled";
                    return failure(HttpStatus.CONFLICT,
                            "This file was already imported as #" + dup.getId() + " (" + dupName + ")"
                            + (dup.getCreatedAt() != null ? " on " + dup.getCreatedAt().toLocalDate() : "")
                            + ". Edit a value or upload a different file to import a changed version.");
                }
            }
        }

        Long batchId = null;
        if (importBatchRepository != null) {
            com.multiship.backend.model.ImportBatch batch = new com.multiship.backend.model.ImportBatch();
            batch.setCreatedBy(requestedBy);
            batch.setFileName(normName);
            batch.setStatus(status);
            batch.setCreatedAt(java.time.LocalDateTime.now());
            batch.setTotalRows(total);
            batch.setSavedRows(saved);
            batch.setInvalidRows(invalid);
            batch.setContentHash(contentHash);
            try {
                batch.setRowsJson(importObjectMapper != null
                        ? importObjectMapper.writeValueAsString(safe) : "[]");
            } catch (Exception e) {
                batch.setRowsJson("[]");
            }
            batch = importBatchRepository.save(batch);
            batchId = batch.getId();
        }

        log.info("Order import {} ({}): {} saved, {} invalid → batch {}",
                draft ? "draft-save" : "save", requestedBy, saved, invalid, batchId);
        // Logs page: user-activity trail for the import.
        if (auditService != null && batchId != null) {
            auditService.logEvent(AuditService.CAT_ACTIVITY, AuditService.SEV_INFO,
                    AuditService.IMPORT_SAVED, AuditService.IMPORT_BATCH, batchId,
                    normName, null,
                    total + " row(s) imported · " + saved + " ready, " + invalid + " need fixes",
                    null, requestedBy);
        }
        String message = (draft && invalid > 0)
                ? "Draft saved to history · " + saved + " ready, " + invalid + " still need fixing"
                : total + " row(s) saved to history · all ready to generate";
        return success(OrderImportPreviewDTO.builder()
                        .totalRows(total)
                        .validRows(saved)
                        .invalidRows(invalid)
                        .batchId(batchId == null ? null : batchId.intValue())
                        .rows(safe)
                        .build(),
                message);
    }

    @Override
    public java.util.List<com.multiship.backend.dto.ImportBatchDTO> history() {
        if (importBatchRepository == null) return java.util.List.of();
        // WMS/API fetches surface under the "API" section of All Orders, not in
        // the CSV/XLSX Import history — filter them out here.
        return summariesFor(importBatchRepository.findAllByDeletedAtIsNullOrderByIdDesc()
                .stream().filter(b -> !isApiSource(b.getSource())).toList());
    }

    /** Batches from a non-file source (WMS / external API) — shown in the API section. */
    @Override
    public java.util.List<com.multiship.backend.dto.ImportBatchDTO> apiBatches() {
        if (importBatchRepository == null) return java.util.List.of();
        return summariesFor(importBatchRepository.findAllByDeletedAtIsNullOrderByIdDesc()
                .stream().filter(b -> isApiSource(b.getSource())).toList());
    }

    /** True for batches that belong under the API section (WMS pulls, external API). */
    private static boolean isApiSource(String source) {
        if (source == null) return false;
        String s = source.trim().toUpperCase();
        return s.equals("WMS") || s.equals("API");
    }

    @Override
    public java.util.List<com.multiship.backend.dto.ImportBatchDTO> deletedHistory() {
        if (importBatchRepository == null) return java.util.List.of();
        return summariesFor(importBatchRepository.findAllByDeletedAtIsNotNullOrderByIdDesc());
    }

    /** Map batches to summary DTOs, applying the caller's tenant scope.
     *  Sprint 50 Tier 0.5 PR G — ImportBatch has no direct tenant column so we
     *  derive membership from the first non-blank clientCode in the payload
     *  (all rows in one save() call share the same clamped code). Operators
     *  (resolveScope empty) see everything. */
    private java.util.List<com.multiship.backend.dto.ImportBatchDTO> summariesFor(
            java.util.List<com.multiship.backend.model.ImportBatch> batches) {
        java.util.Optional<String> scope = tenantScope == null
                ? java.util.Optional.empty()
                : tenantScope.resolveScope();
        return batches.stream()
                .filter(b -> {
                    if (scope.isEmpty()) return true;
                    String owner = firstClientCode(parseBatchRows(b));
                    return owner != null && scope.get().equalsIgnoreCase(owner.trim());
                })
                .map(b -> com.multiship.backend.dto.ImportBatchDTO.builder()
                        .id(b.getId())
                        .createdBy(b.getCreatedBy())
                        .fileName(b.getFileName())
                        .status(b.getStatus())
                        .labelBatchId(b.getLabelBatchId())
                        .createdAt(b.getCreatedAt() == null ? null : b.getCreatedAt().toString())
                        .totalRows(b.getTotalRows())
                        .savedRows(b.getSavedRows())
                        .invalidRows(b.getInvalidRows())
                        .deletedAt(b.getDeletedAt() == null ? null : b.getDeletedAt().toString())
                        .deletedBy(b.getDeletedBy())
                        .billingMode(StringUtils.hasText(b.getBillingMode()) ? b.getBillingMode() : "AUTO")
                        .source(StringUtils.hasText(b.getSource()) ? b.getSource() : "BULK")
                        .build())
                .toList();
    }

    @Override
    public com.multiship.backend.dto.ImportBatchDTO setBillingMode(Long id, String mode, String requestedBy) {
        if (importBatchRepository == null || id == null) return null;
        com.multiship.backend.model.ImportBatch b = importBatchRepository.findById(id).orElse(null);
        if (b == null) return null;
        requireMatch(firstClientCode(parseBatchRows(b)));
        String requested = mode == null ? "" : mode.trim().toUpperCase(Locale.ROOT);
        if (!requested.equals("AUTO") && !requested.equals("PLATFORM")) {
            throw new ImportBatchStateException(400, "Billing mode must be AUTO (client account) or PLATFORM (platform account).");
        }
        requireActionable(b, "change which account it bills");
        String m = requested;
        b.setBillingMode(m);
        b = importBatchRepository.save(b);
        log.info("Import batch {} billing mode set to {} by {}", id, m, requestedBy);
        return toBatchDTO(b, parseBatchRows(b));
    }

    @Override
    public com.multiship.backend.dto.ImportBatchDTO softDeleteBatch(Long id, String requestedBy) {
        if (importBatchRepository == null || id == null) return null;
        com.multiship.backend.model.ImportBatch b = importBatchRepository.findById(id).orElse(null);
        if (b == null) return null;
        // Tenant boundary — a scoped USER may only delete a batch their tenant owns.
        requireMatch(firstClientCode(parseBatchRows(b)));
        if (b.getDeletedAt() == null && isGenerating(b)) {
            throw new ImportBatchStateException(409, "Import #" + id + " is generating labels right now — wait for the run"
                    + " to finish (or cancel it) before moving it to Trash.");
        }
        if (b.getDeletedAt() == null) {              // idempotent: skip if already trashed
            b.setDeletedAt(java.time.LocalDateTime.now());
            b.setDeletedBy(requestedBy);
            b = importBatchRepository.save(b);
            log.info("Import batch {} soft-deleted by {}", id, requestedBy);
        }
        return toBatchDTO(b, parseBatchRows(b));
    }

    @Override
    public com.multiship.backend.dto.ImportBatchDTO restoreBatch(Long id) {
        return restoreBatch(id, false);
    }

    /**
     * Restore from Trash. An identical live twin always blocks (409, move it to
     * Trash first). Orders that are also in other live imports ask first — 409
     * IMPORT_DUPLICATE_ORDERS unless allowDuplicate, like "Save anyway".
     */
    @Override
    public com.multiship.backend.dto.ImportBatchDTO restoreBatch(Long id, boolean allowDuplicate) {
        if (importBatchRepository == null || id == null) return null;
        com.multiship.backend.model.ImportBatch b = importBatchRepository.findById(id).orElse(null);
        if (b == null) return null;
        requireMatch(firstClientCode(parseBatchRows(b)));
        if (b.getDeletedAt() != null && StringUtils.hasText(b.getContentHash())) {
            com.multiship.backend.model.ImportBatch live = importBatchRepository
                    .findFirstByContentHashAndDeletedAtIsNullOrderByIdDesc(b.getContentHash()).orElse(null);
            if (live != null && !live.getId().equals(b.getId())) {
                throw new ImportBatchStateException(409, "Import #" + live.getId()
                        + (StringUtils.hasText(live.getFileName()) ? " (" + live.getFileName() + ")" : "")
                        + " holds the same orders and is not in Trash — restoring #" + id
                        + " would duplicate them. Move #" + live.getId() + " to Trash first.");
            }
        }
        if (b.getDeletedAt() != null && !allowDuplicate) {
            String overlap = liveOverlapSummary(parseBatchRows(b), b.getId());
            if (overlap != null) {
                throw new ImportBatchStateException(409, overlap + " Restoring #" + id + " creates duplicate orders.",
                        com.multiship.backend.dto.ErrorCode.IMPORT_DUPLICATE_ORDERS.name());
            }
        }
        if (b.getDeletedAt() != null) {
            b.setDeletedAt(null);
            b.setDeletedBy(null);
            b = importBatchRepository.save(b);
            log.info("Import batch {} restored from Trash", id);
        }
        return toBatchDTO(b, parseBatchRows(b));
    }

    @Override
    public int purgeTrash(String requestedBy) {
        if (importBatchRepository == null) return 0;
        // Only purge batches this tenant may see — reuse the same scope filter
        // as the Trash list so a scoped USER can never wipe another tenant's
        // deleted imports.
        java.util.Optional<String> scope = tenantScope == null
                ? java.util.Optional.empty()
                : tenantScope.resolveScope();
        java.util.List<com.multiship.backend.model.ImportBatch> toPurge =
                importBatchRepository.findAllByDeletedAtIsNotNullOrderByIdDesc().stream()
                        .filter(b -> {
                            if (scope.isEmpty()) return true;
                            String owner = firstClientCode(parseBatchRows(b));
                            return owner != null && scope.get().equalsIgnoreCase(owner.trim());
                        })
                        .toList();
        if (!toPurge.isEmpty()) {
            importBatchRepository.deleteAll(toPurge);
            log.info("Trash emptied by {} — {} batch(es) permanently deleted", requestedBy, toPurge.size());
        }
        return toPurge.size();
    }

    @Override
    public com.multiship.backend.dto.ImportBatchDTO historyDetail(Long id) {
        if (importBatchRepository == null || id == null) return null;
        com.multiship.backend.model.ImportBatch b = importBatchRepository.findById(id).orElse(null);
        if (b == null) return null;
        List<OrderImportRowDTO> parsedRows = java.util.List.of();
        if (importObjectMapper != null && b.getRowsJson() != null) {
            try {
                parsedRows = importObjectMapper.readValue(
                        b.getRowsJson(),
                        new com.fasterxml.jackson.core.type.TypeReference<List<OrderImportRowDTO>>() {});
            } catch (Exception e) {
                parsedRows = java.util.List.of();
            }
        }
        // Sprint 50 Tier 0.5 PR G — enforce tenant match before returning
        // the payload. ImportBatch has no direct tenant column, so the
        // clientCode on the persisted rows is the source of truth.
        // Throws AccessDeniedException (→ 403) for a scoped USER whose
        // tenant doesn't own this batch. Silent for operators.
        requireMatch(firstClientCode(parsedRows));
        return com.multiship.backend.dto.ImportBatchDTO.builder()
                .id(b.getId())
                .createdBy(b.getCreatedBy())
                .fileName(b.getFileName())
                .status(b.getStatus())
                .labelBatchId(b.getLabelBatchId())
                .createdAt(b.getCreatedAt() == null ? null : b.getCreatedAt().toString())
                .totalRows(b.getTotalRows())
                .savedRows(b.getSavedRows())
                .invalidRows(b.getInvalidRows())
                .deletedAt(b.getDeletedAt() == null ? null : b.getDeletedAt().toString())
                .deletedBy(b.getDeletedBy())
                .billingMode(StringUtils.hasText(b.getBillingMode()) ? b.getBillingMode() : "AUTO")
                .source(StringUtils.hasText(b.getSource()) ? b.getSource() : "BULK")
                .rows(parsedRows)
                .build();
    }

    /**
     * Generate carrier labels for a previously-saved import (Data History).
     * The batch moves INITIATE → IN_PROGRESS (persisted so a concurrent read
     * sees it) → COMPLETE (every row got a label) / PARTIAL_COMPLETE (some
     * failed) / back to INITIATE (nothing generated — safe to retry).
     */
    @Override
    public com.multiship.backend.dto.ImportBatchDTO generateLabelsForBatch(Long id, String requestedBy) {
        // Preserves original behavior: re-send every row, client/row account.
        return generateLabelsForBatch(id, requestedBy, false, false);
    }

    @Override
    public com.multiship.backend.dto.ImportBatchDTO generateLabelsForBatch(
            Long id, String requestedBy, boolean onlyFailed, boolean usePlatformAccount) {
        return generateLabelsForBatch(id, requestedBy, onlyFailed, usePlatformAccount, false);
    }

    @Override
    public com.multiship.backend.dto.ImportBatchDTO generateLabelsForBatch(
            Long id, String requestedBy, boolean onlyFailed, boolean usePlatformAccount, boolean allowDuplicate) {
        if (importBatchRepository == null || id == null) return null;
        com.multiship.backend.model.ImportBatch batch = importBatchRepository.findById(id).orElse(null);
        if (batch == null) return null;

        // Parse the stored rows.
        List<OrderImportRowDTO> rows = new ArrayList<>();
        if (importObjectMapper != null && batch.getRowsJson() != null) {
            try {
                rows = importObjectMapper.readValue(
                        batch.getRowsJson(),
                        new com.fasterxml.jackson.core.type.TypeReference<List<OrderImportRowDTO>>() {});
            } catch (Exception e) {
                rows = new ArrayList<>();
            }
        }
        // Sprint 50 Tier 0.5 PR G — enforce tenant match before we spend
        // any carrier-billing cycles generating labels. A scoped USER
        // trying to fire label generation on a foreign tenant's batch
        // gets 403 here rather than after we've minted N labels.
        requireMatch(firstClientCode(rows));
        if (batch.getDeletedAt() != null) {
            throw new ImportBatchStateException(409, "Import #" + id + " is in Trash — restore it before generating labels.");
        }

        // Sprint 55 audit #302 F3.2 — retry-safe filter: skip rows already
        // GENERATED so we don't re-bill the carrier for successful ones.
        // Filtered rows keep their existing generatedStatus/tracking on
        // save; only the not-yet-generated subset is re-processed.
        // A row's status is frozen at generation time. If its order was since
        // repaired from the Orders grid (Edit → Fix & regenerate), it is live
        // at the carrier — re-sending the stale row would buy a second label
        // and flip the order back to ERROR. Sync from the order first.
        syncRowsWithLiveOrders(rows, batch.getLabelBatchId());
        // Rows that already carry a label are never sent again — Generate as well
        // as Retry. (Generate used to re-send them: a second Generate through the
        // API bought duplicate labels.) onlyFailed is kept for callers; both paths
        // now process exactly the rows without a label.
        List<OrderImportRowDTO> rowsToProcess = rows.stream()
                .filter(r -> !"GENERATED".equalsIgnoreCase(r.getGeneratedStatus()))
                .toList();
        // Rows flagged at upload/pull as "already generated as order #N" are
        // about to be shipped a second time. Stop and ask unless the caller
        // confirmed (allowDuplicate) — five labelled copies of one WMS order
        // is exactly what this gate prevents.
        if (!allowDuplicate) {
            List<OrderImportRowDTO> dup = rowsToProcess.stream()
                    .filter(r -> !"GENERATED".equalsIgnoreCase(r.getGeneratedStatus()))
                    .filter(OrderImportServiceImpl::flaggedAsDuplicate).toList();
            if (!dup.isEmpty()) throw new DuplicateShipmentException(duplicateSummary(dup));
        }
        // Nothing the carrier can be asked for — every order is labelled already or
        // still needs fixes. Say so instead of starting an empty run.
        {
            int needFix = 0;
            boolean anyEligible = false;
            for (List<OrderImportRowDTO> g : stagingGroups(rowsToProcess).values()) {
                boolean hasErrors = g.stream().anyMatch(r -> r.getErrors() != null && !r.getErrors().isEmpty());
                if (hasErrors) needFix++;
                else anyEligible = true;
            }
            if (!anyEligible) {
                throw new ImportBatchStateException(422, needFix > 0
                        ? "Nothing to generate — " + needFix + (needFix == 1 ? " order needs" : " orders need")
                                + " fixes first, and every other order is already labelled."
                        : "Nothing to generate — every order in this import is already labelled.");
            }
        }
        // A retry stays in the file's label batch: rows edited in the grid
        // may have lost their stamp, and the commit would mint a new batch
        // for them (import #29 said batch 24, its repaired order said 25).
        if (batch.getLabelBatchId() != null) {
            for (OrderImportRowDTO r : rowsToProcess) {
                if (r.getBatchId() == null) r.setBatchId(batch.getLabelBatchId());
            }
        }
        // Honor the persisted billing mode too, so the platform-account choice
        // survives reloads/retries even when the caller omits the flag.
        boolean platform = usePlatformAccount || "PLATFORM".equalsIgnoreCase(
                batch.getBillingMode() == null ? "" : batch.getBillingMode());

        // Import I-11 — atomic CAS gate against concurrent-generate race.
        // Two operators clicking Generate on the same batch used to BOTH
        // enter this method, fan out per-row label calls in parallel, and
        // produce duplicate paid shipments. Now the status transition to
        // IN_PROGRESS runs as a single UPDATE that only succeeds when the
        // row is currently in an accepted "ready to start" state. If
        // another JVM/thread has already flipped it, updated==0 and we
        // 409-refuse the second attempt.
        java.util.Collection<String> allowedFrom = java.util.List.of(
                "INITIATE", "DRAFT", "COMPLETE", "PARTIAL_COMPLETE", "FAILED", "CANCELLED");
        int updated = importBatchRepository.atomicallyTransitionStatus(id, "IN_PROGRESS", allowedFrom);
        if (updated == 0) {
            // Re-read the current status so the operator sees exactly why
            // (RUNNING elsewhere / already terminal / unknown state).
            com.multiship.backend.model.ImportBatch fresh = importBatchRepository.findById(id).orElse(null);
            String cur = fresh == null ? "unknown" : fresh.getStatus();
            throw new ConcurrentBatchGenerationException(
                    "Import #" + id + " cannot start label generation: current status is "
                            + cur + ". Wait for the in-flight run to finish or refresh Import history.");
        }
        // This run owns the import now: a Cancel left over from a run that ended
        // abnormally must not stop it. (Cleared only after winning the claim, so a
        // refused second Generate can't wipe a real Cancel of the running one.)
        cancelledBatchIds.remove(id);
        // The status is now IN_PROGRESS in the DB; keep the in-memory
        // entity in sync so downstream save() writes don't overwrite it
        // with a stale value.
        batch.setStatus("IN_PROGRESS");
        publishBatchEvent(batch, "batch-updated");

        // Reuse the commit path — it generates labels and stamps each row's
        // generatedStatus (GENERATED / FAILED) in place. platform forces the
        // house account for every row; onlyFailed limits to the not-yet-done subset.
        // Note: cancellation flag is NOT cleared here — an operator who
        // called cancel BEFORE the run should still stop this run. The
        // finally block below always clears it on exit so a subsequent
        // Generate starts with a clean slate.
        if (!rowsToProcess.isEmpty()) {
            // WMS/API batches persist their generated orders as source=API.
            String sourceOverride = isApiSource(batch.getSource()) ? "API" : null;
            // Register a live progress counter (total = groups, since one label
            // covers a whole orderRef group) that a concurrent poll can read, and
            // pass a per-group tick into commit. Always removed when the run ends.
            GenProgress prog = new GenProgress(countGroups(rowsToProcess));
            generationProgressByBatch.put(id, prog);
            try {
                commit(rowsToProcess, requestedBy, platform, sourceOverride,
                        () -> prog.done.incrementAndGet(),
                        () -> cancelledBatchIds.contains(id),
                        n -> prog.note = n);
            } finally {
                generationProgressByBatch.remove(id);
            }
        }

        int total = rows.size();
        int generated = (int) rows.stream()
                .filter(r -> "GENERATED".equalsIgnoreCase(r.getGeneratedStatus()))
                .count();
        int failed = (int) rows.stream()
                .filter(r -> "FAILED".equalsIgnoreCase(r.getGeneratedStatus()))
                .count();
        int invalid = (int) rows.stream()
                .filter(r -> r.getErrors() != null && !r.getErrors().isEmpty())
                .count();

        // savedRows/invalidRows keep their data-validity meaning from save();
        // label progress is conveyed by the status + the per-row Label column.
        // Import I-3 — if cancel() flipped the flag during the run, the
        // status is CANCELLED regardless of the mix of outcomes. Preserves
        // any labels that finished before the toggle so they stay
        // downloadable / voidable from Data History.
        boolean wasCancelled = cancelledBatchIds.remove(id);
        batch.setStatus(wasCancelled
                ? "CANCELLED"
                : deriveGenerationStatus(total, generated, failed, invalid));
        // commit() stamps every generated row with the shared label batchId;
        // lift it onto the import so the file row shows which All-Orders batch
        // its labels belong to. Keep any prior id if this run generated none.
        Integer labelBatchId = firstBatchId(rows);
        if (labelBatchId != null) batch.setLabelBatchId(labelBatchId);
        try {
            if (importObjectMapper != null) batch.setRowsJson(importObjectMapper.writeValueAsString(rows));
        } catch (Exception ex) {
            // Sprint 50 PR N post-audit #12 — was `ignore`; that hid every
            // serialisation failure. If rowsJson silently stops reflecting
            // the latest outcomes, data-history rows drift with no
            // diagnostic. Log so ops can trace regressions.
            log.warn("Import batch {} rowsJson serialisation failed — keeping prior payload: {}",
                    id, ex.getMessage());
        }
        batch = importBatchRepository.save(batch);
        // Terminal-state notification — SSE subscribers on the FE
        // update instantly instead of waiting for the 4s auto-poll.
        publishBatchEvent(batch, "batch-updated");

        log.info("Import batch {} label generation ({}): {} → {} (labelBatch {})",
                id, requestedBy, ordersSummary(rows), batch.getStatus(), batch.getLabelBatchId());
        // Logs page: batch-generation summary in the shipment trail (per-order
        // LABEL_GENERATED / CARRIER_REJECTED rows come from the carrier layer).
        if (auditService != null) {
            auditService.logEvent(AuditService.CAT_SHIPMENT,
                    failed > 0 ? AuditService.SEV_WARN : AuditService.SEV_INFO,
                    AuditService.IMPORT_GENERATED, AuditService.IMPORT_BATCH, id,
                    batch.getFileName(), null,
                    ordersSummary(rows)
                            + (invalid > 0 ? " · " + invalid + " row(s) still need fixes" : ""),
                    null, requestedBy);
        }
        return toBatchDTO(batch, rows);
    }

    /**
     * Generate a label for ONE row of a saved batch, so the operator can ship
     * rows individually straight from Data History. Updates that row's outcome
     * and re-derives the batch status from all rows.
     */
    @Override
    public com.multiship.backend.dto.ImportBatchDTO generateLabelForRow(Long id, int rowNumber, String requestedBy) {
        return generateLabelForRow(id, rowNumber, requestedBy, false);
    }

    /**
     * Import I-3 — request cooperative cancellation of an in-flight
     * generate-labels-for-batch run. Sets a flag that workers pick up on
     * their next per-group poll inside {@link #commit(List, String, boolean, String, Runnable, java.util.function.BooleanSupplier)}.
     * Returns HTTP 404 if the batch id is unknown, 409 if the batch is
     * already in a terminal state (nothing to cancel).
     *
     * <p>Tenant-scoped via {@code requireMatch} — a scoped USER cannot
     * cancel another tenant's job even if they guess the id.
     *
     * <p>Called from OrderImportController.cancelGeneration().
     */
    @Override
    public ApiResponse<String> cancelGeneration(Long id) {
        if (importBatchRepository == null || id == null) {
            return ApiResponse.<String>builder()
                    .status("error").code(HttpStatus.NOT_FOUND.value())
                    .errorCode(ErrorCode.BULK_JOB_NOT_FOUND.name())
                    .message("Batch id is required.").build();
        }
        com.multiship.backend.model.ImportBatch batch = importBatchRepository.findById(id).orElse(null);
        if (batch == null) {
            return ApiResponse.<String>builder()
                    .status("error").code(HttpStatus.NOT_FOUND.value())
                    .errorCode(ErrorCode.BULK_JOB_NOT_FOUND.name())
                    .message("Import batch " + id + " does not exist.")
                    .build();
        }
        // Tenant match — parse enough of rowsJson to get the first clientCode.
        try {
            List<OrderImportRowDTO> rows = importObjectMapper == null || batch.getRowsJson() == null
                    ? java.util.List.of()
                    : importObjectMapper.readValue(batch.getRowsJson(),
                            new com.fasterxml.jackson.core.type.TypeReference<List<OrderImportRowDTO>>() {});
            requireMatch(firstClientCode(rows));
        } catch (Exception ignored) {
            // Malformed rowsJson: fall through — tenant clamp on submit
            // already protected the write; this is a nice-to-have hardening.
        }
        String current = batch.getStatus();
        if ("COMPLETE".equalsIgnoreCase(current)
                || "PARTIAL_COMPLETE".equalsIgnoreCase(current)
                || "FAILED".equalsIgnoreCase(current)
                || "CANCELLED".equalsIgnoreCase(current)) {
            return ApiResponse.<String>builder()
                    .status("error").code(HttpStatus.CONFLICT.value())
                    .errorCode(ErrorCode.BULK_JOB_ALREADY_TERMINAL.name())
                    .message("Import #" + id + " is already in terminal state " + current + " — nothing to cancel.")
                    .build();
        }
        // Only a running import can be cancelled — a flag set on an idle import
        // stayed behind and stopped (and marked CANCELLED) its next real run.
        if (!isGenerating(batch)) {
            return ApiResponse.<String>builder()
                    .status("error").code(HttpStatus.CONFLICT.value())
                    .errorCode(ErrorCode.IMPORT_BATCH_STATE.name())
                    .message("Import #" + id + " isn't generating labels right now — nothing to cancel.")
                    .build();
        }
        cancelledBatchIds.add(id);
        log.info("Import batch {} cancellation requested (current status={}).", id, current);
        publishBatchEvent(batch, "batch-cancel-requested");
        return ApiResponse.<String>builder()
                .status("success").code(HttpStatus.OK.value())
                .message("Cancellation requested. Workers will stop after the current in-flight groups.")
                .data("cancelled")
                .build();
    }

    /**
     * Publish a real-time SSE event for the FE. Fire-and-forget —
     * publishes never throw and never block business logic. When
     * {@code appEventBus} isn't wired (pure-Mockito tests), silent
     * no-op.
     *
     * <p>Tenant scope comes from the first row's clientCode; batches
     * are submit-time tenant-clamped so peeking the first row is
     * always accurate. Null tenant when rowsJson is missing/blank —
     * SseController treats null-tenant events as platform-scope.
     */
    private void publishBatchEvent(com.multiship.backend.model.ImportBatch batch, String eventType) {
        if (appEventBus == null || batch == null) return;
        try {
            String tenant = resolveTenantForBatch(batch);
            appEventBus.publish(new com.multiship.backend.events.ImportBatchEvent(
                    eventType,
                    tenant,
                    batch.getId(),
                    batch.getStatus(),
                    batch.getTotalRows(),
                    batch.getSavedRows(),
                    batch.getInvalidRows()));
        } catch (Exception ex) {
            log.debug("Import batch event publish for {} threw: {}", batch.getId(), ex.getMessage());
        }
    }

    /** Best-effort tenant lookup for a batch — reads first row's
     *  clientCode from rowsJson. Null on legacy or empty rows. */
    private String resolveTenantForBatch(com.multiship.backend.model.ImportBatch batch) {
        if (importObjectMapper == null || batch.getRowsJson() == null) return null;
        try {
            List<OrderImportRowDTO> rows = importObjectMapper.readValue(
                    batch.getRowsJson(),
                    new com.fasterxml.jackson.core.type.TypeReference<List<OrderImportRowDTO>>() {});
            return firstClientCode(rows);
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Import I-11 — thrown by {@link #generateLabelsForBatch} when the
     * atomic CAS finds the batch already IN_PROGRESS. Controller layer
     * maps this to 409 IMPORT_BATCH_ALREADY_GENERATING so the operator
     * knows a second Generate click was rejected (not that the batch is
     * missing or broken).
     */
    /** An Import-history request the import's state doesn't allow — carries the HTTP status to answer with. */
    public static class ImportBatchStateException extends RuntimeException {
        private final int status;
        private final String errorCode;
        public ImportBatchStateException(int status, String message) {
            this(status, message, null);
        }
        public ImportBatchStateException(int status, String message, String errorCode) {
            super(message);
            this.status = status;
            this.errorCode = errorCode;
        }
        public int getStatus() { return status; }
        /** Specific code for the client to act on (null = IMPORT_BATCH_STATE). */
        public String getErrorCode() { return errorCode; }
    }

    private static boolean isGenerating(com.multiship.backend.model.ImportBatch b) {
        String st = b.getStatus() == null ? "" : b.getStatus().trim().toUpperCase(Locale.ROOT);
        return st.equals("IN_PROGRESS") || st.equals("GENERATING");
    }

    /** Edits and label runs need a live import that isn't mid-run. */
    private static void requireActionable(com.multiship.backend.model.ImportBatch b, String action) {
        if (b.getDeletedAt() != null) {
            throw new ImportBatchStateException(409, "Import #" + b.getId() + " is in Trash — restore it before you " + action + ".");
        }
        if (isGenerating(b)) {
            throw new ImportBatchStateException(409, "Import #" + b.getId() + " is generating labels right now — wait for the run"
                    + " to finish (or cancel it) before you " + action + ".");
        }
    }

    public static class ConcurrentBatchGenerationException extends RuntimeException {
        public ConcurrentBatchGenerationException(String message) { super(message); }
    }

    /** A row whose orderRef already produced a live label (preview/pull advisory). */
    static boolean flaggedAsDuplicate(OrderImportRowDTO r) {
        return r.getWarnings() != null && r.getWarnings().stream()
                .anyMatch(w -> w != null && w.contains("was already generated as order"));
    }

    /** "2 order(s) already labelled: ORD-1 → #900274, ORD-2 → #900273" for the confirm dialog. */
    static String duplicateSummary(List<OrderImportRowDTO> dup) {
        Map<String, String> byRef = new LinkedHashMap<>();
        for (OrderImportRowDTO r : dup) {
            String ref = StringUtils.hasText(r.getOrderRef()) ? r.getOrderRef().trim() : "row " + r.getRowNumber();
            if (byRef.containsKey(ref)) continue;
            String w = r.getWarnings().stream().filter(x -> x.contains("was already generated as order")).findFirst().orElse("");
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("as order (\\d+)").matcher(w);
            byRef.put(ref, m.find() ? "#" + m.group(1) : "?");
        }
        StringBuilder sb = new StringBuilder();
        sb.append(byRef.size()).append(" order(s) in this batch already have a live label: ");
        int i = 0;
        for (Map.Entry<String, String> e : byRef.entrySet()) {
            if (i++ > 0) sb.append(", ");
            if (i > 6) { sb.append("…"); break; }
            sb.append(e.getKey()).append(" → ").append(e.getValue());
        }
        sb.append(". Generating again creates duplicate shipments and carrier charges.");
        return sb.toString();
    }

    /** Raised when a generate would re-ship orders that already have a live label and the caller didn't confirm. */
    public static class DuplicateShipmentException extends RuntimeException {
        public DuplicateShipmentException(String message) { super(message); }
    }

    @Override
    public com.multiship.backend.dto.ImportBatchDTO generateLabelForRow(Long id, int rowNumber, String requestedBy, boolean allowDuplicate) {
        if (importBatchRepository == null || id == null) return null;
        com.multiship.backend.model.ImportBatch batch = importBatchRepository.findById(id).orElse(null);
        if (batch == null) return null;

        List<OrderImportRowDTO> rows = new ArrayList<>();
        if (importObjectMapper != null && batch.getRowsJson() != null) {
            try {
                rows = importObjectMapper.readValue(
                        batch.getRowsJson(),
                        new com.fasterxml.jackson.core.type.TypeReference<List<OrderImportRowDTO>>() {});
            } catch (Exception e) {
                // Sprint 50 PR N post-audit #13 — was silent; that gave
                // an empty rows list, firstClientCode(rows)==null, and
                // requireMatch(null) which for OPERATORS is a no-op —
                // effectively a silent operator-only tenant guard skip.
                // Log the corruption so ops can see + investigate, and
                // still fall through with empty rows (the guard below
                // throws for scoped users on null; operators get an
                // empty result which is the safer response).
                log.warn("Import batch {} rowsJson parse failed — treating as empty: {}",
                        id, e.getMessage());
                rows = new ArrayList<>();
            }
        }
        // Sprint 50 Tier 0.5 PR G — enforce tenant match on the parent
        // batch before we generate for a single row. Single-row generation
        // must have the same tenant boundary as full-batch generation.
        requireMatch(firstClientCode(rows));
        // A single-row label during a batch run could label the same order twice.
        requireActionable(batch, "generate a single row");
        OrderImportRowDTO target = rows.stream()
                .filter(r -> r.getRowNumber() == rowNumber)
                .findFirst()
                .orElse(null);
        if (target == null) throw new ImportBatchStateException(404, "Row " + rowNumber + " is not in import #" + id + ".");

        // Generate this row's whole order (commit mutates the rows in place).
        // Rows sharing an orderRef are ONE shipment — item lines and extra
        // parcels of the same order — so sending the clicked row alone would
        // mint a second order for the same customer order (and a 1-box label
        // for a 2-box shipment). WMS/API batches persist their order as
        // source=API, matching full-batch generate.
        String sourceOverride = isApiSource(batch.getSource()) ? "API" : null;
        List<OrderImportRowDTO> group = new ArrayList<>();
        if (StringUtils.hasText(target.getOrderRef())) {
            String ref = target.getOrderRef().trim().toUpperCase(Locale.ROOT);
            for (OrderImportRowDTO r : rows) {
                if (StringUtils.hasText(r.getOrderRef()) && ref.equals(r.getOrderRef().trim().toUpperCase(Locale.ROOT))) {
                    group.add(r);
                }
            }
        }
        if (group.isEmpty()) group.add(target);
        syncRowsWithLiveOrders(group, batch.getLabelBatchId());
        boolean groupLabelled = group.stream().anyMatch(r -> "GENERATED".equalsIgnoreCase(r.getGeneratedStatus())
                && r.getGeneratedOrderNo() != null);
        if (!groupLabelled) {
            List<String> errs = group.stream()
                    .flatMap(r -> (r.getErrors() == null ? List.<String>of() : r.getErrors()).stream())
                    .distinct().limit(3).toList();
            if (!errs.isEmpty()) {
                throw new ImportBatchStateException(422, "Order "
                        + (StringUtils.hasText(target.getOrderRef()) ? target.getOrderRef().trim() : "on row " + rowNumber)
                        + " needs fixes before it can be labelled: " + String.join("; ", errs) + ".");
            }
        }
        if (!allowDuplicate) {
            List<OrderImportRowDTO> dup = group.stream()
                    .filter(r -> !"GENERATED".equalsIgnoreCase(r.getGeneratedStatus()))
                    .filter(OrderImportServiceImpl::flaggedAsDuplicate).toList();
            if (!dup.isEmpty()) {
                throw new DuplicateShipmentException(duplicateSummary(dup));
            }
        }
        OrderImportRowDTO done = group.stream()
                .filter(r -> "GENERATED".equalsIgnoreCase(r.getGeneratedStatus()) && r.getGeneratedOrderNo() != null)
                .findFirst().orElse(null);
        if (done != null) {
            // The order is already labelled (another row of the group was
            // generated, or it was repaired from the Orders grid) — attach the
            // remaining rows to it instead of shipping the same order twice.
            if ("GENERATED".equalsIgnoreCase(target.getGeneratedStatus())) {
                target.setGeneratedMessage("Already labelled as order #" + done.getGeneratedOrderNo() + " — not re-sent.");
            }
            for (OrderImportRowDTO r : group) {
                if ("GENERATED".equalsIgnoreCase(r.getGeneratedStatus())) continue;
                r.setGeneratedStatus("GENERATED");
                r.setGeneratedOrderNo(done.getGeneratedOrderNo());
                r.setGeneratedTrackingNumber(done.getGeneratedTrackingNumber());
                r.setBatchId(done.getBatchId());
                r.setGeneratedMessage("Part of order #" + done.getGeneratedOrderNo() + " — already labelled, not re-sent.");
            }
        } else {
            commit(group, requestedBy, false, sourceOverride);
        }

        int total = rows.size();
        int generated = (int) rows.stream()
                .filter(r -> "GENERATED".equalsIgnoreCase(r.getGeneratedStatus()))
                .count();
        int failed = (int) rows.stream()
                .filter(r -> "FAILED".equalsIgnoreCase(r.getGeneratedStatus()))
                .count();
        int invalid = (int) rows.stream()
                .filter(r -> r.getErrors() != null && !r.getErrors().isEmpty())
                .count();
        batch.setStatus(deriveGenerationStatus(total, generated, failed, invalid));
        Integer labelBatchId = firstBatchId(rows);
        if (labelBatchId != null) batch.setLabelBatchId(labelBatchId);
        try {
            if (importObjectMapper != null) batch.setRowsJson(importObjectMapper.writeValueAsString(rows));
        } catch (Exception ex) {
            // Sprint 50 PR N post-audit #12 — was `ignore`; that hid every
            // serialisation failure. If rowsJson silently stops reflecting
            // the latest outcomes, data-history rows drift with no
            // diagnostic. Log so ops can trace regressions.
            log.warn("Import batch {} rowsJson serialisation failed — keeping prior payload: {}",
                    id, ex.getMessage());
        }
        batch = importBatchRepository.save(batch);

        log.info("Import batch {} row {} label ({}): {} → batch {} (labelBatch {})",
                id, rowNumber, requestedBy, target.getGeneratedStatus(), batch.getStatus(), batch.getLabelBatchId());
        return toBatchDTO(batch, rows);
    }

    @Override
    public GenProgressView generationProgress(Long id) {
        GenProgress p = id == null ? null : generationProgressByBatch.get(id);
        if (p == null) return new GenProgressView(0, 0, false);
        // Clamp done ≤ total in case a poll lands between the last tick and removal.
        return new GenProgressView(Math.min(p.done.get(), p.total), p.total, true, p.note);
    }

    /** Count of orderRef groups in a row set — one label (and one progress tick)
     *  covers a whole group, so this is the progress bar's denominator. */
    private static int countGroups(List<OrderImportRowDTO> rows) {
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (OrderImportRowDTO r : rows) {
            keys.add(StringUtils.hasText(r.getOrderRef())
                    ? r.getOrderRef().trim() : "__row_" + r.getRowNumber());
        }
        return keys.size();
    }

    /**
     * Sprint 51 — inline correction of one saved row from Data History.
     * Replaces the target row with the operator's edit, re-validates the
     * whole batch (so cross-row + international + reference rules stay
     * consistent), re-stamps each still-ungenerated row SAVED / NEEDS_FIX,
     * and recomputes the batch counts + status. Rows already carrying a
     * label (GENERATED) are immutable — the edit is ignored for them.
     */
    @Override
    public com.multiship.backend.dto.ImportBatchDTO updateBatchRow(
            Long id, int rowNumber, OrderImportRowDTO edited, String requestedBy) {
        return editBatchRow(id, rowNumber, current -> edited, requestedBy);
    }

    /**
     * Row edit as the API receives it: the JSON body is applied on top of the
     * stored row, so a partial body changes only the fields it names (the UI
     * sends the whole row, which is the same as a replace). A missing row is
     * 404 before the body is read; an empty or malformed body is 400.
     */
    @Override
    public com.multiship.backend.dto.ImportBatchDTO updateBatchRowJson(
            Long id, int rowNumber, String json, String requestedBy) {
        return editBatchRow(id, rowNumber, current -> mergeRowJson(current, json), requestedBy);
    }

    private com.multiship.backend.dto.ImportBatchDTO editBatchRow(Long id, int rowNumber,
            java.util.function.UnaryOperator<OrderImportRowDTO> applyEdit, String requestedBy) {
        if (importBatchRepository == null || id == null) return null;
        com.multiship.backend.model.ImportBatch batch = importBatchRepository.findById(id).orElse(null);
        if (batch == null) return null;

        List<OrderImportRowDTO> rows = new ArrayList<>();
        if (importObjectMapper != null && batch.getRowsJson() != null) {
            try {
                rows = importObjectMapper.readValue(
                        batch.getRowsJson(),
                        new com.fasterxml.jackson.core.type.TypeReference<List<OrderImportRowDTO>>() {});
            } catch (Exception e) {
                log.warn("Import batch {} rowsJson parse failed on edit — treating as empty: {}",
                        id, e.getMessage());
                rows = new ArrayList<>();
            }
        }
        // Same tenant boundary as read / generate: a scoped USER may only
        // edit a batch their own tenant owns.
        requireMatch(firstClientCode(rows));
        // An edit saved during a run would be overwritten when the run saves its
        // rows; an edit to an import in Trash would change data nobody sees.
        requireActionable(batch, "edit it");

        int index = -1;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).getRowNumber() == rowNumber) { index = i; break; }
        }
        if (index < 0) throw new ImportBatchStateException(404, "Row " + rowNumber + " is not in import #" + id + ".");

        OrderImportRowDTO current = rows.get(index);
        // A generated row is already a live shipment — never mutate it here; say so.
        if ("GENERATED".equalsIgnoreCase(current.getGeneratedStatus())) {
            throw new ImportBatchStateException(409, "Row " + rowNumber + " is already labelled"
                    + (current.getGeneratedOrderNo() != null ? " (order #" + current.getGeneratedOrderNo() + ")" : "")
                    + " — change the shipment from the Orders page instead.");
        }

        // Apply the edit. Force the row number so the client can't renumber
        // a row by editing, and drop any stale generation outcome so a
        // previously-FAILED row re-enters the SAVED / NEEDS_FIX lifecycle.
        OrderImportRowDTO edited = applyEdit.apply(current);
        if (edited == null) edited = new OrderImportRowDTO();
        edited.setRowNumber(rowNumber);
        edited.setGeneratedStatus(null);
        // Keep the label batch the file was uploaded under — nulling it made a
        // later Retry mint a second batch number for the same file.
        edited.setBatchId(current.getBatchId());
        rows.set(index, edited);
        // A shipment-level change on one row of an order applies to the order:
        // every other row of the same orderRef that still carried the OLD
        // value (typically inherited from this row) follows it, so fixing a
        // bad account once fixes the whole order instead of erroring every
        // continuation row with "differs from the first row".
        propagateGroupEdit(rows, current, edited);

        // Re-validate the whole batch — mutates each row's errors/warnings
        // in place through the same pipeline preview/commit use.
        validate(rows);

        // Re-stamp lifecycle status for every row that hasn't shipped.
        for (OrderImportRowDTO r : rows) {
            if ("GENERATED".equalsIgnoreCase(r.getGeneratedStatus())
                    || "FAILED".equalsIgnoreCase(r.getGeneratedStatus())) {
                continue;
            }
            boolean clean = r.getErrors() == null || r.getErrors().isEmpty();
            r.setGeneratedStatus(clean ? "SAVED" : "NEEDS_FIX");
        }

        int total = rows.size();
        int invalid = (int) rows.stream()
                .filter(r -> r.getErrors() != null && !r.getErrors().isEmpty())
                .count();
        int generated = (int) rows.stream()
                .filter(r -> "GENERATED".equalsIgnoreCase(r.getGeneratedStatus()))
                .count();
        int failed = (int) rows.stream()
                .filter(r -> "FAILED".equalsIgnoreCase(r.getGeneratedStatus()))
                .count();

        batch.setTotalRows(total);
        batch.setSavedRows(readyRowCount(rows));
        batch.setInvalidRows(invalid);
        batch.setStatus(deriveGenerationStatus(total, generated, failed, invalid));
        try {
            if (importObjectMapper != null) batch.setRowsJson(importObjectMapper.writeValueAsString(rows));
        } catch (Exception ex) {
            log.warn("Import batch {} rowsJson serialisation failed on edit — keeping prior payload: {}",
                    id, ex.getMessage());
        }
        batch = importBatchRepository.save(batch);

        log.info("Import batch {} row {} edited ({}): {} → {} valid / {} held → status {}",
                id, rowNumber, requestedBy, rows.get(index).getGeneratedStatus(),
                total - invalid, invalid, batch.getStatus());
        return toBatchDTO(batch, rows);
    }

    /**
     * Batch status from label-generation outcomes:
     *   INITIATE         — no row attempted yet (fresh save, nothing generated/failed)
     *   COMPLETE         — every row got a label
     *   FAILED           — every row was attempted and all failed
     *   PARTIAL_COMPLETE — some labels made, or some rows still pending
     */
    private String deriveGenerationStatus(int total, int generated, int failed, int invalid) {
        if (total == 0) return "INITIATE";
        if (generated == total) return "COMPLETE";
        if (failed == total) return "FAILED";
        // Nothing labelled but something failed (the rest still need fixes): the
        // run failed — "Partial complete" suggested some labels exist.
        if (generated == 0 && failed > 0) return "FAILED";
        // Nothing has generated or failed yet: a batch still carrying bad rows
        // stays a DRAFT (work-in-progress); an all-clean batch is INITIATE
        // (ready to generate).
        if (generated == 0 && failed == 0) return invalid > 0 ? "DRAFT" : "INITIATE";
        return "PARTIAL_COMPLETE";
    }

    /** First non-null label batchId across a set of rows (all generated rows
     *  of one import share it), or null if nothing has generated yet. */
    private Integer firstBatchId(List<OrderImportRowDTO> rows) {
        if (rows == null) return null;
        return rows.stream()
                .map(OrderImportRowDTO::getBatchId)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    /** Build the history DTO (list + rows) from an entity + parsed rows. */
    private com.multiship.backend.dto.ImportBatchDTO toBatchDTO(
            com.multiship.backend.model.ImportBatch batch, List<OrderImportRowDTO> rows) {
        return com.multiship.backend.dto.ImportBatchDTO.builder()
                .id(batch.getId())
                .createdBy(batch.getCreatedBy())
                .fileName(batch.getFileName())
                .status(batch.getStatus())
                .labelBatchId(batch.getLabelBatchId())
                .createdAt(batch.getCreatedAt() == null ? null : batch.getCreatedAt().toString())
                .totalRows(batch.getTotalRows())
                .savedRows(batch.getSavedRows())
                .invalidRows(batch.getInvalidRows())
                .deletedAt(batch.getDeletedAt() == null ? null : batch.getDeletedAt().toString())
                .deletedBy(batch.getDeletedBy())
                .billingMode(StringUtils.hasText(batch.getBillingMode()) ? batch.getBillingMode() : "AUTO")
                .source(StringUtils.hasText(batch.getSource()) ? batch.getSource() : "BULK")
                .rows(rows)
                .build();
    }

    /**
     * Sprint 41 — convert a single import row to a ManualShipmentRequest.
     * Kept for tests / legacy call paths; the commit loop uses the
     * {@link #toManualShipmentRequest(List)} group overload so multi-row
     * orders fold into one shipment with a customs items[] array.
     */
    static com.multiship.backend.dto.ManualShipmentRequest toManualShipmentRequest(OrderImportRowDTO row) {
        return toManualShipmentRequest(List.of(row));
    }

    /**
     * Sprint 48 — convert an orderRef group (leader + item rows) into one
     * ManualShipmentRequest. Recipient / carrier / weight / service come
     * from the group's leader (row 0). Every row in the group contributes
     * a customs {@code Item} when it carries any item-level data
     * (description, HS code, SKU, quantity, or unit value).
     *
     * <p>Domestic-only groups (no item-level data on any row) send the
     * legacy single-goods block — no customs items array — so we don't
     * force customs data on shipments that don't need it.
     */
    static com.multiship.backend.dto.ManualShipmentRequest toManualShipmentRequest(List<OrderImportRowDTO> group) {
        OrderImportRowDTO leader = group.get(0);
        com.multiship.backend.dto.ManualShipmentRequest req =
                new com.multiship.backend.dto.ManualShipmentRequest();
        com.multiship.backend.dto.ManualShipmentRequest.Address recipient =
                new com.multiship.backend.dto.ManualShipmentRequest.Address();
        recipient.setName(leader.getRecipientName());
        recipient.setCompany(leader.getRecipientCompany());
        recipient.setPhone(leader.getRecipientPhone());
        recipient.setEmail(leader.getRecipientEmail());
        recipient.setAddressLine1(leader.getAddressLine1());
        recipient.setAddressLine2(leader.getAddressLine2());
        recipient.setCity(leader.getCity());
        recipient.setState(leader.getState());
        recipient.setPostalCode(leader.getPostalCode());
        recipient.setCountryCode(leader.getCountryCode());
        // Auto-flag residential when the row's serviceType requires it
        // (FedEx Home Delivery today). OrderImportRowDTO has no
        // dedicated residential column, so before this fix EVERY
        // imported row defaulted to commercial and FedEx refused Home
        // Delivery with an opaque CUSTOMER.DESTINATION.INVALID
        // response. Auto-fill fixes the common case; operators who
        // need residential on a NON-Home-Delivery service should
        // (future) get an explicit spreadsheet column — separate ask.
        if (com.multiship.backend.service.carriers.ResidentialRequiredServices
                .requiresResidential(leader.getServiceType())) {
            recipient.setResidential(true);
        }
        req.setRecipient(recipient);

        req.setCarrierCode(leader.getCarrierCode());
        req.setAccountNumber(leader.getAccountNumber());
        // Sprint 51 fix #2 — carry the row's warehouse so the manual path
        // resolves that warehouse's address as ship-from (and thus the
        // origin country that drives the international/customs decision).
        // When blank, processGroup fills the sender from the client's
        // configured ship-from instead of letting it fall back to the
        // platform default shipper country.
        req.setWarehouseCode(leader.getWarehouseCode());
        req.setWeight(leader.getWeight());
        req.setWeightUnit(leader.getWeightUnit());
        // Rows that carry their OWN weight are parcels: a WMS sends one row
        // per shipment/container of an order, so two rows of 0.24 + 0.22 LB
        // must ship as two packages, not one 0.24 LB box. Continuation rows
        // that merely inherited the leader's weight are item lines, not boxes.
        List<OrderImportRowDTO> parcelRows = new ArrayList<>();
        for (OrderImportRowDTO row : group) {
            if (row.getWeight() != null && row.getWeight().signum() > 0 && !Boolean.TRUE.equals(row.getWeightInherited())) {
                parcelRows.add(row);
            }
        }
        if (parcelRows.size() > 1) {
            List<com.multiship.backend.dto.PackageDetailDTO> pkgs = new ArrayList<>(parcelRows.size());
            java.math.BigDecimal total = java.math.BigDecimal.ZERO;
            int seq = 0;
            for (OrderImportRowDTO row : parcelRows) {
                seq++;
                total = total.add(row.getWeight());
                pkgs.add(com.multiship.backend.dto.PackageDetailDTO.builder()
                        .sequenceNumber(seq)
                        .weight(row.getWeight())
                        .weightUnit(firstNonBlankStr(row.getWeightUnit(), leader.getWeightUnit()))
                        .length(row.getLength() != null ? row.getLength() : leader.getLength())
                        .width(row.getWidth() != null ? row.getWidth() : leader.getWidth())
                        .height(row.getHeight() != null ? row.getHeight() : leader.getHeight())
                        .dimUnit(firstNonBlankStr(row.getDimUnit(), leader.getDimUnit()))
                        .packageType(firstNonBlankStr(row.getPackageType(), leader.getPackageType()))
                        .reference(firstNonBlankStr(row.getReference(), leader.getReference()))
                        .build());
            }
            req.setPackages(pkgs);
            req.setWeight(total);
        }
        req.setLength(leader.getLength());
        req.setWidth(leader.getWidth());
        req.setHeight(leader.getHeight());
        req.setDimUnit(leader.getDimUnit());
        req.setCurrency(leader.getCurrency());
        req.setIncoterms(leader.getIncoterms());
        // The customer's own reference travels with the order (Ref # column,
        // commercial invoice). The orderRef is the fallback so a bulk order is
        // always traceable back to the file that created it.
        req.setReference(StringUtils.hasText(leader.getReference())
                ? leader.getReference().trim() : leader.getOrderRef());
        // Sprint 48 revision — declaredValue is derived from item rows
        // rather than a per-row column. Sum unitValue × quantity across
        // every row in the group that carries item data; blank when the
        // group has no item data at all (domestic single-item shipment).
        java.math.BigDecimal derivedValue = java.math.BigDecimal.ZERO;
        boolean sawItemValue = false;
        for (OrderImportRowDTO row : group) {
            if (row.getItemUnitValue() == null) continue;
            int qty = row.getItemQuantity() != null ? row.getItemQuantity() : 1;
            derivedValue = derivedValue.add(row.getItemUnitValue()
                    .multiply(java.math.BigDecimal.valueOf(qty)));
            sawItemValue = true;
        }
        if (sawItemValue) req.setDeclaredValue(derivedValue);
        // goodsDescription — shipment-level description slot. Use the
        // first non-blank itemDescription across the group so operators
        // don't retype (removed goodsDescription column).
        for (OrderImportRowDTO row : group) {
            if (StringUtils.hasText(row.getItemDescription())) {
                req.setGoodsDescription(row.getItemDescription());
                break;
            }
        }
        // Bulk CSV/XLSX import — distinct from a single manual shipment
        // (MANUAL) or an external API call (API), so operators can filter/
        // spot these on the Shipment & Label list.
        req.setSource("BULK");
        // Sprint 51 — carry the row's client so the persisted order records
        // its owning client (custNo + tenantId), matching the manual-UI path.
        // Without this the order fell back to custNo="MANUAL" / tenantId=null,
        // breaking tenant scoping and forcing client-scoped features (e.g. the
        // commercial invoice) to reverse-resolve the client from the billing
        // account. generateManualLabel re-clamps this to the caller's scope.
        req.setClientCode(leader.getClientCode());

        // Customs items: any row (leader OR item rows) that carries
        // item-level data becomes one Item on the invoice. Skip rows
        // that are purely shipment-level (domestic-only leader with no
        // customs data).
        List<com.multiship.backend.dto.ManualShipmentRequest.Item> items = new ArrayList<>();
        for (OrderImportRowDTO row : group) {
            if (!rowHasItemData(row)) continue;
            com.multiship.backend.dto.ManualShipmentRequest.Item it =
                    new com.multiship.backend.dto.ManualShipmentRequest.Item();
            if (parcelRows.size() > 1) {
                int idx = parcelRows.indexOf(row);
                it.setBoxSeq(idx >= 0 ? idx + 1 : 1);
            }
            it.setDescription(row.getItemDescription());
            it.setHsCode(row.getHsCode());
            it.setCountryOfOrigin(row.getCountryOfOrigin());
            it.setQuantity(row.getItemQuantity() != null ? row.getItemQuantity() : 1);
            it.setUnitValue(row.getItemUnitValue());
            it.setSku(row.getItemSku());
            items.add(it);
        }
        if (!items.isEmpty()) req.setItems(items);
        return req;
    }

    /** True when the row carries any per-item data. Blank-rows shouldn't
     *  become empty customs entries. */
    private static boolean rowHasItemData(OrderImportRowDTO row) {
        return StringUtils.hasText(row.getItemDescription())
                || StringUtils.hasText(row.getItemSku())
                || StringUtils.hasText(row.getHsCode())
                || StringUtils.hasText(row.getCountryOfOrigin())
                || row.getItemQuantity() != null
                || row.getItemUnitValue() != null;
    }

    @Override
    public byte[] xlsxTemplate(Long accountId) {
        // Sprint 48 — accountId is retained on the signature for backwards
        // compatibility with existing callers but the universal template
        // doesn't scope to a single account any more. Every client + every
        // carrier account + every warehouse gets baked into the reference
        // sheet with cascading dropdowns; operators pick per-row inside the
        // workbook.
        //
        // Sprint 51 BP-M6 — scoped USERs get their own tenant's clients +
        // accounts baked into the dropdowns, never a competitor's. Platform
        // operators still see everything so they can prep templates for any
        // client.
        java.util.Optional<String> scope = tenantScope == null ? java.util.Optional.empty() : tenantScope.resolveScope();
        List<Client> clients;
        if (clientRepository == null) {
            clients = List.of();
        } else if (scope.isPresent()) {
            clients = clientRepository.findByClientCodeInIgnoreCase(
                    java.util.List.of(scope.get().toUpperCase(Locale.ROOT)));
        } else {
            clients = clientRepository.findAll();
        }
        List<CarrierAccountRef> accounts;
        if (accountRefRepository == null) {
            accounts = List.of();
        } else if (scope.isPresent()) {
            accounts = accountRefRepository.findActiveByCustomerNoInOrPlatform(
                    java.util.List.of(scope.get().toUpperCase(Locale.ROOT)));
        } else {
            accounts = accountRefRepository.findByActiveTrue();
        }
        List<com.multiship.backend.model.ShippingService> services = shippingServiceRepository != null
                ? shippingServiceRepository.findAllByOrderByCarrierAscSortOrderAsc()
                : List.of();
        List<com.multiship.backend.model.PackagePreset> presets = packagePresetRepository != null
                ? packagePresetRepository.findAllByOrderByIsDefaultDescNameAsc()
                : List.of();
        // Precompute clientCode → List<warehouseCode>. Sprint 51 BP-M6 —
        // resolve only the warehouse ids referenced by the tenant-visible
        // clients rather than scanning every Warehouse platform-wide.
        java.util.Map<Long, String> warehouseCodeById = new java.util.HashMap<>();
        java.util.Map<String, List<String>> clientWarehouseCodes = new java.util.HashMap<>();
        if (clientWarehouseRepository != null && !clients.isEmpty()) {
            java.util.Map<String, List<ClientWarehouse>> attachmentsByClient = new java.util.LinkedHashMap<>();
            java.util.Set<Long> warehouseIds = new java.util.HashSet<>();
            for (Client c : clients) {
                String code = c.getClientCode();
                if (code == null || code.isBlank()) continue;
                List<ClientWarehouse> attached = clientWarehouseRepository
                        .findByClientCodeIgnoreCaseOrderByIsDefaultDescCreatedAtAsc(code);
                attachmentsByClient.put(code.toUpperCase(Locale.ROOT), attached);
                for (ClientWarehouse cw : attached) {
                    if (cw.getWarehouseId() != null) warehouseIds.add(cw.getWarehouseId());
                }
            }
            if (warehouseRepository != null && !warehouseIds.isEmpty()) {
                for (Warehouse w : warehouseRepository.findByIdInAndActiveTrue(warehouseIds)) {
                    if (w.getId() != null && w.getCode() != null) {
                        warehouseCodeById.put(w.getId(), w.getCode());
                    }
                }
            }
            for (var entry : attachmentsByClient.entrySet()) {
                List<String> codes = new java.util.ArrayList<>();
                for (ClientWarehouse cw : entry.getValue()) {
                    String whCode = warehouseCodeById.get(cw.getWarehouseId());
                    if (whCode != null) codes.add(whCode);
                }
                clientWarehouseCodes.put(entry.getKey(), codes);
            }
        }
        // accountId parameter is ignored — universal template.
        if (accountId != null) log.debug("xlsxTemplate ignored accountId={} (universal template)", accountId);
        return OrderImportTemplateBuilder.build(
                HEADERS, clients, accounts, clientWarehouseCodes, services, presets);
    }

    @Override
    public byte[] csvTemplate() {
        // CSV template lags the XLSX template in features (no in-workbook
        // dropdowns). It's a plain schema dump + one representative sample
        // row so operators know the column ordering; the .xlsx template
        // is the recommended path (dropdowns + cascading + samples).
        //
        // Sprint 48 column ordering: orderRef, clientCode, billTo,
        // warehouseCode, recipientName, ..., itemDescription, hsCode,
        // countryOfOrigin (see HEADERS). Row values below must stay in
        // that exact positional order.
        StringBuilder sb = new StringBuilder();
        sb.append(String.join(",", HEADERS)).append('\n');
        // Sample row — international UK shipment with 1 line-item so
        // operators see all the columns exercised in one go. Column
        // ordering must match HEADERS exactly (Sprint 48 revision:
        // declaredValue + goodsDescription no longer present).
        // Three sample rows that pass the importer's own validation: a domestic
        // parcel, and a two-line international order (rows sharing an orderRef
        // fold into one shipment). Client / warehouse / account are left blank
        // so the sample never names entities that don't exist on this install
        // — blank values resolve through the client default cascade.
        for (Map<String, String> row : sampleRows(sampleClientCode())) {
            List<String> cells = new ArrayList<>(HEADERS.size());
            for (String h : HEADERS) cells.add(row.getOrDefault(h, ""));
            sb.append(String.join(",", cells)).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static Map<String, String> sampleRow(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    /** First active client on this install, so the sample rows validate as shipped. */
    private String sampleClientCode() {
        if (clientRepository == null) return "";
        try {
            return clientRepository.findAll().stream()
                    .filter(c -> c != null && Client.STATUS_ACTIVE.equalsIgnoreCase(c.getStatus()))
                    .map(Client::getClientCode)
                    .filter(StringUtils::hasText)
                    .sorted()
                    .findFirst()
                    .orElse("");
        } catch (Exception e) {
            return "";
        }
    }

    static List<Map<String, String>> sampleRows(String clientCode) {
        return List.of(
                sampleRow("orderRef", "SAMPLE-1001", "clientCode", clientCode, "billTo", "SENDER",
                        "recipientName", "Jordan Lee", "recipientPhone", "2125550101",
                        "recipientEmail", "jordan.lee@example.com",
                        "addressLine1", "1600 Amphitheatre Pkwy", "city", "Mountain View", "state", "CA",
                        "postalCode", "94043", "countryCode", "US",
                        "carrierCode", "FEDEX", "serviceType", "FEDEX_GROUND", "packageType", "YOUR_PACKAGING",
                        "weight", "2.5", "weightUnit", "LB", "length", "12", "width", "10", "height", "8", "dimUnit", "IN",
                        "currency", "USD", "reference", "SAMPLE-1001"),
                sampleRow("orderRef", "SAMPLE-1002", "clientCode", clientCode, "billTo", "SENDER",
                        "recipientName", "Ava Chen", "recipientCompany", "Chen & Co Ltd",
                        "recipientPhone", "442071234567", "recipientEmail", "ava.chen@example.co.uk",
                        "addressLine1", "221B Baker Street", "city", "London",
                        "postalCode", "NW1 6XE", "countryCode", "GB",
                        "carrierCode", "FEDEX", "serviceType", "INTERNATIONAL_PRIORITY", "packageType", "YOUR_PACKAGING",
                        "weight", "3.2", "weightUnit", "LB", "length", "14", "width", "12", "height", "10", "dimUnit", "IN",
                        "currency", "USD", "incoterms", "DAP", "reference", "SAMPLE-1002",
                        "itemDescription", "Silk lining natural", "itemSku", "SKU-100", "itemQuantity", "2",
                        "itemUnitValue", "45.00", "hsCode", "5007.20.00", "countryOfOrigin", "IT"),
                sampleRow("orderRef", "SAMPLE-1002",
                        "itemDescription", "Cotton scarf", "itemSku", "SKU-101", "itemQuantity", "3",
                        "itemUnitValue", "12.00", "hsCode", "6214.90.00", "countryOfOrigin", "IN"));
    }

    /* -------------------------- Parsers -------------------------- */

    private List<OrderImportRowDTO> parseCsv(InputStream body) throws Exception {
        List<OrderImportRowDTO> out = new ArrayList<>();
        // Wrap in a PushbackInputStream so we can peek + swallow a UTF-8
        // BOM (0xEF 0xBB 0xBF) — Excel writes one when Save As CSV, and
        // without stripping it the first header column reads as "﻿orderRef"
        // instead of "orderRef" and every value on that column comes back null.
        java.io.PushbackInputStream pb = new java.io.PushbackInputStream(body, 3);
        int b1 = pb.read();
        if (b1 != -1) {
            int b2 = pb.read();
            int b3 = pb.read();
            if (b1 != 0xEF || b2 != 0xBB || b3 != 0xBF) {
                if (b3 != -1) pb.unread(b3);
                if (b2 != -1) pb.unread(b2);
                pb.unread(b1);
            }
        }
        try (InputStreamReader reader = new InputStreamReader(pb, StandardCharsets.UTF_8);
             CSVParser parser = CSVFormat.DEFAULT.builder()
                     .setHeader().setSkipHeaderRecord(true)
                     .setIgnoreEmptyLines(true).setTrim(true)
                     .build().parse(reader)) {
            Map<String, Integer> headerMap = lowerCasedHeaderMap(parser.getHeaderMap());
            Map<String, OrderImportRowDTO> leaders = new LinkedHashMap<>();
            int rowNo = 0;
            for (CSVRecord rec : parser) {
                rowNo++;
                if (isBlank(rec)) continue;
                ColumnReader csvReader = name -> get(rec, headerMap, name);
                OrderImportRowDTO built = buildRow(rowNo, csvReader);
                inheritFromLeader(built, leaders);
                captureExtraColumns(built, headerMap, csvReader);
                out.add(built);
            }
        }
        return out;
    }

    private List<OrderImportRowDTO> parseXlsx(InputStream body) throws Exception {
        List<OrderImportRowDTO> out = new ArrayList<>();
        DataFormatter fmt = new DataFormatter();
        try (Workbook workbook = new XSSFWorkbook(body)) {
            Sheet sheet = workbook.getSheetAt(0);
            if (sheet == null || sheet.getPhysicalNumberOfRows() == 0) return out;

            Row header = sheet.getRow(sheet.getFirstRowNum());
            Map<String, Integer> headerMap = new LinkedHashMap<>();
            if (header != null) {
                for (Cell cell : header) {
                    String label = fmt.formatCellValue(cell).trim();
                    if (StringUtils.hasText(label)) {
                        headerMap.put(label.toLowerCase(Locale.ROOT), cell.getColumnIndex());
                    }
                }
            }

            int rowNo = 0;
            Map<String, OrderImportRowDTO> xlsxLeaders = new LinkedHashMap<>();
            for (int i = sheet.getFirstRowNum() + 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;
                rowNo++;
                boolean anyValue = false;
                for (Cell cell : row) {
                    if (StringUtils.hasText(fmt.formatCellValue(cell))) { anyValue = true; break; }
                }
                if (!anyValue) continue;

                Map<String, Integer> capturedHeader = headerMap;
                int finalI = i;
                ColumnReader xlsxReader = name -> readCell(sheet, finalI, capturedHeader, name, fmt);
                OrderImportRowDTO built = buildRow(rowNo, xlsxReader);
                inheritFromLeader(built, xlsxLeaders);
                captureExtraColumns(built, capturedHeader, xlsxReader);
                out.add(built);
            }
        }
        return out;
    }

    /**
     * Rows after the first of an orderRef may carry only the item columns
     * (the documented multi-line contract). Blank shipment-level fields
     * inherit the leader's values and the row is validated again — before
     * this, a two-line order needed every recipient field retyped on the
     * second line or it failed with "recipientName is required".
     */
    private void inheritFromLeader(OrderImportRowDTO row, Map<String, OrderImportRowDTO> leaders) {
        if (row == null || !StringUtils.hasText(row.getOrderRef())) return;
        String key = row.getOrderRef().trim().toUpperCase(Locale.ROOT);
        OrderImportRowDTO leader = leaders.get(key);
        if (leader == null) {
            leaders.put(key, row);
            return;
        }
        if (!StringUtils.hasText(row.getClientCode())) row.setClientCode(leader.getClientCode());
        if (!StringUtils.hasText(row.getBillTo())) row.setBillTo(leader.getBillTo());
        if (!StringUtils.hasText(row.getWarehouseCode())) row.setWarehouseCode(leader.getWarehouseCode());
        if (!StringUtils.hasText(row.getRecipientName())) row.setRecipientName(leader.getRecipientName());
        if (!StringUtils.hasText(row.getRecipientCompany())) row.setRecipientCompany(leader.getRecipientCompany());
        if (!StringUtils.hasText(row.getRecipientPhone())) row.setRecipientPhone(leader.getRecipientPhone());
        if (!StringUtils.hasText(row.getRecipientEmail())) row.setRecipientEmail(leader.getRecipientEmail());
        if (!StringUtils.hasText(row.getAddressLine1())) row.setAddressLine1(leader.getAddressLine1());
        if (!StringUtils.hasText(row.getAddressLine2())) row.setAddressLine2(leader.getAddressLine2());
        if (!StringUtils.hasText(row.getCity())) row.setCity(leader.getCity());
        if (!StringUtils.hasText(row.getState())) row.setState(leader.getState());
        if (!StringUtils.hasText(row.getPostalCode())) row.setPostalCode(leader.getPostalCode());
        if (!StringUtils.hasText(row.getCountryCode())) row.setCountryCode(leader.getCountryCode());
        if (!StringUtils.hasText(row.getCarrierCode())) row.setCarrierCode(leader.getCarrierCode());
        if (!StringUtils.hasText(row.getAccountNumber())) row.setAccountNumber(leader.getAccountNumber());
        if (!StringUtils.hasText(row.getServiceType())) row.setServiceType(leader.getServiceType());
        if (!StringUtils.hasText(row.getPackageType())) row.setPackageType(leader.getPackageType());
        if (row.getWeight() == null) { row.setWeight(leader.getWeight()); row.setWeightInherited(true); }
        if (!StringUtils.hasText(row.getWeightUnit())) row.setWeightUnit(leader.getWeightUnit());
        if (row.getLength() == null) row.setLength(leader.getLength());
        if (row.getWidth() == null) row.setWidth(leader.getWidth());
        if (row.getHeight() == null) row.setHeight(leader.getHeight());
        if (!StringUtils.hasText(row.getDimUnit())) row.setDimUnit(leader.getDimUnit());
        if (!StringUtils.hasText(row.getCurrency())) row.setCurrency(leader.getCurrency());
        if (!StringUtils.hasText(row.getIncoterms())) row.setIncoterms(leader.getIncoterms());
        if (!StringUtils.hasText(row.getReference())) row.setReference(leader.getReference());
        // Re-validate with the inherited values; keep the parse errors
        // ("… is not a number") that only buildRow can see.
        List<String> keep = new ArrayList<>();
        for (String e : row.getErrors() == null ? List.<String>of() : row.getErrors()) {
            if (e.contains("is not a")) keep.add(e);
        }
        List<String> errors = new ArrayList<>(validateRow(row));
        errors.addAll(keep);
        row.setErrors(errors);
    }

    private static boolean isBlank(CSVRecord rec) {
        for (int i = 0; i < rec.size(); i++) {
            if (StringUtils.hasText(rec.get(i))) return false;
        }
        return true;
    }

    /** Lower-cased canonical headers, for spotting columns we don't own. */
    private static final java.util.Set<String> KNOWN_HEADERS_LOWER =
            HEADERS.stream().map(h -> h.toLowerCase(Locale.ROOT))
                    .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));

    /** Columns the downloadable error file adds — ignored (not custom fields) when it is uploaded again. */
    private static final java.util.Set<String> IGNORED_HEADERS_LOWER = java.util.Set.of("errors", "error", "warnings");

    /**
     * Tier 4 — stash every column that isn't part of the canonical schema.
     * Those are candidate custom-field values; {@link #validateCustomFields}
     * decides which are real fields for the row's client and which are
     * unrecognised. Capturing them here (rather than dropping them at parse
     * time) is what lets a tenant add "PO number" or "Department" to the
     * sheet and have it validated and stored.
     */
    private static void captureExtraColumns(OrderImportRowDTO row,
                                            Map<String, Integer> headerMap,
                                            ColumnReader reader) {
        Map<String, String> extras = new LinkedHashMap<>();
        for (String header : headerMap.keySet()) {
            if (header == null || header.isBlank() || KNOWN_HEADERS_LOWER.contains(header)
                    || IGNORED_HEADERS_LOWER.contains(header)) continue;
            String value = sanitise(reader.read(header));
            if (StringUtils.hasText(value)) extras.put(header.trim(), value);
        }
        if (!extras.isEmpty()) row.setCustomFields(extras);
    }

    private static Map<String, Integer> lowerCasedHeaderMap(Map<String, Integer> raw) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (var e : raw.entrySet()) {
            if (e.getKey() != null) out.put(e.getKey().toLowerCase(Locale.ROOT).trim(), e.getValue());
        }
        return out;
    }

    private static String get(CSVRecord rec, Map<String, Integer> headerMap, String columnName) {
        Integer idx = headerMap.get(columnName.toLowerCase(Locale.ROOT));
        if (idx == null || idx >= rec.size()) return null;
        String value = rec.get(idx);
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static String readCell(Sheet sheet, int rowIndex, Map<String, Integer> headerMap,
                                    String columnName, DataFormatter fmt) {
        Integer idx = headerMap.get(columnName.toLowerCase(Locale.ROOT));
        if (idx == null) return null;
        Row row = sheet.getRow(rowIndex);
        if (row == null) return null;
        Cell cell = row.getCell(idx);
        if (cell == null) return null;
        String value = fmt.formatCellValue(cell).trim();
        return StringUtils.hasText(value) ? value : null;
    }

    /* -------------------------- Row build + validate -------------------------- */

    @FunctionalInterface
    private interface ColumnReader {
        String read(String columnName);
    }

    OrderImportRowDTO buildRow(int rowNumber, ColumnReader r) {
        // Wrap every read in sanitise() so downstream validators + carrier
        // connectors see clean strings — no control chars, no carrier-
        // forbidden characters. sanitise() returns null for whitespace-only
        // input, preserving today's "blank = null" semantics.
        ColumnReader s = name -> sanitise(r.read(name));
        OrderImportRowDTO out = new OrderImportRowDTO();
        out.setRowNumber(rowNumber);
        out.setOrderRef(s.read("orderRef"));
        // Sprint 48 — universal-template columns.
        out.setClientCode(upper(s.read("clientCode")));
        out.setBillTo(upper(s.read("billTo")));
        out.setWarehouseCode(upper(s.read("warehouseCode")));
        out.setRecipientName(s.read("recipientName"));
        out.setRecipientCompany(s.read("recipientCompany"));
        out.setRecipientPhone(s.read("recipientPhone"));
        out.setRecipientEmail(s.read("recipientEmail"));
        out.setAddressLine1(s.read("addressLine1"));
        out.setAddressLine2(s.read("addressLine2"));
        out.setCity(s.read("city"));
        out.setState(s.read("state"));
        out.setPostalCode(s.read("postalCode"));
        out.setCountryCode(upper(s.read("countryCode")));
        out.setCarrierCode(upper(s.read("carrierCode")));
        out.setAccountNumber(s.read("accountNumber"));
        out.setServiceType(s.read("serviceType"));
        out.setPackageType(s.read("packageType"));
        // Capture the raw numeric strings so an unparseable value ("2 lbs",
        // "abc") surfaces as an explicit row error instead of silently
        // becoming null and tripping a misleading "is required" later.
        String rawWeight = s.read("weight");
        String rawQty = s.read("itemQuantity");
        String rawUnitValue = s.read("itemUnitValue");
        out.setWeight(parseDecimal(rawWeight));
        out.setWeightUnit(upper(s.read("weightUnit")));
        out.setLength(parseDecimal(s.read("length")));
        out.setWidth(parseDecimal(s.read("width")));
        out.setHeight(parseDecimal(s.read("height")));
        out.setDimUnit(upper(s.read("dimUnit")));
        out.setCurrency(upper(s.read("currency")));
        out.setIncoterms(upper(s.read("incoterms")));
        out.setReference(s.read("reference"));
        // Sprint 48 revision — declaredValue + goodsDescription removed
        // from HEADERS; derived at commit time from item rows.
        // Sprint 48 — per-item customs data.
        out.setItemDescription(s.read("itemDescription"));
        out.setItemSku(s.read("itemSku"));
        out.setItemQuantity(parseInt(rawQty));
        out.setItemUnitValue(parseDecimal(rawUnitValue));
        out.setHsCode(s.read("hsCode"));
        out.setCountryOfOrigin(upper(s.read("countryOfOrigin")));
        List<String> errors = new ArrayList<>(validateRow(out));
        if (StringUtils.hasText(rawWeight) && out.getWeight() == null) {
            errors.add("weight '" + rawWeight + "' is not a number");
        }
        if (StringUtils.hasText(rawQty) && out.getItemQuantity() == null) {
            errors.add("itemQuantity '" + rawQty + "' is not a whole number");
        }
        if (StringUtils.hasText(rawUnitValue) && out.getItemUnitValue() == null) {
            errors.add("itemUnitValue '" + rawUnitValue + "' is not a number");
        }
        out.setErrors(errors);
        return out;
    }

    /**
     * Strip control characters (0x00–0x1F, 0x7F) and carrier-forbidden
     * chars ({@code < > \ | `}) from a user-supplied string. Returns null
     * when the result is blank so downstream code can treat "just noise"
     * the same as "not supplied at all".
     *
     * <p>Sprint 48 — needed because CSV uploads and Excel paste-in flows
     * routinely carry stray control characters (BOMs, non-breaking
     * spaces sneak in as 0x00 in some exports), and UPS + FedEx reject
     * name / address fields containing any of the punctuation set.
     */
    static String sanitise(String s) {
        if (s == null) return null;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c <= 0x1F || c == 0x7F) continue;
            if (c == '<' || c == '>' || c == '\\' || c == '|' || c == '`') continue;
            sb.append(c);
        }
        String out = sb.toString().trim();
        return out.isEmpty() ? null : out;
    }

    private static Integer parseInt(String s) {
        if (!StringUtils.hasText(s)) return null;
        try { return Integer.parseInt(s.trim().replace(",", "")); }
        catch (NumberFormatException ex) {
            log.debug("OrderImport parseInt: non-numeric input '{}'", s);
            return null;
        }
    }

    /* ------------------- Tier 1: field-shape validation ------------------- */

    /** Pragmatic email shape — mirrors the manual-form validator. */
    private static final java.util.regex.Pattern EMAIL_RE =
            java.util.regex.Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    /** Phone charset — optional +, digits, spaces, dashes, parens, dots. */
    private static final java.util.regex.Pattern PHONE_RE =
            java.util.regex.Pattern.compile("^\\+?[\\d\\s\\-().]+$");
    private static final java.util.regex.Pattern ISO2_RE =
            java.util.regex.Pattern.compile("^[A-Za-z]{2}$");
    private static final java.util.regex.Pattern CURRENCY_RE =
            java.util.regex.Pattern.compile("^[A-Za-z]{3}$");
    /** HS code — 6 to 10 digits, dots allowed ("6109.10.0012"). */
    private static final java.util.regex.Pattern HS_RE =
            java.util.regex.Pattern.compile("^\\d{4,6}(\\.?\\d{2,4}){0,2}$");
    /** Minimum HS/tariff digit count the DESTINATION (importing) country expects
     *  — the first 6 are the international HS; countries extend it (US HTS=10,
     *  EU CN=8…). Mirrors the manual form's HS_MIN_DIGITS. Default 6. */
    private static final Map<String, Integer> HS_MIN_DIGITS = Map.ofEntries(
            Map.entry("US", 10), Map.entry("CA", 8),
            Map.entry("GB", 8), Map.entry("IE", 8), Map.entry("DE", 8), Map.entry("FR", 8),
            Map.entry("IT", 8), Map.entry("ES", 8), Map.entry("NL", 8), Map.entry("BE", 8),
            Map.entry("LU", 8), Map.entry("AT", 8), Map.entry("PT", 8), Map.entry("DK", 8),
            Map.entry("SE", 8), Map.entry("FI", 8), Map.entry("PL", 8), Map.entry("CZ", 8),
            Map.entry("HU", 8), Map.entry("RO", 8), Map.entry("GR", 8),
            Map.entry("IN", 8), Map.entry("CN", 8), Map.entry("AU", 8), Map.entry("MX", 8),
            Map.entry("BR", 8), Map.entry("ZA", 8), Map.entry("JP", 9), Map.entry("KR", 10),
            Map.entry("SG", 8));
    private static final java.util.Set<String> WEIGHT_UNITS = java.util.Set.of("LB", "KG", "LBS", "KGS", "OZ");
    private static final java.util.Set<String> BILL_TO_VALUES = java.util.Set.of("SENDER", "RECIPIENT", "THIRD_PARTY");
    /**
     * Sprint 51 — destinations where the carriers (FedEx/UPS/USPS) require a
     * state / province code and reject the shipment without one. Import
     * validation makes {@code state} mandatory for these countries so a bad
     * row is caught in review instead of failing at label generation.
     */
    private static final java.util.Set<String> STATE_REQUIRED_COUNTRIES =
            java.util.Set.of("US", "CA", "AU", "IN", "BR", "MX");
    /** Every valid ISO-3166 alpha-2 country code — rejects shape-valid but
     *  bogus codes like "ZZ" that the ISO2 regex alone lets through. */
    private static final java.util.Set<String> VALID_ISO_COUNTRIES =
            java.util.Set.of(java.util.Locale.getISOCountries());
    /** Valid state/province codes for the countries where we require state,
     *  so a bad code ("XX") is caught, not just a blank one. */
    private static final java.util.Set<String> US_STATES = java.util.Set.of(
            "AL","AK","AZ","AR","CA","CO","CT","DE","FL","GA","HI","ID","IL","IN","IA","KS","KY","LA",
            "ME","MD","MA","MI","MN","MS","MO","MT","NE","NV","NH","NJ","NM","NY","NC","ND","OH","OK",
            "OR","PA","RI","SC","SD","TN","TX","UT","VT","VA","WA","WV","WI","WY",
            "DC","PR","VI","GU","AS","MP");
    private static final java.util.Set<String> CA_PROVINCES = java.util.Set.of(
            "AB","BC","MB","NB","NL","NS","NT","NU","ON","PE","QC","SK","YT");
    private static final java.util.Set<String> AU_STATES = java.util.Set.of(
            "ACT","NSW","NT","QLD","SA","TAS","VIC","WA");
    private static final Map<String, java.util.Set<String>> STATE_CODES = Map.of(
            "US", US_STATES, "CA", CA_PROVINCES, "AU", AU_STATES);
    /** Generic postal fallback for countries not in ZIP_PATTERNS — alphanumeric,
     *  spaces/dashes, 2–12 chars — so unmodelled destinations aren't a free pass. */
    private static final java.util.regex.Pattern GENERIC_ZIP =
            java.util.regex.Pattern.compile("^[A-Za-z0-9][A-Za-z0-9 -]{1,11}$");
    /** SKU / reference — alphanumeric plus - _ . / and spaces. */
    private static final java.util.regex.Pattern SKU_RE =
            java.util.regex.Pattern.compile("^[A-Za-z0-9][A-Za-z0-9 ._/-]{0,39}$");
    private static final java.util.regex.Pattern REFERENCE_RE =
            java.util.regex.Pattern.compile("^[A-Za-z0-9 ._/#-]{0,40}$");
    /** Per-carrier account-number format (mirrors the SPA add-account drawer,
     *  carrierAccountValidation.ts). Applied to THIRD_PARTY account numbers. */
    private static final Map<String, java.util.regex.Pattern> ACCOUNT_FORMATS = Map.of(
            "UPS",   java.util.regex.Pattern.compile("^[A-Za-z0-9]{6,10}$"),
            "FEDEX", java.util.regex.Pattern.compile("^\\d{9}$"),
            "DHL",   java.util.regex.Pattern.compile("^\\d{9,12}$"),
            "USPS",  java.util.regex.Pattern.compile("^[A-Za-z0-9._@-]{3,50}$"));
    /** Human hint for the account-format error message per carrier. */
    private static final Map<String, String> ACCOUNT_FORMAT_HINT = Map.of(
            "UPS", "6-10 letters/digits", "FEDEX", "9 digits",
            "DHL", "9-12 digits", "USPS", "3-50 chars");
    /** Length caps so a 10,000-char city can't slip through the import. */
    private static final int MAX_TEXT_LEN = 60;
    private static final int MAX_NAME_LEN = 50;
    /**
     * Sprint 51 security fix — server-side XSS guard mirroring the SPA's
     * SAFE_TEXT_RE. The CSV/XLSX import and the public External API both
     * write recipient names straight to the DB WITHOUT passing through the
     * React form regex, and those values later render in the operator
     * dashboard and print on labels/packing slips. React escapes on render,
     * so this is defense-in-depth, but the check belongs on the server too:
     * reject angle brackets so no stored markup can reach a non-React
     * consumer (PDF, CSV export, a future template engine). Null/blank pass.
     */
    private static final java.util.regex.Pattern SAFE_TEXT_RE =
            java.util.regex.Pattern.compile("^[^<>]*$");
    /** Free-text columns the SAFE_TEXT guard applies to, label → getter. */
    private static boolean unsafeText(String v) {
        return v != null && !SAFE_TEXT_RE.matcher(v).matches();
    }
    /** Sanity cap so a stray grams value doesn't book a 5-ton parcel. */
    private static final BigDecimal MAX_WEIGHT = new BigDecimal("9999");

    /** Country-aware postal patterns — same country set as the manual forms.
     *  Unmodelled countries fall back to a permissive alphanumeric check. */
    private static final Map<String, java.util.regex.Pattern> ZIP_PATTERNS = Map.ofEntries(
            Map.entry("US", java.util.regex.Pattern.compile("^\\d{5}(-\\d{4})?$")),
            Map.entry("CA", java.util.regex.Pattern.compile("^[A-Za-z]\\d[A-Za-z][ -]?\\d[A-Za-z]\\d$")),
            Map.entry("GB", java.util.regex.Pattern.compile("^[A-Za-z]{1,2}\\d[A-Za-z\\d]?[ ]?\\d[A-Za-z]{2}$")),
            Map.entry("DE", java.util.regex.Pattern.compile("^\\d{5}$")),
            Map.entry("FR", java.util.regex.Pattern.compile("^\\d{5}$")),
            Map.entry("IT", java.util.regex.Pattern.compile("^\\d{5}$")),
            Map.entry("ES", java.util.regex.Pattern.compile("^\\d{5}$")),
            Map.entry("NL", java.util.regex.Pattern.compile("^\\d{4}\\s?[A-Za-z]{2}$")),
            Map.entry("AU", java.util.regex.Pattern.compile("^\\d{4}$")),
            Map.entry("IN", java.util.regex.Pattern.compile("^\\d{6}$")),
            Map.entry("JP", java.util.regex.Pattern.compile("^\\d{3}-?\\d{4}$")),
            Map.entry("CN", java.util.regex.Pattern.compile("^\\d{6}$")),
            Map.entry("BR", java.util.regex.Pattern.compile("^\\d{5}-?\\d{3}$")),
            Map.entry("MX", java.util.regex.Pattern.compile("^\\d{5}$")),
            Map.entry("SG", java.util.regex.Pattern.compile("^\\d{6}$")));

    public static List<String> validateRow(OrderImportRowDTO row) {
        List<String> errors = new ArrayList<>();
        // Sprint 51 — clientCode is the owning-client identifier; a blank one
        // was silently accepted (validateReferences only checks it when
        // present) and produced an orphaned custNo="MANUAL" order with no
        // client, markup, or customs profile. Require it. Tenant-scoped users
        // never hit this — their blank code is clamped to their own tenant
        // before validation runs; only platform operators can leave it blank.
        if (!StringUtils.hasText(row.getClientCode())) errors.add("clientCode is required");
        if (!StringUtils.hasText(row.getRecipientName())) errors.add("recipientName is required");
        if (!StringUtils.hasText(row.getAddressLine1())) errors.add("addressLine1 is required");
        if (!StringUtils.hasText(row.getCity())) errors.add("city is required");
        if (!StringUtils.hasText(row.getPostalCode())) errors.add("postalCode is required");
        if (!StringUtils.hasText(row.getCountryCode())) errors.add("countryCode is required");
        // State/province is mandatory for destinations whose carriers demand it
        // (US, CA, AU, IN, BR, MX). Applies to domestic and international alike.
        String stateCountry = row.getCountryCode();
        if (StringUtils.hasText(stateCountry)
                && STATE_REQUIRED_COUNTRIES.contains(stateCountry.trim().toUpperCase(Locale.ROOT))
                && !StringUtils.hasText(row.getState())) {
            errors.add("state is required for " + stateCountry.trim().toUpperCase(Locale.ROOT) + " shipments");
        }
        if (row.getWeight() == null || row.getWeight().signum() <= 0) {
            errors.add("weight must be > 0");
        } else if (row.getWeight().compareTo(MAX_WEIGHT) > 0) {
            errors.add("weight must be " + MAX_WEIGHT + " or less");
        }
        // A weight without a unit is ambiguous — require it alongside weight.
        if (!StringUtils.hasText(row.getWeightUnit())) {
            errors.add("weightUnit is required (LB or KG)");
        }

        // --- shape checks: only fire when the value is present ---
        String country = row.getCountryCode();
        String countryUp = StringUtils.hasText(country) ? country.trim().toUpperCase(Locale.ROOT) : null;
        boolean countryShapeOk = countryUp != null && ISO2_RE.matcher(country.trim()).matches();
        if (countryUp != null && !countryShapeOk) {
            errors.add("countryCode '" + country + "' must be a 2-letter ISO code");
        } else if (countryUp != null && !VALID_ISO_COUNTRIES.contains(countryUp)) {
            errors.add("countryCode '" + country + "' is not a recognized country");
        }
        // State/province must be a real code for countries we validate.
        if (countryShapeOk && STATE_CODES.containsKey(countryUp) && StringUtils.hasText(row.getState())
                && !STATE_CODES.get(countryUp).contains(row.getState().trim().toUpperCase(Locale.ROOT))) {
            errors.add("state '" + row.getState() + "' is not a valid " + countryUp + " state/province code");
        }
        String zip = row.getPostalCode();
        if (StringUtils.hasText(zip) && countryShapeOk) {
            java.util.regex.Pattern p = ZIP_PATTERNS.get(countryUp);
            if (p != null && !p.matcher(zip.trim()).matches()) {
                errors.add("postalCode '" + zip + "' doesn't match the " + countryUp + " format");
            } else if (p == null && !GENERIC_ZIP.matcher(zip.trim()).matches()) {
                // Unmodelled country — still enforce a sane postal shape.
                errors.add("postalCode '" + zip + "' is not a valid postal code");
            }
        }
        String email = row.getRecipientEmail();
        if (StringUtils.hasText(email) && !EMAIL_RE.matcher(email.trim()).matches()) {
            errors.add("recipientEmail '" + email + "' is not a valid email");
        }
        String phone = row.getRecipientPhone();
        if (!StringUtils.hasText(phone)) {
            // Carriers (FedEx especially) reject a shipment with no recipient
            // phone — catch it here instead of failing at label time.
            errors.add("recipientPhone is required");
        } else {
            String digits = phone.replaceAll("\\D", "");
            if (!PHONE_RE.matcher(phone.trim()).matches()) {
                errors.add("recipientPhone '" + phone + "' contains invalid characters");
            } else if (digits.length() < 7 || digits.length() > 15) {
                errors.add("recipientPhone needs 7-15 digits");
            }
        }
        String unit = row.getWeightUnit();
        if (StringUtils.hasText(unit) && !WEIGHT_UNITS.contains(unit.trim().toUpperCase(Locale.ROOT))) {
            errors.add("weightUnit '" + unit + "' must be LB or KG");
        }
        String currency = row.getCurrency();
        if (StringUtils.hasText(currency) && !CURRENCY_RE.matcher(currency.trim()).matches()) {
            errors.add("currency '" + currency + "' must be a 3-letter ISO code (e.g. USD)");
        }
        String billTo = row.getBillTo();
        if (StringUtils.hasText(billTo) && !BILL_TO_VALUES.contains(billTo.trim().toUpperCase(Locale.ROOT))) {
            errors.add("billTo '" + billTo + "' must be SENDER, RECIPIENT, or THIRD_PARTY");
        }
        if ("THIRD_PARTY".equalsIgnoreCase(billTo == null ? "" : billTo.trim())
                && !StringUtils.hasText(row.getAccountNumber())) {
            errors.add("billTo=THIRD_PARTY requires an accountNumber");
        }
        String hs = row.getHsCode();
        if (StringUtils.hasText(hs) && !HS_RE.matcher(hs.trim()).matches()) {
            errors.add("hsCode '" + hs + "' must be 6-10 digits (dots allowed)");
        }
        String origin = row.getCountryOfOrigin();
        if (StringUtils.hasText(origin)) {
            if (!ISO2_RE.matcher(origin.trim()).matches()) {
                errors.add("countryOfOrigin '" + origin + "' must be a 2-letter ISO code");
            } else if (!VALID_ISO_COUNTRIES.contains(origin.trim().toUpperCase(Locale.ROOT))) {
                errors.add("countryOfOrigin '" + origin + "' is not a recognized country");
            }
        }
        if (row.getItemQuantity() != null && row.getItemQuantity() < 1) {
            errors.add("itemQuantity must be 1 or more");
        }
        if (row.getItemUnitValue() != null && row.getItemUnitValue().signum() <= 0) {
            errors.add("itemUnitValue must be > 0");
        } else if (row.getItemUnitValue() != null && row.getItemUnitValue().scale() > 2) {
            errors.add("itemUnitValue must have at most 2 decimal places");
        }
        // SKU / reference — keep them to a safe alphanumeric shape.
        if (StringUtils.hasText(row.getItemSku()) && !SKU_RE.matcher(row.getItemSku().trim()).matches()) {
            errors.add("itemSku '" + row.getItemSku() + "' has invalid characters (letters, digits, -_./ only)");
        }
        if (StringUtils.hasText(row.getReference()) && !REFERENCE_RE.matcher(row.getReference().trim()).matches()) {
            errors.add("reference '" + row.getReference() + "' has invalid characters");
        }
        // THIRD_PARTY account numbers must match the carrier's account format.
        if ("THIRD_PARTY".equalsIgnoreCase(billTo == null ? "" : billTo.trim())
                && StringUtils.hasText(row.getAccountNumber())
                && StringUtils.hasText(row.getCarrierCode())) {
            String cc = row.getCarrierCode().trim().toUpperCase(Locale.ROOT);
            java.util.regex.Pattern fmt = ACCOUNT_FORMATS.get(cc);
            if (fmt != null && !fmt.matcher(row.getAccountNumber().trim()).matches()) {
                errors.add("accountNumber '" + row.getAccountNumber() + "' is not a valid " + cc
                        + " account (" + ACCOUNT_FORMAT_HINT.getOrDefault(cc, "check the format") + ")");
            }
        }
        // Length caps so oversized free-text can't slip through the import.
        if (row.getCity() != null && row.getCity().length() > MAX_TEXT_LEN)
            errors.add("city must be " + MAX_TEXT_LEN + " characters or fewer");
        if (row.getAddressLine1() != null && row.getAddressLine1().length() > MAX_TEXT_LEN)
            errors.add("addressLine1 must be " + MAX_TEXT_LEN + " characters or fewer");
        if (row.getAddressLine2() != null && row.getAddressLine2().length() > MAX_TEXT_LEN)
            errors.add("addressLine2 must be " + MAX_TEXT_LEN + " characters or fewer");
        if (row.getRecipientName() != null && row.getRecipientName().length() > MAX_NAME_LEN)
            errors.add("recipientName must be " + MAX_NAME_LEN + " characters or fewer");
        if (row.getRecipientCompany() != null && row.getRecipientCompany().length() > MAX_NAME_LEN)
            errors.add("recipientCompany must be " + MAX_NAME_LEN + " characters or fewer");

        // Batch #8 post-mortem (2026-09-12) — service ↔ destination lane
        // check. Pre-fix, an operator who set UPS 3 Day Select or FedEx
        // Express Saver on an AK/HI row shipped it straight to the
        // carrier and burned a real round-trip on a UPS 121210 / FedEx
        // SERVICETYPE.NOTSUPPORTED. All 25 errors in Batch #8 (out of
        // 7,368 orders) traced to this exact pattern. Rules encoded in
        // CarrierServiceLaneRules; ignored on non-US lanes and on
        // service/carrier combos we don't have a rule for (falls through
        // to the reactive carrier response, same as pre-fix behaviour).
        String laneErr = com.multiship.backend.service.carriers.CarrierServiceLaneRules
                .checkLane(row.getCarrierCode(), row.getServiceType(),
                        row.getState(), row.getCountryCode());
        if (laneErr != null) errors.add("serviceType: " + laneErr);

        // Batch #6 post-mortem (2026-09-12) — US exports to strategic-
        // country destinations (CN + others) require an EEI filing
        // (FTR exemption code or AES ITN) regardless of monetary value.
        // The bulk-import DTO doesn't carry those fields, so any such
        // row will always fail FedEx SHIPMENTVALIDATION.EEIEDIT.ERROR.
        // Flag it at validate time so the operator switches to the
        // manual /orders/new flow (which exposes FTR / AES) or fills
        // them via Data History before generating. Sender is assumed
        // US-origin at import time (backend defaults to platform
        // ship-from); origin-country field isn't on the row DTO.
        String eeiErr = com.multiship.backend.service.carriers.EeiRequirementRules
                .check("US", row.getCountryCode(), null, null);
        if (eeiErr != null) errors.add("customs.eei: " + eeiErr);

        // Sprint 51 security fix — reject stored markup in free-text fields
        // (server-side mirror of the SPA guard; also covers non-UI import).
        // Each message starts with the column name so the review UI renders
        // it under that field, matching the existing per-field convention.
        if (unsafeText(row.getRecipientName()))    errors.add("recipientName must not contain < or >");
        if (unsafeText(row.getRecipientCompany())) errors.add("recipientCompany must not contain < or >");
        if (unsafeText(row.getAddressLine1()))     errors.add("addressLine1 must not contain < or >");
        if (unsafeText(row.getAddressLine2()))     errors.add("addressLine2 must not contain < or >");
        if (unsafeText(row.getCity()))             errors.add("city must not contain < or >");
        if (unsafeText(row.getState()))            errors.add("state must not contain < or >");
        if (unsafeText(row.getItemDescription()))  errors.add("itemDescription must not contain < or >");
        if (unsafeText(row.getReference()))        errors.add("reference must not contain < or >");

        return errors;
    }

    private static String upper(String s) {
        return s == null ? null : s.trim().toUpperCase(Locale.ROOT);
    }

    private static BigDecimal parseDecimal(String s) {
        if (!StringUtils.hasText(s)) return null;
        try { return new BigDecimal(s.trim().replace(",", "")); }
        catch (NumberFormatException ex) {
            log.debug("OrderImport parseDecimal: non-numeric input '{}'", s);
            return null;
        }
    }

    /** SHA-256 over the row DATA fields (ignoring errors/warnings/generated
     *  status), so two uploads of the same file collide even if renamed, while
     *  an edited file hashes differently. Null when hashing is unavailable —
     *  callers then skip the duplicate guard rather than block. */
    public static String contentHash(List<OrderImportRowDTO> rows) {
        if (rows == null || rows.isEmpty()) return null;
        StringBuilder sb = new StringBuilder(rows.size() * 64);
        for (OrderImportRowDTO r : rows) {
            String[] fields = {
                r.getOrderRef(), r.getClientCode(), r.getBillTo(), r.getWarehouseCode(),
                r.getRecipientName(), r.getRecipientCompany(), r.getRecipientPhone(), r.getRecipientEmail(),
                r.getAddressLine1(), r.getAddressLine2(), r.getCity(), r.getState(),
                r.getPostalCode(), r.getCountryCode(), r.getCarrierCode(), r.getAccountNumber(),
                r.getServiceType(), r.getPackageType(), String.valueOf(r.getWeight()), r.getWeightUnit(),
                r.getCurrency(), r.getReference(), r.getItemDescription(), r.getItemSku(),
                String.valueOf(r.getItemQuantity()), String.valueOf(r.getItemUnitValue()),
                r.getHsCode(), r.getCountryOfOrigin(),
                r.getCustomFields() == null ? "" : new java.util.TreeMap<>(r.getCustomFields()).toString(),
            };
            for (String f : fields) sb.append(f == null ? "" : f.trim()).append('');
            sb.append('');
        }
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                                     .append(Character.forDigit(b & 0xF, 16));
            return hex.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Sprint 48 revision — international-shipment rule: when a group
     * ships to a country other than the shipper's origin, the group MUST
     * have at least one row with complete customs details
     * (itemDescription + hsCode + countryOfOrigin + itemQuantity +
     * itemUnitValue). Without those, every int'l carrier connector
     * fails at the customs block.
     *
     * <p>Sprint 51 — "international" is now decided per row against the
     * OWNING CLIENT'S ship-from country (Client.shipFrom.country →
     * defaultOriginCountry), matching label-time resolution, instead of a
     * hardcoded US assumption. A client that ships from India treats an
     * India destination as domestic; a US-origin client shipping to India
     * is international. When the client's origin is unknown we keep the
     * legacy US baseline so behaviour never silently loosens.
     */
    private void validateInternationalItems(List<OrderImportRowDTO> rows) {
        // Resolve each client's ship-from country once (upper-cased).
        Map<String, String> originByClient = new java.util.HashMap<>();
        if (clientRepository != null) {
            java.util.Set<String> codes = new java.util.LinkedHashSet<>();
            for (OrderImportRowDTO r : rows) {
                if (StringUtils.hasText(r.getClientCode())) codes.add(r.getClientCode().trim());
            }
            if (!codes.isEmpty()) {
                for (Client c : clientRepository.findByClientCodeInIgnoreCase(new ArrayList<>(codes))) {
                    String origin = c.getShipFrom() != null && StringUtils.hasText(c.getShipFrom().getCountry())
                            ? c.getShipFrom().getCountry() : c.getDefaultOriginCountry();
                    if (StringUtils.hasText(origin) && StringUtils.hasText(c.getClientCode())) {
                        originByClient.put(c.getClientCode().trim().toUpperCase(Locale.ROOT),
                                origin.trim().toUpperCase(Locale.ROOT));
                    }
                }
            }
        }
        // carrier → (serviceCode → scope DOMESTIC|INTERNATIONAL|BOTH). Used to
        // catch a domestic-only service (e.g. FEDEX_GROUND) put on a shipment
        // that crosses a border, which otherwise defaults through and fails at
        // the carrier for a second, unrelated-looking reason.
        Map<String, Map<String, String>> serviceScope = new java.util.HashMap<>();
        if (shippingServiceRepository != null) {
            for (com.multiship.backend.model.ShippingService s
                    : shippingServiceRepository.findAllByOrderByCarrierAscSortOrderAsc()) {
                if (s.getCarrier() == null || s.getServiceCode() == null || s.getScope() == null) continue;
                serviceScope
                        .computeIfAbsent(s.getCarrier().toUpperCase(Locale.ROOT), k -> new java.util.HashMap<>())
                        .put(s.getServiceCode().toUpperCase(Locale.ROOT), s.getScope().trim().toUpperCase(Locale.ROOT));
            }
        }
        Map<String, List<OrderImportRowDTO>> groups = new LinkedHashMap<>();
        for (OrderImportRowDTO row : rows) {
            String key = StringUtils.hasText(row.getOrderRef())
                    ? row.getOrderRef().trim()
                    : "__row_" + row.getRowNumber();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }
        for (List<OrderImportRowDTO> group : groups.values()) {
            OrderImportRowDTO leader = group.get(0);
            String country = leader.getCountryCode();
            if (!StringUtils.hasText(country)) continue; // country-required error fires elsewhere
            // Domestic when the destination equals the owning client's ship-from
            // country; unknown origin falls back to the legacy US baseline.
            String origin = StringUtils.hasText(leader.getClientCode())
                    ? originByClient.getOrDefault(leader.getClientCode().trim().toUpperCase(Locale.ROOT), "US")
                    : "US";
            if (country.trim().equalsIgnoreCase(origin)) continue; // domestic

            // Accumulate every international-only requirement on the leader.
            // Each message starts with the column name so the review UI paints
            // it on that field's cell (and tooltip) instead of one long
            // row-level sentence.
            List<String> errs = new ArrayList<>(
                    leader.getErrors() == null ? List.of() : leader.getErrors());
            String suffix = " is required for international shipments (countryCode=" + country + ")";

            // (0) Service level must be able to cross the border. A blank service
            //     defaults to the carrier's domestic Ground at label time (which
            //     then fails for an international lane), and a DOMESTIC-scope
            //     service (e.g. FEDEX_GROUND) can't cross a border either — catch
            //     both on the serviceType cell instead of at the carrier.
            //     Unknown/BOTH scope on a named service is left alone.
            String svc = normalizeOrNull(leader.getServiceType());
            String carr = normalizeOrNull(leader.getCarrierCode());
            if (svc == null) {
                errs.add("serviceType is required for international shipments (countryCode=" + country
                        + "); a blank service ships domestic Ground and can't cross a border");
            } else if (carr != null) {
                String scope = serviceScope.getOrDefault(carr, java.util.Map.of()).get(svc);
                if ("DOMESTIC".equals(scope)) {
                    errs.add("serviceType '" + svc + "' is a domestic-only service and cannot ship to "
                            + country.trim().toUpperCase(Locale.ROOT)
                            + "; choose an international service for this carrier");
                }
            }

            // (a) Shipment-level customs essentials the carrier rejects without.
            //     Phone is a hard carrier requirement for international delivery
            //     + customs contact; currency denominates the declared value.
            if (!StringUtils.hasText(leader.getRecipientPhone())) errs.add("recipientPhone" + suffix);
            if (!StringUtils.hasText(leader.getCurrency()))       errs.add("currency" + suffix);

            // (b) Full customs commodity line — at least one row in the group
            //     must carry description + HS + origin + qty + unit value.
            boolean hasFullItem = false;
            for (OrderImportRowDTO row : group) {
                if (StringUtils.hasText(row.getItemDescription())
                        && StringUtils.hasText(row.getHsCode())
                        && StringUtils.hasText(row.getCountryOfOrigin())
                        && row.getItemQuantity() != null
                        && row.getItemUnitValue() != null) {
                    hasFullItem = true;
                    break;
                }
            }
            if (!hasFullItem) {
                if (!StringUtils.hasText(leader.getItemDescription())) errs.add("itemDescription" + suffix);
                if (!StringUtils.hasText(leader.getHsCode()))           errs.add("hsCode" + suffix);
                if (!StringUtils.hasText(leader.getCountryOfOrigin()))  errs.add("countryOfOrigin" + suffix);
                if (leader.getItemQuantity() == null)                   errs.add("itemQuantity" + suffix);
                if (leader.getItemUnitValue() == null)                  errs.add("itemUnitValue" + suffix);
            }
            // (c) Country-wise HS length — a present HS code must carry the digit
            //     count the importing country expects (US/CA=10, EU=8…). Mirrors
            //     the manual form so bulk + manual reject the same short codes.
            int minHs = HS_MIN_DIGITS.getOrDefault(country.trim().toUpperCase(Locale.ROOT), 6);
            for (OrderImportRowDTO row : group) {
                String hs = row.getHsCode();
                if (!StringUtils.hasText(hs)) continue;
                int digits = hs.replaceAll("\\D", "").length();
                if (digits >= 6 && digits < minHs) {
                    String msg = "hsCode '" + hs.trim() + "' needs at least " + minHs + " digits for "
                            + country.trim().toUpperCase(Locale.ROOT) + " (you entered " + digits + ")";
                    if (!errs.contains(msg)) errs.add(msg);
                }
            }
            leader.setErrors(errs);
        }
    }

    /**
     * Sprint 50 Tier 1 finding #8 — per-group commit worker. Runs on a
     * fanOutExecutor thread. Mutates only rows in {@code group} + calls
     * generateManualLabel (which is @Transactional on its own bean, so
     * each row's persistence gets its own tx — safe under concurrent
     * invocation). Catches every failure per-group so one bad row can't
     * take down the whole batch.
     */
    private GroupOutcome processGroup(List<OrderImportRowDTO> group, Integer batchId, boolean usePlatformAccount,
                                      String sourceOverride) {
        OrderImportRowDTO leader = group.get(0);
        // Merge shape errors with the reference/international errors already
        // stamped on the row by the pre-loop validators (commit() runs
        // validateReferences + validateInternationalItems before fanning out).
        List<String> errors = new ArrayList<>(validateRow(leader));
        if (leader.getErrors() != null) {
            for (String e : leader.getErrors()) {
                if (!errors.contains(e)) errors.add(e);
            }
        }
        leader.setErrors(errors);
        if (!errors.isEmpty()) {
            for (int i = 1; i < group.size(); i++) {
                group.get(i).setErrors(List.of("orderRef leader failed validation"));
            }
            return new GroupOutcome(0, group.size(), 0);
        }
        int valid = group.size();
        if (carrierService == null) return new GroupOutcome(valid, 0, 0);

        try {
            com.multiship.backend.dto.ManualShipmentRequest req = toManualShipmentRequest(group);
            // WMS/API-fetched batches persist their orders as source=API (not
            // the default BULK) so they stay grouped under the API section.
            if (StringUtils.hasText(sourceOverride)) req.setSource(sourceOverride);

            // Sprint 51 fix #2 — establish the ship-from origin from the
            // client's configured address. Without a sender the manual path
            // falls back to the PLATFORM default shipper country, which makes
            // the international/customs decision wrong for every bulk row (a
            // US client's US-domestic parcel looked international, and a
            // genuine cross-border shipment looked domestic — so no customs
            // block was persisted and none was sent to the carrier). A named
            // warehouse still wins: the manual path overrides `from` with the
            // warehouse address when warehouseCode is set.
            applyClientOrigin(req, leader.getClientCode());
            // A blank warehouseCode means the client's DEFAULT warehouse — the
            // same one the New Shipment form pre-selects — not the client's
            // registered address (which put a Santa Monica exporter on a
            // parcel that ships from Austin).
            applyDefaultWarehouse(req, leader.getClientCode());
            // The CSV's serviceType is honoured or the row fails. It used to be
            // dropped on the floor (the request only carries a service id), so
            // every row silently shipped on the carrier default (ground) —
            // including international rows.
            String serviceError = applyRequestedService(req, leader);
            if (serviceError != null) {
                List<String> ce = new ArrayList<>(leader.getErrors() == null ? List.of() : leader.getErrors());
                ce.add(serviceError);
                leader.setErrors(ce);
                for (int i = 1; i < group.size(); i++) {
                    group.get(i).setErrors(List.of("orderRef leader failed validation"));
                }
                return new GroupOutcome(0, group.size(), 0);
            }

            // Sprint 51 fix #1 — when the CSV leaves the bill-to account blank,
            // resolve it the way the operator-facing flow would instead of
            // hard-failing. The single-order cascade returns CHOOSE_ACCOUNT and
            // lets the operator pick; the interactive manual path deliberately
            // demands an explicit account for that picker. Bulk has no picker,
            // so we resolve the client's default here — the same account the
            // picker would pre-select — and fall back to the platform (house)
            // account when the client has none, mirroring the cascade's
            // SCENARIO_DEFAULT. Only genuine ambiguity (several client accounts,
            // none flagged default) still fails, now with an actionable message.
            // Sprint 51 — "Use platform account" (Data History generate option):
            // force the platform (house) account for this carrier, overriding
            // whatever the row/client resolution would pick. Otherwise fall back
            // to the normal cascade only when the bill-to account is blank.
            if (usePlatformAccount) {
                BulkAccountPick pick = resolvePlatformAccount(leader.getCarrierCode());
                if (pick.error != null) {
                    List<String> ce = new ArrayList<>(leader.getErrors() == null ? List.of() : leader.getErrors());
                    ce.add(pick.error);
                    leader.setErrors(ce);
                    for (int i = 1; i < group.size(); i++) {
                        group.get(i).setErrors(List.of("orderRef leader failed validation"));
                    }
                    return new GroupOutcome(0, group.size(), 0);
                }
                req.setAccountNumber(pick.accountNumber);
            } else if (!StringUtils.hasText(req.getAccountNumber())) {
                BulkAccountPick pick = resolveBulkAccount(leader.getClientCode(), leader.getCarrierCode());
                if (pick.error != null) {
                    List<String> ce = new ArrayList<>(leader.getErrors() == null ? List.of() : leader.getErrors());
                    ce.add(pick.error);
                    leader.setErrors(ce);
                    for (int i = 1; i < group.size(); i++) {
                        group.get(i).setErrors(List.of("orderRef leader failed validation"));
                    }
                    return new GroupOutcome(0, group.size(), 0);
                }
                req.setAccountNumber(pick.accountNumber);
            }

            // Reuse the order row from a prior attempt (a failed row carries the
            // ERROR order's number) so a retry UPDATEs that same order instead of
            // INSERTing a second one for the source row. Null on the first attempt
            // → a fresh order is minted.
            // Carrier in a rate-limit cool-down: don't call it (that only extends the
            // throttle); queue the order for the automatic retry pass instead.
            String rlCarrier = carrierKey(leader);
            Long pausedUntil = carrierPauseUntil.get(rlCarrier);
            if (pausedUntil != null && pausedUntil > System.currentTimeMillis()) {
                markRateLimited(group, rlCarrier, batchId);
                return new GroupOutcome(valid, 0, 0, true);
            }
            Integer existingOrderNo = leader.getGeneratedOrderNo();
            ApiResponse<com.multiship.backend.dto.LabelGenerationResponse> resp =
                    carrierService.generateManualLabel(req, null, existingOrderNo);
            com.multiship.backend.dto.LabelGenerationResponse data =
                    resp == null ? null : resp.getData();
            if (resp != null && "success".equalsIgnoreCase(resp.getStatus()) && data != null
                    && StringUtils.hasText(data.getTrackingNumber())) {
                Integer orderNo = data.getOrderNo() == null ? null : data.getOrderNo().intValue();
                for (OrderImportRowDTO gr : group) {
                    gr.setGeneratedOrderNo(orderNo);
                    gr.setGeneratedTrackingNumber(data.getTrackingNumber());
                    gr.setGeneratedStatus("GENERATED");
                    gr.setGeneratedMessage(data.getMessage());
                    gr.setBatchId(batchId);
                }
                if (orderNo != null && batchId != null && orderRepository != null) {
                    orderRepository.findByOrderNo(orderNo).ifPresent(order -> {
                        order.setBatchId(batchId);
                        orderRepository.save(order);
                    });
                }
                // Tier 4 — persist the validated custom-field values onto the
                // order. Keys were re-mapped to their definition's fieldKey
                // during validation, so they're already in the shape
                // upsertValues expects. Non-fatal: the label is bought and a
                // metadata write must not fail the import.
                if (orderNo != null && customFieldService != null
                        && leader.getCustomFields() != null && !leader.getCustomFields().isEmpty()) {
                    try {
                        customFieldService.upsertValues(orderNo, leader.getClientCode(), leader.getCustomFields());
                    } catch (Exception ex) {
                        log.warn("Custom field write failed for order {}: {}", orderNo, ex.getMessage());
                    }
                }
                carrierStrikes.remove(rlCarrier);
                return new GroupOutcome(valid, 0, 1);
            } else {
                String raw = resp == null ? "no response" : resp.getMessage();
                if (raw != null) log.warn("Order import group (leader row {}) carrier rejection: {}",
                        leader.getRowNumber(), raw);
                // A platform validation failure ("accountNumber 999999 is not a
                // registered UPS account…") is already an operator sentence —
                // the carrier humanizer turned it into "UPS rejected the billing
                // account", which points at the wrong place (no carrier was called).
                String code = resp == null || resp.getErrorCode() == null ? "" : String.valueOf(resp.getErrorCode());
                if ("CARRIER_RATE_LIMITED".equals(code)) {
                    noteRateLimited(rlCarrier, raw);
                    markRateLimited(group, rlCarrier, batchId);
                    return new GroupOutcome(valid, 0, 0, true);
                }
                String msg = code.contains("VALIDATION") ? raw : humanizeCarrierError(raw, leader);
                // The ERROR order's number comes back in the failure data — record
                // it on every row of the group so a retry reuses it (no duplicate
                // INSERT), and attach it to this batch so failed orders aren't
                // orphaned with a null batchId.
                Integer failedNo = (data != null && data.getOrderNo() != null)
                        ? data.getOrderNo().intValue() : null;
                for (OrderImportRowDTO gr : group) {
                    gr.setGeneratedStatus("FAILED");
                    gr.setGeneratedMessage(msg);
                    if (failedNo != null) gr.setGeneratedOrderNo(failedNo);
                    if (batchId != null) gr.setBatchId(batchId);
                }
                if (failedNo != null && batchId != null && orderRepository != null) {
                    orderRepository.findByOrderNo(failedNo).ifPresent(order -> {
                        order.setBatchId(batchId);
                        orderRepository.save(order);
                    });
                }
                return new GroupOutcome(valid, 0, 0);
            }
        } catch (Exception ex) {
            log.warn("Order import group (leader row {}) failed at label generation: {}",
                    leader.getRowNumber(), ex.getMessage());
            String raw = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            String msg = humanizeCarrierError(raw, leader);
            for (OrderImportRowDTO gr : group) {
                gr.setGeneratedStatus("FAILED");
                gr.setGeneratedMessage(msg);
            }
            return new GroupOutcome(valid, 0, 0);
        }
    }

    /**
     * Turn a raw carrier rejection into one operator-facing sentence. The raw
     * connector output ("FEDEX createShipment HTTP 400: {\"transactionId\":…}")
     * is debug noise — it's logged at WARN and kept out of the grid. Known
     * failure tokens map to an actionable sentence naming the carrier and, where
     * relevant, the fix. Anything unrecognised falls back to a plain rejection
     * line rather than leaking the payload. The "(saved as order N)" suffix the
     * carrier layer appends is preserved so the operator can still find the row.
     */
    private static String humanizeCarrierError(String raw, OrderImportRowDTO leader) {
        // Delegates to the shared humanizer so the bulk grid, the manual toast
        // and the Logs page all describe the same failure with the same words.
        return com.multiship.backend.util.CarrierErrorMessages.humanize(
                raw, leader == null ? null : leader.getCarrierCode());
    }

    /** Sprint 50 Tier 1 finding #8 — return shape for a per-group commit worker. */
    private record GroupOutcome(int valid, int invalid, int generated, boolean rateLimited) {
        GroupOutcome(int valid, int invalid, int generated) { this(valid, invalid, generated, false); }
    }

    /**
     * One commit worker: the cancel gate, then the carrier call. Ticks the progress
     * bar once when the group settles; a group deferred by a carrier rate limit ticks
     * when its automatic retry settles instead, so the bar doesn't reach 100% while
     * orders are still queued.
     */
    private Callable<GroupOutcome> groupTask(List<OrderImportRowDTO> group, Integer batchId, boolean usePlatformAccount,
                                             String sourceOverride, Runnable onGroupComplete,
                                             java.util.function.BooleanSupplier cancelCheck) {
        return () -> {
            GroupOutcome outcome = null;
            try {
                // Import I-3 — cancel gate checked BEFORE the carrier call so queued
                // groups are skipped as soon as the operator hits Cancel. In-flight
                // carrier calls finish naturally (a paid label can't be interrupted).
                if (cancelCheck != null && cancelCheck.getAsBoolean()) {
                    for (OrderImportRowDTO r : group) {
                        r.setGeneratedStatus("FAILED");
                        java.util.List<String> errs = new java.util.ArrayList<>(
                                r.getErrors() == null ? java.util.List.of() : r.getErrors());
                        errs.add("Cancelled by operator before dispatch");
                        r.setErrors(errs);
                    }
                    outcome = new GroupOutcome(0, group.size(), 0);
                    return outcome;
                }
                outcome = processGroup(group, batchId, usePlatformAccount, sourceOverride);
                return outcome;
            } finally {
                if (onGroupComplete != null && (outcome == null || !outcome.rateLimited())) onGroupComplete.run();
            }
        };
    }

    /**
     * Carrier rate limits (HTTP 429). In the 2026-09-10 load test UPS throttled
     * from about order 480 and 139 orders failed; an immediate retry recovered
     * none, a retry minutes later recovered them all. So: when a carrier answers
     * 429, every import worker holds off that carrier until the cool-down ends
     * (no hammering), and the throttled orders are resent automatically after it.
     */
    private final Map<String, Long> carrierPauseUntil = new java.util.concurrent.ConcurrentHashMap<>();
    /** Consecutive rate-limit episodes per carrier, for the escalating wait (reset by a success). */
    private final Map<String, Integer> carrierStrikes = new java.util.concurrent.ConcurrentHashMap<>();
    /** Cool-down per episode (1st, 2nd, 3rd, 4th+). Instance field so tests can shorten it. */
    private long[] rateLimitWaitsMs = {30_000L, 60_000L, 120_000L, 240_000L};
    private static final int MAX_RATE_LIMIT_PASSES = 4;

    private static String carrierKey(OrderImportRowDTO leader) {
        return leader == null || leader.getCarrierCode() == null ? "" : leader.getCarrierCode().trim().toUpperCase(Locale.ROOT);
    }

    private static String carrierLabel(String key) {
        return "FEDEX".equals(key) ? "FedEx" : (key == null || key.isBlank() ? "The carrier" : key);
    }

    private void noteRateLimited(String carrier, String message) {
        long now = System.currentTimeMillis();
        Long hinted = null;
        if (message != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("retry after (\\d+)\\s*s", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(message);
            if (m.find()) hinted = Long.parseLong(m.group(1)) * 1000L;
        }
        final Long hint = hinted;
        carrierPauseUntil.compute(carrier, (k, until) -> {
            boolean newEpisode = until == null || until <= now;
            int strikes = newEpisode ? carrierStrikes.merge(k, 1, Integer::sum) : carrierStrikes.getOrDefault(k, 1);
            long wait = rateLimitWaitsMs[Math.min(Math.max(strikes, 1), rateLimitWaitsMs.length) - 1];
            if (hint != null) wait = Math.max(wait, hint);
            long next = now + wait;
            return until == null ? next : Math.max(until, next);
        });
    }

    private static void markRateLimited(List<OrderImportRowDTO> group, String carrier, Integer batchId) {
        for (OrderImportRowDTO gr : group) {
            gr.setGeneratedStatus("FAILED");
            gr.setGeneratedMessage(carrierLabel(carrier)
                    + " asked us to slow down (rate limit) — this order is queued for an automatic retry.");
            if (batchId != null) gr.setBatchId(batchId);
        }
    }

    /** Milliseconds until every carrier in these groups is out of its cool-down (at least 1 s). */
    private long waitForCarriers(List<List<OrderImportRowDTO>> groups) {
        long now = System.currentTimeMillis();
        long until = now + 1_000L;
        for (List<OrderImportRowDTO> g : groups) {
            Long u = carrierPauseUntil.get(carrierKey(g.get(0)));
            if (u != null && u > until) until = u;
        }
        return until - now;
    }

    /**
     * Sprint 51 fix #2 — set the ship-from origin on a bulk request from the
     * client's configured ship-from address, so the international/customs
     * determination runs against the real origin instead of the platform
     * default shipper country. No-op when a sender is already present, the
     * client is unknown, or the client has no usable origin country (leaving
     * the existing platform-default behaviour untouched). A named warehouse
     * on the row still wins — the manual path resolves and overrides `from`
     * with the warehouse address downstream.
     */
    private void applyClientOrigin(com.multiship.backend.dto.ManualShipmentRequest req, String clientCode) {
        if (req.getSender() != null || !StringUtils.hasText(clientCode) || clientRepository == null) return;
        Client client = clientRepository.findByClientCodeIgnoreCase(clientCode.trim()).orElse(null);
        if (client == null) return;
        com.multiship.backend.model.Address sf = client.getShipFrom();
        String country = sf != null && StringUtils.hasText(sf.getCountry())
                ? sf.getCountry() : client.getDefaultOriginCountry();
        if (!StringUtils.hasText(country)) return; // nothing better than the platform default
        com.multiship.backend.dto.ManualShipmentRequest.Address sender =
                new com.multiship.backend.dto.ManualShipmentRequest.Address();
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

    /**
     * Sprint 51 fix #1 — result of resolving a bulk row's bill-to account when
     * the CSV omitted it. Exactly one field is non-null: {@code accountNumber}
     * (the account to bill, possibly the platform account) or {@code error}
     * (an actionable per-row validation message).
     */
    private record BulkAccountPick(String accountNumber, String error) {
        static BulkAccountPick of(String accountNumber) { return new BulkAccountPick(accountNumber, null); }
        static BulkAccountPick fail(String error) { return new BulkAccountPick(null, error); }
    }

    /**
     * Resolve the bill-to account for a bulk row that left {@code accountNumber}
     * blank, mirroring the single-order account cascade's selection order:
     * <ol>
     *   <li>The client's own complete, active accounts on this carrier — pick
     *       the one flagged {@code clientDefault}; if none is flagged but there
     *       is exactly one, use it (that's what the picker would pre-select).</li>
     *   <li>No client account on this carrier → the platform (house) account,
     *       matching the cascade's SCENARIO_DEFAULT auto-ship.</li>
     * </ol>
     * Genuine ambiguity (several client accounts, none flagged default) fails
     * with a message telling the operator how to disambiguate.
     */
    /** Pick the platform (house) account for a carrier, ignoring client/row
     *  accounts entirely — backs the "Use platform account" generate option. */
    private BulkAccountPick resolvePlatformAccount(String carrierCode) {
        String carrier = StringUtils.hasText(carrierCode)
                ? carrierCode.trim().toUpperCase(Locale.ROOT) : null;
        if (carrier == null) {
            return BulkAccountPick.fail("carrierCode is required to resolve a bill-to account");
        }
        CarrierAccountRef platform = accountRefRepository
                .findPlatformAccountsByCarrier(carrier).stream().findFirst().orElse(null);
        if (platform != null) return BulkAccountPick.of(platform.getAccountNumber());
        return BulkAccountPick.fail("No platform " + carrier
                + " account is configured; add one under Carriers before using the platform account");
    }

    private BulkAccountPick resolveBulkAccount(String clientCode, String carrierCode) {
        String carrier = StringUtils.hasText(carrierCode)
                ? carrierCode.trim().toUpperCase(Locale.ROOT) : null;
        if (carrier == null) {
            return BulkAccountPick.fail("carrierCode is required to resolve a bill-to account");
        }

        List<CarrierAccountRef> clientAccounts = StringUtils.hasText(clientCode)
                ? accountRefRepository
                    .findByCustomerNoIgnoreCaseOrderByClientDefaultDescUpdatedAtDesc(clientCode.trim()).stream()
                    .filter(a -> !Boolean.FALSE.equals(a.getActive()))
                    .filter(CarrierAccountRef::isComplete)
                    .filter(a -> carrier.equalsIgnoreCase(
                            a.getCarrierCode() == null ? "" : a.getCarrierCode().trim()))
                    .toList()
                : List.of();

        if (!clientAccounts.isEmpty()) {
            List<CarrierAccountRef> flagged = clientAccounts.stream()
                    .filter(a -> Boolean.TRUE.equals(a.getClientDefault()))
                    .toList();
            if (!flagged.isEmpty()) return BulkAccountPick.of(flagged.get(0).getAccountNumber());
            if (clientAccounts.size() == 1) return BulkAccountPick.of(clientAccounts.get(0).getAccountNumber());
            return BulkAccountPick.fail(clientCode + " has " + clientAccounts.size() + " " + carrier
                    + " accounts and no default; set a default account or put accountNumber in the row");
        }

        // The client has no usable account on this carrier — fall back to the
        // platform (house) account, per the client's rule: a bulk row that leaves
        // accountNumber blank bills the platform account. Only when there is no
        // platform account for the carrier either is there nothing left to bill.
        CarrierAccountRef platform = accountRefRepository
                .findPlatformAccountsByCarrier(carrier).stream().findFirst().orElse(null);
        if (platform != null) return BulkAccountPick.of(platform.getAccountNumber());

        return BulkAccountPick.fail("No " + carrier
                + " account is available to bill; add a client or platform account, or set accountNumber");
    }

    private static OrderImportPreviewDTO buildPreview(List<OrderImportRowDTO> rows) {
        int invalid = (int) rows.stream()
                .filter(r -> r.getErrors() != null && !r.getErrors().isEmpty())
                .count();
        return OrderImportPreviewDTO.builder()
                .totalRows(rows.size())
                .validRows(rows.size() - invalid)
                .invalidRows(invalid)
                .rows(rows)
                .build();
    }

    // ════════════════════════════════════════════════════════════════════
    // Staging — bulk-upload restructure (2026-09-11).
    //   1. Upload   → the file is parsed and parked in import_staging_*.
    //   2. Validate → staged rows are validated and can be edited in place.
    //   3. Save     → ONLY fully valid orders (every row of the orderRef valid)
    //                 go to Import history (import_batch) through save().
    // Orders with errors stay staged until fixed, discarded or expired, and can
    // be downloaded as an error file. No labels are generated here.
    // ════════════════════════════════════════════════════════════════════

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.multiship.backend.repository.ImportStagingUploadRepository stagingUploadRepository;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.multiship.backend.repository.ImportStagingRowRepository stagingRowRepository;
    @org.springframework.beans.factory.annotation.Value("${import.staging.retention-days:7}")
    private int stagingRetentionDays = 7;

    private static final com.fasterxml.jackson.databind.ObjectMapper STAGING_JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private record StagingCounts(int totalRows, int validRows, int totalOrders, int validOrders,
                                 int invalidOrders, int savedOrders, int readyOrders) {}

    private com.fasterxml.jackson.databind.ObjectMapper stagingJson() {
        return importObjectMapper != null ? importObjectMapper : STAGING_JSON;
    }

    @Override
    public ApiResponse<StagingUploadDTO> stageUpload(String filename, InputStream body,
                                                     boolean allowDuplicate, String requestedBy) {
        if (stagingUploadRepository == null || stagingRowRepository == null) {
            return stagingFailure(HttpStatus.SERVICE_UNAVAILABLE, "Upload staging is not available on this server.");
        }
        // Same parse + validation (and Import-history duplicate-file gate) as before.
        // Parse + validate. The duplicate-file gate runs below instead, AFTER the
        // staging check, so re-uploading a file whose upload is still waiting (even
        // partly saved) offers "Continue" rather than "already imported".
        ApiResponse<OrderImportPreviewDTO> parsed = preview(filename, body, null, true);
        if (parsed == null || !"success".equalsIgnoreCase(parsed.getStatus()) || parsed.getData() == null) {
            HttpStatus st = parsed == null ? HttpStatus.BAD_REQUEST
                    : java.util.Optional.ofNullable(HttpStatus.resolve(parsed.getCode())).orElse(HttpStatus.BAD_REQUEST);
            return stagingFailure(st, parsed == null ? "Upload failed." : parsed.getMessage());
        }
        List<OrderImportRowDTO> rows = parsed.getData().getRows() == null
                ? new ArrayList<>() : new ArrayList<>(parsed.getData().getRows());
        if (rows.isEmpty()) {
            return stagingFailure(HttpStatus.UNPROCESSABLE_ENTITY, "The file has no order rows.");
        }
        String hash = contentHash(rows);
        if (!allowDuplicate) {
            // 1) Already waiting in staging: offer to continue it. Staging the same
            //    file twice would let both copies reach Import history.
            if (hash != null) {
                ImportStagingUpload open = stagingUploadRepository
                        .findFirstByContentHashAndStatusOrderByIdDesc(hash, "OPEN").orElse(null);
                if (open != null && canAccessStaging(open, requestedBy)) {
                    StagingUploadDTO waiting = stagingSummary(open);
                    return ApiResponse.<StagingUploadDTO>builder()
                            .status("error").code(HttpStatus.CONFLICT.value())
                            .errorCode(ErrorCode.VALIDATION_ERROR.name())
                            .message("Already uploaded as upload #" + open.getId() + " (" + open.getFileName() + "): "
                                    + waiting.getReadyOrders() + " ready · " + needFixes(waiting.getInvalidOrders())
                                    + (waiting.getSavedOrders() > 0 ? " · " + waiting.getSavedOrders() + " saved" : "")
                                    + ". Continue that upload, or upload this file anyway as a new one.")
                            .data(waiting)
                            .build();
                }
            }
            // 2) Already in Import history — same file name or same content.
            if (importBatchRepository != null) {
                String normName = StringUtils.hasText(filename) ? filename.trim() : null;
                if (normName != null) {
                    com.multiship.backend.model.ImportBatch byName = importBatchRepository
                            .findFirstByFileNameIgnoreCaseAndDeletedAtIsNullOrderByIdDesc(normName).orElse(null);
                    if (byName != null) {
                        return stagingFailure(HttpStatus.CONFLICT,
                                "A file named \"" + normName + "\" is already imported as #" + byName.getId()
                                + (byName.getCreatedAt() != null ? " on " + byName.getCreatedAt().toLocalDate() : "")
                                + ". Delete it from Import history (or rename the file) before importing again.");
                    }
                }
                if (hash != null) {
                    com.multiship.backend.model.ImportBatch dup = importBatchRepository
                            .findFirstByContentHashAndDeletedAtIsNullOrderByIdDesc(hash).orElse(null);
                    if (dup != null) {
                        return stagingFailure(HttpStatus.CONFLICT,
                                "This file was already imported as #" + dup.getId()
                                + " (" + (StringUtils.hasText(dup.getFileName()) ? dup.getFileName() : "Untitled") + ")"
                                + ". Edit a value or upload a different file to import a changed version.");
                    }
                }
            }
        }
        flagAlreadyInImportHistory(rows, Map.of());
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        ImportStagingUpload up = new ImportStagingUpload();
        up.setCreatedBy(requestedBy);
        up.setFileName(StringUtils.hasText(filename) ? clip(filename.trim(), 260) : "upload");
        up.setContentHash(hash);
        up.setStatus("OPEN");
        up.setCreatedAt(now);
        up.setUpdatedAt(now);
        up = stagingUploadRepository.save(up);
        List<ImportStagingRow> entities = new ArrayList<>(rows.size());
        for (OrderImportRowDTO r : rows) {
            ImportStagingRow e = new ImportStagingRow();
            e.setUploadId(up.getId());
            writeStagingRow(e, r);
            entities.add(e);
        }
        stagingRowRepository.saveAll(entities);
        Map<Integer, Long> saved = Map.of();
        StagingCounts c = stagingCounts(rows, saved);
        applyStagingCounts(up, c);
        stagingUploadRepository.save(up);
        log.info("Import staging #{} ({}): {} row(s) / {} order(s) — {} valid, {} with errors",
                up.getId(), requestedBy, c.totalRows(), c.totalOrders(), c.validOrders(), c.invalidOrders());
        String msg = c.invalidOrders() == 0
                ? (c.totalOrders() == 1 ? "The order is ready to save." : "All " + c.totalOrders() + " orders are ready to save.")
                : c.validOrders() + " of " + countNoun(c.totalOrders(), "order") + " ready to save · "
                        + needFixes(c.invalidOrders()) + ".";
        return stagingSuccess(toStagingDTO(up, rows, saved), msg);
    }

    @Override
    public ApiResponse<StagingUploadDTO> getStaging(Long id, String requestedBy) {
        ImportStagingUpload up = findStaging(id, requestedBy);
        if (up == null) return stagingFailure(HttpStatus.NOT_FOUND, "Upload not found — it may have expired or been discarded.");
        List<ImportStagingRow> ents = stagingRowRepository.findByUploadIdOrderByRowNoAsc(up.getId());
        List<OrderImportRowDTO> rows = readStagingRows(ents);
        requireMatch(firstClientCode(rows));
        Map<Integer, Long> saved = savedRowMap(ents);
        // Re-check on open: reference data may have changed, or these orders may
        // have reached Import history through another upload in the meantime.
        revalidateStaged(rows, saved);
        persistChangedStagingRows(ents, rows);
        applyStagingCounts(up, stagingCounts(rows, saved));
        stagingUploadRepository.save(up);
        return stagingSuccess(toStagingDTO(up, rows, saved), null);
    }

    @Override
    public ApiResponse<StagingUploadDTO> updateStagingRow(Long id, int rowNumber, OrderImportRowDTO edited,
                                                          String requestedBy) {
        return editStagingRow(id, rowNumber, current -> edited, requestedBy);
    }

    /** Same edit with a JSON body applied on top of the staged row (see updateBatchRowJson). */
    @Override
    public ApiResponse<StagingUploadDTO> updateStagingRowJson(Long id, int rowNumber, String json, String requestedBy) {
        return editStagingRow(id, rowNumber, current -> mergeRowJson(current, json), requestedBy);
    }

    private ApiResponse<StagingUploadDTO> editStagingRow(Long id, int rowNumber,
            java.util.function.UnaryOperator<OrderImportRowDTO> applyEdit, String requestedBy) {
        ImportStagingUpload up = findStaging(id, requestedBy);
        if (up == null) return stagingFailure(HttpStatus.NOT_FOUND, "Upload not found — it may have expired or been discarded.");
        List<ImportStagingRow> ents = stagingRowRepository.findByUploadIdOrderByRowNoAsc(up.getId());
        List<OrderImportRowDTO> rows = readStagingRows(ents);
        requireMatch(firstClientCode(rows));
        Map<Integer, Long> saved = savedRowMap(ents);
        int index = -1;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).getRowNumber() == rowNumber) { index = i; break; }
        }
        if (index < 0) return stagingFailure(HttpStatus.NOT_FOUND, "Row " + rowNumber + " is not in this upload.");
        if (saved.containsKey(rowNumber)) {
            return stagingFailure(HttpStatus.CONFLICT, "Row " + rowNumber + " is already saved to Import history #"
                    + saved.get(rowNumber) + " — edit it there.");
        }
        OrderImportRowDTO current = rows.get(index);
        OrderImportRowDTO edited = applyEdit.apply(current);
        if (edited == null) edited = new OrderImportRowDTO();
        edited.setRowNumber(rowNumber);
        edited.setBatchId(current.getBatchId());
        edited.setGeneratedStatus(null);
        edited.setGeneratedMessage(null);
        edited.setGeneratedOrderNo(null);
        edited.setGeneratedTrackingNumber(null);
        rows.set(index, edited);
        propagateGroupEdit(rows, current, edited);
        revalidateStaged(rows, saved);
        persistChangedStagingRows(ents, rows);
        applyStagingCounts(up, stagingCounts(rows, saved));
        up.setUpdatedAt(java.time.LocalDateTime.now());
        stagingUploadRepository.save(up);
        return stagingSuccess(toStagingDTO(up, rows, saved), null);
    }

    @Override
    public ApiResponse<StagingUploadDTO> saveStaging(Long id, String requestedBy, boolean includeErrors,
                                                     boolean allowDuplicate) {
        ImportStagingUpload up = findStaging(id, requestedBy);
        if (up == null) return stagingFailure(HttpStatus.NOT_FOUND, "Upload not found — it may have expired or been discarded.");
        List<ImportStagingRow> ents = stagingRowRepository.findByUploadIdOrderByRowNoAsc(up.getId());
        List<OrderImportRowDTO> rows = readStagingRows(ents);
        requireMatch(firstClientCode(rows));
        Map<Integer, Long> saved = savedRowMap(ents);
        // Reference data can change while a file waits (an account deactivated, a
        // client removed) — validate once more before anything is saved.
        revalidateStaged(rows, saved);
        persistChangedStagingRows(ents, rows);

        // "Ignore errors and save": fully valid orders only. "Proceed with errors":
        // every unsaved order; the flagged ones are fixed later in Import history.
        List<OrderImportRowDTO> toSave = new ArrayList<>();
        int orders = 0;
        int withErrors = 0;
        for (List<OrderImportRowDTO> g : stagingGroups(rows).values()) {
            boolean alreadySaved = g.stream().allMatch(r -> saved.containsKey(r.getRowNumber()));
            boolean valid = g.stream().allMatch(OrderImportServiceImpl::stagedRowValid);
            if (!alreadySaved && (valid || includeErrors)) {
                toSave.addAll(g);
                orders++;
                if (!valid) withErrors++;
            }
        }
        StagingCounts before = stagingCounts(rows, saved);
        if (toSave.isEmpty()) {
            applyStagingCounts(up, before);
            stagingUploadRepository.save(up);
            return stagingFailure(HttpStatus.UNPROCESSABLE_ENTITY, before.totalOrders() > 0 && before.savedOrders() == before.totalOrders()
                    ? "Everything in this upload is already saved."
                    : "Nothing is ready to save — fix the orders that need fixes, or use Save all, including errors.");
        }
        // Orders already in Import history (typically the same file uploaded twice
        // with "Upload anyway") would be saved a second time — ask first.
        if (!allowDuplicate) {
            java.util.LinkedHashMap<String, String> dups = new java.util.LinkedHashMap<>();
            java.util.regex.Pattern importNo = java.util.regex.Pattern.compile("\\(#(\\d+)\\)");
            for (OrderImportRowDTO r : toSave) {
                if (r.getWarnings() == null) continue;
                for (String w : r.getWarnings()) {
                    if (w == null || !w.contains(IN_HISTORY_MARKER)) continue;
                    java.util.regex.Matcher m = importNo.matcher(w);
                    dups.putIfAbsent(StringUtils.hasText(r.getOrderRef()) ? r.getOrderRef().trim() : "row " + r.getRowNumber(),
                            m.find() ? "#" + m.group(1) : "?");
                }
            }
            if (!dups.isEmpty()) {
                StringBuilder list = new StringBuilder();
                int i = 0;
                for (Map.Entry<String, String> e : dups.entrySet()) {
                    if (i == 6) { list.append(", …"); break; }
                    if (i++ > 0) list.append(", ");
                    list.append(e.getKey()).append(" (").append(e.getValue()).append(")");
                }
                return stagingFailure(HttpStatus.CONFLICT, countNoun(dups.size(), "order") + " in this save "
                        + (dups.size() == 1 ? "is" : "are") + " already in Import history: " + list
                        + ". Saving again creates duplicates.");
            }
        }
        // Copies: save() re-validates and mutates the rows it is handed.
        List<OrderImportRowDTO> copies = new ArrayList<>(toSave.size());
        for (OrderImportRowDTO r : toSave) copies.add(stagingJson().convertValue(r, OrderImportRowDTO.class));
        // allowDuplicate: the duplicate-file gate already ran when the file was staged.
        // draft=includeErrors: a batch that carries errors is parked as a Draft
        // (its valid rows can still be labelled; the flagged rows wait for fixes).
        ApiResponse<OrderImportPreviewDTO> res = save(copies, requestedBy, up.getFileName(), includeErrors, true);
        if (res == null || !"success".equalsIgnoreCase(res.getStatus()) || res.getData() == null
                || res.getData().getBatchId() == null) {
            HttpStatus st = res == null ? HttpStatus.INTERNAL_SERVER_ERROR
                    : java.util.Optional.ofNullable(HttpStatus.resolve(res.getCode())).orElse(HttpStatus.UNPROCESSABLE_ENTITY);
            return stagingFailure(st, res == null ? "Save failed." : res.getMessage());
        }
        long batchId = res.getData().getBatchId().longValue();
        java.util.Set<Integer> savedNos = new java.util.HashSet<>();
        for (OrderImportRowDTO r : toSave) savedNos.add(r.getRowNumber());
        Map<Integer, Long> savedAfter = new java.util.HashMap<>(saved);
        List<ImportStagingRow> marked = new ArrayList<>();
        for (ImportStagingRow e : ents) {
            if (savedNos.contains(e.getRowNo())) {
                e.setSavedBatchId(batchId);
                marked.add(e);
                savedAfter.put(e.getRowNo(), batchId);
            }
        }
        stagingRowRepository.saveAll(marked);
        StagingCounts after = stagingCounts(rows, savedAfter);
        applyStagingCounts(up, after);
        up.setLastSavedBatchId(batchId);
        up.setUpdatedAt(java.time.LocalDateTime.now());
        stagingUploadRepository.save(up);
        int left = after.totalOrders() - after.savedOrders();
        log.info("Import staging #{} ({}): saved {} order(s) / {} row(s) to Import history #{}; {} order(s) left in staging",
                up.getId(), requestedBy, orders, toSave.size(), batchId, left);
        String msg = withErrors > 0
                ? "Saved " + countNoun(orders, "order") + " to Import history #" + batchId + ", including "
                        + withErrors + " that " + (withErrors == 1 ? "needs" : "need") + " fixes — fix "
                        + (withErrors == 1 ? "it" : "them") + " there before labelling."
                : "Saved " + countNoun(orders, "order") + " to Import history #" + batchId + "."
                        + (left > 0 ? " " + countNoun(left, "order") + " that " + (left == 1 ? "needs" : "need")
                                + " fixes " + (left == 1 ? "stays" : "stay") + " in this upload." : "");
        return stagingSuccess(toStagingDTO(up, rows, savedAfter), msg);
    }

    @Override
    public ApiResponse<StagingUploadDTO> discardStaging(Long id, String requestedBy) {
        ImportStagingUpload up = findStaging(id, requestedBy);
        if (up == null) return stagingFailure(HttpStatus.NOT_FOUND, "Upload not found — it may have expired or been discarded.");
        stagingRowRepository.deleteAllForUpload(up.getId());
        stagingUploadRepository.delete(up);
        log.info("Import staging #{} discarded by {}", id, requestedBy);
        return stagingSuccess(null, "Upload discarded. Orders already saved stay in Import history.");
    }

    @Override
    public StagingErrorFile stagingErrorFile(Long id, String format, String requestedBy) {
        ImportStagingUpload up = findStaging(id, requestedBy);
        if (up == null) return null;
        List<ImportStagingRow> ents = stagingRowRepository.findByUploadIdOrderByRowNoAsc(up.getId());
        List<OrderImportRowDTO> rows = readStagingRows(ents);
        requireMatch(firstClientCode(rows));
        Map<Integer, Long> saved = savedRowMap(ents);
        // Every row of each unsaved order that has an error: a re-upload of the
        // corrected file then brings back complete orders, not stray item lines.
        List<OrderImportRowDTO> out = new ArrayList<>();
        for (List<OrderImportRowDTO> g : stagingGroups(rows).values()) {
            if (g.stream().allMatch(r -> saved.containsKey(r.getRowNumber()))) continue;
            if (g.stream().allMatch(OrderImportServiceImpl::stagedRowValid)) continue;
            out.addAll(g);
        }
        if (out.isEmpty()) return null;
        java.util.LinkedHashSet<String> customKeys = new java.util.LinkedHashSet<>();
        for (OrderImportRowDTO r : out) if (r.getCustomFields() != null) customKeys.addAll(r.getCustomFields().keySet());
        List<String> header = new ArrayList<>(HEADERS);
        header.addAll(customKeys);
        header.add("errors");
        List<List<String>> table = new ArrayList<>(out.size());
        for (OrderImportRowDTO r : out) {
            List<String> cells = new ArrayList<>(header.size());
            for (String h : HEADERS) cells.add(nzs(stagingCell(r, h)));
            for (String k : customKeys) cells.add(nzs(r.getCustomFields() == null ? null : r.getCustomFields().get(k)));
            List<String> errs = r.getErrors() == null ? List.of() : r.getErrors();
            cells.add(errs.isEmpty()
                    ? "(no errors on this row — another row of order " + nzs(r.getOrderRef()) + " has errors)"
                    : String.join("; ", errs));
            table.add(cells);
        }
        String base = up.getFileName() == null ? "upload" : up.getFileName().replaceAll("\\.[^.]+$", "");
        if (base.isBlank()) base = "upload";
        base = base + "-errors";
        boolean xlsx = format != null ? "xlsx".equalsIgnoreCase(format.trim())
                : up.getFileName() != null && up.getFileName().toLowerCase(Locale.ROOT).endsWith(".xlsx");
        try {
            return xlsx
                    ? new StagingErrorFile(errorsXlsx(header, table), base + ".xlsx",
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                    : new StagingErrorFile(errorsCsv(header, table), base + ".csv", "text/csv; charset=UTF-8");
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Override
    public List<StagingUploadDTO> listStaging(String requestedBy) {
        if (stagingUploadRepository == null || !StringUtils.hasText(requestedBy)) return List.of();
        return stagingUploadRepository.findByCreatedByIgnoreCaseAndStatusOrderByIdDesc(requestedBy, "OPEN")
                .stream().map(this::stagingSummary).toList();
    }

    // ---- staging helpers ------------------------------------------------------

    /** "1 order" / "4 orders". */
    private static String countNoun(int k, String noun) {
        return k + " " + noun + (k == 1 ? "" : "s");
    }

    /** "1 needs fixes" / "4 need fixes". */
    private static String needFixes(int k) {
        return k + (k == 1 ? " needs fixes" : " need fixes");
    }

    /** Counts from the stored upload header (no rows) — for the list and the "already uploaded" answer. */
    private StagingUploadDTO stagingSummary(ImportStagingUpload up) {
        return StagingUploadDTO.builder()
                .id(up.getId())
                .fileName(up.getFileName())
                .status(up.getStatus())
                .totalRows(up.getTotalRows())
                .validRows(up.getValidRows())
                .invalidRows(up.getInvalidRows())
                .totalOrders(up.getTotalOrders())
                .validOrders(up.getValidOrders())
                .invalidOrders(Math.max(0, up.getTotalOrders() - up.getValidOrders()))
                .savedOrders(up.getSavedOrders())
                .readyOrders(Math.max(0, up.getValidOrders() - up.getSavedOrders()))
                .lastSavedBatchId(up.getLastSavedBatchId())
                .createdAt(up.getCreatedAt())
                .expiresAt(up.getCreatedAt() == null ? null : up.getCreatedAt().plusDays(Math.max(1, stagingRetentionDays)))
                .build();
    }

    private ImportStagingUpload findStaging(Long id, String requestedBy) {
        if (stagingUploadRepository == null || stagingRowRepository == null || id == null) return null;
        ImportStagingUpload up = stagingUploadRepository.findById(id).orElse(null);
        if (up == null) return null;
        if (!canAccessStaging(up, requestedBy)) {
            throw new org.springframework.security.access.AccessDeniedException("This upload belongs to another user.");
        }
        return up;
    }

    /** The uploader, or an admin. */
    private static boolean canAccessStaging(ImportStagingUpload up, String requestedBy) {
        if (requestedBy != null && requestedBy.equalsIgnoreCase(up.getCreatedBy())) return true;
        org.springframework.security.core.Authentication auth =
                org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    private static boolean stagedRowValid(OrderImportRowDTO r) {
        return r.getErrors() == null || r.getErrors().isEmpty();
    }

    /** orderRef groups in file order; a row without an orderRef is its own order. */
    private static Map<String, List<OrderImportRowDTO>> stagingGroups(List<OrderImportRowDTO> rows) {
        Map<String, List<OrderImportRowDTO>> groups = new LinkedHashMap<>();
        for (OrderImportRowDTO r : rows) {
            String key = StringUtils.hasText(r.getOrderRef())
                    ? r.getOrderRef().trim().toUpperCase(Locale.ROOT) : "#row" + r.getRowNumber();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
        }
        return groups;
    }

    private static StagingCounts stagingCounts(List<OrderImportRowDTO> rows, Map<Integer, Long> saved) {
        int validRows = 0;
        for (OrderImportRowDTO r : rows) if (stagedRowValid(r)) validRows++;
        int total = 0, valid = 0, invalid = 0, savedOrders = 0, ready = 0;
        for (List<OrderImportRowDTO> g : stagingGroups(rows).values()) {
            total++;
            boolean v = g.stream().allMatch(OrderImportServiceImpl::stagedRowValid);
            boolean s = g.stream().allMatch(r -> saved.containsKey(r.getRowNumber()));
            if (v) valid++;
            if (s) savedOrders++;
            if (!s && !v) invalid++;
            if (!s && v) ready++;
        }
        return new StagingCounts(rows.size(), validRows, total, valid, invalid, savedOrders, ready);
    }

    private static void applyStagingCounts(ImportStagingUpload up, StagingCounts c) {
        up.setTotalRows(c.totalRows());
        up.setValidRows(c.validRows());
        up.setInvalidRows(c.totalRows() - c.validRows());
        up.setTotalOrders(c.totalOrders());
        up.setValidOrders(c.validOrders());
        up.setSavedOrders(c.savedOrders());
        up.setStatus(c.totalOrders() > 0 && c.savedOrders() == c.totalOrders() ? "SAVED" : "OPEN");
    }

    private StagingUploadDTO toStagingDTO(ImportStagingUpload up, List<OrderImportRowDTO> rows, Map<Integer, Long> saved) {
        StagingCounts c = stagingCounts(rows, saved);
        return StagingUploadDTO.builder()
                .id(up.getId())
                .fileName(up.getFileName())
                .status(c.totalOrders() > 0 && c.savedOrders() == c.totalOrders() ? "SAVED" : "OPEN")
                .totalRows(c.totalRows())
                .validRows(c.validRows())
                .invalidRows(c.totalRows() - c.validRows())
                .totalOrders(c.totalOrders())
                .validOrders(c.validOrders())
                .invalidOrders(c.invalidOrders())
                .savedOrders(c.savedOrders())
                .readyOrders(c.readyOrders())
                .lastSavedBatchId(up.getLastSavedBatchId())
                .savedRowNumbers(saved.keySet().stream().sorted().toList())
                .createdAt(up.getCreatedAt())
                .expiresAt(up.getCreatedAt() == null ? null : up.getCreatedAt().plusDays(Math.max(1, stagingRetentionDays)))
                .rows(rows)
                .build();
    }

    private List<OrderImportRowDTO> readStagingRows(List<ImportStagingRow> ents) {
        List<OrderImportRowDTO> rows = new ArrayList<>(ents.size());
        for (ImportStagingRow e : ents) {
            try {
                rows.add(stagingJson().readValue(e.getRowJson(), OrderImportRowDTO.class));
            } catch (Exception ex) {
                OrderImportRowDTO broken = new OrderImportRowDTO();
                broken.setRowNumber(e.getRowNo());
                broken.setOrderRef(e.getOrderRef());
                broken.setErrors(List.of("This row could not be read back from staging — upload the file again."));
                rows.add(broken);
            }
        }
        return rows;
    }

    private void writeStagingRow(ImportStagingRow e, OrderImportRowDTO r) {
        e.setRowNo(r.getRowNumber());
        e.setOrderRef(r.getOrderRef() == null ? null : clip(r.getOrderRef().trim(), 120));
        e.setValid(stagedRowValid(r));
        try {
            e.setRowJson(stagingJson().writeValueAsString(r));
        } catch (Exception ex) {
            throw new IllegalStateException("Could not stage row " + r.getRowNumber(), ex);
        }
    }

    /** Writes back only rows whose content or validity changed (an edit touches a handful, not the file). */
    private void persistChangedStagingRows(List<ImportStagingRow> ents, List<OrderImportRowDTO> rows) {
        Map<Integer, ImportStagingRow> byNo = new java.util.HashMap<>();
        for (ImportStagingRow e : ents) byNo.put(e.getRowNo(), e);
        List<ImportStagingRow> changed = new ArrayList<>();
        for (OrderImportRowDTO r : rows) {
            ImportStagingRow e = byNo.get(r.getRowNumber());
            if (e == null) continue;
            String json = e.getRowJson();
            boolean valid = e.isValid();
            String ref = e.getOrderRef();
            writeStagingRow(e, r);
            if (!java.util.Objects.equals(json, e.getRowJson()) || valid != e.isValid()
                    || !java.util.Objects.equals(ref, e.getOrderRef())) {
                changed.add(e);
            }
        }
        if (!changed.isEmpty()) stagingRowRepository.saveAll(changed);
    }

    private static Map<Integer, Long> savedRowMap(List<ImportStagingRow> ents) {
        Map<Integer, Long> m = new java.util.HashMap<>();
        for (ImportStagingRow e : ents) if (e.getSavedBatchId() != null) m.put(e.getRowNo(), e.getSavedBatchId());
        return m;
    }

    /** The full server-side pipeline, the same sequence save() runs, plus the Import-history duplicate check. */
    private void revalidateStaged(List<OrderImportRowDTO> rows, Map<Integer, Long> saved) {
        for (OrderImportRowDTO row : rows) {
            row.setClientCode(clamp(row.getClientCode()));
            row.setErrors(new ArrayList<>(validateRow(row)));
            row.setWarnings(List.of());
        }
        resolveNamesToCodes(rows);
        validateReferences(rows);
        validateBusinessRules(rows);
        validateCustomFields(rows);
        validateInternationalItems(rows);
        flagAlreadyInImportHistory(rows, saved);
    }

    /** Text the Save gate recognises on a duplicate warning. */
    private static final String IN_HISTORY_MARKER = "is already in Import history";

    /**
     * Unsaved staged orders whose orderRef is already in a live (not trashed)
     * import — typically the same file uploaded twice with "Upload anyway".
     * Saving them again would create duplicate orders, so the rows get a warning
     * and Save asks before writing them.
     */
    private void flagAlreadyInImportHistory(List<OrderImportRowDTO> rows, Map<Integer, Long> saved) {
        if (importBatchRepository == null || rows == null || rows.isEmpty()) return;
        java.util.Set<String> refs = new java.util.HashSet<>();
        for (OrderImportRowDTO r : rows) {
            if (saved.containsKey(r.getRowNumber()) || !StringUtils.hasText(r.getOrderRef())) continue;
            refs.add(r.getOrderRef().trim().toUpperCase(Locale.ROOT));
        }
        if (refs.isEmpty()) return;
        Map<String, Long> where = new java.util.HashMap<>();
        try {
            for (Object[] o : importBatchRepository.findBatchesHoldingOrderRefs(refs)) {
                if (o != null && o.length > 1 && o[0] != null && o[1] != null) {
                    where.put(String.valueOf(o[0]), ((Number) o[1]).longValue());
                }
            }
        } catch (Exception ex) {
            log.warn("Import staging: duplicate check against Import history skipped: {}", ex.getMessage());
            return;
        }
        if (where.isEmpty()) return;
        for (OrderImportRowDTO r : rows) {
            if (saved.containsKey(r.getRowNumber()) || !StringUtils.hasText(r.getOrderRef())) continue;
            Long batch = where.get(r.getOrderRef().trim().toUpperCase(Locale.ROOT));
            if (batch == null) continue;
            List<String> w = new ArrayList<>(r.getWarnings() == null ? List.of() : r.getWarnings());
            w.add("orderRef " + r.getOrderRef().trim() + " " + IN_HISTORY_MARKER + " (#" + batch
                    + ") — saving it again creates a duplicate order");
            r.setWarnings(w);
        }
    }

    /** A staged row's value for one template column, as it would be typed in the file. */
    private static String stagingCell(OrderImportRowDTO r, String header) {
        boolean itemLine = Boolean.TRUE.equals(r.getWeightInherited());
        return switch (header) {
            case "orderRef" -> r.getOrderRef();
            case "clientCode" -> r.getClientCode();
            case "billTo" -> r.getBillTo();
            case "warehouseCode" -> r.getWarehouseCode();
            case "recipientName" -> r.getRecipientName();
            case "recipientCompany" -> r.getRecipientCompany();
            case "recipientPhone" -> r.getRecipientPhone();
            case "recipientEmail" -> r.getRecipientEmail();
            case "addressLine1" -> r.getAddressLine1();
            case "addressLine2" -> r.getAddressLine2();
            case "city" -> r.getCity();
            case "state" -> r.getState();
            case "postalCode" -> r.getPostalCode();
            case "countryCode" -> r.getCountryCode();
            case "carrierCode" -> r.getCarrierCode();
            case "accountNumber" -> r.getAccountNumber();
            case "serviceType" -> r.getServiceType();
            case "packageType" -> r.getPackageType();
            // An item line took its order's weight; leave it blank so a re-upload
            // doesn't turn the line into an extra parcel.
            case "weight" -> itemLine ? null : plainNumber(r.getWeight());
            case "weightUnit" -> itemLine ? null : r.getWeightUnit();
            case "length" -> plainNumber(r.getLength());
            case "width" -> plainNumber(r.getWidth());
            case "height" -> plainNumber(r.getHeight());
            case "dimUnit" -> r.getDimUnit();
            case "currency" -> r.getCurrency();
            case "incoterms" -> r.getIncoterms();
            case "reference" -> r.getReference();
            case "itemDescription" -> r.getItemDescription();
            case "itemSku" -> r.getItemSku();
            case "itemQuantity" -> r.getItemQuantity() == null ? null : String.valueOf(r.getItemQuantity());
            case "itemUnitValue" -> plainNumber(r.getItemUnitValue());
            case "hsCode" -> r.getHsCode();
            case "countryOfOrigin" -> r.getCountryOfOrigin();
            default -> null;
        };
    }

    private static String plainNumber(BigDecimal v) {
        return v == null ? null : v.stripTrailingZeros().toPlainString();
    }

    private static String nzs(String v) {
        return v == null ? "" : v;
    }

    private static String clip(String v, int max) {
        return v.length() <= max ? v : v.substring(0, max);
    }

    private static byte[] errorsCsv(List<String> header, List<List<String>> table) throws java.io.IOException {
        java.io.StringWriter sw = new java.io.StringWriter();
        sw.write('﻿');   // BOM: Excel opens UTF-8 correctly; the importer strips it on upload
        try (org.apache.commons.csv.CSVPrinter printer = new org.apache.commons.csv.CSVPrinter(sw, CSVFormat.DEFAULT)) {
            printer.printRecord(header);
            for (List<String> row : table) printer.printRecord(row);
        }
        return sw.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] errorsXlsx(List<String> header, List<List<String>> table) throws java.io.IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            org.apache.poi.ss.usermodel.Sheet sheet = wb.createSheet("Errors");
            org.apache.poi.ss.usermodel.Font bold = wb.createFont();
            bold.setBold(true);
            org.apache.poi.ss.usermodel.CellStyle headStyle = wb.createCellStyle();
            headStyle.setFont(bold);
            org.apache.poi.ss.usermodel.Font red = wb.createFont();
            red.setColor(org.apache.poi.ss.usermodel.IndexedColors.RED.getIndex());
            org.apache.poi.ss.usermodel.CellStyle errStyle = wb.createCellStyle();
            errStyle.setFont(red);
            org.apache.poi.ss.usermodel.Row head = sheet.createRow(0);
            for (int i = 0; i < header.size(); i++) {
                org.apache.poi.ss.usermodel.Cell c = head.createCell(i);
                c.setCellValue(header.get(i));
                c.setCellStyle(headStyle);
            }
            int last = header.size() - 1;
            for (int r = 0; r < table.size(); r++) {
                org.apache.poi.ss.usermodel.Row row = sheet.createRow(r + 1);
                List<String> cells = table.get(r);
                for (int i = 0; i < cells.size(); i++) {
                    org.apache.poi.ss.usermodel.Cell c = row.createCell(i);
                    c.setCellValue(cells.get(i));
                    if (i == last) c.setCellStyle(errStyle);
                }
            }
            for (int i = 0; i < last; i++) sheet.setColumnWidth(i, 16 * 256);
            sheet.setColumnWidth(last, 90 * 256);
            sheet.createFreezePane(0, 1);
            wb.write(bos);
            return bos.toByteArray();
        }
    }

    private static ApiResponse<StagingUploadDTO> stagingSuccess(StagingUploadDTO data, String message) {
        return ApiResponse.<StagingUploadDTO>builder()
                .status("success").code(200).message(message).data(data).build();
    }

    private static ApiResponse<StagingUploadDTO> stagingFailure(HttpStatus status, String message) {
        return ApiResponse.<StagingUploadDTO>builder()
                .status("error").code(status.value())
                // Missing / conflicting uploads use the same code as Import history's state errors.
                .errorCode((status == HttpStatus.NOT_FOUND || status == HttpStatus.CONFLICT
                        ? ErrorCode.IMPORT_BATCH_STATE : ErrorCode.VALIDATION_ERROR).name())
                .message(message).data(null).build();
    }

    private static ApiResponse<OrderImportPreviewDTO> success(OrderImportPreviewDTO data, String message) {
        return ApiResponse.<OrderImportPreviewDTO>builder()
                .status("success").code(200).message(message).data(data).build();
    }

    private static ApiResponse<OrderImportPreviewDTO> failure(HttpStatus status, String message) {
        return ApiResponse.<OrderImportPreviewDTO>builder()
                .status("error").code(status.value())
                .errorCode(ErrorCode.VALIDATION_ERROR.name())
                .message(message).data(null).build();
    }

    /** Used only in tests to keep IntelliJ happy about unused imports. */
    @SuppressWarnings("unused")
    private static ByteArrayOutputStream noop() {
        return new ByteArrayOutputStream();
    }
}
