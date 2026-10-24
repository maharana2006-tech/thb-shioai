package com.multiship.backend.controller;

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

    // Sprint 52 PR B — passthrough for carrier's real label bytes when
    // the stored artifact matches the requested format. See
    // LabelArtifactResolver javadoc.
    @Autowired
    private com.multiship.backend.service.LabelArtifactResolver labelArtifactResolver;

    // PR #536 — carrier ZPL → PNG/PDF via bundled zebrash-cli. Feature-
    // flagged by label.render-carrier-zpl.
    @Autowired
    private com.multiship.backend.service.ZebrashRenderer zebrashRenderer;
    // Phase 3 (Shipment & Label re-architecture) — the printable label for the
    // print and document-bundle flows comes from the same pipeline as the
    // /api/v1/labels resource: stored ZPL → one renderer. The old in-controller
    // /label/{zpl,pdf,preview.png} endpoints and their helpers are gone.
    @Autowired
    private com.multiship.backend.service.label.LabelPrintableService labelPrintable;

    // PR #552 — concat carrier per-piece PDFs into one multi-page file
    // when the operator asks for the "all pkgs" PDF and the carrier
    // returned PDF format (not ZPL) per piece.
    @Autowired
    private com.multiship.backend.service.PdfMerger pdfMerger;


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

    /** Parses the FedEx MaxiCode payload embedded in the returned ZPL so
     *  the label preview can surface fields FedEx accepted but did not
     *  render as visible text on the thermal label (recipient line 2,
     *  phone, EEI statement, commodity, etc). */
    @Autowired
    private com.multiship.backend.service.carriers.parse.FedExMaxiCodeParser fedExMaxiCodeParser;

    @Autowired
    private com.multiship.backend.service.PackingSlipService packingSlipService;

    // Sprint 51 — the platform's own commercial-invoice PDF: an
    // always-available operator document for international orders.
    @Autowired
    private com.multiship.backend.service.CommercialInvoiceService commercialInvoiceService;

    // PR-Printer-R7c — resolves per (client, carrier) invoice copies.
    // Optional so the dispatch keeps working if the service isn't
    // wired (defensive; production always has it).
    /** Remembers what was printed (Bulk Mailer shows it). Optional for hand-built tests. */
    @Autowired(required = false)
    private com.multiship.backend.service.printing.DocumentPrintLog documentPrintLog;

    @Autowired(required = false)
    private com.multiship.backend.service.InvoiceCopiesService invoiceCopiesService;

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
            @Parameter(description = "PENDING | GENERATED | ERROR | VOIDED") @RequestParam(required = false) String status,
            @Parameter(description = "Scope to one tenant (required for TENANT role)") @RequestParam(required = false) String tenantId,
            @Parameter(description = "Matches order #, batch #, city, customer code, customer_ref, or tracking number as substrings. "
                    + "Prefix syntax (2026-09-15) narrows to one field: batch:14 (exact), order:900044, tracking:1Z, client:ARHDEV. "
                    + "Explicit column filters win over prefixes.") @RequestParam(required = false) String search,
            @Parameter(description = "READY | NEEDS_DETAILS | BLOCKED — filters PENDING orders by the account-resolution cascade, computed server-side") @RequestParam(required = false) String resolution,
            @Parameter(description = "Column filter: client code contains") @RequestParam(required = false) String customer,
            @Parameter(description = "Column filter: destination city or state contains") @RequestParam(required = false) String city,
            @Parameter(description = "Column filter: order # contains") @RequestParam(required = false) String orderNo,
            @Parameter(description = "Column filter: tracking number contains") @RequestParam(required = false) String tracking,
            @Parameter(description = "Column filter: exact label-batch id (2026-09-14). Non-digit chars stripped server-side.") @RequestParam(required = false) String batch,
            @Parameter(description = "Created on or after (yyyy-MM-dd)") @RequestParam(required = false) String createdFrom,
            @Parameter(description = "Created on or before (yyyy-MM-dd)") @RequestParam(required = false) String createdTo,
            @Parameter(description = "Order source: MANUAL | BULK | API | WMS | ERP") @RequestParam(required = false) String source,
            @Parameter(description = "Shipping channel: D2C | B2B") @RequestParam(required = false) String channel,
            @Parameter(description = "Carrier: UPS | FEDEX | USPS | DHL") @RequestParam(required = false) String carrier,
            @Parameter(description = "Returns F3 — Y = returns only, N = shipments only, blank = all")
            @RequestParam(required = false) String isReturn,
            @Parameter(description = "BUG-001 — intl tri-state: Y = international only, N = domestic only, blank = all")
            @RequestParam(required = false) String intl,
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
                .batchId(batch)
                .createdFrom(createdFrom)
                .createdTo(createdTo)
                .source(source)
                .channel(channel)
                .carrier(carrier)
                .isReturn(isReturn)
                .intl(intl)
                .build();

        ApiResponse<PageResponseDTO<OrderResponseDTO>> response =
                orderService.listOrders(filters, includeResolution, paginationRequest);
        return ResponseEntity.status(response.getCode()).body(response);
    }

    /**
     * Select-all-filtered (2026-09-13) — return only the order numbers
     * matching the same filter set the main list uses. Powers the
     * Orders workspace's "Select all M matching the filter" affordance
     * without shuttling 10k full-row payloads through the paginated
     * list endpoint.
     */
    @Operation(summary = "Order numbers only (for select-all-filtered)",
            description = "Same filter surface as GET /orders, but returns just order_no[]. No pagination; caller controls its own hard cap.")
    @PreAuthorize("(hasAnyRole('ADMIN', 'USER') and @accessScope.canAccessTenant(authentication, #tenantId)) or @orderAccess.canViewTenant(authentication, #tenantId)")
    @GetMapping("/ids")
    public ResponseEntity<ApiResponse<java.util.List<Integer>>> listOrderIds(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String resolution,
            @RequestParam(required = false) String customer,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String orderNo,
            @RequestParam(required = false) String tracking,
            @RequestParam(required = false) String batch,
            @RequestParam(required = false) String createdFrom,
            @RequestParam(required = false) String createdTo,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) String carrier,
            @RequestParam(required = false) String isReturn) {

        OrderListFilters filters = OrderListFilters.builder()
                .status(status)
                .tenantId(tenantId)
                .search(search)
                .resolution(resolution)
                .customer(customer)
                .city(city)
                .orderNo(orderNo)
                .tracking(tracking)
                .batchId(batch)
                .createdFrom(createdFrom)
                .createdTo(createdTo)
                .source(source)
                .channel(channel)
                .carrier(carrier)
                .isReturn(isReturn)
                .build();

        ApiResponse<java.util.List<Integer>> response = orderService.listOrderNos(filters);
        return ResponseEntity.status(response.getCode()).body(response);
    }

    /**
     * Batch-filter dropdown (2026-09-14 operator ask). Returns distinct
     * label_batch.batch_id values (with counts) matching the current
     * filter surface, so the FE Batch picker shows only batches the
     * operator can see under the active date/client/status/... filters.
     */
    @Operation(summary = "Distinct batches for the filter dropdown",
            description = "Same filter surface as GET /orders; response is [{batchId, count}] sorted newest-first.")
    @PreAuthorize("(hasAnyRole('ADMIN', 'USER') and @accessScope.canAccessTenant(authentication, #tenantId)) or @orderAccess.canViewTenant(authentication, #tenantId)")
    @GetMapping("/batches")
    public ResponseEntity<ApiResponse<java.util.List<java.util.Map<String, Object>>>> listBatches(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String resolution,
            @RequestParam(required = false) String customer,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String orderNo,
            @RequestParam(required = false) String tracking,
            @RequestParam(required = false) String createdFrom,
            @RequestParam(required = false) String createdTo,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) String carrier,
            @RequestParam(required = false) String isReturn) {

        // NOTE: no batch param here on purpose — this endpoint POPULATES
        // the batch dropdown, so filtering by batch would collapse it to
        // one entry. Everything else composes normally.
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
                .carrier(carrier)
                .isReturn(isReturn)
                .build();

        ApiResponse<java.util.List<java.util.Map<String, Object>>> response = orderService.listBatches(filters);
        return ResponseEntity.status(response.getCode()).body(response);
    }

    /**
     * Tab counts for the Labels work queue: ready, needsDetails, chooseAccount,
     * clientMissing, failed, generated.
     *
     * <p><b>Scope is intentionally global-per-tenant.</b> This endpoint does
     * NOT accept a batch, client, date, or keyword filter and never should —
     * the tab bar answers "what work needs my attention right now across my
     * whole queue?" not "what's in the current search view?". Adding filter
     * params would:
     * <ul>
     *   <li>Change the semantic operators expect (Ready: 5 when there are
     *       actually 500 ready orders elsewhere would be misleading).</li>
     *   <li>Collapse the {@code queueStatsCache} hit rate (60s Caffeine
     *       keyed per-tenant, tuned for dashboard polls per Sprint 51 BP-M8) —
     *       adding filter dims to the cache key defeats the point.</li>
     *   <li>Diverge from {@code DashboardService} which consumes the same
     *       counts and clearly wants tenant-wide totals.</li>
     * </ul>
     *
     * <p>If a batch-scoped roll-up is ever needed, add a NEW endpoint like
     * {@code /orders/batches/{id}/stats} rather than parameterising this one —
     * it keeps the caches independent and the semantic explicit.
     *
     * <p>Considered and deliberately rejected as part of the batch column
     * filter feature (2026-09-14). See PR #641 / #642 discussion.
     */
    @Operation(summary = "Work-queue counts",
            description = "One aggregate pass: {ready, needsDetails, blocked, failed, generated}. Scope is always tenant-wide — see the javadoc for why batch/other filters are NOT accepted.")
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

    /**
     * V76 — edit the internal per-order ops note post-creation.
     * ADMIN/USER can PATCH; TENANT scoped by the same order-access
     * check as GET (owns the row → can edit its own note). 500-char
     * cap enforced at the DB column + here to fail fast with a
     * friendly message. Sending null / blank clears the note.
     */
    @PreAuthorize("@orderAccess.canViewOrder(authentication, #orderNo)")
    @org.springframework.web.bind.annotation.PatchMapping("/{orderNo}/note")
    public ResponseEntity<ApiResponse<OrderResponseDTO>> updateOrderNote(
            @PathVariable Integer orderNo,
            @RequestBody java.util.Map<String, String> body) {
        String raw = body == null ? null : body.get("note");
        String note;
        if (raw == null) {
            note = null;
        } else {
            String trimmed = raw.trim();
            note = trimmed.isEmpty() ? null : trimmed;
            if (note != null && note.length() > 500) {
                return ResponseEntity.badRequest().body(ApiResponse.<OrderResponseDTO>builder()
                        .status("ERROR").code(400).timestamp(java.time.LocalDateTime.now())
                        .message("Note must be at most 500 characters.")
                        .errorCode("BAD_REQUEST").build());
            }
        }
        ApiResponse<OrderResponseDTO> response = orderService.updateOrderNote(orderNo, note);
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
    public ResponseEntity<byte[]> getPackingSlip(
            @PathVariable Integer orderNo,
            // BUG-109 — Settings > Templates > Preview passes templateId so
            // an admin previews THIS template on the order's data, not
            // whatever the order's own client resolves to. Blank/absent =
            // per-order resolution (regular order-view path).
            @RequestParam(value = "templateId", required = false) Long templateId) {
        try {
            byte[] pdf = packingSlipService.renderWithTemplate(orderNo, templateId);
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
    /**
     * Shipment document set as ONE file: the commercial invoice (all pages,
     * including the per-piece annex) followed by the main 4x6 label of
     * every package. What an operator hands over with a multi-piece
     * international parcel, downloadable in one click instead of N + 1.
     * 422 when the order has no customs data; labels are best-effort —
     * if none can be resolved the invoice alone is returned.
     */
    @PreAuthorize("@orderAccess.canViewOrder(authentication, #orderNo)")
    @GetMapping(value = "/{orderNo}/shipment-documents",
            produces = org.springframework.http.MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> getShipmentDocuments(@PathVariable Integer orderNo) {
        byte[] invoice = null;
        try {
            invoice = commercialInvoiceService.render(orderNo);
        } catch (IllegalArgumentException notFound) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.NOT_FOUND).build();
        } catch (IllegalStateException noCustoms) {
            // Domestic (no customs data): the document set is the labels alone.
            invoice = null;
        }
        java.util.List<byte[]> parts = new java.util.ArrayList<>(2);
        if (invoice != null) parts.add(invoice);
        int pkgs = 1;
        try {
            ApiResponse<OrderWithLinesDTO> peek = orderService.getOrderWithLines(orderNo);
            pkgs = effectivePkgCount(peek.getData());
            // Only a GENERATED label belongs in the document set — the
            // an unlabelled order contributes no page (there is no synthetic label).
            boolean labelled = orderTrackingRepository.findByOrderNo(orderNo)
                    .map(t -> Boolean.TRUE.equals(t.getIsLabelGenerated())
                            && t.getTrackingNumber() != null && !t.getTrackingNumber().isBlank())
                    .orElse(false);
            if (labelled) {
                ResponseEntity<byte[]> labels = labelPrintable.pdf(orderNo, null)
                        .map(ResponseEntity::ok)
                        .orElseGet(() -> ResponseEntity.notFound().build());
                if (labels.getStatusCode().is2xxSuccessful() && labels.getBody() != null && labels.getBody().length > 0) {
                    parts.add(labels.getBody());
                }
            }
        } catch (RuntimeException ex) {
            org.slf4j.LoggerFactory.getLogger(OrderController.class).warn(
                    "shipment-documents: labels unavailable for order {} — returning invoice only: {}",
                    orderNo, ex.getMessage());
        }
        if (parts.isEmpty()) {
            // Neither an invoice (domestic) nor a generated label — nothing to hand over.
            return ResponseEntity.status(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY).build();
        }
        byte[] merged = parts.size() == 1 ? parts.get(0) : pdfMerger.mergeToOne(parts);
        String name = (invoice == null ? "labels-" : "shipment-documents-") + orderNo + (pkgs > 1 ? "-all" + pkgs : "") + ".pdf";
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=" + name)
                .header("Content-Type", org.springframework.http.MediaType.APPLICATION_PDF_VALUE)
                .body(merged);
    }

    /** Orders to print together, and which document: LABEL or COMMERCIAL_INVOICE. */
    public record BulkDocumentPrintRequest(java.util.List<Integer> orderNumbers, String docType) {}

    /** Most orders one bulk print may merge into a single PDF. */
    private static final int MAX_BULK_PRINT_ORDERS = 500;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.multiship.backend.config.OrderAccessEvaluator orderAccessEvaluator;

    @Operation(summary = "One merged PDF of labels or commercial invoices for many orders",
            description = "Bulk print from the Orders page. docType LABEL = each order's label (the label "
                    + "page per package, as Print Label sends); COMMERCIAL_INVOICE = each international "
                    + "order's invoice. Orders you can't view, without a generated label, or without customs "
                    + "data are skipped — X-Documents-Included / X-Documents-Skipped / X-Skipped-Orders say "
                    + "which. At most " + MAX_BULK_PRINT_ORDERS + " orders; 400/422 carry the reason in X-Error.")
    @PreAuthorize("isAuthenticated()")
    @PostMapping(value = "/documents/print", produces = org.springframework.http.MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> printDocuments(@org.springframework.web.bind.annotation.RequestBody BulkDocumentPrintRequest request) {
        java.util.List<Integer> orderNos = request == null || request.orderNumbers() == null
                ? java.util.List.of()
                : request.orderNumbers().stream().filter(java.util.Objects::nonNull).distinct().toList();
        String docType = request == null || request.docType() == null
                ? "LABEL" : request.docType().trim().toUpperCase(java.util.Locale.ROOT);
        if (!docType.equals("LABEL") && !docType.equals("COMMERCIAL_INVOICE")) {
            return printRefusal(org.springframework.http.HttpStatus.BAD_REQUEST, "docType must be LABEL or COMMERCIAL_INVOICE.");
        }
        if (orderNos.isEmpty()) {
            return printRefusal(org.springframework.http.HttpStatus.BAD_REQUEST, "Select at least one order to print.");
        }
        if (orderNos.size() > MAX_BULK_PRINT_ORDERS) {
            return printRefusal(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                    "Print at most " + MAX_BULK_PRINT_ORDERS + " orders at a time (" + orderNos.size() + " selected).");
        }
        org.springframework.security.core.Authentication auth =
                org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        java.util.List<byte[]> parts = new java.util.ArrayList<>(orderNos.size());
        java.util.List<Integer> skipped = new java.util.ArrayList<>();
        java.util.List<Integer> included = new java.util.ArrayList<>();
        boolean labels = docType.equals("LABEL");
        for (Integer orderNo : orderNos) {
            // The per-order check the single-document endpoints apply, so a bulk
            // print never reaches an order its caller couldn't print one by one.
            if (orderAccessEvaluator != null && !orderAccessEvaluator.canViewOrder(auth, orderNo)) {
                skipped.add(orderNo);
                continue;
            }
            try {
                if (labels) {
                    boolean labelled = orderTrackingRepository.findByOrderNo(orderNo)
                            .map(t -> Boolean.TRUE.equals(t.getIsLabelGenerated())
                                    && t.getTrackingNumber() != null && !t.getTrackingNumber().isBlank())
                            .orElse(false);
                    ResponseEntity<byte[]> pdf = labelled
                            ? labelPrintable.pdf(orderNo, null)
                                    .map(ResponseEntity::ok)
                                    .orElseGet(() -> ResponseEntity.notFound().build())
                            : null;
                    if (pdf != null && pdf.getStatusCode().is2xxSuccessful() && pdf.getBody() != null && pdf.getBody().length > 0) {
                        parts.add(pdf.getBody());
                        included.add(orderNo);
                    } else {
                        skipped.add(orderNo);
                    }
                } else {
                    parts.add(commercialInvoiceService.render(orderNo));
                    included.add(orderNo);
                }
            } catch (IllegalArgumentException | IllegalStateException notPrintable) {
                // Not found, or no customs data (domestic) — nothing to print for it.
                skipped.add(orderNo);
            } catch (RuntimeException ex) {
                org.slf4j.LoggerFactory.getLogger(OrderController.class)
                        .warn("Bulk print: {} for order {} failed: {}", docType, orderNo, ex.getMessage());
                skipped.add(orderNo);
            }
        }
        String skippedList = skipped.stream().limit(50).map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(","));
        if (parts.isEmpty()) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY)
                    .header("X-Error", labels
                            ? "None of the selected orders has a generated label to print."
                            : "None of the selected orders has a commercial invoice; invoices exist for international shipments only.")
                    .header("X-Documents-Included", "0")
                    .header("X-Documents-Skipped", String.valueOf(skipped.size()))
                    .header("X-Skipped-Orders", skippedList)
                    .build();
        }
        byte[] merged = parts.size() == 1 ? parts.get(0) : pdfMerger.mergeToOne(parts);
        if (documentPrintLog != null) {
            documentPrintLog.record(included, docType, com.multiship.backend.service.printing.DocumentPrintLog.BROWSER,
                    null, auth == null ? null : auth.getName());
        }
        return ResponseEntity.ok()
                .header("Content-Disposition", "inline; filename="
                        + (labels ? "labels-" : "commercial-invoices-") + parts.size() + ".pdf")
                .header("Content-Type", org.springframework.http.MediaType.APPLICATION_PDF_VALUE)
                .header("X-Documents-Included", String.valueOf(parts.size()))
                .header("X-Documents-Skipped", String.valueOf(skipped.size()))
                .header("X-Skipped-Orders", skippedList)
                .body(merged);
    }

    private static ResponseEntity<byte[]> printRefusal(org.springframework.http.HttpStatus status, String reason) {
        return ResponseEntity.status(status).header("X-Error", reason).build();
    }

    /** Orders to send, which document, and optionally one printer for all (else each client's printer). */
    public record SendToPrinterRequest(java.util.List<Integer> orderNumbers, String docType, Long printerId) {}

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.multiship.backend.service.printing.PrinterService printerService;

    @Operation(summary = "Send labels or commercial invoices straight to network printers",
            description = "Each order's document goes to its client's assigned printer (else the default "
                    + "printer), or to printerId for all of them. ZPL printers receive the label ZPL; PDF "
                    + "printers one merged PDF each. The response says what every printer received, which "
                    + "orders have no printer assigned, and which were skipped (no label / no invoice / no access).")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @PostMapping("/documents/send-to-printer")
    public ResponseEntity<ApiResponse<java.util.Map<String, Object>>> sendToPrinter(
            @org.springframework.web.bind.annotation.RequestBody SendToPrinterRequest request) {
        java.util.List<Integer> orderNos = request == null || request.orderNumbers() == null
                ? java.util.List.of()
                : request.orderNumbers().stream().filter(java.util.Objects::nonNull).distinct().toList();
        String docType = request == null || request.docType() == null
                ? "LABEL" : request.docType().trim().toUpperCase(java.util.Locale.ROOT);
        if (printerService == null) {
            return sendRefusal(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "Printing is not available on this server.");
        }
        if (!docType.equals("LABEL") && !docType.equals("COMMERCIAL_INVOICE")) {
            return sendRefusal(org.springframework.http.HttpStatus.BAD_REQUEST, "docType must be LABEL or COMMERCIAL_INVOICE.");
        }
        if (orderNos.isEmpty()) {
            return sendRefusal(org.springframework.http.HttpStatus.BAD_REQUEST, "Select at least one order to print.");
        }
        if (orderNos.size() > MAX_BULK_PRINT_ORDERS) {
            return sendRefusal(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                    "Send at most " + MAX_BULK_PRINT_ORDERS + " orders at a time (" + orderNos.size() + " selected).");
        }
        boolean labels = docType.equals("LABEL");
        com.multiship.backend.model.Printer chosen = null;
        if (request.printerId() != null) {
            try {
                chosen = printerService.get(request.printerId());
            } catch (java.util.NoSuchElementException gone) {
                return sendRefusal(org.springframework.http.HttpStatus.NOT_FOUND, "That printer no longer exists.");
            }
            if (!chosen.isActive()) {
                return sendRefusal(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, chosen.getName() + " is switched off in Printers.");
            }
            if (!labels && "ZPL".equals(chosen.getFormat())) {
                return sendRefusal(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                        chosen.getName() + " is a ZPL label printer; commercial invoices need a PDF printer.");
            }
        }
        org.springframework.security.core.Authentication auth =
                org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        String user = auth == null ? null : auth.getName();

        // Route every order to a printer first.
        java.util.Map<Long, com.multiship.backend.model.Printer> byId = new java.util.LinkedHashMap<>();
        java.util.Map<Long, java.util.List<Integer>> routed = new java.util.LinkedHashMap<>();
        java.util.List<Integer> unassigned = new java.util.ArrayList<>();
        java.util.List<Integer> skipped = new java.util.ArrayList<>();
        java.util.List<Integer> wrongFormat = new java.util.ArrayList<>();
        for (Integer orderNo : orderNos) {
            if (orderAccessEvaluator != null && !orderAccessEvaluator.canViewOrder(auth, orderNo)) {
                skipped.add(orderNo);
                continue;
            }
            String clientCode = printerService.clientCodeOf(orderNo);
            if (clientCode == null && !isLabelled(orderNo)) {
                skipped.add(orderNo); // no such order
                continue;
            }
            com.multiship.backend.model.Printer printer = chosen != null ? chosen
                    : printerService.resolve(clientCode, docType).orElse(null);
            if (printer == null) {
                unassigned.add(orderNo);
                continue;
            }
            if (!labels && "ZPL".equals(printer.getFormat())) {
                wrongFormat.add(orderNo);
                continue;
            }
            byId.putIfAbsent(printer.getId(), printer);
            routed.computeIfAbsent(printer.getId(), k -> new java.util.ArrayList<>()).add(orderNo);
        }

        // Then build one job per printer, in the format that printer takes.
        java.util.List<java.util.Map<String, Object>> results = new java.util.ArrayList<>();
        int sent = 0;
        for (java.util.Map.Entry<Long, java.util.List<Integer>> e : routed.entrySet()) {
            com.multiship.backend.model.Printer printer = byId.get(e.getKey());
            boolean zpl = "ZPL".equals(printer.getFormat());
            java.util.List<byte[]> pdfParts = new java.util.ArrayList<>();
            StringBuilder zplJob = new StringBuilder();
            int documents = 0;
            java.util.List<Integer> onThisPrinter = new java.util.ArrayList<>();
            for (Integer orderNo : e.getValue()) {
                byte[] pdf = null;
                String zplText = null;
                try {
                    if (!labels) {
                        pdf = commercialInvoiceService.render(orderNo);
                    } else if (isLabelled(orderNo)) {
                        if (zpl) {
                            // Verbatim stored ZPL, every package, spooled in order.
                            zplText = labelPrintable.zpl(orderNo).orElse(null);
                        } else {
                            // Same render as the preview and the download.
                            pdf = labelPrintable.pdf(orderNo, null).orElse(null);
                        }
                    }
                } catch (IllegalArgumentException | IllegalStateException notPrintable) {
                    // not found / no customs data — nothing to send for it
                } catch (RuntimeException ex) {
                    org.slf4j.LoggerFactory.getLogger(OrderController.class)
                            .warn("Send to printer: {} for order {} failed: {}", docType, orderNo, ex.getMessage());
                }
                if (zplText != null && !zplText.isBlank()) {
                    zplJob.append(zplText.strip()).append('\n');
                    documents++;
                    onThisPrinter.add(orderNo);
                } else if (pdf != null && pdf.length > 0) {
                    onThisPrinter.add(orderNo);
                    // PR-Printer-R7c — INVOICE branch multiplies by the
                    // configured per (client, carrier) copies rule.
                    // LABEL always prints once. Copies value is bounded
                    // 1..20 at the DB, defensive Math.max(1) below.
                    int copies = !labels && invoiceCopiesService != null
                            ? Math.max(1, invoiceCopiesService.resolveCopiesForOrder(orderNo))
                            : 1;
                    for (int c = 0; c < copies; c++) pdfParts.add(pdf);
                    documents += copies;
                } else {
                    skipped.add(orderNo);
                }
            }
            java.util.Map<String, Object> r = new java.util.LinkedHashMap<>();
            r.put("printerId", printer.getId());
            r.put("printer", printer.getName());
            r.put("format", printer.getFormat());
            r.put("orders", e.getValue().size());
            r.put("documents", documents);
            if (documents == 0) {
                r.put("ok", false);
                r.put("message", "Nothing to print — none of these orders has the document yet.");
            } else {
                byte[] payload = zpl ? zplJob.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)
                        : (pdfParts.size() == 1 ? pdfParts.get(0) : pdfMerger.mergeToOne(pdfParts));
                String what = documents + (labels ? (documents == 1 ? " label" : " labels")
                        : (documents == 1 ? " invoice" : " invoices"));
                try {
                    printerService.send(printer, payload, "Multiship " + what, user);
                    if (documentPrintLog != null) {
                        documentPrintLog.record(onThisPrinter, docType,
                                com.multiship.backend.service.printing.DocumentPrintLog.PRINTER, printer.getName(), user);
                    }
                    r.put("ok", true);
                    r.put("message", "Sent " + what + " (" + printer.getFormat() + ", " + Math.max(1, payload.length / 1024) + " KB).");
                    sent += documents;
                } catch (Exception ex) {
                    if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
                    r.put("ok", false);
                    r.put("message", "Could not reach " + printer.getHost() + ":" + printer.getPort() + " — "
                            + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
                }
            }
            results.add(r);
        }

        java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("docType", docType);
        data.put("sent", sent);
        data.put("printers", results);
        data.put("unassigned", unassigned);
        data.put("skipped", skipped);
        data.put("wrongFormat", wrongFormat);
        long failed = results.stream().filter(r -> !Boolean.TRUE.equals(r.get("ok"))).count();
        StringBuilder msg = new StringBuilder();
        msg.append(sent).append(labels ? (sent == 1 ? " label" : " labels") : (sent == 1 ? " invoice" : " invoices"))
                .append(" sent to ").append(results.size() - failed).append(results.size() - failed == 1 ? " printer" : " printers");
        if (failed > 0) msg.append(" · ").append(failed).append(failed == 1 ? " printer failed" : " printers failed");
        if (!unassigned.isEmpty()) msg.append(" · ").append(unassigned.size()).append(unassigned.size() == 1 ? " order has" : " orders have").append(" no printer assigned");
        if (!wrongFormat.isEmpty()) msg.append(" · ").append(wrongFormat.size()).append(" routed to a ZPL printer, which can't print invoices");
        if (!skipped.isEmpty()) msg.append(" · ").append(skipped.size()).append(" skipped (").append(labels ? "no label" : "no invoice").append(")");
        return ResponseEntity.ok(ApiResponse.<java.util.Map<String, Object>>builder()
                .status(sent > 0 && failed == 0 ? "SUCCESS" : sent > 0 ? "PARTIAL" : "ERROR").code(200)
                .timestamp(java.time.LocalDateTime.now())
                .message(msg.toString())
                .data(data)
                .build());
    }

    private boolean isLabelled(Integer orderNo) {
        return orderTrackingRepository.findByOrderNo(orderNo)
                .map(t -> Boolean.TRUE.equals(t.getIsLabelGenerated())
                        && t.getTrackingNumber() != null && !t.getTrackingNumber().isBlank())
                .orElse(false);
    }

    private static ResponseEntity<ApiResponse<java.util.Map<String, Object>>> sendRefusal(
            org.springframework.http.HttpStatus status, String reason) {
        return ResponseEntity.status(status).body(ApiResponse.<java.util.Map<String, Object>>builder()
                .status("ERROR").code(status.value()).timestamp(java.time.LocalDateTime.now())
                .errorCode(com.multiship.backend.dto.ErrorCode.VALIDATION_ERROR.name())
                .message(reason).build());
    }

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

    @Operation(summary = "The documents table, one page at a time",
            description = "Same rows as /documents, filtered and sorted in the database: q matches the order "
                    + "number, tracking number, recipient, client or city; carrier is matched anywhere in the "
                    + "stored code; status LIVE|VOIDED; invoice YES|NO; from/to on the generation date; sort "
                    + "generated|order|recipient|destination|carrier|billed. Tenant-scoped for client users.")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping("/documents/page")
    public ResponseEntity<ApiResponse<org.springframework.data.domain.Page<
            com.multiship.backend.service.OrderDocumentSummaryService.DocumentRow>>> pageDocuments(
            @org.springframework.web.bind.annotation.RequestParam(required = false) String q,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String carrier,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String status,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String invoice,
            @org.springframework.web.bind.annotation.RequestParam(required = false)
            @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate from,
            @org.springframework.web.bind.annotation.RequestParam(required = false)
            @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate to,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "generated") String sort,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "DESC") String dir,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "0") int page,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "25") int size) {
        org.springframework.data.domain.Page<com.multiship.backend.service.OrderDocumentSummaryService.DocumentRow> result =
                orderDocumentSummaryService.page(new com.multiship.backend.service.OrderDocumentSummaryService.Query(
                        q, carrier, status, invoice, from, to, sort, "ASC".equalsIgnoreCase(dir)), page, size);
        return ResponseEntity.ok(ApiResponse.<org.springframework.data.domain.Page<
                        com.multiship.backend.service.OrderDocumentSummaryService.DocumentRow>>builder()
                .status("SUCCESS").code(200).timestamp(java.time.LocalDateTime.now())
                .message(result.getTotalElements() + " labelled order(s).")
                .data(result)
                .build());
    }

    @Operation(summary = "Counts for the documents filter menu",
            description = "Over the whole scope, not the current filters: total, live, voided, with an invoice, and per carrier.")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping("/documents/facets")
    public ResponseEntity<ApiResponse<com.multiship.backend.service.OrderDocumentSummaryService.Facets>> documentFacets() {
        return ResponseEntity.ok(ApiResponse.<com.multiship.backend.service.OrderDocumentSummaryService.Facets>builder()
                .status("SUCCESS").code(200).timestamp(java.time.LocalDateTime.now())
                .message("ok")
                .data(orderDocumentSummaryService.facets())
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

    @Operation(summary = "Generate labels for a shipment split across warehouses (Sprint 47)",
            description = "Groups the input `lines` by `warehouseCode`, calls the single-shipment " +
                    "label generator once per group, and returns a `ShipmentGroup` id plus one " +
                    "`ChildShipment` per warehouse. Fail-all rollback: any child failure aborts " +
                    "the whole batch — no partial persistence. `POST /shipments` " +
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
        // POST /shipments (ManualShipmentService). SplitAbortException must be handled INSIDE
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
    /**
     * Reconstructs + returns the exact JSON body {@link CarrierConnector
     * #createShipment} would POST for this order. No carrier call is made.
     * Backed by {@link CarrierService#previewCarrierPayload}. Purpose:
     * let operators audit "what did we actually send to FedEx / UPS /
     * DHL / USPS for this order" — including customs commodities, EEI
     * statement, dutiesPayment, dimensions, service — without having to
     * enable wire logging + re-generate the label. Answers the very
     * common "is item info in the payload or not?" question directly.
     */
    @Operation(summary = "Admin: reconstruct the carrier-outbound payload for one order",
            description = "Rebuilds ShipmentRequestDTO from persisted Order + OrderCustoms "
                    + "and returns the connector's exact JSON payload (FedEx /ship/v1 body, "
                    + "UPS ShipConfirm body, etc.) without any carrier hop. Includes customs "
                    + "commodities, EEI statement, duty payment, dimensions, and service type. "
                    + "ADMIN-only.")
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/{orderNo}/carrier-payload-preview")
    public ResponseEntity<ApiResponse<java.util.Map<String, Object>>> getCarrierPayloadPreview(
            @PathVariable Long orderNo) {
        ApiResponse<java.util.Map<String, Object>> response = carrierService.previewCarrierPayload(orderNo);
        return ResponseEntity.status(response.getCode()).body(response);
    }

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
