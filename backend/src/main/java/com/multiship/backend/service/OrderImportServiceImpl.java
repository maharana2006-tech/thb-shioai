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
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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
     * Sprint 50 Tier 1 finding #8 — fan-out executor for the commit loop.
     * Pre-fix the loop processed groups serially on the request thread; a
     * 500-row XLSX took 40-120 min because each carrier call is 5-15s.
     * Mirrors {@link BulkLabelServiceImpl#fanOutExecutor} — bounded pool
     * of daemon threads shared across every commit call. Concurrency cap
     * matches BulkLabelServiceImpl's WORKER_CONCURRENCY convention.
     */
    private static final int IMPORT_COMMIT_CONCURRENCY = 4;
    private static final int IMPORT_MAX_PER_TENANT = 2;
    private final ExecutorService fanOutExecutor = Executors.newFixedThreadPool(
            IMPORT_COMMIT_CONCURRENCY, r -> {
                Thread t = new Thread(r, "order-import-commit");
                t.setDaemon(true);
                return t;
            });

    /**
     * Sprint 50 Tier 1 finding #15 — fair-share wrapper so one tenant's
     * 500-row import can't monopolise the {@value #IMPORT_COMMIT_CONCURRENCY}-slot
     * pool while another tenant's 10-row import waits.
     *
     * <p>Sprint 50 PR K — commit() is called synchronously from the
     * OrderImportController HTTP handler, so the caller thread IS a
     * Tomcat worker. Bound the total time the HTTP thread can be blocked
     * on permit acquire to 30s; on overflow the batch aborts with a
     * TenantSaturatedException that the controller surfaces as 429.
     */
    private static final long IMPORT_MAX_BATCH_WAIT_MS = 30_000L;
    private final com.multiship.backend.service.fairness.FairTenantExecutor fairExecutor =
            new com.multiship.backend.service.fairness.FairTenantExecutor(
                    fanOutExecutor, IMPORT_MAX_PER_TENANT, 60, IMPORT_MAX_BATCH_WAIT_MS);

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

        // ---- one snapshot per call: batched lookups, no per-row queries ----
        java.util.Set<String> activeClients = new java.util.HashSet<>();
        java.util.Set<String> inactiveClients = new java.util.HashSet<>();
        for (Client c : clientRepository.findAll()) {
            String code = c.getClientCode() == null ? null : c.getClientCode().toUpperCase(Locale.ROOT);
            if (code == null) continue;
            if (c.isActive()) activeClients.add(code); else inactiveClients.add(code);
        }
        Map<Long, String> warehouseCodeById = new LinkedHashMap<>();
        if (warehouseRepository != null) {
            for (Warehouse w : warehouseRepository.findAll()) {
                if (w.getCode() != null) warehouseCodeById.put(w.getId(), w.getCode().toUpperCase(Locale.ROOT));
            }
        }
        // clientCode → set of attached warehouse codes
        Map<String, java.util.Set<String>> attachedByClient = new LinkedHashMap<>();
        if (clientWarehouseRepository != null) {
            for (OrderImportRowDTO row : rows) {
                String cc = row.getClientCode();
                if (!StringUtils.hasText(cc)) continue;
                String key = cc.toUpperCase(Locale.ROOT);
                if (attachedByClient.containsKey(key)) continue;
                java.util.Set<String> codes = new java.util.HashSet<>();
                for (ClientWarehouse cw : clientWarehouseRepository
                        .findByClientCodeIgnoreCaseOrderByIsDefaultDescCreatedAtAsc(key)) {
                    String code = warehouseCodeById.get(cw.getWarehouseId());
                    if (code != null) codes.add(code);
                }
                attachedByClient.put(key, codes);
            }
        }
        // carrier → set of known account numbers (uppercased)
        Map<String, java.util.Set<String>> accountsByCarrier = new LinkedHashMap<>();
        if (accountRefRepository != null) {
            for (CarrierAccountRef ref : accountRefRepository.findAll()) {
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
                    warnings.add("accountNumber " + account + " is not a registered "
                            + carrier + " account; the default account cascade may override it");
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
                warnings.add("packageType '" + pkg + "' is not a registered package preset");
            }

            row.setErrors(errors);
            row.setWarnings(warnings);
        }
    }

    private static String normalizeOrNull(String v) {
        return StringUtils.hasText(v) ? v.trim().toUpperCase(Locale.ROOT) : null;
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
        log.warn("Order import commit ({}): fanning {} groups across {} worker(s).",
                requestedBy, groupCount, IMPORT_COMMIT_CONCURRENCY);

        List<Callable<GroupOutcome>> tasks = new ArrayList<>(groupCount);
        for (Map.Entry<String, List<OrderImportRowDTO>> entry : groups.entrySet()) {
            List<OrderImportRowDTO> group = entry.getValue();
            tasks.add(() -> processGroup(group, batchId));
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
    public ApiResponse<OrderImportPreviewDTO> save(List<OrderImportRowDTO> rows, String requestedBy, String fileName) {
        List<OrderImportRowDTO> safe = rows == null ? java.util.List.of() : rows;
        // Sprint 50 Tier 0.5 PR G — clamp before we persist rowsJson so a
        // scoped USER can't seed the import_batch table with foreign
        // clientCodes that later history() calls would surface.
        for (OrderImportRowDTO row : safe) {
            row.setClientCode(clamp(row.getClientCode()));
        }
        int total = safe.size();
        int invalid = (int) safe.stream()
                .filter(r -> r.getErrors() != null && !r.getErrors().isEmpty())
                .count();
        int saved = total - invalid;
        // Freshly saved data has no labels yet → INITIATE. Status advances to
        // IN_PROGRESS / COMPLETE / PARTIAL_COMPLETE when the operator later
        // runs "Generate labels" on this batch from Data History.
        String status = "INITIATE";
        // Mark the valid rows SAVED so the summary UI can badge them.
        for (OrderImportRowDTO r : safe) {
            if (r.getErrors() == null || r.getErrors().isEmpty()) {
                r.setGeneratedStatus("SAVED");
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
            try {
                batch.setRowsJson(importObjectMapper != null
                        ? importObjectMapper.writeValueAsString(safe) : "[]");
            } catch (Exception e) {
                batch.setRowsJson("[]");
            }
            batch = importBatchRepository.save(batch);
            batchId = batch.getId();
        }

        log.info("Order import save ({}): {} saved, {} invalid → batch {}",
                requestedBy, saved, invalid, batchId);
        return success(OrderImportPreviewDTO.builder()
                        .totalRows(total)
                        .validRows(saved)
                        .invalidRows(invalid)
                        .batchId(batchId == null ? null : batchId.intValue())
                        .rows(safe)
                        .build(),
                saved + " order(s) saved to history"
                        + (invalid > 0 ? " · " + invalid + " row(s) skipped" : ""));
    }

    @Override
    public java.util.List<com.multiship.backend.dto.ImportBatchDTO> history() {
        if (importBatchRepository == null) return java.util.List.of();
        // Sprint 50 Tier 0.5 PR G — filter to the caller's tenant when
        // scoped. ImportBatch has no direct tenant column so we derive
        // membership from the first non-blank clientCode in the payload
        // (all rows in one save() call share the same clamped code).
        // Operators (resolveScope empty) see everything.
        java.util.Optional<String> scope = tenantScope == null
                ? java.util.Optional.empty()
                : tenantScope.resolveScope();
        return importBatchRepository.findAllByOrderByIdDesc().stream()
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
                        .build())
                .toList();
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

        // Mark IN_PROGRESS before the (potentially slow) carrier calls.
        batch.setStatus("IN_PROGRESS");
        importBatchRepository.save(batch);

        // Reuse the commit path — it generates labels and stamps each row's
        // generatedStatus (GENERATED / FAILED) in place.
        if (!rows.isEmpty()) {
            commit(rows, requestedBy);
        }

        int total = rows.size();
        int generated = (int) rows.stream()
                .filter(r -> "GENERATED".equalsIgnoreCase(r.getGeneratedStatus()))
                .count();
        int failed = (int) rows.stream()
                .filter(r -> "FAILED".equalsIgnoreCase(r.getGeneratedStatus()))
                .count();

        // savedRows/invalidRows keep their data-validity meaning from save();
        // label progress is conveyed by the status + the per-row Label column.
        batch.setStatus(deriveGenerationStatus(total, generated, failed));
        // commit() stamps every generated row with the shared label batchId;
        // lift it onto the import so the file row shows which All-Orders batch
        // its labels belong to. Keep any prior id if this run generated none.
        Integer labelBatchId = firstBatchId(rows);
        if (labelBatchId != null) batch.setLabelBatchId(labelBatchId);
        try {
            if (importObjectMapper != null) batch.setRowsJson(importObjectMapper.writeValueAsString(rows));
        } catch (Exception ignore) { /* keep prior rowsJson */ }
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
        batch.setStatus(deriveGenerationStatus(total, generated, failed));
        Integer labelBatchId = firstBatchId(rows);
        if (labelBatchId != null) batch.setLabelBatchId(labelBatchId);
        try {
            if (importObjectMapper != null) batch.setRowsJson(importObjectMapper.writeValueAsString(rows));
        } catch (Exception ignore) { /* keep prior rowsJson */ }
        batch = importBatchRepository.save(batch);

        log.info("Import batch {} row {} label ({}): {} → batch {} (labelBatch {})",
                id, rowNumber, requestedBy, target.getGeneratedStatus(), batch.getStatus(), batch.getLabelBatchId());
        return toBatchDTO(batch, rows);
    }

    /**
     * Batch status from label-generation outcomes:
     *   INITIATE         — no row attempted yet (fresh save, nothing generated/failed)
     *   COMPLETE         — every row got a label
     *   FAILED           — every row was attempted and all failed
     *   PARTIAL_COMPLETE — some labels made, or some rows still pending
     */
    private String deriveGenerationStatus(int total, int generated, int failed) {
        if (total == 0) return "INITIATE";
        if (generated == total) return "COMPLETE";
        if (failed == total) return "FAILED";
        if (generated == 0 && failed == 0) return "INITIATE";
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
        List<Client> clients = clientRepository != null
                ? clientRepository.findAll()
                : List.of();
        List<CarrierAccountRef> accounts = accountRefRepository != null
                ? accountRefRepository.findAll()
                : List.of();
        List<com.multiship.backend.model.ShippingService> services = shippingServiceRepository != null
                ? shippingServiceRepository.findAllByOrderByCarrierAscSortOrderAsc()
                : List.of();
        List<com.multiship.backend.model.PackagePreset> presets = packagePresetRepository != null
                ? packagePresetRepository.findAllByOrderByIsDefaultDescNameAsc()
                : List.of();
        // Precompute clientCode → List<warehouseCode>. ClientWarehouse only
        // carries warehouseId; resolve to Warehouse.code once via a single
        // findAll on WarehouseRepository so the builder doesn't need a repo
        // dependency (avoids test-constructor fanout).
        java.util.Map<Long, String> warehouseCodeById = new java.util.HashMap<>();
        if (warehouseRepository != null) {
            for (Warehouse w : warehouseRepository.findAll()) {
                if (w.getId() != null && w.getCode() != null
                        && Boolean.TRUE.equals(w.getActive())) {
                    warehouseCodeById.put(w.getId(), w.getCode());
                }
            }
        }
        java.util.Map<String, List<String>> clientWarehouseCodes = new java.util.HashMap<>();
        if (clientWarehouseRepository != null) {
            for (Client c : clients) {
                String code = c.getClientCode();
                if (code == null || code.isBlank()) continue;
                List<ClientWarehouse> attached = clientWarehouseRepository
                        .findByClientCodeIgnoreCaseOrderByIsDefaultDescCreatedAtAsc(code);
                List<String> codes = new java.util.ArrayList<>();
                for (ClientWarehouse cw : attached) {
                    String whCode = warehouseCodeById.get(cw.getWarehouseId());
                    if (whCode != null) codes.add(whCode);
                }
                clientWarehouseCodes.put(code.toUpperCase(Locale.ROOT), codes);
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
                out.add(buildRow(rowNo, name -> get(rec, headerMap, name)));
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
                out.add(buildRow(rowNo, name -> readCell(sheet, finalI, capturedHeader, name, fmt)));
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
        catch (NumberFormatException ex) { return null; }
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
        if (!StringUtils.hasText(row.getRecipientName())) errors.add("recipientName is required");
        if (!StringUtils.hasText(row.getAddressLine1())) errors.add("addressLine1 is required");
        if (!StringUtils.hasText(row.getCity())) errors.add("city is required");
        if (!StringUtils.hasText(row.getPostalCode())) errors.add("postalCode is required");
        if (!StringUtils.hasText(row.getCountryCode())) errors.add("countryCode is required");
        if (row.getWeight() == null || row.getWeight().signum() <= 0) {
            errors.add("weight must be > 0");
        } else if (row.getWeight().compareTo(MAX_WEIGHT) > 0) {
            errors.add("weight must be " + MAX_WEIGHT + " or less");
        }

        // --- shape checks: only fire when the value is present ---
        String country = row.getCountryCode();
        if (StringUtils.hasText(country) && !ISO2_RE.matcher(country.trim()).matches()) {
            errors.add("countryCode '" + country + "' must be a 2-letter ISO code");
        }
        String zip = row.getPostalCode();
        if (StringUtils.hasText(zip) && StringUtils.hasText(country)
                && ISO2_RE.matcher(country.trim()).matches()) {
            java.util.regex.Pattern p = ZIP_PATTERNS.get(country.trim().toUpperCase(Locale.ROOT));
            if (p != null && !p.matcher(zip.trim()).matches()) {
                errors.add("postalCode '" + zip + "' doesn't match the "
                        + country.trim().toUpperCase(Locale.ROOT) + " format");
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
        if (StringUtils.hasText(origin) && !ISO2_RE.matcher(origin.trim()).matches()) {
            errors.add("countryOfOrigin '" + origin + "' must be a 2-letter ISO code");
        }
        if (row.getItemQuantity() != null && row.getItemQuantity() < 1) {
            errors.add("itemQuantity must be 1 or more");
        }
        if (row.getItemUnitValue() != null && row.getItemUnitValue().signum() <= 0) {
            errors.add("itemUnitValue must be > 0");
        }
        return errors;
    }

    private static String upper(String s) {
        return s == null ? null : s.trim().toUpperCase(Locale.ROOT);
    }

    private static BigDecimal parseDecimal(String s) {
        if (!StringUtils.hasText(s)) return null;
        try { return new BigDecimal(s.trim().replace(",", "")); }
        catch (NumberFormatException ex) { return null; }
    }

    /**
     * Sprint 48 revision — international-shipment rule: when a group
     * ships to a country other than the shipper's origin, the group MUST
     * have at least one row with complete customs details
     * (itemDescription + hsCode + countryOfOrigin + itemQuantity +
     * itemUnitValue). Without those, every int'l carrier connector
     * fails at the customs block.
     *
     * <p>We heuristic "domestic" as {@code countryCode == "US"} for now.
     * When ship-from resolution lands the shipper origin will drive this
     * per row; today the "was your destination equal to the shipper's
     * home country" check is a US-centric approximation but the vast
     * majority of tenants ship US-domestic.
     */
    private static void validateInternationalItems(List<OrderImportRowDTO> rows) {
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
            if ("US".equalsIgnoreCase(country.trim())) continue; // domestic heuristic
            // International — need at least one row with full customs
            // commodity data.
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
                List<String> errs = new ArrayList<>(
                        leader.getErrors() == null ? List.of() : leader.getErrors());
                errs.add("International shipment (countryCode=" + country
                        + ") requires at least one row with itemDescription + hsCode"
                        + " + countryOfOrigin + itemQuantity + itemUnitValue");
                leader.setErrors(errs);
            }
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
    private GroupOutcome processGroup(List<OrderImportRowDTO> group, Integer batchId) {
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
