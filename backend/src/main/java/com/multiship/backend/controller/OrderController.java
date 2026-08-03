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
@CrossOrigin(origins = "*")
public class OrderController {

    @Autowired
    private OrderService orderService;

    @Autowired
    private CarrierService carrierService;

    @Autowired
    private com.multiship.backend.service.ZplLabelService zplLabelService;

    @Autowired
    private CarrierProperties carrierProperties;

    @Autowired
    private com.multiship.backend.repository.OrderTrackingRepository orderTrackingRepository;

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

    @Autowired
    private com.multiship.backend.service.shipment.MultiWarehouseLabelService multiWarehouseLabelService;

    @Autowired
    private com.multiship.backend.service.shipment.MultiWarehousePreviewService multiWarehousePreviewService;

    /** Map a client Address value object into the label payload shape. */
    private Map<String, Object> addressMap(com.multiship.backend.model.Address a, String fallbackName) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", a.getName() != null && !a.getName().isBlank() ? a.getName() : fallbackName);
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
    @PreAuthorize("hasAnyRole('ADMIN', 'USER') or @orderAccess.canViewTenant(authentication, #tenantId)")
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponseDTO<OrderResponseDTO>>> listOrders(
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size, capped at 100") @RequestParam(defaultValue = "20") int size,
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
            @Parameter(description = "Attach the cascade's account pick (accountResolution) to each row") @RequestParam(defaultValue = "false") boolean includeResolution) {

        if (!isValidSortBy(sortBy)) {
            sortBy = "orderNo";
        }
        if (!isValidSortDirection(sortDirection)) {
            sortDirection = "ASC";
        }

        PaginationRequestDTO paginationRequest = PaginationRequestDTO.builder()
                .page(page)
                .size(size)
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
        if (client != null && client.getShipFrom() != null && client.getShipFrom().hasValue()) {
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
        com.multiship.backend.model.ShippingService ruledService = shippingConfigService
                .resolveService(
                        com.multiship.backend.service.ShippingConfigService.canonicalCarrierFor(order.getShipviaCd()),
                        clientCode, order.getShipviaCd(), shipToCountry, serviceIntl, shipperCountry)
                .orElse(null);
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
    public ResponseEntity<String> getLabelZpl(@PathVariable Integer orderNo) {
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

        String zpl = zplLabelService.buildLabel(orderResponse.getData(), resolution, labelDetails);

        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=label-" + orderNo + ".zpl")
                .body(zpl);
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
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "502", description = "errorCode CARRIER_FAILURE — the upstream carrier rejected the request")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @PostMapping("/{orderNo}/label")
    public ResponseEntity<ApiResponse<com.multiship.backend.dto.LabelGenerationResponse>> generateLabel(
            @PathVariable Long orderNo,
            @Parameter(description = "Client-chosen key making retries safe: the same key returns the existing label as a success instead of a 409.")
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @org.springframework.web.bind.annotation.RequestBody(required = false) com.multiship.backend.dto.GenerateLabelRequest request,
            @org.springframework.security.core.annotation.AuthenticationPrincipal org.springframework.security.core.userdetails.UserDetails userDetails) {
        Long accountId = request != null ? request.getAccountId() : null;
        ApiResponse<com.multiship.backend.dto.LabelGenerationResponse> response =
                carrierService.generateLabel(orderNo, userDetails, idempotencyKey, accountId);
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
            @org.springframework.web.bind.annotation.RequestBody com.multiship.backend.dto.ManualShipmentRequest request,
            @org.springframework.security.core.annotation.AuthenticationPrincipal org.springframework.security.core.userdetails.UserDetails userDetails) {
        ApiResponse<com.multiship.backend.dto.LabelGenerationResponse> response =
                carrierService.generateManualLabel(request, userDetails);
        return ResponseEntity.status(response.getCode()).body(response);
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
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @PostMapping("/multi-warehouse-label")
    public ResponseEntity<ApiResponse<com.multiship.backend.dto.MultiWarehouseLabelResponse>> generateMultiWarehouseLabel(
            @org.springframework.web.bind.annotation.RequestBody com.multiship.backend.dto.MultiWarehouseLabelRequest request,
            @org.springframework.security.core.annotation.AuthenticationPrincipal org.springframework.security.core.userdetails.UserDetails userDetails) {
        try {
            ApiResponse<com.multiship.backend.dto.MultiWarehouseLabelResponse> response =
                    multiWarehouseLabelService.generate(request, userDetails);
            return ResponseEntity.status(response.getCode()).body(response);
        } catch (com.multiship.backend.service.shipment.SplitAbortException ex) {
            // The service throws when any child fails so @Transactional rolls
            // back everything. Map to a 422 with the offending warehouse +
            // detail so the operator can see exactly which one aborted.
            return ResponseEntity.status(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY).body(
                    ApiResponse.<com.multiship.backend.dto.MultiWarehouseLabelResponse>builder()
                            .status("error")
                            .code(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY.value())
                            .errorCode(com.multiship.backend.dto.ErrorCode.CARRIER_FAILURE.name())
                            .message("Split aborted at warehouse " + ex.getWarehouseCode()
                                    + ": " + ex.getDetail() + ". No labels were bought.")
                            .build());
        }
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
            @org.springframework.web.bind.annotation.RequestBody com.multiship.backend.dto.MultiWarehouseLabelRequest request) {
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
        return orderTrackingRepository.findByOrderNo(orderNo)
                .filter(tracking -> Boolean.TRUE.equals(tracking.getIsLabelGenerated()))
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
