package com.multiship.backend.service;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.OrderImportPreviewDTO;
import com.multiship.backend.dto.OrderImportRowDTO;
import com.multiship.backend.model.CarrierAccountRef;
import com.multiship.backend.model.Client;
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
     * Sprint 50 PR K — commit() is called synchronously from the
     * OrderImportController HTTP handler, so the caller thread IS a
     * Tomcat worker. Bound the total time the HTTP thread can be blocked
     * on permit acquire to 30s; on overflow the batch aborts with a
     * TenantSaturatedException that the controller surfaces as 429.
     */
    private static final long IMPORT_MAX_BATCH_WAIT_MS = 30_000L;
    private com.multiship.backend.service.fairness.FairTenantExecutor fairExecutor;

    @PostConstruct
    void initExecutors() {
        ensureExecutors();
        log.info("OrderImportServiceImpl fan-out ready: commitConcurrency={} maxPerTenant={}",
                importCommitConcurrency, importMaxPerTenant);
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
            "weight", "weightUnit", "currency",
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
        Map<String, java.util.Set<String>> accountsByCarrier = new LinkedHashMap<>();
        if (accountRefRepository != null && !rowClientCodes.isEmpty()) {
            for (CarrierAccountRef ref : accountRefRepository.findActiveByCustomerNoInOrPlatform(rowClientCodes)) {
                if (ref.getCarrierCode() == null || ref.getAccountNumber() == null) continue;
                accountsByCarrier
                        .computeIfAbsent(ref.getCarrierCode().toUpperCase(Locale.ROOT), k -> new java.util.HashSet<>())
                        .add(ref.getAccountNumber().trim().toUpperCase(Locale.ROOT));
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
                java.util.Set<String> known = accountsByCarrier.getOrDefault(carrier, java.util.Set.of());
                if (!known.contains(account)) {
                    // A shipper's own account number, if provided, MUST be one
                    // registered in the platform (this client's accounts +
                    // platform accounts). Not found → hard error. THIRD_PARTY
                    // (external bill-to) accounts are exempt — they live on the
                    // recipient's carrier account, not ours, and are only
                    // format-checked in validateRow.
                    errors.add("accountNumber " + account + " is not a registered "
                            + carrier + " account in the platform");
                }
            }

            String service = normalizeOrNull(row.getServiceType());
            if (service != null && carrier != null && KNOWN_CARRIERS.contains(carrier)) {
                java.util.Set<String> known = servicesByCarrier.getOrDefault(carrier, java.util.Set.of());
                if (!known.isEmpty() && !known.contains(service)) {
                    warnings.add("serviceType '" + service + "' is not in the " + carrier
                            + " service catalog; the carrier may reject it");
                }
            }

            String pkg = normalizeOrNull(row.getPackageType());
            if (pkg != null && !knownPackages.isEmpty() && !knownPackages.contains(pkg)) {
                errors.add("packageType '" + pkg + "' is not a registered package preset");
            }

            row.setErrors(errors);
            row.setWarnings(warnings);
        }
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

    /** Group key used for order grouping — mirrors commit()/international rules. */
    private static String groupKeyOf(OrderImportRowDTO row) {
        return StringUtils.hasText(row.getOrderRef())
                ? row.getOrderRef().trim()
                : "__row_" + row.getRowNumber();
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
            // Mint one batch id for this file upload right away and stamp
            // every row with it, so the whole sheet is grouped together
            // from the moment it's uploaded — commit() re-uses this same
            // id (rows round-trip it back) instead of minting a new one.
            if (orderRepository != null && !rows.isEmpty()) {
                Integer batchId = orderRepository.findMaxBatchId() + 1;
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

    /**
     * @param usePlatformAccount when true, every group bills to the platform
     *   (house) account for its carrier, overriding any row/client account.
     *   Used by the Data History "Use platform account" generate option.
     */
    public ApiResponse<OrderImportPreviewDTO> commit(List<OrderImportRowDTO> rows, String requestedBy,
                                                     boolean usePlatformAccount) {
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
                .orElse(orderRepository == null ? null : orderRepository.findMaxBatchId() + 1);
        // Frontend may edit rows post-preview; re-run the name→code
        // reverse-lookup here so a value the operator pasted in
        // ("UPS Ground") still resolves to the wire code before the
        // carrier connector sees it.
        resolveNamesToCodes(rows);
        // Final server-side gate for the dynamic reference checks — start
        // from a clean slate (rows round-trip stale preview errors) so the
        // merge below sees only current failures.
        for (OrderImportRowDTO row : rows) row.setErrors(List.of());
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

        List<Callable<GroupOutcome>> tasks = new ArrayList<>(groupCount);
        for (Map.Entry<String, List<OrderImportRowDTO>> entry : groups.entrySet()) {
            List<OrderImportRowDTO> group = entry.getValue();
            tasks.add(() -> processGroup(group, batchId, usePlatformAccount));
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
            for (Future<GroupOutcome> f : futures) {
                GroupOutcome outcome = f.get();
                valid += outcome.valid;
                invalid += outcome.invalid;
                generated += outcome.generated;
            }
        } catch (com.multiship.backend.service.fairness.FairTenantExecutor.TenantSaturatedException sat) {
            // Sprint 50 PR K — tenant already has IMPORT_MAX_PER_TENANT batches
            // in flight; refuse rather than pin an HTTP thread. Any already-
            // submitted tasks complete on their own; caller retries the rest.
            log.warn("Order import commit for tenant {} aborted: {} of {} groups submitted",
                    tenantKey, sat.getSubmittedTasks(), sat.getTotalTasks());
            return ApiResponse.<OrderImportPreviewDTO>builder()
                    .status("error").code(HttpStatus.TOO_MANY_REQUESTS.value())
                    .errorCode(ErrorCode.TENANT_RATE_LIMITED.name())
                    .message("Too many concurrent import batches for this client. "
                            + sat.getSubmittedTasks() + " of " + sat.getTotalTasks()
                            + " groups accepted; retry the remainder in ~30s.")
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
        int saved = total - invalid;

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

        // Sprint 51 — reject re-uploading the same file. Hash the row DATA (not
        // transient validation state), so an identical file is blocked even if
        // renamed, while a file the operator has actually edited hashes
        // differently and imports fine.
        String contentHash = contentHash(safe);
        if (contentHash != null && importBatchRepository != null) {
            com.multiship.backend.model.ImportBatch dup = importBatchRepository
                    .findFirstByContentHashAndDeletedAtIsNullOrderByIdDesc(contentHash).orElse(null);
            if (dup != null) {
                String dupName = StringUtils.hasText(dup.getFileName()) ? dup.getFileName() : "Untitled";
                return failure(HttpStatus.CONFLICT,
                        "This file was already imported as #" + dup.getId()
                        + " (" + dupName + ")"
                        + (dup.getCreatedAt() != null ? " on " + dup.getCreatedAt().toLocalDate() : "")
                        + ". Edit a value or upload a different file to import a changed version.");
            }
        }

        Long batchId = null;
        if (importBatchRepository != null) {
            com.multiship.backend.model.ImportBatch batch = new com.multiship.backend.model.ImportBatch();
            batch.setCreatedBy(requestedBy);
            batch.setFileName(StringUtils.hasText(fileName) ? fileName.trim() : "Untitled import");
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
        return summariesFor(importBatchRepository.findAllByDeletedAtIsNullOrderByIdDesc());
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
                        .build())
                .toList();
    }

    @Override
    public com.multiship.backend.dto.ImportBatchDTO setBillingMode(Long id, String mode, String requestedBy) {
        if (importBatchRepository == null || id == null) return null;
        com.multiship.backend.model.ImportBatch b = importBatchRepository.findById(id).orElse(null);
        if (b == null) return null;
        requireMatch(firstClientCode(parseBatchRows(b)));
        String m = "PLATFORM".equalsIgnoreCase(mode == null ? "" : mode.trim()) ? "PLATFORM" : "AUTO";
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
        if (importBatchRepository == null || id == null) return null;
        com.multiship.backend.model.ImportBatch b = importBatchRepository.findById(id).orElse(null);
        if (b == null) return null;
        requireMatch(firstClientCode(parseBatchRows(b)));
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

        // Sprint 55 audit #302 F3.2 — retry-safe filter: skip rows already
        // GENERATED so we don't re-bill the carrier for successful ones.
        // Filtered rows keep their existing generatedStatus/tracking on
        // save; only the not-yet-generated subset is re-processed.
        List<OrderImportRowDTO> rowsToProcess = onlyFailed
                ? rows.stream()
                        .filter(r -> !"GENERATED".equalsIgnoreCase(r.getGeneratedStatus()))
                        .toList()
                : rows;
        // Honor the persisted billing mode too, so the platform-account choice
        // survives reloads/retries even when the caller omits the flag.
        boolean platform = usePlatformAccount || "PLATFORM".equalsIgnoreCase(
                batch.getBillingMode() == null ? "" : batch.getBillingMode());

        // Mark IN_PROGRESS before the (potentially slow) carrier calls.
        batch.setStatus("IN_PROGRESS");
        importBatchRepository.save(batch);

        // Reuse the commit path — it generates labels and stamps each row's
        // generatedStatus (GENERATED / FAILED) in place. platform forces the
        // house account for every row; onlyFailed limits to the not-yet-done subset.
        if (!rowsToProcess.isEmpty()) {
            commit(rowsToProcess, requestedBy, platform);
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
        batch.setStatus(deriveGenerationStatus(total, generated, failed, invalid));
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

        log.info("Import batch {} label generation ({}): {}/{} labels → {} (labelBatch {})",
                id, requestedBy, generated, total, batch.getStatus(), batch.getLabelBatchId());
        return toBatchDTO(batch, rows);
    }

    /**
     * Generate a label for ONE row of a saved batch, so the operator can ship
     * rows individually straight from Data History. Updates that row's outcome
     * and re-derives the batch status from all rows.
     */
    @Override
    public com.multiship.backend.dto.ImportBatchDTO generateLabelForRow(Long id, int rowNumber, String requestedBy) {
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
        OrderImportRowDTO target = rows.stream()
                .filter(r -> r.getRowNumber() == rowNumber)
                .findFirst()
                .orElse(null);
        if (target == null) return toBatchDTO(batch, rows);

        // Generate just this one row (commit mutates it in place).
        commit(new ArrayList<>(List.of(target)), requestedBy);

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

        int index = -1;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).getRowNumber() == rowNumber) { index = i; break; }
        }
        if (index < 0) return toBatchDTO(batch, rows);

        OrderImportRowDTO current = rows.get(index);
        // A generated row is already a live shipment — never mutate it here.
        if ("GENERATED".equalsIgnoreCase(current.getGeneratedStatus())) {
            return toBatchDTO(batch, rows);
        }

        // Apply the edit. Force the row number so the client can't renumber
        // a row by editing, and drop any stale generation outcome so a
        // previously-FAILED row re-enters the SAVED / NEEDS_FIX lifecycle.
        if (edited == null) edited = new OrderImportRowDTO();
        edited.setRowNumber(rowNumber);
        edited.setGeneratedStatus(null);
        edited.setBatchId(null);
        rows.set(index, edited);

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
        batch.setSavedRows(total - invalid);
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
        req.setCurrency(leader.getCurrency());
        req.setReference(leader.getReference());
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
        sb.append("ORD-2001,")                              // orderRef
                .append("MA1885,SENDER,WH-EAST,")           // clientCode, billTo, warehouseCode
                .append("Ava Chen,,4402071234567,ava.chen@example.co.uk,")  // recipient
                .append("221B Baker Street,,London,LDN,NW1 6XE,GB,")         // address
                .append("FEDEX,F98765,INTERNATIONAL_PRIORITY,YOUR_PACKAGING,")// carrier + service
                .append("3.2,LB,USD,")                                        // weight + currency
                .append("ORD-2001,")                                          // reference
                .append("Silk lining natural,SKU-100,2,45.00,5007.20,IT\n"); // per-item customs
        return sb.toString().getBytes(StandardCharsets.UTF_8);
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
            int rowNo = 0;
            for (CSVRecord rec : parser) {
                rowNo++;
                if (isBlank(rec)) continue;
                ColumnReader csvReader = name -> get(rec, headerMap, name);
                OrderImportRowDTO built = buildRow(rowNo, csvReader);
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
                captureExtraColumns(built, capturedHeader, xlsxReader);
                out.add(built);
            }
        }
        return out;
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
            if (header == null || header.isBlank() || KNOWN_HEADERS_LOWER.contains(header)) continue;
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
        out.setCurrency(upper(s.read("currency")));
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

    static List<String> validateRow(OrderImportRowDTO row) {
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
        if (StringUtils.hasText(phone)) {
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
    private static String contentHash(List<OrderImportRowDTO> rows) {
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
    private GroupOutcome processGroup(List<OrderImportRowDTO> group, Integer batchId, boolean usePlatformAccount) {
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

            ApiResponse<com.multiship.backend.dto.LabelGenerationResponse> resp =
                    carrierService.generateManualLabel(req, null);
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
                return new GroupOutcome(valid, 0, 1);
            } else {
                String msg = resp == null ? "no response" : resp.getMessage();
                for (OrderImportRowDTO gr : group) {
                    gr.setGeneratedStatus("FAILED");
                    gr.setGeneratedMessage(msg);
                }
                return new GroupOutcome(valid, 0, 0);
            }
        } catch (Exception ex) {
            log.warn("Order import group (leader row {}) failed at label generation: {}",
                    leader.getRowNumber(), ex.getMessage());
            String msg = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            for (OrderImportRowDTO gr : group) {
                gr.setGeneratedStatus("FAILED");
                gr.setGeneratedMessage(msg);
            }
            return new GroupOutcome(valid, 0, 0);
        }
    }

    /** Sprint 50 Tier 1 finding #8 — return shape for a per-group commit worker. */
    private record GroupOutcome(int valid, int invalid, int generated) {}

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
