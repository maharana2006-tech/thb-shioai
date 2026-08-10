package com.multiship.backend.service.external;

import com.multiship.backend.config.ApiKeyPrincipal;
import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.LabelGenerationResponse;
import com.multiship.backend.dto.ManualShipmentRequest;
import com.multiship.backend.dto.external.*;
import com.multiship.backend.model.CarrierAccountRef;
import com.multiship.backend.model.Client;
import com.multiship.backend.model.Order;
import com.multiship.backend.model.OrderTracking;
import com.multiship.backend.model.ShippingService;
import com.multiship.backend.repository.CarrierAccountRefRepository;
import com.multiship.backend.model.Warehouse;
import com.multiship.backend.repository.ClientRepository;
import com.multiship.backend.repository.OrderRepository;
import com.multiship.backend.repository.OrderTrackingRepository;
import com.multiship.backend.repository.ShippingServiceRepository;
import com.multiship.backend.service.CarrierService;
import com.multiship.backend.service.ShippingConfigService;
import com.multiship.backend.service.carriers.CarrierConnector;
import com.multiship.backend.service.intake.ClientCodeTranslationService;
import com.multiship.backend.service.resolution.ShipmentResolutionException;
import com.multiship.backend.service.resolution.ShipmentResolutionService;
import com.multiship.backend.model.OrderRawCodes;
import com.multiship.backend.repository.OrderRawCodesRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Orchestrates the public shipping API on top of the platform's existing label,
 * tracking and resolution machinery. Every operation is scoped to the client the
 * API key was issued for.
 */
@Slf4j
@Service
public class ExternalApiService {

    private final ShippingConfigService shippingConfigService;
    private final ShippingServiceRepository serviceRepository;
    private final CarrierAccountRefRepository carrierAccountRefRepository;
    private final ClientRepository clientRepository;
    private final OrderRepository orderRepository;
    private final OrderTrackingRepository orderTrackingRepository;
    private final CarrierService carrierService;
    private final CarrierProperties carrierProperties;
    private final ShipmentResolutionService resolutionService;
    private final ClientCodeTranslationService translationService;
    private final OrderRawCodesRepository orderRawCodesRepository;
    private final Map<String, CarrierConnector> connectorsByCode;

    /** Sprint 46 — optional so existing tests don't have to plumb another dep. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ExternalWebhookDispatcher webhookDispatcher;

    public ExternalApiService(ShippingConfigService shippingConfigService,
                              ShippingServiceRepository serviceRepository,
                              CarrierAccountRefRepository carrierAccountRefRepository,
                              ClientRepository clientRepository,
                              OrderRepository orderRepository,
                              OrderTrackingRepository orderTrackingRepository,
                              CarrierService carrierService,
                              CarrierProperties carrierProperties,
                              ShipmentResolutionService resolutionService,
                              ClientCodeTranslationService translationService,
                              OrderRawCodesRepository orderRawCodesRepository,
                              List<CarrierConnector> carrierConnectors) {
        this.shippingConfigService = shippingConfigService;
        this.serviceRepository = serviceRepository;
        this.carrierAccountRefRepository = carrierAccountRefRepository;
        this.clientRepository = clientRepository;
        this.orderRepository = orderRepository;
        this.orderTrackingRepository = orderTrackingRepository;
        this.carrierService = carrierService;
        this.carrierProperties = carrierProperties;
        this.resolutionService = resolutionService;
        this.translationService = translationService;
        this.orderRawCodesRepository = orderRawCodesRepository;
        this.connectorsByCode = carrierConnectors.stream()
                .collect(Collectors.toMap(c -> c.getCarrierCode().toUpperCase(Locale.ROOT), Function.identity(), (a, b) -> a));
    }

    // ─────────────────────────── create shipment ───────────────────────────

    public ExternalShipmentResponse createShipment(ApiKeyPrincipal caller, ExternalShipmentRequest req) {
        String clientCode = effectiveClient(caller, req.getClientCode());
        if (req.getShipTo() == null || !StringUtils.hasText(req.getShipTo().getAddressLine1())) {
            throw new ExternalApiException(422, ErrorCode.VALIDATION_ERROR, "shipTo address is required.");
        }
        if (req.getParcel() == null || req.getParcel().getWeight() == null
                || req.getParcel().getWeight().signum() <= 0) {
            throw new ExternalApiException(422, ErrorCode.VALIDATION_ERROR, "parcel.weight (> 0) is required.");
        }

        // Phase 5c — order-intake translation. Snapshot raw ERP codes before
        // we mutate anything, then translate each via the client's alias
        // tables. When a client has zero rows in a given table, translation
        // silently no-ops; when they have some rows but not one matching the
        // incoming code, the translator throws UNKNOWN_* (strict policy).
        String rawShipvia = req.getShipMethod();
        String rawDestCountry = req.getShipTo().getCountryCode();
        String rawPackageCode = req.getParcel().getPackagingCode();

        Long translatedShipviaService;
        Long translatedPackagePreset;
        try {
            translationService.translateDestCountry(clientCode, rawDestCountry)
                    .ifPresent(iso -> req.getShipTo().setCountryCode(iso));
            translatedShipviaService = translationService.translateShipvia(clientCode, rawShipvia).orElse(null);
            translatedPackagePreset = translationService.translatePackage(clientCode, rawPackageCode).orElse(null);
        } catch (ShipmentResolutionException e) {
            throw toExternal(e);
        }

        // 3PL guardrail #1: destination allowed for this client?
        try {
            resolutionService.assertShipToAllowed(clientCode, req.getShipTo().getCountryCode());
        } catch (ShipmentResolutionException e) {
            throw toExternal(e);
        }

        // Ship-from: warehouse (resolved via resolver) > request override > client's
        // legacy shipFrom. The resolver throws when a supplied warehouseCode isn't
        // attached to the client — surface that as a 422/403 immediately.
        Warehouse resolvedWarehouse = null;
        try {
            resolvedWarehouse = resolutionService.resolveWarehouse(clientCode, req.getWarehouseCode()).orElse(null);
        } catch (ShipmentResolutionException e) {
            throw toExternal(e);
        }
        ExternalAddress from = resolvedWarehouse != null
                ? warehouseToAddress(resolvedWarehouse)
                : (req.getShipFrom() != null ? req.getShipFrom() : clientShipFrom(clientCode));

        String destCountry = req.getShipTo().getCountryCode();
        String originCountry = from != null ? from.getCountryCode() : carrierProperties.getShipper().getCountryCode();
        boolean international = StringUtils.hasText(destCountry) && StringUtils.hasText(originCountry)
                && !destCountry.trim().equalsIgnoreCase(originCountry.trim());

        // Carrier + service.
        String carrier;
        Long serviceId = null;
        String serviceCode = null;
        String resolvedVia;
        if (StringUtils.hasText(req.getCarrierCode())) {
            carrier = req.getCarrierCode().trim().toUpperCase(Locale.ROOT);
            resolvedVia = "CARRIER_OVERRIDE";
        } else if (translatedShipviaService != null) {
            // Phase 5c — client has a direct shipvia alias, use it and skip
            // the rule resolver entirely.
            ShippingService svc = serviceRepository.findById(translatedShipviaService)
                    .orElseThrow(() -> new ExternalApiException(422, ErrorCode.UNKNOWN_SHIPVIA_CODE,
                            "Shipvia alias points at a shipping service that no longer exists."));
            carrier = svc.getCarrier();
            serviceId = svc.getId();
            serviceCode = svc.getServiceCode();
            resolvedVia = "SHIPVIA_ALIAS";
        } else {
            Optional<ShippingService> svc = shippingConfigService.resolveRule(clientCode, req.getShipMethod(), destCountry);
            if (svc.isEmpty()) {
                throw new ExternalApiException(422, ErrorCode.VALIDATION_ERROR,
                        "No carrier could be resolved for shipMethod '" + req.getShipMethod()
                        + "'. Configure a ship-method rule, or send carrierCode.");
            }
            carrier = svc.get().getCarrier();
            serviceId = svc.get().getId();
            serviceCode = svc.get().getServiceCode();
            resolvedVia = "SHIPMETHOD_RULE";
        }

        // 3PL guardrail #2: service on the client's allowlist? (Also enforces
        // the G1 warehouse gate — passing the resolved warehouse id.)
        if (serviceId != null) {
            try {
                Long warehouseId = resolvedWarehouse != null ? resolvedWarehouse.getId() : null;
                resolutionService.assertServiceAllowed(clientCode, serviceId, destCountry, warehouseId);
            } catch (ShipmentResolutionException e) {
                throw toExternal(e);
            }
        }

        // Bill-to account.
        Long accountId = null;
        String accountNumber = StringUtils.hasText(req.getAccountNumber()) ? req.getAccountNumber().trim() : null;
        if (accountNumber == null) {
            accountId = clientDefaultAccount(clientCode, carrier)
                    .or(() -> platformAccount(carrier))
                    .map(CarrierAccountRef::getId)
                    .orElseThrow(() -> new ExternalApiException(422, ErrorCode.NO_DEFAULT_ACCOUNT,
                            "No " + carrier + " account is available for client " + clientCode
                            + ". Add a carrier account or send accountNumber."));
        }

        ManualShipmentRequest manual = new ManualShipmentRequest();
        manual.setSender(toManual(from));
        manual.setRecipient(toManual(req.getShipTo()));
        manual.setIsReturn(Boolean.TRUE.equals(req.getIsReturn()));
        manual.setCarrierCode(carrier);
        manual.setServiceId(serviceId);
        manual.setAccountId(accountId);
        manual.setAccountNumber(accountNumber);
        manual.setClientCode(clientCode);
        manual.setSource("API");
        manual.setReference(req.getReference());
        manual.setDeclaredValue(req.getDeclaredValue());
        manual.setCurrency(req.getCurrency());
        manual.setReasonForExport(req.getReasonForExport());
        manual.setIncoterms(req.getIncoterms());

        ExternalParcel p = req.getParcel();
        manual.setWeight(p.getWeight());
        // Sprint 50 Tier 1 finding #16 — no more silent LB default. A KG
        // shipment posted with a missing unit used to label as LB (under-
        // declaring by ~2.2×). Require it explicitly. When Sprint 50 Tier 2
        // lands Client.defaultWeightUnit, that can fill in first before this
        // check fires. TODO: replace with Client.defaultWeightUnit fallback.
        if (p.getWeight() != null && !StringUtils.hasText(p.getWeightUnit())) {
            throw new ExternalApiException(422, ErrorCode.UNIT_REQUIRED,
                    "parcel.weight was supplied without parcel.weightUnit. "
                            + "Send 'kg' or 'lb' explicitly — silent defaulting to 'lb' is no longer allowed.");
        }
        manual.setWeightUnit(p.getWeightUnit());
        manual.setLength(p.getLength());
        manual.setWidth(p.getWidth());
        manual.setHeight(p.getHeight());
        manual.setDimUnit(p.getDimUnit());
        // Sprint 50 Tier 1 finding #16 — declared value without currency is
        // a customs misdeclaration risk (EUR declared as USD). Require an
        // explicit currency for any non-zero declared value.
        // TODO: replace with Client.defaultCurrency fallback once Sprint 50 Tier 2 lands.
        if (req.getDeclaredValue() != null
                && req.getDeclaredValue().signum() > 0
                && !StringUtils.hasText(req.getCurrency())) {
            throw new ExternalApiException(422, ErrorCode.CURRENCY_REQUIRED,
                    "declaredValue was supplied without currency. Send a 3-letter ISO code "
                            + "(e.g. 'USD', 'EUR', 'GBP') — silent defaulting to 'USD' is no longer allowed.");
        }
        // packagingCode: prefer the Phase-5c client alias when configured; else
        // fall back to matching by preset name / carrier package code.
        Long resolvedPresetId = null;
        if (translatedPackagePreset != null) {
            resolvedPresetId = translatedPackagePreset;
            manual.setPackagePresetId(resolvedPresetId);
        } else if (StringUtils.hasText(p.getPackagingCode())) {
            Optional<Long> presetHit = shippingConfigService.listPresets().getData().stream()
                    .filter(pr -> p.getPackagingCode().equalsIgnoreCase(pr.getName())
                            || p.getPackagingCode().equalsIgnoreCase(pr.getCarrierPackageCode()))
                    .findFirst()
                    .map(pr -> pr.getId());
            if (presetHit.isPresent()) {
                resolvedPresetId = presetHit.get();
                manual.setPackagePresetId(resolvedPresetId);
            }
        }
        // 3PL guardrail #3: package on the client's allowlist?
        if (resolvedPresetId != null) {
            try {
                resolutionService.assertPackageAllowed(clientCode, resolvedPresetId);
            } catch (ShipmentResolutionException e) {
                throw toExternal(e);
            }
        }
        if (req.getItems() != null && !req.getItems().isEmpty()) {
            manual.setItems(req.getItems().stream().map(this::toManualItem).collect(Collectors.toList()));
        }

        ApiResponse<LabelGenerationResponse> resp = carrierService.generateManualLabel(manual, caller);
        if (resp.getData() == null || !"SUCCESS".equalsIgnoreCase(resp.getStatus())) {
            int status = resp.getCode() > 0 ? resp.getCode() : 502;
            ErrorCode ec = parseErrorCode(resp.getErrorCode(), ErrorCode.CARRIER_FAILURE);
            throw new ExternalApiException(status, ec,
                    resp.getMessage() != null ? resp.getMessage() : "The carrier rejected the shipment.");
        }

        LabelGenerationResponse label = resp.getData();

        // Phase 5c — audit sidecar: preserve the raw ERP codes the caller sent
        // so an admin can always trace back what actually came in. Best-effort
        // — a failure here doesn't block the label from being returned.
        if (label.getOrderNo() != null) {
            try {
                orderRawCodesRepository.save(OrderRawCodes.builder()
                        .orderNo(label.getOrderNo().intValue())
                        .clientCode(clientCode)
                        .rawShipvia(rawShipvia)
                        .rawDestCountry(rawDestCountry)
                        .rawPackageCode(rawPackageCode)
                        .refOrderNumber(req.getRefOrderNumber())
                        .build());
            } catch (Exception ex) {
                log.warn("Failed to persist OrderRawCodes for order {}: {}", label.getOrderNo(), ex.getMessage());
            }
        }

        // 3PL snapshot: read straight off the label response. carrierService.
        // generateManualLabel already ran applyMarkup + isPastCutoff and
        // persisted the snapshot on OrderTracking (Phase 4d), so we don't
        // re-run the resolver here — the values printed on the label and the
        // values on the response are guaranteed to be the same row.
        ExternalShipmentResponse response = ExternalShipmentResponse.builder()
                .shipmentId(label.getOrderNo())
                .reference(req.getReference())
                .refOrderNumber(req.getRefOrderNumber())
                .clientCode(clientCode)
                .carrier(carrier)
                .service(serviceCode)
                .resolvedVia(resolvedVia)
                .international(international)
                .warehouseCode(label.getWarehouseCode() != null
                        ? label.getWarehouseCode()
                        : (resolvedWarehouse != null ? resolvedWarehouse.getCode() : null))
                .trackingNumber(label.getTrackingNumber())
                .trackingUrl(label.getTrackingUrl())
                .labelUrl(label.getLabelUrl())
                .labelPdf(label.getLabelPdf())
                .labelDocumentUrl("/api/v1/orders/" + label.getOrderNo() + "/label")
                .shippingCost(label.getShippingCost())
                .carrierAmount(label.getCarrierAmount())
                .billableAmount(label.getBillableAmount())
                .markupKind(label.getMarkupKind())
                .markupValue(label.getMarkupValue())
                .markupCurrency(label.getMarkupCurrency())
                .dispatchNextBusinessDay(label.getDispatchNextBusinessDay())
                .estimatedDelivery(label.getEstimatedDelivery())
                .status(label.getStatus())
                .build();

        // Sprint 46 — broadcast to subscribed webhooks. Fire-and-forget:
        // dispatcher runs @Async so a slow partner receiver never gates
        // the label response.
        if (webhookDispatcher != null && caller != null) {
            try {
                webhookDispatcher.fire(
                        com.multiship.backend.model.ExternalWebhookSubscription.EventType.LABEL_GENERATED,
                        caller.getApiKeyId(),
                        java.util.Map.of(
                                "shipmentId", response.getShipmentId(),
                                "trackingNumber", response.getTrackingNumber() == null ? "" : response.getTrackingNumber(),
                                "carrier", response.getCarrier() == null ? "" : response.getCarrier(),
                                "service", response.getService() == null ? "" : response.getService(),
                                "clientCode", clientCode == null ? "" : clientCode
                        ));
            } catch (Exception ignored) { /* never fail label on webhook dispatch */ }
        }

        return response;
    }

    // ─────────────────────────────── rates ─────────────────────────────────

    public ExternalRateResponse rate(ApiKeyPrincipal caller, ExternalRateRequest req) {
        String clientCode = effectiveClient(caller, req.getClientCode());
        String destCountry = req.getShipTo() != null ? req.getShipTo().getCountryCode() : null;

        // 3PL guardrail: destination allowed for this client?
        try {
            resolutionService.assertShipToAllowed(clientCode, destCountry);
        } catch (ShipmentResolutionException e) {
            throw toExternal(e);
        }

        // Ship-from precedence: resolved warehouse > explicit shipFrom > platform default.
        Warehouse resolvedWarehouse = null;
        try {
            resolvedWarehouse = resolutionService.resolveWarehouse(clientCode, req.getWarehouseCode()).orElse(null);
        } catch (ShipmentResolutionException e) {
            throw toExternal(e);
        }
        String originCountry = resolvedWarehouse != null && resolvedWarehouse.getAddress() != null
                ? resolvedWarehouse.getAddress().getCountry()
                : (req.getShipFrom() != null ? req.getShipFrom().getCountryCode()
                        : carrierProperties.getShipper().getCountryCode());
        boolean international = StringUtils.hasText(destCountry) && StringUtils.hasText(originCountry)
                && !destCountry.trim().equalsIgnoreCase(originCountry.trim());
        String neededScope = international ? "INTERNATIONAL" : "DOMESTIC";

        List<ShippingService> services = new ArrayList<>();
        if (StringUtils.hasText(req.getShipMethod())) {
            shippingConfigService.resolveRule(clientCode, req.getShipMethod(), destCountry).ifPresent(services::add);
        }
        if (services.isEmpty() && StringUtils.hasText(req.getCarrierCode())) {
            String carrier = req.getCarrierCode().trim().toUpperCase(Locale.ROOT);
            serviceRepository.findByCarrierIgnoreCaseAndEnabledTrueOrderBySortOrderAsc(carrier).stream()
                    .filter(s -> "BOTH".equalsIgnoreCase(s.getScope()) || neededScope.equalsIgnoreCase(s.getScope()))
                    .forEach(services::add);
        }
        if (services.isEmpty()) {
            throw new ExternalApiException(422, ErrorCode.VALIDATION_ERROR,
                    "Provide a shipMethod that resolves to a service, or a carrierCode to list its services.");
        }

        // Tag options with allowlist status — return everything so the caller
        // can see what's available, but flag the ones a shipment call would
        // reject as SERVICE_NOT_ALLOWED. G1: warehouse-gate is enforced too
        // when we've resolved one.
        Long warehouseId = resolvedWarehouse != null ? resolvedWarehouse.getId() : null;
        Set<Long> allowed = resolutionService.allowedServiceIds(clientCode, warehouseId);
        boolean noAllowlist = allowed.isEmpty();
        List<ExternalRateResponse.Option> options = services.stream()
                .map(s -> ExternalRateResponse.Option.builder()
                        .carrier(s.getCarrier())
                        .serviceCode(s.getServiceCode())
                        .serviceName(s.getName())
                        .scope(s.getScope())
                        .estimatedAmount(null)
                        // TODO: Sprint 50 Tier 2 — source from Client.defaultCurrency instead of hardcode.
                        // Safe today because estimatedAmount is null (pricing not enabled here);
                        // no real currency is riding on this value.
                        .currency("USD")
                        .allowed(noAllowlist || allowed.contains(s.getId()))
                        .build())
                .collect(Collectors.toList());

        return ExternalRateResponse.builder()
                .options(options)
                .pricingAvailable(false)
                .note("Live rate pricing is not enabled in this environment; options list the available services. "
                        + "Create a shipment to obtain the actual label and cost.")
                .build();
    }

    // ────────────────────────────── tracking ───────────────────────────────

    public ExternalTrackingResponse track(ApiKeyPrincipal caller, Long shipmentId) {
        Order order = loadScopedOrder(caller, shipmentId);
        OrderTracking tracking = orderTrackingRepository.findByOrderNo(order.getOrderNo())
                .filter(t -> StringUtils.hasText(t.getTrackingNumber()))
                .orElseThrow(() -> new ExternalApiException(409, ErrorCode.VALIDATION_ERROR,
                        "Shipment " + shipmentId + " has no tracking number yet."));

        String carrier = carrierOf(tracking);

        // A voided shipment reports VOIDED without troubling the carrier.
        if ("VOIDED".equalsIgnoreCase(order.getOrderStatus())) {
            return trackingFrom(shipmentId, tracking, carrier, "VOIDED", null, null, false);
        }

        // Live carrier lookup; on any failure (e.g. a fallback tracking number the
        // carrier doesn't know, or a carrier outage) degrade to the last-known
        // platform status rather than erroring.
        CarrierConnector connector = carrier != null ? connectorsByCode.get(carrier) : null;
        if (connector != null) {
            try {
                CarrierConnector.TrackingResult tr = connector.trackShipment(tracking.getTrackingNumber());
                if (tr != null) {
                    return trackingFrom(shipmentId, tracking, carrier,
                            tr.status(), tr.currentLocation(), tr.estimatedDelivery(), tr.delivered());
                }
            } catch (Exception ex) {
                log.debug("Carrier tracking lookup failed for {} ({}): {}",
                        tracking.getTrackingNumber(), carrier, ex.getMessage());
            }
        }
        return trackingFrom(shipmentId, tracking, carrier,
                firstNonBlank(tracking.getStatus(), "GENERATED"), null, null, false);
    }

    private ExternalTrackingResponse trackingFrom(Long shipmentId, OrderTracking tracking, String carrier,
                                                  String status, String location,
                                                  java.time.LocalDateTime eta, boolean delivered) {
        return ExternalTrackingResponse.builder()
                .shipmentId(shipmentId)
                .trackingNumber(tracking.getTrackingNumber())
                .carrier(carrier)
                .status(status)
                .currentLocation(location)
                .estimatedDelivery(eta)
                .delivered(delivered)
                .trackingUrl(tracking.getTrackingUrl())
                .build();
    }

    // ─────────────────────────────── void ──────────────────────────────────

    public Map<String, Object> voidShipment(ApiKeyPrincipal caller, Long shipmentId) {
        Order order = loadScopedOrder(caller, shipmentId);
        if ("VOIDED".equalsIgnoreCase(order.getOrderStatus())) {
            throw new ExternalApiException(409, ErrorCode.VALIDATION_ERROR,
                    "Shipment " + shipmentId + " is already voided.");
        }
        order.setOrderStatus("VOIDED");
        orderRepository.save(order);
        orderTrackingRepository.findByOrderNo(order.getOrderNo()).ifPresent(t -> {
            t.setStatus("VOIDED");
            t.setUpdatedAt(LocalDateTime.now());
            orderTrackingRepository.save(t);
        });

        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("shipmentId", shipmentId);
        out.put("status", "VOIDED");
        out.put("carrierVoided", false);
        out.put("note", "Shipment voided on the platform. A carrier-side label cancellation is not wired in this "
                + "environment; ensure the label is not used.");
        return out;
    }

    // ────────────────────────── address validation ─────────────────────────

    public ExternalAddressValidationResponse validateAddress(ExternalAddress a) {
        List<String> issues = new ArrayList<>();
        if (a == null) {
            return ExternalAddressValidationResponse.builder()
                    .valid(false).issues(List.of("No address supplied.")).carrierValidated(false).build();
        }
        if (!StringUtils.hasText(a.getName()) && !StringUtils.hasText(a.getCompany()))
            issues.add("name or company is required.");
        if (!StringUtils.hasText(a.getAddressLine1())) issues.add("addressLine1 is required.");
        if (!StringUtils.hasText(a.getCity())) issues.add("city is required.");
        if (!StringUtils.hasText(a.getPostalCode())) issues.add("postalCode is required.");
        String country = a.getCountryCode() != null ? a.getCountryCode().trim().toUpperCase(Locale.ROOT) : null;
        if (country == null || country.length() != 2) issues.add("countryCode must be a 2-letter ISO code.");
        if ("US".equals(country) && StringUtils.hasText(a.getPostalCode())
                && !a.getPostalCode().trim().matches("\\d{5}(-\\d{4})?")) {
            issues.add("US postalCode must be 5 digits (or ZIP+4).");
        }

        ExternalAddress normalized = new ExternalAddress();
        normalized.setName(a.getName());
        normalized.setCompany(a.getCompany());
        normalized.setPhone(a.getPhone());
        normalized.setEmail(a.getEmail());
        normalized.setAddressLine1(a.getAddressLine1());
        normalized.setAddressLine2(a.getAddressLine2());
        normalized.setCity(a.getCity());
        normalized.setState(a.getState() != null ? a.getState().trim().toUpperCase(Locale.ROOT) : null);
        normalized.setPostalCode(a.getPostalCode() != null ? a.getPostalCode().trim() : null);
        normalized.setCountryCode(country);

        return ExternalAddressValidationResponse.builder()
                .valid(issues.isEmpty())
                .issues(issues)
                .normalized(normalized)
                .carrierValidated(false)
                .build();
    }

    // ─────────────────────────────── helpers ───────────────────────────────

    /**
     * The client a call operates on. A client-bound key always acts as its own
     * client (a differing clientCode in the body is rejected, not silently
     * ignored); a platform-wide (WMS) key must name an existing client per call.
     */
    private String effectiveClient(ApiKeyPrincipal caller, String requested) {
        String req = StringUtils.hasText(requested) ? requested.trim() : null;
        if (!caller.isAllClients()) {
            if (req != null && !req.equalsIgnoreCase(caller.getClientCode())) {
                throw new ExternalApiException(403, ErrorCode.UNAUTHORIZED,
                        "This API key ships for client " + caller.getClientCode()
                        + " and cannot act for '" + req + "'.");
            }
            return caller.getClientCode();
        }
        if (req == null) {
            throw new ExternalApiException(422, ErrorCode.VALIDATION_ERROR,
                    "clientCode is required — this is a platform-wide key, so each call must name the client it ships for.");
        }
        return clientRepository.findByClientCodeIgnoreCase(req)
                .map(Client::getClientCode)
                .orElseThrow(() -> new ExternalApiException(422, ErrorCode.VALIDATION_ERROR,
                        "Client '" + req + "' was not found."));
    }

    private Order loadScopedOrder(ApiKeyPrincipal caller, Long shipmentId) {
        if (shipmentId == null) {
            throw new ExternalApiException(404, ErrorCode.ORDER_NOT_FOUND, "Shipment not found.");
        }
        Order order = orderRepository.findByOrderNo(shipmentId.intValue()).orElse(null);
        String owner = order == null ? null
                : firstNonBlank(order.getTenantId(), order.getCustNo());
        // A platform-wide (WMS) key may read/void any client's shipment.
        boolean owned = owner != null
                && (caller.isAllClients() || owner.trim().equalsIgnoreCase(caller.getClientCode()));
        if (order == null || !owned) {
            // Do not leak existence of other clients' shipments.
            throw new ExternalApiException(404, ErrorCode.ORDER_NOT_FOUND, "Shipment " + shipmentId + " not found.");
        }
        return order;
    }

    private Optional<CarrierAccountRef> clientDefaultAccount(String clientCode, String carrier) {
        if (!StringUtils.hasText(clientCode)) return Optional.empty();
        return carrierAccountRefRepository
                .findByCustomerNoIgnoreCaseOrderByClientDefaultDescUpdatedAtDesc(clientCode).stream()
                .filter(a -> carrier.equalsIgnoreCase(a.getCarrierCode()))
                .findFirst();
    }

    private Optional<CarrierAccountRef> platformAccount(String carrier) {
        return carrierAccountRefRepository.findPlatformAccountsByCarrier(carrier).stream().findFirst();
    }

    /** Resolve a carrier code for a shipment's tracking row (via its bill-to account, then service prefix). */
    private String carrierOf(OrderTracking tracking) {
        if (StringUtils.hasText(tracking.getAccountNumber())) {
            Optional<CarrierAccountRef> acct = carrierAccountRefRepository
                    .findFirstByAccountNumberIgnoreCaseOrderByUpdatedAtDesc(tracking.getAccountNumber());
            if (acct.isPresent() && StringUtils.hasText(acct.get().getCarrierCode())) {
                return acct.get().getCarrierCode().toUpperCase(Locale.ROOT);
            }
        }
        String svc = tracking.getShipViaCd();
        if (svc != null) {
            String u = svc.toUpperCase(Locale.ROOT);
            if (u.startsWith("FEDEX")) return "FEDEX";
            if (u.startsWith("UPS")) return "UPS";
            if (u.startsWith("USPS") || u.startsWith("STAMPS")) return "USPS";
        }
        return null;
    }

    /**
     * Convert a resolver exception into the external API's exception shape.
     * The 3PL 422s (SHIP_TO_DENIED, SERVICE_NOT_ALLOWED, PACKAGE_NOT_ALLOWED,
     * MARKUP_INVALID, NO_DEFAULT_WAREHOUSE) map to 422; WAREHOUSE_ATTACH_FORBIDDEN
     * is a 403 because it names a specific tenancy boundary the caller crossed.
     */
    private static ExternalApiException toExternal(ShipmentResolutionException e) {
        int status = e.getErrorCode() == ErrorCode.WAREHOUSE_ATTACH_FORBIDDEN ? 403 : 422;
        return new ExternalApiException(status, e.getErrorCode(), e.getMessage());
    }

    /**
     * A resolved {@link Warehouse}'s address, in the external API shape.
     * Returns null when the warehouse has no address (never expected at
     * runtime — created via the UI which requires country).
     */
    private ExternalAddress warehouseToAddress(Warehouse w) {
        if (w == null || w.getAddress() == null) return null;
        com.multiship.backend.model.Address a = w.getAddress();
        ExternalAddress ext = new ExternalAddress();
        // Warehouse address doesn't carry a company field distinct from name;
        // reuse name for both so labels look right regardless of which the
        // carrier expects.
        ext.setName(a.getName());
        ext.setCompany(a.getName());
        ext.setPhone(a.getPhone());
        ext.setAddressLine1(a.getLine1());
        ext.setAddressLine2(a.getLine2());
        ext.setCity(a.getCity());
        ext.setState(a.getState());
        ext.setPostalCode(a.getZip());
        ext.setCountryCode(a.getCountry());
        return ext;
    }

    private ExternalAddress clientShipFrom(String clientCode) {
        Client client = clientRepository.findByClientCodeIgnoreCase(clientCode).orElse(null);
        if (client == null || client.getShipFrom() == null || !StringUtils.hasText(client.getShipFrom().getName())) {
            return null;
        }
        var sf = client.getShipFrom();
        ExternalAddress a = new ExternalAddress();
        a.setName(sf.getName());
        a.setPhone(sf.getPhone());
        a.setAddressLine1(sf.getLine1());
        a.setAddressLine2(sf.getLine2());
        a.setCity(sf.getCity());
        a.setState(sf.getState());
        a.setPostalCode(sf.getZip());
        a.setCountryCode(sf.getCountry());
        return a;
    }

    private ManualShipmentRequest.Address toManual(ExternalAddress a) {
        if (a == null) return null;
        ManualShipmentRequest.Address m = new ManualShipmentRequest.Address();
        m.setName(a.getName());
        m.setCompany(a.getCompany());
        m.setPhone(a.getPhone());
        m.setEmail(a.getEmail());
        m.setAddressLine1(a.getAddressLine1());
        m.setAddressLine2(a.getAddressLine2());
        m.setCity(a.getCity());
        m.setState(a.getState());
        m.setPostalCode(a.getPostalCode());
        m.setCountryCode(a.getCountryCode());
        return m;
    }

    private ManualShipmentRequest.Item toManualItem(ExternalItem src) {
        ManualShipmentRequest.Item it = new ManualShipmentRequest.Item();
        it.setDescription(src.getDescription());
        it.setSku(src.getSku());
        it.setHsCode(src.getHsCode());
        it.setCountryOfOrigin(src.getCountryOfOrigin());
        it.setQuantity(src.getQuantity());
        it.setUnitValue(src.getUnitValue());
        it.setWeight(src.getWeight());
        return it;
    }

    private static ErrorCode parseErrorCode(String code, ErrorCode fallback) {
        if (!StringUtils.hasText(code)) return fallback;
        try {
            return ErrorCode.valueOf(code);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) if (StringUtils.hasText(v)) return v.trim();
        return null;
    }
}
