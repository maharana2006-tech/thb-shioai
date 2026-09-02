package com.multiship.backend.controller;

import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.OrderAccountResolutionDTO;
import com.multiship.backend.dto.OrderListFilters;
import com.multiship.backend.dto.OrderWithCarrierDTO;
import com.multiship.backend.dto.OrderListResponseDTO;
import com.multiship.backend.dto.OrderResponseDTO;
import com.multiship.backend.dto.OrderWithLinesDTO;
import com.multiship.backend.dto.PageResponseDTO;
import com.multiship.backend.dto.PaginationRequestDTO;
import com.multiship.backend.service.OrderService;
import com.multiship.backend.service.CarrierService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.List;

@Tag(name = "Orders", description = "The unified order list, single-order reads, and label documents")
@RestController
@RequestMapping("/api/v1/orders")
// Sprint 49 Tier 1: @CrossOrigin("*") removed — SecurityConfig applies restrictive CORS globally.
public class OrderController {

    @Autowired
    private OrderService orderService;

    @Autowired
    private CarrierService carrierService;

    @Autowired
    private com.multiship.backend.service.ZplLabelService zplLabelService;

    @Autowired
    private com.multiship.backend.service.PdfLabelService pdfLabelService;

    // Sprint 52 PR B — passthrough for carrier's real label bytes when
    // the stored artifact matches the requested format. See
    // LabelArtifactResolver javadoc.
    @Autowired
    private com.multiship.backend.service.LabelArtifactResolver labelArtifactResolver;

    // PR #536 — carrier ZPL → PNG/PDF via bundled zebrash-cli. Feature-
    // flagged; when off, falls back to the pre-existing facsimile
    // renderers (ZplLabelService + PdfLabelService).
    @Autowired
    private com.multiship.backend.service.ZebrashRenderer zebrashRenderer;
    @Autowired
    private com.multiship.backend.service.ZebrashPdfService zebrashPdfService;
    @Autowired
    private com.multiship.backend.service.ZebrashCompositor zebrashCompositor;

    @org.springframework.beans.factory.annotation.Value("${label.render-carrier-zpl:false}")
    private boolean renderCarrierZplEnabled;

    @Autowired
    private CarrierProperties carrierProperties;

    @Autowired
    private com.multiship.backend.repository.OrderTrackingRepository orderTrackingRepository;

    // Diagnostic /label-state endpoint reads Order.packagesJson (added V33)
    // and per-package labelFilePath rows directly. Adding these repos here
    // rather than pushing the logic into OrderService — this is diagnostic-
    // only, no reuse, no business logic worth abstracting.
    @Autowired
    private com.multiship.backend.repository.OrderRepository orderRepositoryForDiag;

    @Autowired
    private com.multiship.backend.repository.LabelPackageRepository labelPackageRepositoryForDiag;

    @Autowired
    private com.multiship.backend.repository.CarrierAccountRefRepository carrierAccountRefRepository;

    @Autowired
    private com.multiship.backend.repository.ClientRepository clientRepository;

    @Autowired
    private com.multiship.backend.repository.OrderCustomsRepository orderCustomsRepository;

    @Autowired
    private com.multiship.backend.repository.ClientCustomsProfileRepository clientCustomsProfileRepository;

    @Autowired
    private com.multiship.backend.service.ShippingConfigService shippingConfigService;

    @Autowired
    private com.multiship.backend.service.PackingSlipService packingSlipService;

    // Sprint 51 — the platform's own commercial-invoice PDF: an
    // always-available operator document for international orders.
    @Autowired
    private com.multiship.backend.service.CommercialInvoiceService commercialInvoiceService;

    // Unified documents table — one row per labelled order (tracking + label
    // + invoice + statement figures together).
    @Autowired
    private com.multiship.backend.service.OrderDocumentSummaryService orderDocumentSummaryService;

    @Autowired
    private com.multiship.backend.service.shipment.MultiWarehouseLabelService multiWarehouseLabelService;

    @Autowired
    private com.multiship.backend.service.shipment.MultiWarehousePreviewService multiWarehousePreviewService;

    /**
     * Sprint 51 R2 — money-touching endpoints (manual-label,
     * multi-warehouse-label) route through this to dedupe partner /
     * operator retries. See audit finding #2.
     */
    @Autowired
    private com.multiship.backend.service.external.IdempotencyService idempotency;

    /**
     * PR #544 follow-up — derive the effective package count from an
     * {@link OrderWithLinesDTO}. Every label-serving endpoint that loops
     * over packages must use this, not {@code getPackageCount()} alone,
     * because the two data sources can disagree:
     *
     * <ul>
     *   <li>{@code label_package} row count = actual per-piece labels the
     *       carrier issued (from {@code generateManualLabel} success)</li>
     *   <li>{@code Order.package_count} = intent at order-creation time
     *       (also written on the manual-label error path — where it used to
     *       be stomped to {@code 1} pre-Part-B)</li>
     * </ul>
     *
     * <p>When they disagree, the {@code label_package} row count is the
     * source of truth — those rows are what the passthrough resolver can
     * actually serve. Using {@code MAX(...)} matches the FE picker's own
     * signal at {@code LabelDocumentPage.tsx:449-451}, so the picker's
     * "1 of 2" tabs and the composite always agree on how many labels
     * to render.
     */
    static int effectivePkgCount(OrderWithLinesDTO data) {
        if (data == null) return 1;
        int fromPackages = data.getPackages() == null ? 0 : data.getPackages().size();
        int fromCount = data.getPackageCount() == null ? 0 : data.getPackageCount();
        return Math.max(1, Math.max(fromPackages, fromCount));
    }

    /**
     * PR #548 — Build the badge text overlaid on the top-right of each
     * composite panel. Format:
     *   "PKG 1 OF 3"          (single-pkg or no tracking known)
     *   "PKG 1 OF 3\n1Z999AA10123456784"   (multi-pkg with per-piece tracking)
     * Returns {@code null} when no meaningful badge can be built — caller
     * skips the overlay for that panel (fail-open).
     */
    static String badgeFor(int pkgIndex, int totalPkgs,
            java.util.List<com.multiship.backend.dto.LabelPackageDTO> packages) {
        if (totalPkgs < 1 || pkgIndex < 1 || pkgIndex > totalPkgs) return null;
        String line1 = "PKG " + pkgIndex + " OF " + totalPkgs;
        if (packages == null || packages.isEmpty()) return line1;
        // Match by sequenceNumber; fall back to positional if seq is null
        // (legacy rows before Sprint 47).
        String tracking = packages.stream()
                .filter(p -> p.getSequenceNumber() != null && p.getSequenceNumber() == pkgIndex)
                .map(com.multiship.backend.dto.LabelPackageDTO::getTrackingNumber)
                .filter(t -> t != null && !t.isBlank())
                .findFirst()
                .orElse(null);
        if (tracking == null && pkgIndex - 1 < packages.size()) {
            String posTracking = packages.get(pkgIndex - 1).getTrackingNumber();
            if (posTracking != null && !posTracking.isBlank()) tracking = posTracking;
        }
        return tracking == null ? line1 : line1 + "\n" + tracking;
    }

    /** Map a client Address value object into the label payload shape. */
    private Map<String, Object> addressMap(com.multiship.backend.model.Address a, String fallbackName) {
        Map<String, Object> m = new LinkedHashMap<>();
        String resolvedName = a.getName() != null && !a.getName().isBlank() ? a.getName() : fallbackName;
        m.put("name", resolvedName);
        // PR #535 — company line. Carriers print recipient + shipper
        // COMPANY as a distinct label line whenever the wire request
        // carried a separate company field. Address VO has no company
        // slot today, so we synthesise: when the address has its own
        // name (typically a warehouse alias like "Central Warehouse")
        // AND the client's registered name is different, expose the
        // client name as the company. When name == fallbackName (no
        // warehouse alias) leave company null so the FE doesn't print
        // a duplicate line.
        if (fallbackName != null && !fallbackName.isBlank() && !fallbackName.equalsIgnoreCase(resolvedName)) {
            m.put("company", fallbackName);
        }
        m.put("phone", a.getPhone());
        m.put("addressLine1", a.getLine1());
        m.put("addressLine2", a.getLine2());
        m.put("city", a.getCity());
        m.put("state", a.getState());
        m.put("postalCode", a.getZip());
        m.put("countryCode", a.getCountry());
        return m;
    }

    // ===== UNIFIED LIST ENDPOINT =====

    /**
     * The single order-list endpoint: server-side pagination, sorting, and
     * filtering. TENANT users must pass tenantId equal to their own tenant;
     * operators can combine any filters.
     *
     * GET /api/orders?page=0&size=20&sortBy=orderNo&sortDirection=ASC
     *     &status=PENDING&tenantId=ARHDEV&search=miami&resolution=READY
     *     &includeResolution=true
     */
    @Operation(summary = "List orders (THE list endpoint)",
            description = "Server-side pagination, sorting, and filtering; all filters compose. TENANT callers must pass tenantId equal to their own tenant.")
    @PreAuthorize("(hasAnyRole('ADMIN', 'USER') and @accessScope.canAccessTenant(authentication, #tenantId)) or @orderAccess.canViewTenant(authentication, #tenantId)")
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponseDTO<OrderResponseDTO>>> listOrders(
            @RequestParam(defaultValue = "0") int page,
            // Sprint 51 AC-L1 — align on PaginationDefaults (was 20 here, 25 elsewhere).
            @Parameter(description = "Page size, capped at " + com.multiship.backend.common.PaginationDefaults.MAX_SIZE)
            @RequestParam(defaultValue = com.multiship.backend.common.PaginationDefaults.DEFAULT_SIZE_STR) int size,
            @Parameter(description = "orderNo | city | weight | status | createdDate") @RequestParam(defaultValue = "orderNo") String sortBy,
            @RequestParam(defaultValue = "ASC") String sortDirection,
            @Parameter(description = "PENDING | GENERATED | ERROR") @RequestParam(required = false) String status,
            @Parameter(description = "Scope to one tenant (required for TENANT role)") @RequestParam(required = false) String tenantId,
            @Parameter(description = "Matches order #, city, customer code, or tracking number") @RequestParam(required = false) String search,
            @Parameter(description = "READY | NEEDS_DETAILS | BLOCKED — filters PENDING orders by the account-resolution cascade, computed server-side") @RequestParam(required = false) String resolution,
            @Parameter(description = "Column filter: client code contains") @RequestParam(required = false) String customer,
            @Parameter(description = "Column filter: destination city or state contains") @RequestParam(required = false) String city,
            @Parameter(description = "Column filter: order # contains") @RequestParam(required = false) String orderNo,
            @Parameter(description = "Column filter: tracking number contains") @RequestParam(required = false) String tracking,
            @Parameter(description = "Created on or after (yyyy-MM-dd)") @RequestParam(required = false) String createdFrom,
            @Parameter(description = "Created on or before (yyyy-MM-dd)") @RequestParam(required = false) String createdTo,
            @Parameter(description = "Order source: MANUAL | BULK | API | WMS | ERP") @RequestParam(required = false) String source,
            @Parameter(description = "Shipping channel: D2C | B2B") @RequestParam(required = false) String channel,
            @Parameter(description = "Attach the cascade's account pick (accountResolution) to each row") @RequestParam(defaultValue = "false") boolean includeResolution) {

        if (!isValidSortBy(sortBy)) {
            sortBy = "orderNo";
        }
        if (!isValidSortDirection(sortDirection)) {
            sortDirection = "ASC";
        }

        PaginationRequestDTO paginationRequest = PaginationRequestDTO.builder()
                .page(page)
                // Sprint 51 AC-L1 — cap at PaginationDefaults.MAX_SIZE so a
                // pathological ?size=100000 can't nuke the query.
                .size(com.multiship.backend.common.PaginationDefaults.clamp(size))
                .sortBy(sortBy)
                .sortDirection(sortDirection)
                .build();

        OrderListFilters filters = OrderListFilters.builder()
                .status(status)
                .tenantId(tenantId)
                .search(search)
                .resolution(resolution)
                .customer(customer)
                .city(city)
                .orderNo(orderNo)
                .tracking(tracking)
                .createdFrom(createdFrom)
                .createdTo(createdTo)
                .source(source)
                .channel(channel)
                .build();

        ApiResponse<PageResponseDTO<OrderResponseDTO>> response =
                orderService.listOrders(filters, includeResolution, paginationRequest);
        return ResponseEntity.status(response.getCode()).body(response);
    }

    /** Tab counts for the Labels work queue: ready, needsDetails, blocked, failed, generated. */
    @Operation(summary = "Work-queue counts",
            description = "One aggregate pass: {ready, needsDetails, blocked, failed, generated}. 'blocked' = orders with no usable account anywhere in the cascade.")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping("/queue-stats")
    public ResponseEntity<ApiResponse<Map<String, Long>>> getQueueStats() {
        ApiResponse<Map<String, Long>> response = orderService.getQueueStats();
        return ResponseEntity.status(response.getCode()).body(response);
    }

    @PreAuthorize("@orderAccess.canViewOrder(authentication, #orderNo)")
    @GetMapping("/{orderNo}")
    public ResponseEntity<ApiResponse<OrderResponseDTO>> getOrderWithTracking(
            @PathVariable Integer orderNo) {
        ApiResponse<OrderResponseDTO> response = orderService.getOrderWithTracking(orderNo);
        return ResponseEntity.status(response.getCode()).body(response);
    }

    @PreAuthorize("@orderAccess.canViewOrder(authentication, #orderNo)")
    @GetMapping("/{orderNo}/details")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getOrderWithLinesAndCarrier(
            @PathVariable Integer orderNo) {
        ApiResponse<OrderWithLinesDTO> orderResponse = orderService.getOrderWithLines(orderNo);
        if (!"SUCCESS".equalsIgnoreCase(orderResponse.getStatus()) || orderResponse.getData() == null) {
            return ResponseEntity.status(orderResponse.getCode()).body(ApiResponse.<Map<String, Object>>builder()
                    .status(orderResponse.getStatus())
                    .code(orderResponse.getCode())
                    .message(orderResponse.getMessage())
                    .timestamp(orderResponse.getTimestamp())
                    .errors(orderResponse.getErrors())
                    .build());
        }

        // Account details come from the three-scenario cascade and are OPTIONAL —
        // order details must always load even when no account resolves yet.
        OrderAccountResolutionDTO resolution = asShippedResolution(orderNo);
        if (resolution == null) {
            ApiResponse<List<OrderAccountResolutionDTO>> resolutionResponse =
                    carrierService.resolveOrderAccounts(List.of(orderNo));
            resolution = resolutionResponse.getData() != null && !resolutionResponse.getData().isEmpty()
                    ? resolutionResponse.getData().get(0)
                    : null;
        }

        Map<String, Object> carrierAccount = null;
        if (resolution != null && resolution.getAccountNumber() != null) {
            carrierAccount = new LinkedHashMap<>();
            carrierAccount.put("carrierCode", resolution.getCarrierCode());
            carrierAccount.put("carrierName", resolution.getAccountName());
            carrierAccount.put("accountNumber", resolution.getAccountNumber());
            carrierAccount.put("accountCode", resolution.getScenario());
            carrierAccount.put("isDefault", "DEFAULT".equals(resolution.getScenario()));
            carrierAccount.put("active", true);
            carrierAccount.put("environment", resolution.getEnvironment());
            carrierAccount.put("shipViaCd", null);
            carrierAccount.put("shipViaDescription", null);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("order", orderResponse.getData());
        payload.put("carrierAccount", carrierAccount);

        ApiResponse<Map<String, Object>> response = ApiResponse.<Map<String, Object>>builder()
                .status("SUCCESS")
                .code(200)
                .message("Order with line items and carrier account retrieved successfully")
                .timestamp(java.time.LocalDateTime.now())
                .data(payload)
                .build();

        return ResponseEntity.status(response.getCode()).body(response);
    }

    /**
     * Everything needed to render a shipping label and commercial invoice for
     * an order: order + line items, tenant carrier account, label/tracking
     * details, and the configured shipper (ship-from) address.
     */
    @PreAuthorize("@orderAccess.canViewOrder(authentication, #orderNo)")
    @GetMapping("/{orderNo}/label")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getLabelDocument(@PathVariable Integer orderNo) {
        ApiResponse<OrderWithLinesDTO> orderResponse = orderService.getOrderWithLines(orderNo);
        if (!"SUCCESS".equalsIgnoreCase(orderResponse.getStatus()) || orderResponse.getData() == null) {
            return ResponseEntity.status(orderResponse.getCode()).body(ApiResponse.<Map<String, Object>>builder()
                    .status(orderResponse.getStatus())
                    .code(orderResponse.getCode())
                    .message(orderResponse.getMessage())
                    .timestamp(orderResponse.getTimestamp())
                    .errors(orderResponse.getErrors())
                    .build());
        }

        OrderWithLinesDTO order = orderResponse.getData();

        // The account shown on the documents comes from the same three-scenario
        // cascade that label generation uses (order details -> book -> default).
        // Generated labels are branded by the account actually used; only
        // ungenerated previews fall back to the live cascade resolution.
        Object resolution = asShippedResolution(orderNo);
        if (resolution == null) {
            ApiResponse<List<OrderAccountResolutionDTO>> resolutionResponse =
                    carrierService.resolveOrderAccounts(List.of(orderNo));
            resolution = resolutionResponse.getData() != null && !resolutionResponse.getData().isEmpty()
                    ? resolutionResponse.getData().get(0)
                    : null;
        }

        ApiResponse<OrderResponseDTO> trackingResponse = orderService.getOrderWithTracking(orderNo);
        Object labelDetails = trackingResponse.getData() != null ? trackingResponse.getData().getLabelDetails() : null;

        // FROM block: the order's client ship-from when it has one, else the
        // company warehouse. Return block: the client's effective return address.
        String clientCode = order.getTenantId() != null && !order.getTenantId().isBlank()
                ? order.getTenantId() : order.getCustNo();
        com.multiship.backend.model.Client client = clientCode != null
                ? clientRepository.findByClientCodeIgnoreCase(clientCode.trim().toUpperCase()).orElse(null)
                : null;

        Map<String, Object> shipper;
        Map<String, Object> returnTo;
        if (order.getShipFromCountryCd() != null && !order.getShipFromCountryCd().isBlank()) {
            // Manual shipment carried its own ship-from — the authoritative
            // origin the operator entered. Use it verbatim so a domestic
            // shipment is not re-classified international against the platform
            // warehouse default (which lives in a different country).
            shipper = new LinkedHashMap<>();
            shipper.put("name", org.springframework.util.StringUtils.hasText(order.getShipFromName())
                    ? order.getShipFromName() : order.getShipFromCompany());
            shipper.put("company", order.getShipFromCompany());
            shipper.put("phone", order.getShipFromPhone());
            shipper.put("addressLine1", order.getShipFromAddr1());
            shipper.put("addressLine2", order.getShipFromAddr2());
            shipper.put("city", order.getShipFromCity());
            shipper.put("state", order.getShipFromState());
            shipper.put("postalCode", order.getShipFromZip());
            shipper.put("countryCode", order.getShipFromCountryCd());
            returnTo = shipper;
        } else if (client != null && client.getShipFrom() != null && client.getShipFrom().hasValue()) {
            shipper = addressMap(client.getShipFrom(), client.getName());
            returnTo = addressMap(client.effectiveReturnAddress(), client.getName());
        } else {
            CarrierProperties.ShipperDefaults d = carrierProperties.getShipper();
            shipper = new LinkedHashMap<>();
            shipper.put("name", d.getName());
            shipper.put("phone", d.getPhone());
            shipper.put("addressLine1", d.getAddressLine1());
            shipper.put("addressLine2", d.getAddressLine2());
            shipper.put("city", d.getCity());
            shipper.put("state", d.getState());
            shipper.put("postalCode", d.getPostalCode());
            shipper.put("countryCode", d.getCountryCode());
            returnTo = shipper;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("order", order);
        payload.put("resolution", resolution);
        payload.put("label", labelDetails);
        payload.put("shipper", shipper);
        payload.put("returnTo", returnTo);
        payload.put("isReturn", order.getIsReturn() != null && "Y".equalsIgnoreCase(order.getIsReturn()));

        // ===== customs (international shipments only) =====
        // International when the destination country differs from the origin.
        String shipperCountry = String.valueOf(shipper.getOrDefault("countryCode", "")).trim();
        String shipToCountry = order.getShiptoCountryCd() != null ? order.getShiptoCountryCd().trim() : "";
        // Same customs territory (e.g. intra-EU) = no customs border = the
        // document carries no importer/broker blocks, like a domestic parcel.
        boolean international = !shipToCountry.isEmpty() && !shipperCountry.isEmpty()
                && !com.multiship.backend.util.CustomsTerritories.sameTerritory(shipperCountry, shipToCountry);
        payload.put("international", international);

        // ===== how it ships: FULL service resolution (rules first, then the
        // carrier's scope fallback — so the document always shows the service
        // that would actually be used, even when a mapped service is disabled).
        // Service scope uses COUNTRY inequality, not customs territory.
        boolean serviceIntl = !shipToCountry.isEmpty() && !shipperCountry.isEmpty()
                && !shipToCountry.equalsIgnoreCase(shipperCountry);
        // Prefer the EXACT service the order shipped on (its ship_via_cd is a
        // real service code for manual/catalog shipments) so the label prints
        // the purchased service — e.g. "FedEx 2Day", not the lane's default
        // "FedEx Ground". Only legacy ERP codes (P80/F77/L01) or unknown codes
        // fall through to the rule/scope re-resolution.
        String svcCarrier = com.multiship.backend.service.ShippingConfigService
                .canonicalCarrierFor(order.getShipviaCd());
        com.multiship.backend.model.ShippingService ruledService = shippingConfigService
                .serviceByCode(svcCarrier, order.getShipviaCd())
                .orElseGet(() -> shippingConfigService
                        .resolveService(svcCarrier, clientCode, order.getShipviaCd(),
                                shipToCountry, serviceIntl, shipperCountry)
                        .orElse(null));
        if (ruledService != null) {
            Map<String, Object> serviceMap = new LinkedHashMap<>();
            serviceMap.put("carrier", ruledService.getCarrier());
            serviceMap.put("code", ruledService.getServiceCode());
            serviceMap.put("name", ruledService.getName());
            serviceMap.put("scope", ruledService.getScope());
            payload.put("service", serviceMap);
        }
        // Package auto-picked for that service by the order's weight (falls
        // back to the global default preset inside pickPackage).
        shippingConfigService
                .pickPackage(ruledService != null ? ruledService.getId() : null, order.getWeight())
                .ifPresent(pick -> {
                    com.multiship.backend.model.PackagePreset p = pick.preset();
                    Map<String, Object> pkg = new LinkedHashMap<>();
                    pkg.put("name", p.getName());
                    pkg.put("kind", p.getKind());
                    pkg.put("carrierPackageCode", p.getCarrierPackageCode());
                    pkg.put("length", p.getLength());
                    pkg.put("width", p.getWidth());
                    pkg.put("height", p.getHeight());
                    pkg.put("dimUnit", p.getDimUnit());
                    pkg.put("maxWeight", p.getMaxWeight());
                    pkg.put("weightUnit", p.getWeightUnit());
                    // carrier billing math (what this box actually costs to ship)
                    pkg.put("dimWeight", com.multiship.backend.util.PackageMath.dimWeight(p));
                    pkg.put("billableWeight",
                            com.multiship.backend.util.PackageMath.billableWeight(p, order.getWeight()));
                    pkg.put("lengthPlusGirth", com.multiship.backend.util.PackageMath.lengthPlusGirthInches(p));
                    pkg.put("oversizeStatus", com.multiship.backend.util.PackageMath.oversizeStatus(p).name());
                    pkg.put("boxCost", p.getBoxCost());
                    pkg.put("flatRate", p.getFlatRate());
                    payload.put("packagePreset", pkg);
                });

        // Importer + broker: a PER-SHIPMENT override on the order wins (it never
        // touches the client's saved profile); otherwise resolve from the client's
        // profile for this destination country.
        String importerBrokerOverride = order.getImporterBrokerOverride();
        if (international && importerBrokerOverride != null && !importerBrokerOverride.isBlank()) {
            try {
                Map<String, Object> ov = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue(importerBrokerOverride,
                                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                Object imp = ov.get("importer");
                Object brk = ov.get("broker");
                if (imp != null) {
                    payload.put("importer", imp);
                }
                boolean namedBroker = brk instanceof Map
                        && org.springframework.util.StringUtils.hasText(String.valueOf(((Map<?, ?>) brk).get("name")));
                payload.put("brokerage", namedBroker ? "BROKER_SELECT" : "CARRIER_DEFAULT");
                if (namedBroker) {
                    payload.put("broker", brk);
                }
            } catch (Exception ignore) {
                // malformed override → fall through to no importer/broker (rare)
            }
        } else if (international && clientCode != null && !shipToCountry.isEmpty()) {
            clientCustomsProfileRepository
                    .findByClientAndCountry(clientCode.trim().toUpperCase(), shipToCountry.toUpperCase())
                    .ifPresent(p -> {
                        boolean receiverIsImporter = "RECEIVER".equalsIgnoreCase(p.getImporterType());
                        Map<String, Object> importer = new LinkedHashMap<>();
                        if (receiverIsImporter) {
                            // DAP: the order's consignee IS the Importer of
                            // Record — the carrier collects their KYC at
                            // destination. Stamp the consignee, not a company.
                            importer.put("type", "RECEIVER");
                            importer.put("name", order.getShipName());
                            importer.put("contact", order.getShipAttn());
                            importer.put("countryCode", order.getShiptoCountryCd());
                            importer.put("addressLine1", order.getShipAddr1());
                            importer.put("city", order.getShiptoCity());
                            importer.put("state", order.getShiptoState());
                            importer.put("postalCode", order.getShiptoZip());
                        } else {
                            importer.put("type", "BUSINESS");
                            importer.put("name", p.getImporterName());
                            importer.put("contact", p.getImporterContact());
                            importer.put("countryCode", p.getImporterCountry());
                            importer.put("addressLine1", p.getImporterAddress1());
                            importer.put("addressLine2", p.getImporterAddress2());
                            importer.put("phone", p.getImporterPhone());
                            importer.put("city", p.getImporterCity());
                            importer.put("state", p.getImporterState());
                            importer.put("postalCode", p.getImporterPostcode());
                            importer.put("taxId", p.getImporterTaxId());
                            importer.put("taxIdType", p.getImporterTaxIdType());
                            importer.put("eori", p.getImporterEori());
                            importer.put("ioss", p.getImporterIoss());
                            importer.put("companyReg", p.getImporterCompanyReg());
                            importer.put("iec", p.getImporterIec());
                            importer.put("gstin", p.getImporterGstin());
                        }
                        payload.put("importer", importer);

                        // No named broker = the carrier's own brokerage clears
                        // the shipment (broker-inclusive default); a named one
                        // means Broker Select at the destination border.
                        // Presence = name OR company (legacy rows may carry
                        // only a company) — kept in sync with the modal.
                        boolean ownBroker = org.springframework.util.StringUtils.hasText(p.getBrokerName())
                                || org.springframework.util.StringUtils.hasText(p.getBrokerCompany());
                        payload.put("brokerage", ownBroker ? "BROKER_SELECT" : "CARRIER_DEFAULT");
                        if (ownBroker) {
                            Map<String, Object> broker = new LinkedHashMap<>();
                            broker.put("name", p.getBrokerName());
                            broker.put("company", p.getBrokerCompany());
                            broker.put("countryCode", p.getBrokerCountry());
                            broker.put("addressLine1", p.getBrokerAddress1());
                            broker.put("addressLine2", p.getBrokerAddress2());
                            broker.put("phone", p.getBrokerPhone());
                            broker.put("city", p.getBrokerCity());
                            broker.put("state", p.getBrokerState());
                            broker.put("postalCode", p.getBrokerPostcode());
                            broker.put("brokerId", p.getBrokerId());
                            broker.put("license", p.getBrokerLicense());
                            payload.put("broker", broker);
                        }

                        // Shipment defaults carried by the profile.
                        Map<String, Object> defaults = new LinkedHashMap<>();
                        defaults.put("incoterms", p.getIncoterms());
                        defaults.put("dutiesBillTo", p.getDutiesBillTo());
                        defaults.put("dutiesAccount", p.getDutiesAccount());
                        defaults.put("reasonForExport", p.getReasonForExport());
                        defaults.put("currency", p.getCurrency());
                        payload.put("customsDefaults", defaults);

                        if (p.getAccountCarrier() != null || p.getAccountNo() != null) {
                            Map<String, Object> acct = new LinkedHashMap<>();
                            acct.put("carrier", p.getAccountCarrier());
                            acct.put("accountNo", p.getAccountNo());
                            payload.put("customsAccount", acct);
                        }
                    });
        }

        // Per-order goods (customs line items) still ride along when present.
        orderCustomsRepository.findByOrderNoIgnoreCase(String.valueOf(orderNo)).ifPresent(c -> {
            Map<String, Object> customs = new LinkedHashMap<>();
            if (c.getImporterAddress() != null) {
                Map<String, Object> importer = addressMap(c.getImporterAddress(), c.getImporterCompany());
                importer.put("company", c.getImporterCompany());
                importer.put("taxId", c.getImporterTaxId());
                importer.put("vat", c.getImporterVat());
                importer.put("eori", c.getImporterEori());
                customs.put("importer", importer);
            }
            customs.put("incoterms", c.getIncoterms());
            customs.put("reasonForExport", c.getReasonForExport());
            customs.put("currency", c.getCurrency());
            customs.put("weightUnit", c.getWeightUnit());
            customs.put("notes", c.getNotes());
            customs.put("items", c.getItems().stream().map(i -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("description", i.getDescription());
                m.put("hsCode", i.getHsCode());
                m.put("countryOfOrigin", i.getCountryOfOrigin());
                m.put("quantity", i.getQuantity());
                m.put("unitValue", i.getUnitValue());
                m.put("weight", i.getWeight());
                m.put("sku", i.getSku());
                return m;
            }).toList());
            payload.put("customs", customs);
        });

        // ===== charges (what the shipment was billed) =====
        // Freight on the commercial invoice = the carrier's charge for this
        // shipment (billable = carrier + markup; falls back to the raw carrier
        // amount). Currency mirrors the markup currency. Sandbox rates come
        // back as 0.00, so the invoice shows a real freight figure only once a
        // production rate has been captured.
        orderTrackingRepository.findByOrderNo(orderNo).ifPresent(t -> {
            Map<String, Object> charges = new LinkedHashMap<>();
            java.math.BigDecimal freight = t.getBillableAmount() != null
                    ? t.getBillableAmount() : t.getCarrierAmount();
            charges.put("freight", freight);
            charges.put("carrierAmount", t.getCarrierAmount());
            charges.put("billableAmount", t.getBillableAmount());
            charges.put("currency", t.getMarkupCurrency());
            payload.put("charges", charges);
        });

        ApiResponse<Map<String, Object>> response = ApiResponse.<Map<String, Object>>builder()
                .status("SUCCESS")
                .code(200)
                .message("Label document retrieved successfully")
                .timestamp(java.time.LocalDateTime.now())
                .data(payload)
                .build();

        return ResponseEntity.status(response.getCode()).body(response);
    }

    /**
     * Raw ZPL for the order's 4x6 thermal label — what a Zebra printer consumes.
     * In production this returns the carrier-provided artifact; in sandbox it is
     * built locally from the same data as the on-screen label document.
     */
    @Operation(summary = "Raw ZPL for the 4x6 thermal label (text/plain)")
    @PreAuthorize("@orderAccess.canViewOrder(authentication, #orderNo)")
    @GetMapping(value = "/{orderNo}/label/zpl", produces = org.springframework.http.MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> getLabelZpl(@PathVariable Integer orderNo,
            @org.springframework.web.bind.annotation.RequestParam(name = "pkg", required = false) Integer pkgIndex) {
        // Sprint 52 PR B — carrier passthrough. When order_label_tracking.
        // label_file_path holds the carrier's real ZPL bytes (raw ^XA...^XZ
        // or base64-encoded), return them verbatim. On mismatch (stored
        // artifact is PDF/GIF/URL-with-non-ZPL) fall through to the
        // facsimile below.
        //
        // PR #544 — pkgIndex now threaded to the resolver. Multi-package
        // orders persist per-package label URLs in label_package; passing
        // pkgIndex reads from there. Pre-#544, this branch always
        // returned pkg 1's label for multi-package orders (pkg 2..N were
        // unreachable via passthrough).
        //   - ?pkg=N → single package
        //   - ?pkg omitted + multi-pkg order → concatenate all packages'
        //     ZPL blocks (thermal printers spool sequentially)
        //   - ?pkg omitted + single-pkg order → shipment-level (back-compat)
        if (pkgIndex != null && pkgIndex > 0) {
            java.util.Optional<byte[]> single = labelArtifactResolver
                    .resolveAsBytes(orderNo, "ZPL", pkgIndex);
            if (single.isPresent()) {
                return ResponseEntity.ok()
                        .header("Content-Disposition", "attachment; filename=label-"
                                + orderNo + "-pkg" + pkgIndex + ".zpl")
                        .body(new String(single.get()));
            }
        } else {
            // Peek at package count so a multi-pkg order concatenates. Uses
            // effectivePkgCount() rather than raw getPackageCount() so a
            // stale package_count doesn't drop pkg 2..N when label_package
            // rows exist for them.
            ApiResponse<OrderWithLinesDTO> peek = orderService.getOrderWithLines(orderNo);
            int totalPkgs = effectivePkgCount(peek.getData());
            if (totalPkgs > 1) {
                StringBuilder allZpl = new StringBuilder();
                boolean any = false;
                for (int i = 1; i <= totalPkgs; i++) {
                    java.util.Optional<byte[]> pz = labelArtifactResolver
                            .resolveAsBytes(orderNo, "ZPL", i);
                    if (pz.isEmpty()) continue;
                    if (any) allZpl.append('\n');
                    allZpl.append(new String(pz.get()));
                    any = true;
                }
                if (any) {
                    return ResponseEntity.ok()
                            .header("Content-Disposition", "attachment; filename=label-"
                                    + orderNo + "-all" + totalPkgs + ".zpl")
                            .body(allZpl.toString());
                }
            } else {
                java.util.Optional<byte[]> passthrough = labelArtifactResolver
                        .resolveAsBytes(orderNo, "ZPL", null);
                if (passthrough.isPresent()) {
                    return ResponseEntity.ok()
                            .header("Content-Disposition", "attachment; filename=label-" + orderNo + ".zpl")
                            .body(new String(passthrough.get()));
                }
            }
        }

        ApiResponse<OrderWithLinesDTO> orderResponse = orderService.getOrderWithLines(orderNo);
        if (!"SUCCESS".equalsIgnoreCase(orderResponse.getStatus()) || orderResponse.getData() == null) {
            return ResponseEntity.status(orderResponse.getCode()).body("Order " + orderNo + " was not found.");
        }

        OrderAccountResolutionDTO resolution = asShippedResolution(orderNo);
        if (resolution == null) {
            ApiResponse<List<OrderAccountResolutionDTO>> resolutionResponse =
                    carrierService.resolveOrderAccounts(List.of(orderNo));
            resolution = resolutionResponse.getData() != null && !resolutionResponse.getData().isEmpty()
                    ? resolutionResponse.getData().get(0)
                    : null;
        }

        ApiResponse<OrderResponseDTO> trackingResponse = orderService.getOrderWithTracking(orderNo);
        OrderResponseDTO.LabelDetails labelDetails = trackingResponse.getData() != null
                ? trackingResponse.getData().getLabelDetails()
                : null;

        int totalPkgs = effectivePkgCount(orderResponse.getData());
        java.util.List<com.multiship.backend.dto.LabelPackageDTO> allPackages =
                orderResponse.getData().getPackages() == null
                        ? java.util.List.of()
                        : orderResponse.getData().getPackages();

        // When ?pkg is omitted on a multi-box shipment, stream ALL labels in
        // one file (thermal printers spool multi-label ZPL sequentially via
        // consecutive ^XA…^XZ blocks). When ?pkg=N is given, return only that
        // box's label — same as before.
        boolean allPkgs = (pkgIndex == null) && totalPkgs > 1;
        StringBuilder zplOut = new StringBuilder();
        int firstPkg = allPkgs ? 1 : (pkgIndex == null || pkgIndex < 1 ? 1 : Math.min(pkgIndex, totalPkgs));
        int lastPkg = allPkgs ? totalPkgs : firstPkg;
        for (int p = firstPkg; p <= lastPkg; p++) {
            final int currentPkg = p;
            com.multiship.backend.dto.LabelPackageDTO perPkg = allPackages.stream()
                    .filter(x -> x.getSequenceNumber() != null && x.getSequenceNumber() == currentPkg)
                    .findFirst().orElse(null);
            zplOut.append(zplLabelService.buildLabel(orderResponse.getData(), resolution, labelDetails,
                    currentPkg, totalPkgs, perPkg));
        }

        String filenameSuffix;
        if (totalPkgs <= 1) {
            filenameSuffix = "";
        } else if (allPkgs) {
            filenameSuffix = "-all" + totalPkgs;
        } else {
            filenameSuffix = "-pkg" + firstPkg;
        }
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=label-" + orderNo
                        + filenameSuffix + ".zpl")
                .body(zplOut.toString());
    }

    /**
     * Sprint 52 PR A — 4x6" PDF facsimile of the shipping label, sibling
     * of the ZPL endpoint above. Same {@code ?pkg} semantics: omitted on a
     * multi-box shipment returns all boxes as one PDF with N pages;
     * {@code ?pkg=N} returns only that box.
     *
     * <p>PR B will layer in carrier-artifact passthrough — return the
     * carrier's real PDF bytes when they exist for this order in PDF
     * format, and only fall back to this facsimile otherwise.
     */
    @Operation(summary = "PDF facsimile of the 4x6\" shipping label (application/pdf)",
            description = "Sprint 52 PR A — sibling of /label/zpl. Renders a 4x6\" PDF from the "
                    + "same order data as the ZPL endpoint. This is a FACSIMILE for preview / "
                    + "review — not the carrier's canonical label. Carrier-artifact passthrough "
                    + "ships in a follow-up PR.")
    @PreAuthorize("@orderAccess.canViewOrder(authentication, #orderNo)")
    @GetMapping(value = "/{orderNo}/label/pdf",
            produces = org.springframework.http.MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> getLabelPdf(@PathVariable Integer orderNo,
            @org.springframework.web.bind.annotation.RequestParam(name = "pkg", required = false) Integer pkgIndex) {
        // Sprint 52 PR B — carrier passthrough. When the stored
        // label_file_path is a real carrier PDF, return those bytes
        // verbatim. Mirror of the /label/zpl passthrough block above.
        // PR #544 — per-package pkgIndex threading (see /label/zpl above).
        if (pkgIndex != null && pkgIndex > 0) {
            java.util.Optional<byte[]> single = labelArtifactResolver
                    .resolveAsBytes(orderNo, "PDF", pkgIndex);
            if (single.isPresent()) {
                return ResponseEntity.ok()
                        .header("Content-Disposition", "attachment; filename=label-"
                                + orderNo + "-pkg" + pkgIndex + ".pdf")
                        .header("Content-Type", org.springframework.http.MediaType.APPLICATION_PDF_VALUE)
                        .body(single.get());
            }
        } else {
            java.util.Optional<byte[]> passthrough = labelArtifactResolver
                    .resolveAsBytes(orderNo, "PDF", null);
            if (passthrough.isPresent()) {
                return ResponseEntity.ok()
                        .header("Content-Disposition", "attachment; filename=label-" + orderNo + ".pdf")
                        .header("Content-Type", org.springframework.http.MediaType.APPLICATION_PDF_VALUE)
                        .body(passthrough.get());
            }
        }

        // PR #536 — when the flag is on AND the carrier stored ZPL bytes
        // (not a PDF), render those bytes to PNG via bundled zebrash and
        // wrap into a single-page PDF. Preferred over the JSX-facsimile
        // path below because the ZPL is the carrier's canonical label —
        // eliminates the "preview looks nothing like the printed label"
        // gap documented in project_label_preview_audit.md. Fall back to
        // the facsimile if the renderer isn't ready or the ZPL fails to
        // parse (e.g. carrier stored a URL that returned unexpected
        // bytes).
        // PR #544 — per-package pkgIndex support: single page for ?pkg=N,
        // multi-page for pkg omitted on a multi-pkg order.
        if (renderCarrierZplEnabled) {
            try {
                java.util.List<byte[]> zpls = new java.util.ArrayList<>();
                if (pkgIndex != null && pkgIndex > 0) {
                    labelArtifactResolver.resolveAsBytes(orderNo, "ZPL", pkgIndex)
                            .ifPresent(zpls::add);
                } else {
                    ApiResponse<OrderWithLinesDTO> peek = orderService.getOrderWithLines(orderNo);
                    int totalPkgs = effectivePkgCount(peek.getData());
                    if (totalPkgs > 1) {
                        for (int i = 1; i <= totalPkgs; i++) {
                            labelArtifactResolver.resolveAsBytes(orderNo, "ZPL", i)
                                    .ifPresent(zpls::add);
                        }
                    } else {
                        labelArtifactResolver.resolveAsBytes(orderNo, "ZPL", null)
                                .ifPresent(zpls::add);
                    }
                }
                if (!zpls.isEmpty()) {
                    byte[] pdf = zebrashPdfService.renderZplsToPdf(zpls);
                    String suffix = pkgIndex != null && pkgIndex > 0
                            ? "-pkg" + pkgIndex
                            : (zpls.size() > 1 ? "-all" + zpls.size() : "");
                    return ResponseEntity.ok()
                            .header("Content-Disposition", "attachment; filename=label-" + orderNo + suffix + ".pdf")
                            .header("Content-Type", org.springframework.http.MediaType.APPLICATION_PDF_VALUE)
                            .body(pdf);
                }
            } catch (RuntimeException ex) {
                // ZebrashRenderer.RendererUnavailableException,
                // ZplParseException, ZplRenderException — all recoverable
                // via the facsimile path below. Warn-log for observability
                // but don't 500.
                org.slf4j.LoggerFactory.getLogger(OrderController.class)
                        .warn("zebrash PDF render failed for order {} pkg={}, falling back to facsimile: {}",
                                orderNo, pkgIndex, ex.getMessage());
            }
        }

        ApiResponse<OrderWithLinesDTO> orderResponse = orderService.getOrderWithLines(orderNo);
        if (!"SUCCESS".equalsIgnoreCase(orderResponse.getStatus()) || orderResponse.getData() == null) {
            return ResponseEntity.status(orderResponse.getCode()).build();
        }

        OrderAccountResolutionDTO resolution = asShippedResolution(orderNo);
        if (resolution == null) {
            ApiResponse<List<OrderAccountResolutionDTO>> resolutionResponse =
                    carrierService.resolveOrderAccounts(List.of(orderNo));
            resolution = resolutionResponse.getData() != null && !resolutionResponse.getData().isEmpty()
                    ? resolutionResponse.getData().get(0)
                    : null;
        }

        ApiResponse<OrderResponseDTO> trackingResponse = orderService.getOrderWithTracking(orderNo);
        OrderResponseDTO.LabelDetails labelDetails = trackingResponse.getData() != null
                ? trackingResponse.getData().getLabelDetails()
                : null;

        int totalPkgs = effectivePkgCount(orderResponse.getData());
        java.util.List<com.multiship.backend.dto.LabelPackageDTO> allPackages =
                orderResponse.getData().getPackages() == null
                        ? java.util.List.of()
                        : orderResponse.getData().getPackages();

        boolean allPkgs = (pkgIndex == null) && totalPkgs > 1;
        int firstPkg = allPkgs ? 1 : (pkgIndex == null || pkgIndex < 1 ? 1 : Math.min(pkgIndex, totalPkgs));
        int lastPkg = allPkgs ? totalPkgs : firstPkg;

        byte[] pdf = pdfLabelService.buildMultiPagePdf(
                orderResponse.getData(), resolution, labelDetails,
                firstPkg, lastPkg, totalPkgs, allPackages);

        String filenameSuffix;
        if (totalPkgs <= 1) {
            filenameSuffix = "";
        } else if (allPkgs) {
            filenameSuffix = "-all" + totalPkgs;
        } else {
            filenameSuffix = "-pkg" + firstPkg;
        }
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=label-" + orderNo
                        + filenameSuffix + ".pdf")
                .header("Content-Type", org.springframework.http.MediaType.APPLICATION_PDF_VALUE)
                .body(pdf);
    }

    /**
     * PR #536 — PNG preview of the shipping label. Renders the
     * carrier's actual ZPL bytes via bundled zebrash (Go binary)
     * when {@code label.render-carrier-zpl=true}; otherwise 404
     * (FE falls back to the JSX facsimile that already renders on
     * {@code /label/{orderNo}}).
     *
     * <p>Serves as the {@code <img src=…>} source when the FE swaps
     * its facsimile div for a canonical PNG view. Only fires when
     * ZPL passthrough succeeds — carrier stored URL/PDF/other
     * formats return 404 so the FE keeps the facsimile visible.
     */
    @Operation(summary = "PNG rendering of the carrier's ZPL label",
            description = "PR #536 — carrier-canonical PNG via bundled zebrash. "
                    + "PR #544 — accepts optional ?pkg=N for multi-package orders; "
                    + "omitting pkg on a multi-package order returns a vertically-"
                    + "stacked composite of all packages. Returns 404 when the "
                    + "feature flag is off or the carrier artifact isn't ZPL; FE "
                    + "falls back to its JSX facsimile.")
    @PreAuthorize("@orderAccess.canViewOrder(authentication, #orderNo)")
    @GetMapping(value = "/{orderNo}/label/preview.png",
            produces = org.springframework.http.MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> getLabelPreviewPng(@PathVariable Integer orderNo,
            @org.springframework.web.bind.annotation.RequestParam(name = "pkg", required = false) Integer pkgIndex) {
        if (!renderCarrierZplEnabled) {
            return ResponseEntity.status(404).build();
        }
        try {
            byte[] png;
            // Fetch the order shape ONCE so both the single-pkg and multi-
            // pkg branches can derive badges from the same label_package
            // list. Cheap — the DTO is already cached / joined.
            ApiResponse<OrderWithLinesDTO> orderResp = orderService.getOrderWithLines(orderNo);
            int totalPkgs = effectivePkgCount(orderResp.getData());
            java.util.List<com.multiship.backend.dto.LabelPackageDTO> pkgList =
                    orderResp.getData() != null && orderResp.getData().getPackages() != null
                            ? orderResp.getData().getPackages()
                            : java.util.List.of();

            if (pkgIndex != null && pkgIndex > 0) {
                // Explicit pkg → single-panel render for that package's
                // stored ZPL. Overlay a badge so the operator knows which
                // box this label goes on even when the picker isn't
                // visible (e.g. printing directly).
                java.util.Optional<byte[]> zpl =
                        labelArtifactResolver.resolveAsBytes(orderNo, "ZPL", pkgIndex);
                if (zpl.isEmpty()) return ResponseEntity.status(404).build();
                byte[] rendered = zebrashRenderer.renderPng(zpl.get());
                String badge = badgeFor(pkgIndex, totalPkgs, pkgList);
                png = badge == null
                        ? rendered
                        : zebrashCompositor.stackVerticallyWithBadges(
                                java.util.List.of(rendered), java.util.List.of(badge));
            } else if (totalPkgs <= 1) {
                // Single-pkg order — keep the pre-PR-#544 behavior
                // (shipment-level fetch, no badge — there's nothing to
                // annotate against).
                java.util.Optional<byte[]> zpl =
                        labelArtifactResolver.resolveAsBytes(orderNo, "ZPL", null);
                if (zpl.isEmpty()) return ResponseEntity.status(404).build();
                png = zebrashRenderer.renderPng(zpl.get());
            } else {
                // Multi-pkg composite — one panel per package, each with a
                // "PKG N OF M" + tracking badge overlaid before stacking.
                // The badges list runs parallel to the panels list; panels
                // skipped due to unresolvable bytes have their badge index
                // skipped too so numbering stays correct.
                java.util.List<byte[]> panels = new java.util.ArrayList<>(totalPkgs);
                java.util.List<String> badges = new java.util.ArrayList<>(totalPkgs);
                for (int i = 1; i <= totalPkgs; i++) {
                    java.util.Optional<byte[]> zpl =
                            labelArtifactResolver.resolveAsBytes(orderNo, "ZPL", i);
                    if (zpl.isEmpty()) continue; // skip missing panels rather than 404 the whole request
                    panels.add(zebrashRenderer.renderPng(zpl.get()));
                    String b = badgeFor(i, totalPkgs, pkgList);
                    badges.add(b == null ? "" : b);
                }
                if (panels.isEmpty()) return ResponseEntity.status(404).build();
                png = zebrashCompositor.stackVerticallyWithBadges(panels, badges);
            }
            return ResponseEntity.ok()
                    .header("Cache-Control", "private, max-age=60")
                    .header("Content-Type", org.springframework.http.MediaType.IMAGE_PNG_VALUE)
                    .body(png);
        } catch (RuntimeException ex) {
            org.slf4j.LoggerFactory.getLogger(OrderController.class)
                    .warn("zebrash PNG render failed for order {} pkg={}: {}",
                            orderNo, pkgIndex, ex.getMessage());
            return ResponseEntity.status(502).build();
        }
    }

    @Operation(
            summary = "Branded packing slip PDF for the order",
            description = "Sprint 42 — renders a tenant-branded packing slip PDF " +
                    "(the branded page that ships INSIDE the parcel). Applies the " +
                    "tenant's LabelTemplate when present; otherwise the platform " +
                    "default; otherwise built-in defaults. The carrier's shipping " +
                    "label itself is NOT customisable — that's carrier-mandated. " +
                    "Response is application/pdf inline."
    )
    @PreAuthorize("@orderAccess.canViewOrder(authentication, #orderNo)")
    @GetMapping(value = "/{orderNo}/packing-slip",
            produces = org.springframework.http.MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> getPackingSlip(@PathVariable Integer orderNo) {
        try {
            byte[] pdf = packingSlipService.render(orderNo);
            return ResponseEntity.ok()
                    .header("Content-Disposition",
                            "inline; filename=packing-slip-" + orderNo + ".pdf")
                    .header("Content-Type",
                            org.springframework.http.MediaType.APPLICATION_PDF_VALUE)
                    .body(pdf);
        } catch (IllegalArgumentException notFound) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.NOT_FOUND).build();
        }
    }

    @Operation(
            summary = "Download the order's commercial invoice (PDF)",
            description = "The platform's own commercial invoice for an international order, "
                    + "rendered from the persisted customs data and available on demand. "
                    + "404 when the order doesn't exist; 422 when the order has no customs "
                    + "data (domestic / not international). application/pdf inline.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Commercial invoice PDF")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Order not found")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "Order has no customs data")
    @PreAuthorize("@orderAccess.canViewOrder(authentication, #orderNo)")
    @GetMapping(value = "/{orderNo}/commercial-invoice",
            produces = org.springframework.http.MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> getCommercialInvoice(@PathVariable Integer orderNo) {
        try {
            byte[] pdf = commercialInvoiceService.render(orderNo);
            return ResponseEntity.ok()
                    .header("Content-Disposition",
                            "inline; filename=commercial-invoice-" + orderNo + ".pdf")
                    .header("Content-Type",
                            org.springframework.http.MediaType.APPLICATION_PDF_VALUE)
                    .body(pdf);
        } catch (IllegalArgumentException notFound) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.NOT_FOUND).build();
        } catch (IllegalStateException noCustoms) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY).build();
        }
    }

    @Operation(summary = "Unified documents table — one row per labelled order",
            description = "Everything label generation produced, in one common table: tracking "
                    + "number, label availability (PDF/ZPL via /orders/{n}/label/*), commercial "
                    + "invoice availability (/orders/{n}/commercial-invoice, when customs data "
                    + "exists), and the billing-statement figures (carrier cost / markup / "
                    + "billable). Newest first; tenant-scoped for client users.")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping("/documents")
    public ResponseEntity<ApiResponse<java.util.List<
            com.multiship.backend.service.OrderDocumentSummaryService.DocumentRow>>> listDocuments(
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "200") int limit) {
        java.util.List<com.multiship.backend.service.OrderDocumentSummaryService.DocumentRow> rows =
                orderDocumentSummaryService.list(limit);
        return ResponseEntity.ok(ApiResponse.<java.util.List<
                        com.multiship.backend.service.OrderDocumentSummaryService.DocumentRow>>builder()
                .status("SUCCESS").code(200).timestamp(java.time.LocalDateTime.now())
                .message(rows.size() + " labelled order(s).")
                .data(rows)
                .build());
    }

    @Operation(
            summary = "Generate the order's shipping label (idempotent)",
            description = """
                    Creates the label sub-resource: resolves the carrier account via the \
                    three-scenario cascade (order's own details -> saved reference account \
                    -> company default) and purchases the label. Concurrent requests \
                    serialize server-side; the carrier is never billed twice. Retrying \
                    with the same Idempotency-Key returns the existing label as 200.""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Label generated (or returned again for an Idempotency-Key retry)")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "errorCode ORDER_NOT_FOUND")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "errorCode LABEL_ALREADY_GENERATED — existing tracking details in data")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "errorCode NEEDS_CARRIER_DETAILS (prefill in data), ACCOUNT_SELECTION_REQUIRED (pick an account and resend with body {accountId}), or CLIENT_NOT_FOUND")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "502", description = "errorCode CARRIER_FAILURE — the upstream carrier rejected the request; or CLIENT_CARRIER_AUTH_FAILED (Sprint 50 T1 #3) — the client's carrier credentials failed and the caller did not opt into the platform account (resend with useHouseAccount=true to bill the house account)")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @PostMapping("/{orderNo}/label")
    public ResponseEntity<ApiResponse<com.multiship.backend.dto.LabelGenerationResponse>> generateLabel(
            @PathVariable Long orderNo,
            @Parameter(description = "Client-chosen key making retries safe: the same key returns the existing label as a success instead of a 409.")
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @org.springframework.web.bind.annotation.RequestBody(required = false) com.multiship.backend.dto.GenerateLabelRequest request,
            @org.springframework.security.core.annotation.AuthenticationPrincipal org.springframework.security.core.userdetails.UserDetails userDetails) {
        Long accountId = request != null ? request.getAccountId() : null;
        // Sprint 50 Tier 1 (finding #3) — thread the shipper's opt-in for
        // billing the platform (house) account on a client-credential failure.
        // Default false: without the opt-in, the failure surfaces
        // CLIENT_CARRIER_AUTH_FAILED instead of a silent platform bill.
        boolean useHouseAccount = request != null && request.isUseHouseAccount();
        ApiResponse<com.multiship.backend.dto.LabelGenerationResponse> response =
                carrierService.generateLabel(orderNo, userDetails, idempotencyKey, accountId, useHouseAccount);
        return ResponseEntity.status(response.getCode()).body(response);
    }

    /** One-shot manual shipment: operator enters everything and the label is purchased immediately. */
    @Operation(summary = "Create a manual shipment and generate its label in one step",
            description = "The operator supplies ship-from, ship-to, package + weight and picks the carrier account / "
                    + "service / packaging explicitly. The label is purchased immediately and recorded as a manual "
                    + "order (is_manual = 'Y') that appears in the queue/archive.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Label generated — tracking + label URL in data")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "errorCode VALIDATION_ERROR — missing/invalid recipient, weight or account")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "502", description = "errorCode CARRIER_FAILURE — the carrier rejected the shipment")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @PostMapping("/manual-label")
    public ResponseEntity<ApiResponse<com.multiship.backend.dto.LabelGenerationResponse>> generateManualLabel(
            @jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody com.multiship.backend.dto.ManualShipmentRequest request,
            @org.springframework.security.core.annotation.AuthenticationPrincipal org.springframework.security.core.userdetails.UserDetails userDetails,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        // Sprint 51 R2 (audit finding #2) — money-touching. A double-click,
        // browser retry, or 502-then-retry used to purchase N labels; now
        // the same Idempotency-Key replays the first response. Fail-closed
        // on Redis outage: without the dedup guarantee we could double-charge.
        // Namespaced by "user:" + username so this can never collide with
        // the API-key path's numeric namespace (see IdempotencyService).
        String callerId = userDetails != null ? "user:" + userDetails.getUsername() : null;
        return idempotency.executeOrReplay(callerId, idempotencyKey,
                new com.fasterxml.jackson.core.type.TypeReference<ApiResponse<com.multiship.backend.dto.LabelGenerationResponse>>() {},
                () -> {
                    ApiResponse<com.multiship.backend.dto.LabelGenerationResponse> response =
                            carrierService.generateManualLabel(request, userDetails);
                    return ResponseEntity.status(response.getCode()).body(response);
                }, true);
    }

    /** Fix-and-regenerate a failed order in place: apply the operator's corrected
     *  input to the existing order and re-purchase its label, keeping the same
     *  order number and flipping ERROR → GENERATED on success. */
    @Operation(summary = "Fix a failed order and regenerate its label in place",
            description = "Applies corrected ship-from/ship-to/package/service/customs input to an existing "
                    + "order and re-purchases the label on the SAME order number. Rejected (409) when the order "
                    + "already has a generated label — regenerate never silently re-bills a shipped order.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Label generated — order flipped to GENERATED")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Order already has a generated label")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "502", description = "errorCode CARRIER_FAILURE — the carrier rejected the corrected shipment")
    @PreAuthorize("@orderAccess.canViewOrder(authentication, #orderNo)")
    @PostMapping("/{orderNo}/regenerate")
    public ResponseEntity<ApiResponse<com.multiship.backend.dto.LabelGenerationResponse>> regenerateOrder(
            @PathVariable Integer orderNo,
            @jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody com.multiship.backend.dto.ManualShipmentRequest request,
            @org.springframework.security.core.annotation.AuthenticationPrincipal org.springframework.security.core.userdetails.UserDetails userDetails,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        // Guard: never regenerate (re-bill) an order that already shipped.
        com.multiship.backend.dto.OrderResponseDTO.LabelDetails existing =
                orderService.getOrderWithTracking(orderNo).getData() != null
                        ? orderService.getOrderWithTracking(orderNo).getData().getLabelDetails() : null;
        if (existing != null && Boolean.TRUE.equals(existing.getIsGenerated())
                && "GENERATED".equalsIgnoreCase(existing.getStatus())) {
            return ResponseEntity.status(409).body(ApiResponse.<com.multiship.backend.dto.LabelGenerationResponse>builder()
                    .status("error").code(409).message("Order " + orderNo + " already has a generated label; "
                            + "regenerate is only for failed/pending orders.")
                    .errorCode(com.multiship.backend.dto.ErrorCode.LABEL_ALREADY_GENERATED.name())
                    .timestamp(java.time.LocalDateTime.now()).build());
        }
        String callerId = userDetails != null ? "user:" + userDetails.getUsername() : null;
        return idempotency.executeOrReplay(callerId, idempotencyKey,
                new com.fasterxml.jackson.core.type.TypeReference<ApiResponse<com.multiship.backend.dto.LabelGenerationResponse>>() {},
                () -> {
                    ApiResponse<com.multiship.backend.dto.LabelGenerationResponse> response =
                            carrierService.generateManualLabel(request, userDetails, orderNo);
                    return ResponseEntity.status(response.getCode()).body(response);
                }, true);
    }

    @Operation(summary = "Generate labels for a shipment split across warehouses (Sprint 47)",
            description = "Groups the input `lines` by `warehouseCode`, calls the single-shipment " +
                    "label generator once per group, and returns a `ShipmentGroup` id plus one " +
                    "`ChildShipment` per warehouse. Fail-all rollback: any child failure aborts " +
                    "the whole batch — no partial persistence. Existing `/manual-label` endpoint " +
                    "still handles single-warehouse cases; this endpoint is opt-in.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Group + child shipments created")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error — missing clientCode / lines / per-line warehouseCode")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "One of the child shipments failed; batch aborted")
    // Sprint 50 Tier 0.5 PR E - controller role gate stays broad; the
    // service clamps request.clientCode to the caller's own tenant so a
    // scoped USER cannot generate a split for a foreign tenant.
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @PostMapping("/multi-warehouse-label")
    public ResponseEntity<ApiResponse<com.multiship.backend.dto.MultiWarehouseLabelResponse>> generateMultiWarehouseLabel(
            @jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody com.multiship.backend.dto.MultiWarehouseLabelRequest request,
            @org.springframework.security.core.annotation.AuthenticationPrincipal org.springframework.security.core.userdetails.UserDetails userDetails,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        // Sprint 51 R2 (audit finding #2) — money-touching. Multi-warehouse
        // fans out to N carrier calls per request; a retry mid-fanout used
        // to double-generate every warehouse. Same fail-closed policy as
        // /manual-label above. SplitAbortException must be handled INSIDE
        // the wrapped supplier so the mapped 422 response is what gets
        // cached (not thrown past IdempotencyService).
        String callerId = userDetails != null ? "user:" + userDetails.getUsername() : null;
        return idempotency.executeOrReplay(callerId, idempotencyKey,
                new com.fasterxml.jackson.core.type.TypeReference<ApiResponse<com.multiship.backend.dto.MultiWarehouseLabelResponse>>() {},
                () -> {
                    try {
                        ApiResponse<com.multiship.backend.dto.MultiWarehouseLabelResponse> response =
                                multiWarehouseLabelService.generate(request, userDetails);
                        return ResponseEntity.status(response.getCode()).body(response);
                    } catch (com.multiship.backend.service.shipment.SplitAbortException ex) {
                        // The service throws when any child fails so @Transactional
                        // rolls back everything. Map to a 422 with the offending
                        // warehouse + detail so the operator can see exactly which
                        // one aborted.
                        return ResponseEntity.status(org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT).body(
                                ApiResponse.<com.multiship.backend.dto.MultiWarehouseLabelResponse>builder()
                                        .status("error")
                                        .code(org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT.value())
                                        .errorCode(com.multiship.backend.dto.ErrorCode.CARRIER_FAILURE.name())
                                        .message("Split aborted at warehouse " + ex.getWarehouseCode()
                                                + ": " + ex.getDetail() + ". No labels were bought.")
                                        .build());
                    }
                }, true);
    }

    @Operation(summary = "Preview a multi-warehouse split without generating labels (Sprint 47)",
            description = "Dry-run for /multi-warehouse-label. Runs the G3 nearest-warehouse selector on every " +
                    "line missing a warehouseCode and returns the resulting split plan: how many child shipments " +
                    "would be generated, per-line assignment trace (EXPLICIT / AUTO / NONE), and a rollup grouped " +
                    "by warehouse. No labels bought, no rows written.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Preview generated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error — missing clientCode / lines")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @PostMapping("/multi-warehouse-preview")
    public ResponseEntity<ApiResponse<com.multiship.backend.dto.MultiWarehousePreviewResponse>> previewMultiWarehouseSplit(
            @jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody com.multiship.backend.dto.MultiWarehouseLabelRequest request) {
        ApiResponse<com.multiship.backend.dto.MultiWarehousePreviewResponse> response =
                multiWarehousePreviewService.preview(request);
        return ResponseEntity.status(response.getCode()).body(response);
    }

    /** Preview which account (scenario) each order will use at generation time. */
    @Operation(summary = "Preview account resolution for a batch of orders",
            description = "Returns the cascade result per order: scenario ORDER / REFERENCE / NEEDS_DETAILS / DEFAULT / NO_DEFAULT plus the account it would use.")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @PostMapping("/resolve-accounts")
    public ResponseEntity<ApiResponse<List<OrderAccountResolutionDTO>>> resolveOrderAccounts(
            @org.springframework.web.bind.annotation.RequestBody List<Integer> orderNos) {
        ApiResponse<List<OrderAccountResolutionDTO>> response = carrierService.resolveOrderAccounts(orderNos);
        return ResponseEntity.status(response.getCode()).body(response);
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getDashboardStats() {
        ApiResponse<Map<String, Object>> response = orderService.getDashboardStats();
        return ResponseEntity.status(response.getCode()).body(response);
    }


    /**
     * After generation, the label must be branded by the account that was
     * actually used (recorded on the tracking row) — not by a fresh cascade
     * resolution, which can differ when the shipper picked manually or the
     * client default changed since. Returns null when not applicable.
     */
    private OrderAccountResolutionDTO asShippedResolution(Integer orderNo) {
        // Return the account the order was billed to whenever one is stored on
        // the tracking row — including FAILED orders (the account is captured
        // before the carrier call). Without this, a failed order shows "no
        // account resolved" and the fix form can't load its service/package
        // catalogs, which key off the resolved account.
        return orderTrackingRepository.findByOrderNo(orderNo)
                .map(com.multiship.backend.model.OrderTracking::getAccountNumber)
                .filter(org.springframework.util.StringUtils::hasText)
                .flatMap(accountNumber -> carrierAccountRefRepository
                        .findFirstByAccountNumberIgnoreCaseOrderByUpdatedAtDesc(accountNumber.trim())
                        .map(ref -> OrderAccountResolutionDTO.builder()
                                .orderNo(orderNo)
                                .scenario("AS_SHIPPED")
                                .carrierCode(ref.getCarrierCode())
                                .accountNumber(ref.getAccountNumber())
                                .accountName(ref.getAccountName())
                                .environment(ref.getEnvironment())
                                .build()))
                .orElse(null);
    }

    /**
     * Admin diagnostic — the DB state that matters for multi-package
     * label rendering, in one JSON response. Returns:
     *
     * <ul>
     *   <li>{@code order} — Order.packageCount, packagesJson presence
     *       (V33), source, order status, tracking's isLabelGenerated</li>
     *   <li>{@code tracking} — OrderTracking.labelFilePath presence +
     *       detected format via magic-byte sniff</li>
     *   <li>{@code labelPackages} — per label_package row: seq, tracking,
     *       hasLabelFilePath, detected format, and the actual
     *       {@link com.multiship.backend.service.LabelArtifactResolver}
     *       verdict for both ZPL and PDF (exactly what the composite
     *       endpoint would see at render time)</li>
     *   <li>{@code derived} — effectivePkgCount, missing sequences,
     *       unresolvable sequences, and a matrixVerdict label matching
     *       the diagnosis matrix in project_label_preview_audit.md</li>
     * </ul>
     *
     * <p>Answers "why is order N still showing only 1 label" without
     * shell access to Postgres. ADMIN-only — exposes raw storage state.
     */
    @Operation(summary = "Admin: dump the label-rendering state for one order",
            description = "Diagnostic snapshot of label_batch + order_label_tracking + "
                    + "label_package rows for a single order, plus the LabelArtifactResolver "
                    + "verdict for each package. ADMIN-only — reveals raw storage details.")
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/{orderNo}/label-state")
    public ResponseEntity<ApiResponse<com.multiship.backend.dto.LabelStateDTO>> getLabelState(
            @PathVariable Integer orderNo) {
        com.multiship.backend.model.Order order =
                orderRepositoryForDiag.findByOrderNo(orderNo).orElse(null);
        if (order == null) {
            return ResponseEntity.status(404).body(
                    ApiResponse.<com.multiship.backend.dto.LabelStateDTO>builder()
                            .status("error").code(404)
                            .message("Order " + orderNo + " was not found.")
                            .errorCode(com.multiship.backend.dto.ErrorCode.ORDER_NOT_FOUND.name())
                            .timestamp(java.time.LocalDateTime.now())
                            .build());
        }

        com.multiship.backend.model.OrderTracking tracking =
                orderTrackingRepository.findByOrderNo(orderNo).orElse(null);
        java.util.List<com.multiship.backend.model.LabelPackage> packages =
                labelPackageRepositoryForDiag.findByOrderNoOrderBySequenceNumberAsc(orderNo);

        // ── OrderState ─────────────────────────────────────────────────
        String packagesJson = order.getPackagesJson();
        com.multiship.backend.dto.LabelStateDTO.OrderState orderState =
                com.multiship.backend.dto.LabelStateDTO.OrderState.builder()
                        .packageCount(order.getPackageCount())
                        .hasPackagesJson(packagesJson != null && !packagesJson.isBlank())
                        .packagesJsonLength(packagesJson == null ? 0 : packagesJson.length())
                        .source(order.getSource())
                        .orderStatus(order.getOrderStatus())
                        .isLabelGenerated(tracking != null && Boolean.TRUE.equals(tracking.getIsLabelGenerated()))
                        .build();

        // ── TrackingState ──────────────────────────────────────────────
        com.multiship.backend.dto.LabelStateDTO.TrackingState trackingState = null;
        if (tracking != null) {
            String stored = tracking.getLabelFilePath();
            trackingState = com.multiship.backend.dto.LabelStateDTO.TrackingState.builder()
                    .status(tracking.getStatus())
                    .trackingNumber(tracking.getTrackingNumber())
                    .hasLabelFilePath(stored != null && !stored.isBlank())
                    .labelFilePathLength(stored == null ? 0 : stored.length())
                    .detectedFormat(sniffStoredArtifactFormat(stored))
                    .build();
        }

        // ── PackageState per label_package row + resolver verdict ─────
        java.util.List<com.multiship.backend.dto.LabelStateDTO.PackageState> pkgStates =
                new java.util.ArrayList<>(packages.size());
        java.util.List<Integer> unresolvableZpl = new java.util.ArrayList<>();
        for (com.multiship.backend.model.LabelPackage p : packages) {
            String stored = p.getLabelFilePath();
            String detected = sniffStoredArtifactFormat(stored);
            String zplVerdict = resolverVerdict(orderNo, p.getSequenceNumber(), "ZPL");
            String pdfVerdict = resolverVerdict(orderNo, p.getSequenceNumber(), "PDF");
            String zebrashVerdict = zebrashRenderProbe(orderNo, p.getSequenceNumber(), zplVerdict);
            if (!"PRESENT".equals(zplVerdict)) unresolvableZpl.add(p.getSequenceNumber());
            pkgStates.add(com.multiship.backend.dto.LabelStateDTO.PackageState.builder()
                    .sequenceNumber(p.getSequenceNumber())
                    .trackingNumber(p.getTrackingNumber())
                    .hasLabelFilePath(stored != null && !stored.isBlank())
                    .labelFilePathLength(stored == null ? 0 : stored.length())
                    .detectedFormat(detected)
                    .resolverOutcomeZpl(zplVerdict)
                    .resolverOutcomePdf(pdfVerdict)
                    .zebrashOutcomeZpl(zebrashVerdict)
                    .build());
        }

        // ── Derived: effectivePkgCount + missing sequences + verdict ──
        int rowCount = packages.size();
        int fromCount = order.getPackageCount() == null ? 0 : order.getPackageCount();
        int effective = Math.max(1, Math.max(rowCount, fromCount));
        java.util.Set<Integer> presentSeqs = new java.util.HashSet<>();
        for (com.multiship.backend.model.LabelPackage p : packages) {
            if (p.getSequenceNumber() != null) presentSeqs.add(p.getSequenceNumber());
        }
        java.util.List<Integer> missing = new java.util.ArrayList<>();
        for (int i = 1; i <= effective; i++) if (!presentSeqs.contains(i)) missing.add(i);

        String verdict = classifyMatrixState(effective, rowCount, unresolvableZpl.isEmpty());

        com.multiship.backend.dto.LabelStateDTO.Derived derived =
                com.multiship.backend.dto.LabelStateDTO.Derived.builder()
                        .effectivePkgCount(effective)
                        .labelPackageRowCount(rowCount)
                        .missingSequences(missing)
                        .unresolvableZplSequences(unresolvableZpl)
                        .matrixVerdict(verdict)
                        .build();

        com.multiship.backend.dto.LabelStateDTO body =
                com.multiship.backend.dto.LabelStateDTO.builder()
                        .orderNo(orderNo)
                        .order(orderState)
                        .tracking(trackingState)
                        .labelPackages(pkgStates)
                        .derived(derived)
                        .build();

        return ResponseEntity.ok(ApiResponse.<com.multiship.backend.dto.LabelStateDTO>builder()
                .status("success").code(200)
                .message("Label-state snapshot for order " + orderNo)
                .data(body)
                .timestamp(java.time.LocalDateTime.now())
                .build());
    }

    /**
     * Cheap format sniff on a stored {@code label_file_path} value. Does
     * NOT fetch URLs (that would slow the diagnostic to carrier-latency);
     * URL detection is purely by prefix. Returns:
     * NONE | URL | ZPL | PDF | BASE64_UNKNOWN.
     */
    private static String sniffStoredArtifactFormat(String stored) {
        if (stored == null || stored.isBlank()) return "NONE";
        String trimmed = stored.trim();
        String lower = trimmed.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) return "URL";
        if (trimmed.startsWith("^XA")) return "ZPL";
        // Base64 sniff: decode first ~12 bytes to check magic.
        try {
            byte[] head = java.util.Base64.getDecoder().decode(
                    trimmed.length() > 16 ? trimmed.substring(0, 16) : trimmed);
            if (head.length >= 3 && head[0] == '^' && head[1] == 'X' && head[2] == 'A') return "ZPL";
            if (head.length >= 5 && head[0] == '%' && head[1] == 'P' && head[2] == 'D'
                    && head[3] == 'F' && head[4] == '-') return "PDF";
            return "BASE64_UNKNOWN";
        } catch (IllegalArgumentException notBase64) {
            // Fall through — plain text that isn't ZPL/PDF.
            return "BASE64_UNKNOWN";
        }
    }

    /**
     * Runs the actual {@link com.multiship.backend.service.LabelArtifactResolver}
     * for one pkg + format and reports what it returned. This is EXACTLY
     * what the composite endpoint sees at render time — no reinterpretation.
     * Returns PRESENT when bytes are returned, or one of EMPTY_STORED |
     * UNRESOLVABLE_FORMAT | FETCH_FAILED when Optional.empty().
     */
    private String resolverVerdict(Integer orderNo, Integer seq, String format) {
        java.util.Optional<byte[]> out = labelArtifactResolver.resolveAsBytes(orderNo, format, seq);
        if (out.isPresent()) return "PRESENT";
        // We can't cheaply distinguish EMPTY_STORED / UNRESOLVABLE_FORMAT /
        // FETCH_FAILED from Optional.empty alone — the resolver collapses all
        // three to empty. The per-package PackageState.detectedFormat above
        // gives the discriminating hint (NONE / URL / mismatched-format).
        return "EMPTY";
    }

    /**
     * PR #551 — actually invokes the zebrash renderer on the pkg's ZPL
     * bytes and reports whether it produced a valid PNG. Discriminates
     * between "bytes are resolvable" (what {@link #resolverVerdict}
     * reports) and "the FE's `<img src=…>` will actually see a PNG".
     * FedEx sandbox ZPL uses malformed `^CF,0,0,0` that zebrash may
     * reject — this is often why the FE falls back to the JSX facsimile
     * even for STATE_1_OR_2_OK orders (900017 exhibited exactly this).
     *
     * <p>Skipped when the bytes are absent (no point rendering nothing);
     * we don't want to double-count EMPTY as a render failure.
     */
    private String zebrashRenderProbe(Integer orderNo, Integer seq, String zplVerdict) {
        if (!"PRESENT".equals(zplVerdict)) return "SKIPPED";
        try {
            byte[] zpl = labelArtifactResolver.resolveAsBytes(orderNo, "ZPL", seq).orElse(null);
            if (zpl == null || zpl.length == 0) return "SKIPPED";
            byte[] png = zebrashRenderer.renderPng(zpl);
            return png != null && png.length > 0 ? "RENDERABLE" : "RENDER_FAILED";
        } catch (RuntimeException ex) {
            return "RENDER_FAILED";
        }
    }

    /**
     * Places the order into one of the matrix rows documented in
     * project_label_preview_audit.md (states 1-6). Used by ops to skip
     * reading the raw fields and go straight to a remediation.
     */
    private static String classifyMatrixState(int effectivePkgCount, int rowCount, boolean allResolvable) {
        if (effectivePkgCount <= 1) return "STATE_6_SINGLE_PKG_NO_BUG";
        if (rowCount == 0) return "STATE_5_NO_ROWS_FACSIMILE_ONLY";
        if (rowCount < effectivePkgCount) return "STATE_4_MISSING_ROWS";
        // rowCount >= effective at this point.
        if (allResolvable) return "STATE_1_OR_2_OK";
        return "STATE_3_ROW_PRESENT_BUT_BYTES_UNRESOLVABLE";
    }

    // ===== VALIDATION HELPERS =====

    private boolean isValidSortBy(String sortBy) {
        return sortBy != null && (
                sortBy.equals("orderNo") ||
                        sortBy.equals("customer") ||
                        sortBy.equals("generatedAt") ||
                        sortBy.equals("tracking") ||
                        sortBy.equals("city") ||
                        sortBy.equals("weight") ||
                        sortBy.equals("status") ||
                        sortBy.equals("createdDate")
        );
    }

    private boolean isValidSortDirection(String sortDirection) {
        return sortDirection != null && (
                sortDirection.equalsIgnoreCase("ASC") ||
                        sortDirection.equalsIgnoreCase("DESC")
        );
    }
}
