package com.multiship.backend.service;

import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.CarrierConnectRequest;
import com.multiship.backend.dto.CarrierConnectResponse;
import com.multiship.backend.dto.CarrierAccountDTO;
import com.multiship.backend.dto.CarrierListResponse;
import com.multiship.backend.dto.CarrierStatusResponse;
import com.multiship.backend.dto.LabelGenerationResponse;
import com.multiship.backend.dto.ShipmentRequestDTO;
import com.multiship.backend.dto.OrderAccountResolutionDTO;
import com.multiship.backend.exception.CarrierConnectionException;
import com.multiship.backend.model.CarrierAccountRef;
import com.multiship.backend.model.CarrierConfig;
import com.multiship.backend.model.Client;
import com.multiship.backend.model.Order;
import com.multiship.backend.model.OrderCarrierDetails;
import com.multiship.backend.model.OrderTracking;
import com.multiship.backend.model.ShipVia;
import com.multiship.backend.model.User;
import com.multiship.backend.repository.CarrierAccountRefRepository;
import com.multiship.backend.repository.ClientRepository;
import com.multiship.backend.repository.CarrierConfigRepository;
import com.multiship.backend.repository.OrderCarrierDetailsRepository;
import com.multiship.backend.repository.OrderRepository;
import com.multiship.backend.repository.OrderTrackingRepository;
import com.multiship.backend.repository.ShipViaRepository;
import com.multiship.backend.repository.UserRepository;
import com.multiship.backend.model.Warehouse;
import com.multiship.backend.service.carriers.CarrierConnector;
import com.multiship.backend.service.resolution.ShipmentResolutionException;
import com.multiship.backend.service.resolution.ShipmentResolutionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class CarrierServiceImpl implements CarrierService {

    private final List<CarrierConnector> carrierConnectors;
    private final UserRepository userRepository;
    private final CarrierConfigRepository carrierConfigRepository;
    private final ShipViaRepository shipViaRepository;
    private final OrderRepository orderRepository;
    private final OrderTrackingRepository orderTrackingRepository;
    private final CarrierProperties carrierProperties;
    private final OrderCarrierDetailsRepository orderCarrierDetailsRepository;
    private final CarrierAccountRefRepository carrierAccountRefRepository;
    private final ClientRepository clientRepository;
    private final com.multiship.backend.repository.OrderCustomsRepository orderCustomsRepository;
    private final com.multiship.backend.repository.LabelPackageRepository labelPackageRepository;
    private final com.multiship.backend.repository.ShipmentBatchRepository shipmentBatchRepository;
    private final CarrierLimitService carrierLimitService;
    private final ShipmentSplitter shipmentSplitter;
    /**
     * Sprint 50 Tier 0.5 PR E - clamp tenantId on carrier-connect so a
     * scoped USER cannot persist a CarrierConfig for a foreign tenant.
     */
    private final TenantScopeEnforcer tenantScope;

    @org.springframework.beans.factory.annotation.Value("${carrier.auto-split-enabled:true}")
    private boolean autoSplitEnabled;

    /** Sprint 48 B5 — global kill-switch for the packaging validator.
     *  Off = validator is skipped everywhere; over-packaged shipments then
     *  go straight to the carrier (which will reject them with a generic
     *  error). Default true. */
    @org.springframework.beans.factory.annotation.Value("${packaging.validation-enabled:true}")
    private boolean packagingValidationEnabled;
    private final com.multiship.backend.repository.ClientCustomsProfileRepository clientCustomsProfileRepository;
    private final ShippingConfigService shippingConfigService;
    private final CustomsService customsService;
    private final ShipmentResolutionService resolutionService;

    /** Sprint 44 — optional so existing tests that build CarrierServiceImpl
     *  without Spring don't have to plumb another dep. When null, routing
     *  rules simply don't run. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private RoutingRuleService routingRuleService;

    @Override
    @Transactional(readOnly = true)
    public ApiResponse<List<CarrierListResponse>> getAvailableCarriers() {
        List<CarrierListResponse> carriers = carrierConnectors.stream()
                .map(this::toCarrierListResponse)
                .sorted(Comparator.comparing(CarrierListResponse::getCarrierName))
                .toList();

        return success("Available carriers loaded successfully.", carriers);
    }

    @Override
    @Transactional
    public ApiResponse<CarrierConnectResponse> connectToCarrier(CarrierConnectRequest request, UserDetails userDetails) {
        User user = resolveUser(userDetails);
        // Accept UPS/FEDEX/USPS (canonical) or legacy P80/F77/L01; translate
        // to the internal ship-via code only where persistence needs it.
        String canonicalCode = resolveCanonicalCarrierCode(request.getCarrierCode());
        CarrierConnector connector = getCarrierConnector(canonicalCode);
        ShipVia shipVia = resolveShipVia(toShipViaCode(canonicalCode));

        CarrierConnector.CarrierConnectionResult connectionResult = connector.connect(
                request.getClientId(),
                request.getClientSecret(),
                request.getAccountNumber()
        );

        LocalDateTime now = LocalDateTime.now();
        // Sprint 50 Tier 0.5 PR E - clamp caller-supplied tenantId. A
        // scoped USER can only connect a carrier for their own tenant;
        // platform operators pass any value (including null for a
        // platform/house account) through unchanged.
        String tenantId = StringUtils.hasText(request.getTenantId())
                ? tenantScope.clampClientCode(request.getTenantId()).trim().toUpperCase(Locale.ROOT)
                : null;

        // Tenant-aware lookup so an operator session (tenantId null) never
        // clobbers a tenant account row for the same carrier, and vice versa.
        CarrierConfig carrierConfig = (tenantId != null
                ? carrierConfigRepository.findFirstByUserUsernameAndCarrierCodeAndTenantId(
                        user.getUsername(), connector.getCarrierCode(), tenantId)
                : carrierConfigRepository.findFirstByUserUsernameAndCarrierCodeAndTenantIdIsNull(
                        user.getUsername(), connector.getCarrierCode()))
                .orElseGet(CarrierConfig::new);

        carrierConfig.setUser(user);
        carrierConfig.setShipVia(shipVia);
        carrierConfig.setCarrierCode(connector.getCarrierCode());
        carrierConfig.setCarrierName(connector.getCarrierName());
        carrierConfig.setClientId(request.getClientId());
        carrierConfig.setClientSecret(request.getClientSecret());
        carrierConfig.setAccountNumber(request.getAccountNumber());
        carrierConfig.setAccessToken(connectionResult.accessToken());
        carrierConfig.setTokenExpiresAt(connectionResult.tokenExpiresAt());
        carrierConfig.setEnvironment(normalizeEnvironment(request.getEnvironment(), carrierProperties.getDefaultEnvironment()));
        carrierConfig.setActive(true);

        if (tenantId != null) {
            carrierConfig.setTenantId(tenantId);

            boolean makeDefault = Boolean.TRUE.equals(request.getSetAsDefault())
                    || !carrierConfigRepository.existsByTenantIdAndIsDefaultTrue(tenantId);

            if (makeDefault) {
                carrierConfigRepository.findByTenantIdOrderByIsDefaultDescUpdatedAtDesc(tenantId).stream()
                        .filter(existing -> Boolean.TRUE.equals(existing.getIsDefault()))
                        .filter(existing -> !Objects.equals(existing.getId(), carrierConfig.getId()))
                        .forEach(existing -> {
                            existing.setIsDefault(false);
                            carrierConfigRepository.save(existing);
                        });
                carrierConfig.setIsDefault(true);
            }
        }

        carrierConfigRepository.save(carrierConfig);

        persistCarrierDetails(
                user,
                connector.getCarrierCode(),
                request.getClientId(),
                request.getClientSecret(),
                request.getAccountNumber(),
                connectionResult.accessToken(),
                connectionResult.tokenExpiresAt(),
                true,
                now,
                normalizeEnvironment(request.getEnvironment(), carrierProperties.getDefaultEnvironment())
        );

        CarrierConnectResponse response = CarrierConnectResponse.builder()
                .carrierCode(connector.getCarrierCode())
                .carrierName(connector.getCarrierName())
                .connected(true)
                .message(connectionResult.message())
                .accountNumber(request.getAccountNumber())
                .environment(normalizeEnvironment(request.getEnvironment(), carrierProperties.getDefaultEnvironment()))
                .connectedAt(now)
                .tokenExpiresAt(connectionResult.tokenExpiresAt())
                .tokenExpired(false)
                .accessTokenPreview(maskToken(connectionResult.accessToken()))
                .build();

        return success("Carrier connected successfully.", response);
    }

    @Override
    @Transactional
    public ApiResponse<CarrierStatusResponse> disconnectCarrier(UserDetails userDetails) {
        User user = resolveUser(userDetails);
        String carrierCode = resolveCarrierCode(user);

        carrierConfigRepository.findFirstByUserUsernameAndCarrierCodeAndTenantIdIsNull(user.getUsername(), carrierCode)
                .ifPresent(config -> {
                    config.setActive(false);
                    config.setAccessToken(null);
                    config.setTokenExpiresAt(null);
                    config.setClientId(null);
                    config.setClientSecret(null);
                    config.setAccountNumber(null);
                    carrierConfigRepository.save(config);
                });

        persistCarrierDetails(
                user,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                null,
                carrierProperties.getDefaultEnvironment()
        );

        CarrierStatusResponse response = CarrierStatusResponse.builder()
                .carrierCode(carrierCode)
                .carrierName(resolveConnectorName(carrierCode))
                .connected(false)
                .tokenExpired(true)
                .accountNumber(null)
                .environment(carrierProperties.getDefaultEnvironment())
                .message("Carrier disconnected successfully.")
                .documentationUrl(resolveCarrierConfiguration(carrierCode).documentationUrl())
                .connectionGuide(resolveCarrierConfiguration(carrierCode).connectionGuide())
                .build();

        return success("Carrier disconnected successfully.", response);
    }

    @Override
    @Transactional(readOnly = true)
    public ApiResponse<CarrierStatusResponse> getCarrierStatus(UserDetails userDetails) {
        User user = resolveUser(userDetails);
        String carrierCode = resolveCarrierCode(user);
        CarrierConnector connector = getCarrierConnector(carrierCode);
        CarrierConfig config = carrierConfigRepository.findFirstByUserUsernameAndCarrierCodeAndTenantIdIsNull(user.getUsername(), carrierCode)
                .orElse(null);

        CarrierStatusResponse response = CarrierStatusResponse.builder()
                .carrierCode(carrierCode)
                .carrierName(connector.getCarrierName())
                .connected(user.isCarrierConnected())
                .tokenExpired(user.isCarrierTokenExpired())
                .accountNumber(firstNonBlank(user.getCarrierAccountNumber(), config != null ? config.getAccountNumber() : null))
                .environment(firstNonBlank(user.getCarrierEnvironment(), config != null ? config.getEnvironment() : null, carrierProperties.getDefaultEnvironment()))
                .connectedAt(user.getCarrierConnectedAt())
                .tokenExpiresAt(user.getCarrierTokenExpiresAt())
                .message(user.isCarrierConnected() ? "Carrier is connected." : "Carrier is not connected.")
                .documentationUrl(connector.getConfiguration().documentationUrl())
                .connectionGuide(connector.getConfiguration().connectionGuide())
                .build();

        return success("Carrier status loaded successfully.", response);
    }

    // TODO(sprint49-tier2-fix6-followup): method holds a Postgres row lock
    // (via findByOrderNoForUpdate below) for the entire duration of the
    // carrier HTTP call. Splitting this into A) reserve IN_FLIGHT +
    // release lock, B) carrier call, C) persist result — requires an
    // IN_FLIGHT status column + saga for stuck rows. Deliverable in a
    // dedicated PR. Interim mitigations shipped in Tier 2:
    // application.properties sets 30s Hikari leak-detection + 60s tx
    // timeout, so a wedged carrier call surfaces with a stack trace and
    // is auto-rolled-back rather than exhausting the pool.
    @Override
    @Transactional
    public ApiResponse<LabelGenerationResponse> generateLabel(Long orderNo, UserDetails userDetails, String idempotencyKey, Long accountId) {
        // Back-compat overload — default useHouseAccount=false so callers that
        // don't opt in never silently bill the platform account (Sprint 50
        // Tier 1 finding #3).
        return generateLabel(orderNo, userDetails, idempotencyKey, accountId, false);
    }

    @Override
    @Transactional
    public ApiResponse<LabelGenerationResponse> generateLabel(Long orderNo, UserDetails userDetails,
                                                              String idempotencyKey, Long accountId,
                                                              boolean useHouseAccount) {
        User user = resolveUser(userDetails);

        // Row lock: concurrent generations for this order (double-click,
        // retry while the first request is still with the carrier) queue up
        // here. The second request proceeds only after the first commits and
        // then sees the generated label below — the carrier is never called
        // twice for one order.
        Order order = orderRepository.findByOrderNoForUpdate(orderNo.intValue()).orElse(null);

        if (order == null) {
            return failure(HttpStatus.NOT_FOUND, ErrorCode.ORDER_NOT_FOUND, "Order " + orderNo + " was not found.");
        }

        String normalizedKey = StringUtils.hasText(idempotencyKey) ? idempotencyKey.trim() : null;

        OrderTracking existingTracking = orderTrackingRepository.findByOrderNo(order.getOrderNo()).orElse(null);
        if (existingTracking != null
                && Boolean.TRUE.equals(existingTracking.getIsLabelGenerated())
                && "GENERATED".equalsIgnoreCase(existingTracking.getStatus())) {

            LabelGenerationResponse already = LabelGenerationResponse.builder()
                    .orderNo(orderNo)
                    .trackingNumber(existingTracking.getTrackingNumber())
                    .trackingUrl(existingTracking.getTrackingUrl())
                    .carrierAccountCode(existingTracking.getAccountNumber())
                    .status("GENERATED")
                    .message("A label for this order already exists — the existing tracking details are included.")
                    .build();

            // Same Idempotency-Key as the request that generated the label:
            // this is a RETRY of that request (e.g. the response was lost),
            // so return the existing label as a success.
            if (normalizedKey != null && normalizedKey.equals(existingTracking.getIdempotencyKey())) {
                return success("Label already generated by this request — returning the existing label.", already);
            }

            // 409: a NEW attempt to regenerate an existing label — never
            // silently re-bill; the existing tracking details ride along.
            return failure(HttpStatus.CONFLICT, ErrorCode.LABEL_ALREADY_GENERATED,
                    "Order " + orderNo + " already has a generated label ("
                            + existingTracking.getTrackingNumber() + ").",
                    already);
        }

        String tenantId = firstNonBlank(order.getTenantId(), order.getCustNo());

        // A manually chosen account overrides the cascade entirely.
        AccountResolution resolution;
        if (accountId != null) {
            CarrierAccountRef picked = carrierAccountRefRepository.findById(accountId).orElse(null);

            if (picked == null) {
                return failure(HttpStatus.NOT_FOUND, ErrorCode.ACCOUNT_NOT_FOUND,
                        "Carrier account " + accountId + " was not found.");
            }
            if (Boolean.FALSE.equals(picked.getActive()) || !picked.isComplete()) {
                return failure(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.ACCOUNT_INCOMPLETE,
                        "Account " + picked.getAccountNumber() + " is inactive or missing credentials.");
            }

            resolution = AccountResolution.of(AccountResolution.SCENARIO_MANUAL,
                    resolveCanonicalCarrierCode(firstNonBlank(picked.getCarrierCode(), order.getShipviaCd(),
                            carrierProperties.getDefaultCarrierCode())),
                    picked.getAccountNumber(), picked.getClientId(), picked.getClientSecret(),
                    firstNonBlank(picked.getEnvironment(), carrierProperties.getDefaultEnvironment()),
                    picked.getAccountName());
        } else {
            resolution = resolveAccountForOrder(order);
        }

        // 422: nothing resolved automatically — the shipper must pick an
        // account from the book (pending state, not an error queue entry).
        if (AccountResolution.SCENARIO_CHOOSE_ACCOUNT.equals(resolution.scenario())) {
            LabelGenerationResponse chooseAccount = LabelGenerationResponse.builder()
                    .orderNo(orderNo)
                    .carrierCode(resolution.carrierCode())
                    .status("CHOOSE_ACCOUNT")
                    .clientCode(tenantId)
                    // the client's per-carrier default (if any) — the picker pre-selects it
                    .prefillAccountNumber(resolution.accountNumber())
                    .message("Choose which " + resolution.carrierCode() + " account to ship this order with.")
                    .build();
            return failure(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.ACCOUNT_SELECTION_REQUIRED,
                    "Order " + orderNo + " needs a manually selected carrier account.", chooseAccount);
        }


        // 422: the request is well-formed but a business precondition is not
        // met — the caller must supply carrier details before retrying.
        if (AccountResolution.SCENARIO_NEEDS_DETAILS.equals(resolution.scenario())) {
            LabelGenerationResponse needsDetails = LabelGenerationResponse.builder()
                    .orderNo(orderNo)
                    .carrierCode(resolution.carrierCode())
                    .status("NEEDS_DETAILS")
                    .needsDetails(true)
                    .missingFields(resolution.missingFields())
                    .prefillAccountNumber(resolution.accountNumber())
                    .prefillCarrierCode(resolution.carrierCode())
                    .prefillClientId(resolution.clientId())
                    .prefillEnvironment(resolution.environment())
                    .message("This order has partial carrier details. Fill in the missing fields to generate the label.")
                    .build();
            return failure(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.NEEDS_CARRIER_DETAILS,
                    "Carrier details are required before generating this label.", needsDetails);
        }

        // 422: the order's client is not registered — the operator must add
        // the client first (the Clients page / add-client modal), then retry.
        if (AccountResolution.SCENARIO_CLIENT_MISSING.equals(resolution.scenario())) {
            LabelGenerationResponse clientMissing = LabelGenerationResponse.builder()
                    .orderNo(orderNo)
                    .status("CLIENT_MISSING")
                    .clientCode(tenantId)
                    .message("Client " + tenantId + " is not registered. Add the client, then generate again.")
                    .build();
            return failure(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.CLIENT_NOT_FOUND,
                    "Client " + tenantId + " is not registered — add the client before generating labels.",
                    clientMissing);
        }

        // 422: the client is suspended.
        if (AccountResolution.SCENARIO_CLIENT_INACTIVE.equals(resolution.scenario())) {
            return failure(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.CLIENT_INACTIVE,
                    "Client " + tenantId + " is inactive — reactivate the client before generating labels.");
        }

        // 422: no account anywhere in the cascade. Recorded as ERROR so the
        // order surfaces in the failed queue with the reason.
        if (AccountResolution.SCENARIO_NO_DEFAULT.equals(resolution.scenario())) {
            String message = "Order " + orderNo + " has no carrier details and no default account is configured. "
                    + "Ask an admin to set a default account on the Carrier page.";
            markTrackingError(order, message);
            return failure(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.NO_DEFAULT_ACCOUNT, message);
        }

        // 422: an INTERNATIONAL shipment (destination country differs from the
        // origin) cannot be labelled without a customs declaration. This gate
        // surfaces the customs drawer exactly when it is needed; domestic
        // orders never hit it. Pending state — not marked ERROR.
        String customsGate = requireCustomsIfInternational(order, tenantId);
        if (customsGate != null) {
            LabelGenerationResponse needsCustoms = LabelGenerationResponse.builder()
                    .orderNo(orderNo)
                    .status("CUSTOMS_REQUIRED")
                    .clientCode(tenantId)
                    .message(customsGate)
                    .build();
            return failure(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.CUSTOMS_REQUIRED, customsGate, needsCustoms);
        }

        CarrierConnector connector;
        CarrierConnector.ShipmentResult shipmentResult;
        // The account actually used — may switch to the platform account below.
        AccountResolution used = resolution;

        // Sprint 50 Tier 1 (finding #3) — a client-account failure is only
        // silently retried on the PLATFORM (house) account when the shipper
        // explicitly opted in via {@code useHouseAccount=true}. Without the
        // opt-in, the failure surfaces as CLIENT_CARRIER_AUTH_FAILED so the
        // shipper knows their credentials broke instead of the platform
        // absorbing the bill in the background. The auto-fallback still
        // fires for resolutions that were already on the platform (SCENARIO_DEFAULT
        // / SCENARIO_PLATFORM_FALLBACK) or when there is no client-owned
        // credential to point at (no scenario carries client credentials there).
        boolean clientOwnedResolution = AccountResolution.SCENARIO_ORDER.equals(resolution.scenario())
                || AccountResolution.SCENARIO_REFERENCE.equals(resolution.scenario())
                || AccountResolution.SCENARIO_CLIENT_DEFAULT.equals(resolution.scenario())
                || AccountResolution.SCENARIO_MANUAL.equals(resolution.scenario());
        try {
            connector = getCarrierConnector(resolution.carrierCode());
            shipmentResult = attemptShipment(order, resolution, connector);
        } catch (Exception primaryEx) {
            // Client-owned account failed and the shipper did not opt into
            // the platform account → return CLIENT_CARRIER_AUTH_FAILED so the
            // operator either fixes the client's credentials or resends with
            // useHouseAccount=true to bill the platform explicitly.
            if (clientOwnedResolution && !useHouseAccount) {
                String msg = "Client account " + resolution.accountNumber()
                        + " failed at " + resolution.carrierCode() + ": " + primaryEx.getMessage()
                        + ". Fix the client's carrier credentials, or resend with useHouseAccount=true"
                        + " to explicitly bill the platform (house) account.";
                markTrackingError(order, msg);
                log.warn("Order {}: client account {} failed and no useHouseAccount opt-in — refusing silent platform bill.",
                        orderNo, resolution.accountNumber());
                LabelGenerationResponse authFail = LabelGenerationResponse.builder()
                        .orderNo(orderNo)
                        .carrierCode(resolution.carrierCode())
                        .carrierAccountCode(resolution.accountNumber())
                        .clientCode(tenantId)
                        .status("ERROR")
                        .message(msg)
                        .accountSource(resolution.scenario())
                        .build();
                return failure(HttpStatus.BAD_GATEWAY, ErrorCode.CLIENT_CARRIER_AUTH_FAILED, msg, authFail);
            }
            // Fallback: either the resolution wasn't client-owned (e.g. platform
            // account resolution failed on its own) OR the shipper opted in.
            // Retry with the PLATFORM (house) account for the same carrier.
            AccountResolution platform = platformFallback(resolution.carrierCode(), resolution.accountNumber());
            if (platform == null) {
                markTrackingError(order, primaryEx.getMessage());
                log.warn("Label generation failed for order {}: {} (no platform fallback available)", orderNo, primaryEx.getMessage());
                return failure(HttpStatus.BAD_GATEWAY, ErrorCode.CARRIER_FAILURE, primaryEx.getMessage());
            }
            try {
                connector = getCarrierConnector(platform.carrierCode());
                shipmentResult = attemptShipment(order, platform, connector);
                used = platform;
                log.info("Order {}: account {} failed ({}); fell back to platform account {} (useHouseAccount={}).",
                        orderNo, resolution.accountNumber(), primaryEx.getMessage(), platform.accountNumber(), useHouseAccount);
            } catch (Exception fallbackEx) {
                String msg = "Account " + resolution.accountNumber() + " failed (" + primaryEx.getMessage()
                        + ") and the platform account also failed (" + fallbackEx.getMessage() + ").";
                markTrackingError(order, msg);
                log.warn("Order {}: client + platform account both failed.", orderNo);
                return failure(HttpStatus.BAD_GATEWAY, ErrorCode.CARRIER_FAILURE, msg);
            }
        }

        OrderTracking tracking = orderTrackingRepository.findByOrderNo(order.getOrderNo()).orElseGet(OrderTracking::new);
        tracking.setOrderNo(order.getOrderNo());
        tracking.setOrderSuffix(order.getOrderSuffix());
        tracking.setTrackingNumber(shipmentResult.trackingNumber());
        tracking.setTrackingUrl(shipmentResult.trackingUrl());
        tracking.setShipViaCd(order.getShipviaCd());
        tracking.setAccountNumber(used.accountNumber());
        tracking.setIsLabelGenerated(true);
        tracking.setLabelGeneratedAt(LocalDateTime.now());
        tracking.setLabelFilePath(shipmentResult.labelUrl());
        tracking.setStatus("GENERATED");
        tracking.setIdempotencyKey(normalizedKey);
        tracking.setErrorMessage(null);
        tracking.setCreatedAt(tracking.getCreatedAt() != null ? tracking.getCreatedAt() : LocalDateTime.now());
        tracking.setUpdatedAt(LocalDateTime.now());
        orderTrackingRepository.save(tracking);

        boolean usedFallback = used != resolution;
        LabelGenerationResponse response = LabelGenerationResponse.builder()
                .orderNo(orderNo)
                .carrierCode(used.carrierCode())
                .carrierName(connector.getCarrierName())
                .carrierAccountCode(used.accountNumber())
                .tenantId(tenantId)
                .trackingNumber(shipmentResult.trackingNumber())
                .trackingUrl(shipmentResult.trackingUrl())
                .labelUrl(shipmentResult.labelUrl())
                .labelPdf(shipmentResult.labelPdf())
                .status("GENERATED")
                .shippingCost(shipmentResult.shippingCost())
                .estimatedDelivery(shipmentResult.estimatedDelivery())
                .accountSource(used.scenario())
                .message(usedFallback
                        ? "The account failed, so the label was generated on the platform account " + used.accountNumber() + "."
                        : "Shipment label generated successfully using the " + resolution.sourceDescription() + ".")
                .build();

        return success("Label generated successfully.", response);
    }

    /**
     * One-shot MANUAL shipment: the operator supplies every input explicitly
     * (ship-from, ship-to, package + weight, carrier account, service, packaging)
     * — nothing is auto-resolved. Purchase the label immediately, then record it
     * as a manual order (label_batch.is_manual = 'Y') + tracking so it appears in
     * the queue/archive and its label document renders.
     */
    // TODO(sprint49-tier2-fix6-followup): method-level @Transactional
    // holds the DB connection during the carrier HTTP call (5-15s RTT).
    // Follow-up will split into validate → carrier-call (no tx) →
    // persist-result (@Transactional). Interim: 60s tx timeout in
    // application.properties bounds the worst case.
    @Override
    @org.springframework.transaction.annotation.Transactional
    public ApiResponse<LabelGenerationResponse> generateManualLabel(
            com.multiship.backend.dto.ManualShipmentRequest req,
            org.springframework.security.core.userdetails.UserDetails user) {

        if (req == null || req.getRecipient() == null) {
            return failure(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.VALIDATION_ERROR, "Recipient details are required.");
        }
        // Sprint 50 Tier 0.5 PR G — clamp so a scoped USER can't submit a
        // manual shipment against a foreign clientCode. Null/blank input
        // gets forced to the caller's own tenant; operators pass through.
        req.setClientCode(tenantScope.clampClientCode(req.getClientCode()));
        com.multiship.backend.dto.ManualShipmentRequest.Address to = req.getRecipient();
        com.multiship.backend.dto.ManualShipmentRequest.Address from = req.getSender();

        if (!StringUtils.hasText(to.getName()) || !StringUtils.hasText(to.getAddressLine1())
                || !StringUtils.hasText(to.getCity()) || !StringUtils.hasText(to.getPostalCode())
                || !StringUtils.hasText(to.getCountryCode())) {
            return failure(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.VALIDATION_ERROR,
                    "Recipient name, address, city, postal code and country are required.");
        }
        if (req.getWeight() == null || req.getWeight().signum() <= 0) {
            return failure(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.VALIDATION_ERROR,
                    "A shipment weight greater than zero is required.");
        }
        String typedNumber = StringUtils.hasText(req.getAccountNumber()) ? req.getAccountNumber().trim() : null;
        if (req.getAccountId() == null && typedNumber == null) {
            return failure(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.VALIDATION_ERROR,
                    "Enter the bill-to account number for the shipment.");
        }

        // 3PL guardrails — clientCode may be empty for ad-hoc shipments, in
        // which case every resolver call is a no-op (backward-compat).
        String resolvedClient = req.getClientCode();
        boolean hasClient = StringUtils.hasText(resolvedClient);

        // Ship-to gate.
        if (hasClient) {
            try {
                resolutionService.assertShipToAllowed(resolvedClient, to.getCountryCode());
            } catch (ShipmentResolutionException e) {
                return toResolutionFailure(e);
            }
        }

        // Warehouse resolution. When a warehouse code is supplied (or the
        // client has a default attached), override the `from` block with the
        // warehouse address. Any override throws WAREHOUSE_ATTACH_FORBIDDEN
        // when the caller borrowed a warehouse from another tenant.
        String resolvedWarehouseCode = null;
        Long resolvedWarehouseId = null;
        if (hasClient && StringUtils.hasText(req.getWarehouseCode())) {
            try {
                Warehouse w = resolutionService.assertWarehouse(resolvedClient, req.getWarehouseCode());
                from = mergeFromWarehouse(from, w);
                resolvedWarehouseCode = w.getCode();
                resolvedWarehouseId = w.getId();
            } catch (ShipmentResolutionException e) {
                return toResolutionFailure(e);
            }
        }

        // Carrier: from the request, else inferred from the credential account.
        String carrier = resolveCanonicalCarrierCode(firstNonBlank(
                req.getCarrierCode(),
                req.getAccountId() != null
                        ? carrierAccountRefRepository.findById(req.getAccountId())
                                .map(CarrierAccountRef::getCarrierCode).orElse(null)
                        : null,
                "UPS"));

        // Credential account: the picked account, else the typed number on file, else
        // the carrier's platform account (so a hand-typed account still authenticates).
        CarrierAccountRef account = null;
        if (req.getAccountId() != null) {
            account = carrierAccountRefRepository.findById(req.getAccountId()).orElse(null);
        }
        if (account == null && typedNumber != null) {
            account = carrierAccountRefRepository
                    .findFirstByAccountNumberIgnoreCaseAndCarrierCodeIgnoreCase(typedNumber, carrier).orElse(null);
        }
        if (account == null) {
            account = carrierAccountRefRepository.findPlatformAccountsByCarrier(carrier).stream().findFirst().orElse(null);
        }
        if (account == null) {
            return failure(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.VALIDATION_ERROR,
                    "No verified " + carrier + " credentials are available to ship. Add or verify a "
                    + carrier + " account first.");
        }
        // Bill-to account number that prints on the label: the typed number wins.
        String billToNumber = firstNonBlank(typedNumber, account.getAccountNumber(), "ACCOUNT");

        CarrierConnector connector = getCarrierConnector(carrier);

        // Explicit service + packaging picks — no rule/weight auto-resolution.
        com.multiship.backend.model.ShippingService service =
                shippingConfigService.serviceById(req.getServiceId()).orElse(null);
        com.multiship.backend.model.PackagePreset preset =
                shippingConfigService.presetById(req.getPackagePresetId()).orElse(null);

        // Packaging pre-flight — data-driven rules from the preset row:
        // max weight, max L/W/H, dim weight, girth. Sprint 48 B8: also
        // validates each req.packages[i] with box-index-tagged violations
        // ("Box 3: Weight 20 lb exceeds FedEx Envelope limit of 1 lb").
        // Sprint 48 freight/LTL: parcel-service eligibility (piece >150 lb,
        // length >108 in, L+G >165 in, or shipment total > carrier max)
        // adds PARCEL_LIMIT violations pointing operators to freight service.
        // Kill-switch: packaging.validation-enabled=false disables both.
        if (packagingValidationEnabled) {
            com.multiship.backend.util.PackagingValidator.Outcome outcome =
                    preset == null
                            ? new com.multiship.backend.util.PackagingValidator.Outcome(java.util.List.of())
                            : com.multiship.backend.util.PackagingValidator.validateAll(
                                    preset,
                                    req.getWeight(), req.getWeightUnit(),
                                    req.getLength(), req.getWidth(), req.getHeight(), req.getDimUnit(),
                                    req.getPackages());
            // Parcel-eligibility uses per-carrier/service defaults from
            // PackageMath.defaultLimits + the carrier's per-shipment cap
            // (already seeded in carrier_shipping_limit). intl-vs-domestic
            // scope resolved from the recipient country vs the platform
            // shipper default (from-address override is applied further
            // down; this pre-flight uses the platform default).
            String originForScope = carrierProperties.getShipper().getCountryCode();
            boolean intlForScope = to.getCountryCode() != null && originForScope != null
                    && !to.getCountryCode().trim().equalsIgnoreCase(originForScope.trim());
            String serviceCodeForScope = service != null ? service.getServiceCode() : null;
            com.multiship.backend.model.CarrierShippingLimit svcLimit = carrierLimitService.resolveLimit(
                    carrier, serviceCodeForScope, intlForScope);
            outcome = outcome.merge(com.multiship.backend.util.PackagingValidator.validateParcelLimits(
                    carrier, serviceCodeForScope, req.getPackages(),
                    req.getWeight(), req.getWeightUnit(),
                    req.getLength(), req.getWidth(), req.getHeight(), req.getDimUnit(),
                    svcLimit == null ? null : svcLimit.getMaxTotalWeightLb()));
            if (outcome.hardBlock()) {
                return failure(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.VALIDATION_ERROR,
                        outcome.combinedMessage()
                                + " Pick a larger packaging, reduce the weight/dimensions, "
                                + "or set the preset's enforcement to SOFT to override.");
            }
            if (!outcome.violations().isEmpty()) {
                log.warn("Packaging soft-warning on manual shipment (preset={}): {}",
                        preset != null ? preset.getName() : "(none)", outcome.combinedMessage());
            }
        }

        // Sprint 44 — routing rules. Runs after the initial carrier/service
        // pick is known so rules can key off the current selection, but
        // BEFORE allowlist gates so a valid reroute isn't rejected by the
        // allowlist of the original service. When the client isn't set or
        // the routing service isn't wired, this is a no-op.
        if (hasClient && routingRuleService != null) {
            // Audit-fix #4 — resolve destRegion from the destination country
            // via the shared CountryRegions taxonomy. Prior to this the field
            // was hard-coded null, so every region-scoped routing rule (e.g.
            // "if destination is Europe, reroute to DHL") silently failed to
            // match in the label-generation hot path.
            com.multiship.backend.dto.RoutingEvaluationRequest evalReq =
                    com.multiship.backend.dto.RoutingEvaluationRequest.builder()
                            .weightLb(com.multiship.backend.util.UnitConverter.toPounds(
                                    req.getWeight(), req.getWeightUnit()))
                            .destCountry(to.getCountryCode())
                            .destRegion(com.multiship.backend.util.CountryRegions.regionOf(
                                    to.getCountryCode()))
                            .currentCarrier(carrier)
                            .currentServiceId(service != null ? service.getId() : null)
                            .currentWarehouseId(resolvedWarehouseId)
                            .declaredValue(req.getDeclaredValue())
                            .orderSource(req.getSource())
                            .build();
            com.multiship.backend.dto.RoutingEvaluationResult routingResult =
                    routingRuleService.evaluate(resolvedClient, evalReq);
            if ("MATCH".equals(routingResult.getStatus())) {
                if (routingResult.getActionType() == com.multiship.backend.model.RoutingRule.ActionType.BLOCK) {
                    return failure(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.VALIDATION_ERROR,
                            "Blocked by routing rule '" + routingResult.getMatchedRuleName()
                            + "': " + routingResult.getBlockReason());
                }
                // REROUTE — swap the service. If the target carrier differs,
                // re-resolve the carrier + account so the rest of the pipeline
                // hits the right connector.
                if (routingResult.getTargetServiceId() != null) {
                    com.multiship.backend.model.ShippingService rerouted =
                            shippingConfigService.serviceById(routingResult.getTargetServiceId()).orElse(null);
                    if (rerouted == null) {
                        // Audit-fix #5: the rule matched, but its target service
                        // no longer exists (deleted from the catalog). Previously
                        // the code silently continued with the ORIGINAL service —
                        // the rule's intent was lost with no visible signal. Fail
                        // loudly so the operator can fix the rule.
                        return failure(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.VALIDATION_ERROR,
                                "Routing rule '" + routingResult.getMatchedRuleName()
                                + "' targets service #" + routingResult.getTargetServiceId()
                                + " which no longer exists. Fix the rule to point at a live service.");
                    }
                    service = rerouted;
                    String newCarrier = resolveCanonicalCarrierCode(rerouted.getCarrier());
                    if (!newCarrier.equalsIgnoreCase(carrier)) {
                        // Swap carrier + connector; try to find a working
                        // account for the new carrier (existing account, else
                        // the platform account for that carrier).
                        carrier = newCarrier;
                        CarrierAccountRef reroutedAccount = carrierAccountRefRepository
                                .findPlatformAccountsByCarrier(carrier).stream().findFirst().orElse(null);
                        if (reroutedAccount == null) {
                            return failure(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.VALIDATION_ERROR,
                                    "Routing rule '" + routingResult.getMatchedRuleName()
                                    + "' rerouted to " + carrier + " but no verified " + carrier
                                    + " credentials are available.");
                        }
                        account = reroutedAccount;
                        billToNumber = firstNonBlank(typedNumber, account.getAccountNumber(), "ACCOUNT");
                        connector = getCarrierConnector(carrier);
                    }
                    log.info("Routing rule '{}' rerouted client={} to service={} carrier={}",
                            routingResult.getMatchedRuleName(), resolvedClient, service.getServiceCode(), carrier);
                }
                // G2 — REROUTE may also (or only) rewrite the fulfilment
                // warehouse. Verify attach; refuse loudly on a stale target
                // rather than silently keeping the original.
                if (routingResult.getTargetWarehouseId() != null
                        && !routingResult.getTargetWarehouseId().equals(resolvedWarehouseId)) {
                    try {
                        Warehouse rerouteWh = resolutionService.assertWarehouseById(
                                resolvedClient, routingResult.getTargetWarehouseId());
                        from = mergeFromWarehouse(from, rerouteWh);
                        resolvedWarehouseCode = rerouteWh.getCode();
                        resolvedWarehouseId = rerouteWh.getId();
                        log.info("Routing rule '{}' rerouted client={} to warehouse={}",
                                routingResult.getMatchedRuleName(), resolvedClient, rerouteWh.getCode());
                    } catch (ShipmentResolutionException e) {
                        return failure(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.VALIDATION_ERROR,
                                "Routing rule '" + routingResult.getMatchedRuleName()
                                + "' targets warehouse #" + routingResult.getTargetWarehouseId()
                                + " which " + (e.getErrorCode() == ErrorCode.WAREHOUSE_NOT_FOUND
                                        ? "no longer exists."
                                        : "is not attached to this client."));
                    }
                }
            }
        }

        // Allowlist gates on the resolved service + preset. Ad-hoc shipments
        // (no client) skip both — same rule as ship-to above.
        if (hasClient) {
            try {
                if (service != null) {
                    resolutionService.assertServiceAllowed(resolvedClient, service.getId(),
                            to.getCountryCode(), resolvedWarehouseId);
                }
                if (preset != null) {
                    resolutionService.assertPackageAllowed(resolvedClient, preset.getId());
                }
            } catch (ShipmentResolutionException e) {
                return toResolutionFailure(e);
            }
        }

        String serviceType = service != null ? service.getServiceCode()
                : firstNonBlank(connector.getConfiguration().defaultServiceType(), "GROUND");
        String packageType = preset != null
                ? ("CARRIER".equalsIgnoreCase(preset.getKind()) ? preset.getCarrierPackageCode() : "YOUR_PACKAGING")
                : firstNonBlank(connector.getConfiguration().defaultPackageType(), "YOUR_PACKAGING");
        BigDecimal length = req.getLength() != null ? req.getLength() : (preset != null ? preset.getLength() : null);
        BigDecimal width = req.getWidth() != null ? req.getWidth() : (preset != null ? preset.getWidth() : null);
        BigDecimal height = req.getHeight() != null ? req.getHeight() : (preset != null ? preset.getHeight() : null);

        CarrierProperties.ShipperDefaults dflt = carrierProperties.getShipper();
        String fromCountry = firstNonBlank(from != null ? from.getCountryCode() : null, dflt.getCountryCode());

        ShipmentRequestDTO shipmentRequest = ShipmentRequestDTO.builder()
                .carrierCode(carrier)
                .accountNumber(billToNumber)
                .serviceType(serviceType)
                .packageType(packageType)
                .length(length).width(width).height(height)
                .weight(req.getWeight())
                .shipperName(firstNonBlank(from != null ? from.getName() : null, dflt.getName()))
                .shipperPhone(firstNonBlank(from != null ? from.getPhone() : null, dflt.getPhone()))
                .shipperAddressLine1(firstNonBlank(from != null ? from.getAddressLine1() : null, dflt.getAddressLine1()))
                .shipperAddressLine2(firstNonBlank(from != null ? from.getAddressLine2() : null, dflt.getAddressLine2()))
                .shipperCity(firstNonBlank(from != null ? from.getCity() : null, dflt.getCity()))
                .shipperState(firstNonBlank(from != null ? from.getState() : null, dflt.getState()))
                .shipperPostalCode(firstNonBlank(from != null ? from.getPostalCode() : null, dflt.getPostalCode()))
                .shipperCountryCode(fromCountry)
                .recipientName(to.getName())
                .recipientPhone(firstNonBlank(to.getPhone(), "0000000000"))
                .recipientAddressLine1(to.getAddressLine1())
                .recipientAddressLine2(to.getAddressLine2())
                .recipientAddressLine3(to.getAddressLine3())
                .recipientCity(to.getCity())
                .recipientState(firstNonBlank(to.getState(), ""))
                .recipientPostalCode(to.getPostalCode())
                .recipientCountryCode(to.getCountryCode())
                .recipientResidential(to.getResidential())
                .recipientPhoneCountryCode(to.getPhoneCountryCode())
                .referenceNumber(firstNonBlank(req.getReference(), "MANUAL"))
                .specialInstructions(req.getGoodsDescription())
                .declaredValue(req.getDeclaredValue())
                // Sprint 25 — thread the RETURN mode into the ShipmentRequestDTO
                // so connectors emit each carrier's return-label wire flag
                // (UPS ReturnService.Code=8, FedEx returnedShipmentDetail,
                // SWSIM IsReturnLabel=true, DHL pickup.isRequested=true).
                .isReturn(req.getIsReturn())
                // Sprint 27 — thread the DG block so connectors emit their
                // hazmat wire format (UPS HazMatPackageInformation, FedEx
                // dangerousGoodsDetail, DHL content.dangerousGoods, SWSIM
                // HazardousMaterials).
                .dangerousGoods(req.getDangerousGoods())
                // Sprint 35 — signature + insurance. Each connector
                // normalises the enum and emits its carrier-specific wire
                // format (UPS DeliveryConfirmation.DCISType, FedEx
                // packageSpecialServices.signatureOptionType, DHL
                // valueAddedServices SF/SI/II, SWSIM SignatureConfirmation
                // / AdultSignatureRequired + InsuredValue).
                .signatureOption(req.getSignatureOption())
                .insuredValue(req.getInsuredValue())
                .insuredValueCurrency(req.getInsuredValueCurrency())
                // Multi-package boxes 2..N (box 1 is the top-level fields).
                // Connectors' effectivePackages() prefers packages[] when set.
                .packages(req.getPackages())
                .build();

        // Resolve the carrier's MPS cap for this service/scope and split
        // the shipment into sub-requests when we're over the cap. Every
        // sub-request goes to the carrier as its own call; each returns
        // its own master tracking + label. Splitting is skipped when the
        // feature flag is off (safety kill-switch: over-cap requests then
        // fail at the carrier with a 4xx, which the try/catch below surfaces).
        boolean intlForLimit = to.getCountryCode() != null && fromCountry != null
                && !to.getCountryCode().trim().equalsIgnoreCase(fromCountry.trim());
        com.multiship.backend.model.CarrierShippingLimit limit = carrierLimitService.resolveLimit(
                carrier, serviceType, intlForLimit);
        java.math.BigDecimal totalWeightLb = shipmentSplitter.totalWeightLb(shipmentRequest);
        boolean overCap = autoSplitEnabled && carrierLimitService.requiresSplit(
                limit, shipmentRequest.effectivePackages().size(), totalWeightLb);
        java.util.List<ShipmentRequestDTO> subRequests = overCap
                ? shipmentSplitter.split(shipmentRequest, limit)
                : java.util.List.of(shipmentRequest);
        if (subRequests.size() > 1) {
            log.info("Splitting {}-pkg shipment into {} carrier calls (cap={} for {}/{})",
                    shipmentRequest.effectivePackages().size(), subRequests.size(),
                    limit.getMaxPackages(), carrier, serviceType);
        }

        // Loop the sub-requests, collect each carrier's ShipmentResult, then
        // fold them into a single master result for downstream persistence.
        // We save shipment_batch rows below (after the order row exists) so
        // that batch_id can FK back to the order.
        java.util.List<CarrierConnector.ShipmentResult> batchResults = new java.util.ArrayList<>();
        try {
            connector.validateCredentials(account.getClientId(), account.getClientSecret());
            String token = connector.getAccessToken(account.getClientId(), account.getClientSecret(),
                    account.getAccountNumber());
            String envForCall = firstNonBlank(account.getEnvironment(), carrierProperties.getDefaultEnvironment());
            // Sprint 49 Tier 2 — auth-retry per sub-request. If the token
            // 401s mid-loop (rotation / revocation), refresh once and retry
            // that sub only; second failure propagates as CarrierAuthException.
            // account / connector are non-final upstream (fallback path can
            // reassign) — capture into effectively-final locals for the lambda.
            final CarrierAccountRef fAccount = account;
            final CarrierConnector fConnector = connector;
            final String fEnv = envForCall;
            for (ShipmentRequestDTO sub : subRequests) {
                batchResults.add(com.multiship.backend.service.carriers.AuthRetry.withAuthRetry(
                        token,
                        () -> fConnector.getAccessToken(fAccount.getClientId(), fAccount.getClientSecret(),
                                fAccount.getAccountNumber()),
                        t -> fConnector.createShipment(sub, t, fEnv)));
            }
        } catch (Exception ex) {
            log.warn("Manual shipment failed at carrier {}: {}", carrier, ex.getMessage());
            // Persist the failed attempt as an ERROR order so it's visible in
            // the orders list (with the carrier error) and can be retried —
            // instead of silently vanishing when the carrier rejects it.
            Integer failedOrderNo = null;
            try {
                failedOrderNo = orderRepository.nextManualOrderNo();
                boolean intlErr = to.getCountryCode() != null && fromCountry != null
                        && !to.getCountryCode().trim().equalsIgnoreCase(fromCountry.trim());
                Order errOrder = new Order();
                errOrder.setOrderNo(failedOrderNo);
                errOrder.setOrderSuffix(0);
                errOrder.setOrderStatus("ERROR");
                errOrder.setIsError(true);
                errOrder.setIsManual("Y");
                errOrder.setIsReturn(Boolean.TRUE.equals(req.getIsReturn()) ? "Y" : "N");
                errOrder.setSource(firstNonBlank(req.getSource(), "MANUAL"));
                errOrder.setCustNo(firstNonBlank(req.getClientCode(), "MANUAL"));
                errOrder.setTenantId(StringUtils.hasText(req.getClientCode()) ? req.getClientCode().trim() : null);
                errOrder.setShipviaCd(service != null ? service.getServiceCode() : serviceType);
                errOrder.setShipName(to.getName());
                errOrder.setShipAttn(to.getCompany());
                errOrder.setShipAddr1(to.getAddressLine1());
                errOrder.setLocation(to.getAddressLine2());
                errOrder.setShiptoCity(to.getCity());
                errOrder.setShiptoState(to.getState());
                errOrder.setShiptoZip(to.getPostalCode());
                errOrder.setShiptoCountryCd(to.getCountryCode());
                errOrder.setCountryName(to.getCountryCode());
                errOrder.setPhone(to.getPhone());
                errOrder.setWeight(req.getWeight());
                errOrder.setGoodsDesc(req.getGoodsDescription());
                errOrder.setPrice(req.getDeclaredValue());
                errOrder.setIntlYn(intlErr ? "Y" : "N");
                errOrder.setCreatedDate(java.time.LocalDate.now());
                errOrder.setPackageCount(1);
                orderRepository.save(errOrder);
                // Record the carrier error on the tracking row for the detail view.
                OrderTracking errTracking = orderTrackingRepository.findByOrderNo(failedOrderNo)
                        .orElseGet(OrderTracking::new);
                errTracking.setOrderNo(failedOrderNo);
                errTracking.setOrderSuffix(0);
                errTracking.setShipViaCd(errOrder.getShipviaCd());
                errTracking.setAccountNumber(billToNumber);
                errTracking.setIsLabelGenerated(false);
                errTracking.setStatus("FAILED");
                errTracking.setErrorMessage(ex.getMessage());
                errTracking.setCreatedAt(java.time.LocalDateTime.now());
                errTracking.setUpdatedAt(java.time.LocalDateTime.now());
                orderTrackingRepository.save(errTracking);
            } catch (Exception persistEx) {
                log.warn("Could not persist ERROR order for failed manual shipment: {}", persistEx.getMessage());
            }
            return failure(HttpStatus.BAD_GATEWAY, ErrorCode.CARRIER_FAILURE,
                    "The carrier rejected the manual shipment: " + ex.getMessage()
                            + (failedOrderNo != null ? " (saved as order " + failedOrderNo + ")" : ""));
        }
        // Master result = batch 1 (order_label_tracking downstream uses its
        // trackingNumber). label_package rows come from each batch's
        // packages() with matching batch_id.
        CarrierConnector.ShipmentResult result = batchResults.get(0);

        // Sprint 48 B10 — allocate from a Postgres sequence instead of
        // MAX(order_no) + 1. Bulletproof under concurrency; the sequence
        // is created + seeded above the current MAX on startup by
        // OrderNoSequenceInitializer.
        int orderNo = orderRepository.nextManualOrderNo();
        boolean intl = to.getCountryCode() != null && fromCountry != null
                && !to.getCountryCode().trim().equalsIgnoreCase(fromCountry.trim());

        boolean isReturn = Boolean.TRUE.equals(req.getIsReturn());

        Order order = new Order();
        order.setOrderNo(orderNo);
        order.setOrderSuffix(0);
        order.setOrderStatus("GENERATED");
        order.setIsManual("Y");
        order.setIsReturn(isReturn ? "Y" : "N");
        order.setSource(firstNonBlank(req.getSource(), "MANUAL"));
        order.setCustNo(firstNonBlank(req.getClientCode(), "MANUAL"));
        order.setTenantId(StringUtils.hasText(req.getClientCode()) ? req.getClientCode().trim() : null);
        order.setShipviaCd(service != null ? service.getServiceCode() : serviceType);
        order.setShipName(to.getName());
        order.setShipAttn(to.getCompany());
        order.setShipAddr1(to.getAddressLine1());
        order.setLocation(to.getAddressLine2());
        order.setShiptoCity(to.getCity());
        order.setShiptoState(to.getState());
        order.setShiptoZip(to.getPostalCode());
        order.setShiptoCountryCd(to.getCountryCode());
        order.setCountryName(to.getCountryCode());
        order.setPhone(to.getPhone());
        order.setWeight(req.getWeight());
        order.setGoodsDesc(req.getGoodsDescription());
        order.setPrice(req.getDeclaredValue());
        order.setIntlYn(intl ? "Y" : "N");
        order.setCreatedDate(java.time.LocalDate.now());
        // Total package count for multi-box shipments. Frontend sends the
        // primary box plus extraPackages via req.packages; when packages[]
        // is null (single-box legacy path) we count 1.
        order.setPackageCount(shipmentRequest.getPackages() != null
                ? Math.max(1, shipmentRequest.getPackages().size())
                : 1);
        // Per-shipment importer/broker override (does NOT touch the client's saved profile).
        if (req.getImporter() != null || req.getBroker() != null) {
            try {
                java.util.Map<String, Object> ov = new java.util.LinkedHashMap<>();
                ov.put("importer", req.getImporter());
                ov.put("broker", req.getBroker());
                order.setImporterBrokerOverride(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(ov));
            } catch (Exception ignore) {
                // best-effort; a serialization failure just falls back to the client profile
            }
        }
        orderRepository.save(order);

        // Per-batch + per-package persistence. Each sub-request produced its
        // own carrier ShipmentResult; save one shipment_batch row per result
        // and one label_package row per piece with batch_id linked.
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        for (int batchIdx = 0; batchIdx < subRequests.size(); batchIdx++) {
            ShipmentRequestDTO subReq = subRequests.get(batchIdx);
            CarrierConnector.ShipmentResult batchResult = batchResults.get(batchIdx);
            java.util.List<com.multiship.backend.dto.PackageDetailDTO> pkgList = subReq.effectivePackages();
            java.util.List<CarrierConnector.PackageTracking> resultPackages =
                    batchResult.packages() == null ? java.util.List.of() : batchResult.packages();

            com.multiship.backend.model.ShipmentBatch batch = com.multiship.backend.model.ShipmentBatch.builder()
                    .orderNo(orderNo)
                    .batchSeq(batchIdx + 1)
                    .carrierCode(carrier)
                    .masterTrackingNumber(batchResult.trackingNumber())
                    .masterTrackingUrl(batchResult.trackingUrl())
                    .masterLabelUrl(batchResult.labelUrl())
                    .masterLabelPdf(batchResult.labelPdf())
                    .packageCountInBatch(pkgList.size())
                    .shippingCost(batchResult.shippingCost())
                    .rawResponse(batchResult.rawResponse())
                    .createdAt(now)
                    .build();
            shipmentBatchRepository.save(batch);

            // Sprint 49 Tier 3 Fix 7 — collect per-piece rows and saveAll
            // so Hibernate can batch (spring.jpa.properties.hibernate.jdbc.batch_size=50)
            // instead of per-piece single INSERTs.
            java.util.List<com.multiship.backend.model.LabelPackage> pieceRows =
                    new java.util.ArrayList<>(pkgList.size());
            for (int i = 0; i < pkgList.size(); i++) {
                com.multiship.backend.dto.PackageDetailDTO p = pkgList.get(i);
                int seq = p.getSequenceNumber() != null ? p.getSequenceNumber() : (batchIdx * 1000 + i + 1);
                final int seqFinal = seq;
                // Match strategy: sequenceNumber-in-piece list, else positional,
                // else master fallback. Guarantees every row has some tracking id.
                CarrierConnector.PackageTracking pieceMatch = resultPackages.stream()
                        .filter(pt -> pt.sequenceNumber() == seqFinal)
                        .findFirst()
                        .orElse(null);
                if (pieceMatch == null && i < resultPackages.size()) {
                    pieceMatch = resultPackages.get(i);
                }
                String piecelabel = pieceMatch != null ? pieceMatch.labelUrl() : null;
                if (!StringUtils.hasText(piecelabel) && pieceMatch != null) piecelabel = pieceMatch.labelPdf();
                if (!StringUtils.hasText(piecelabel)) piecelabel = batchResult.labelUrl();
                String pieceTracking = pieceMatch != null ? pieceMatch.trackingNumber() : null;
                if (!StringUtils.hasText(pieceTracking)) pieceTracking = batchResult.trackingNumber();
                String pieceTrackingUrl = pieceMatch != null ? pieceMatch.trackingUrl() : null;
                if (!StringUtils.hasText(pieceTrackingUrl)) pieceTrackingUrl = batchResult.trackingUrl();
                com.multiship.backend.model.LabelPackage row = com.multiship.backend.model.LabelPackage.builder()
                        .orderNo(orderNo)
                        .batchId(batch.getId())
                        .sequenceNumber(seq)
                        .trackingNumber(pieceTracking)
                        .trackingUrl(pieceTrackingUrl)
                        .labelFilePath(piecelabel)
                        .weight(p.getWeight() != null ? p.getWeight() : req.getWeight())
                        .weightUnit(firstNonBlank(p.getWeightUnit(), req.getWeightUnit()))
                        .length(p.getLength() != null ? p.getLength() : length)
                        .width(p.getWidth() != null ? p.getWidth() : width)
                        .height(p.getHeight() != null ? p.getHeight() : height)
                        .dimUnit(firstNonBlank(p.getDimUnit(), req.getDimUnit()))
                        .packageType(firstNonBlank(p.getPackageType(), packageType))
                        .declaredValue(p.getDeclaredValue() != null ? p.getDeclaredValue() : req.getDeclaredValue())
                        .reference(p.getReference())
                        .description(p.getDescription())
                        .createdAt(now)
                        .updatedAt(now)
                        .build();
                pieceRows.add(row);
            }
            labelPackageRepository.saveAll(pieceRows);
        }

        // International: persist the operator's commercial-invoice line items so the
        // commercial invoice renders. Importer/broker resolve from the client's
        // customs profile at document time (unchanged).
        if (intl && req.getItems() != null && !req.getItems().isEmpty()) {
            var lines = req.getItems().stream()
                    .filter(it -> StringUtils.hasText(it.getDescription()))
                    .map(it -> com.multiship.backend.dto.OrderCustomsItemDTO.builder()
                            .description(it.getDescription())
                            .hsCode(it.getHsCode())
                            .countryOfOrigin(it.getCountryOfOrigin())
                            .quantity(it.getQuantity())
                            .unitValue(it.getUnitValue())
                            .weight(it.getWeight())
                            .sku(it.getSku())
                            .boxSeq(it.getBoxSeq())  // Sprint 48 B11 — per-item package assignment
                            .build())
                    .toList();
            if (!lines.isEmpty()) {
                com.multiship.backend.dto.OrderCustomsUpsertRequest customsReq =
                        new com.multiship.backend.dto.OrderCustomsUpsertRequest();
                customsReq.setIncoterms(req.getIncoterms());
                customsReq.setReasonForExport(req.getReasonForExport());
                // TODO: Sprint 50 Tier 2 finding #16 — source from Client.defaultCurrency
                // instead of hardcoded USD. The API boundary in ExternalApiService already
                // fails loud on missing currency + non-zero declared value; this internal
                // manual-order path still defaults to USD for now.
                customsReq.setCurrency(firstNonBlank(req.getCurrency(), "USD"));
                customsReq.setItems(lines);
                try {
                    customsService.upsertCustoms(String.valueOf(orderNo), customsReq);
                } catch (Exception ex) {
                    log.warn("Manual order {}: could not save commercial-invoice items: {}", orderNo, ex.getMessage());
                }
            }
        }

        // 3PL snapshot: applyMarkup + isPastCutoff so historical rows carry
        // the config effective at label time (see the Phase-1 open decision).
        // Ad-hoc shipments (empty clientCode) run through as no-ops: markup
        // is 0% PERCENT, cutoff is false, warehouseCode is null.
        com.multiship.backend.service.resolution.MarkupApplied markup;
        try {
            // TODO: Sprint 50 Tier 2 finding #16 — source from Client.defaultCurrency.
            markup = resolutionService.applyMarkup(
                    hasClient ? resolvedClient : "",
                    result.shippingCost(),
                    firstNonBlank(req.getCurrency(), "USD"));
        } catch (ShipmentResolutionException e) {
            return toResolutionFailure(e);
        }
        boolean nextBusinessDay = hasClient
                && resolutionService.isPastCutoff(resolvedClient, java.time.Instant.now());

        OrderTracking tracking = new OrderTracking();
        tracking.setOrderNo(orderNo);
        tracking.setOrderSuffix(0);
        tracking.setTrackingNumber(result.trackingNumber());
        tracking.setTrackingUrl(result.trackingUrl());
        tracking.setShipViaCd(order.getShipviaCd());
        tracking.setAccountNumber(billToNumber);
        tracking.setIsLabelGenerated(true);
        tracking.setLabelGeneratedAt(LocalDateTime.now());
        tracking.setLabelFilePath(result.labelUrl());
        tracking.setStatus("GENERATED");
        tracking.setWarehouseCode(resolvedWarehouseCode);
        tracking.setCarrierAmount(markup.carrierRate());
        tracking.setBillableAmount(markup.billable());
        tracking.setMarkupKind(markup.kind());
        tracking.setMarkupValue(markup.value());
        tracking.setMarkupCurrency(markup.currency());
        tracking.setDispatchNextBusinessDay(nextBusinessDay);
        tracking.setCreatedAt(LocalDateTime.now());
        tracking.setUpdatedAt(LocalDateTime.now());
        orderTrackingRepository.save(tracking);

        LabelGenerationResponse response = LabelGenerationResponse.builder()
                .orderNo((long) orderNo)
                .carrierCode(carrier)
                .carrierName(connector.getCarrierName())
                .carrierAccountCode(billToNumber)
                .tenantId(order.getTenantId())
                .trackingNumber(result.trackingNumber())
                .trackingUrl(result.trackingUrl())
                .labelUrl(result.labelUrl())
                .labelPdf(result.labelPdf())
                .status("GENERATED")
                .shippingCost(markup.billable())
                .estimatedDelivery(result.estimatedDelivery())
                .accountSource("MANUAL")
                .warehouseCode(resolvedWarehouseCode)
                .carrierAmount(markup.carrierRate())
                .billableAmount(markup.billable())
                .markupKind(markup.kind())
                .markupValue(markup.value())
                .markupCurrency(markup.currency())
                .dispatchNextBusinessDay(nextBusinessDay)
                .message("Manual shipment #" + orderNo + " labelled on "
                        + billToNumber + ".")
                .build();
        return success("Label generated successfully.", response);
    }

    /** One shipment attempt against a resolved account; throws on carrier failure. */
    private CarrierConnector.ShipmentResult attemptShipment(Order order, AccountResolution res, CarrierConnector connector) {
        connector.validateCredentials(res.clientId(), res.clientSecret());
        String accessToken = connector.getAccessToken(res.clientId(), res.clientSecret(), res.accountNumber());
        ShipmentRequestDTO shipmentRequest = buildShipmentRequest(order, res.accountNumber(), connector);

        // Fail fast on incomplete customs before the carrier call — the
        // carriers' own errors are terse ("Invalid commodity") and don't
        // name the missing field. IntlShipmentValidator concatenates every
        // gap into one actionable message.
        java.util.List<IntlShipmentValidator.ValidationError> intlErrors =
                IntlShipmentValidator.validate(shipmentRequest);
        if (!intlErrors.isEmpty()) {
            throw new com.multiship.backend.exception.CarrierConnectionException(
                    IntlShipmentValidator.toMessage(intlErrors));
        }

        // Sprint 48 B5 — packaging pre-flight on the order-cascade hot path.
        // Resolves the same preset buildShipmentRequest picked (extra 1ms
        // repo call in return for a clean separation from the request DTO).
        // Any HARD violation surfaces as a CarrierConnectionException with an
        // actionable message before we spend the carrier round-trip.
        if (packagingValidationEnabled) {
            com.multiship.backend.model.ShippingService resolvedService = shippingConfigService
                    .resolveService(connector.getCarrierCode(),
                            firstNonBlank(order.getTenantId(), order.getCustNo()),
                            order.getShipviaCd(), order.getShiptoCountryCd(),
                            isInternational(order), carrierProperties.getShipper().getCountryCode())
                    .orElse(null);
            com.multiship.backend.model.PackagePreset preset = shippingConfigService
                    .pickPackage(resolvedService != null ? resolvedService.getId() : null, order.getWeight())
                    .map(ShippingConfigService.PickedPackage::preset)
                    .orElse(null);
            com.multiship.backend.util.PackagingValidator.Outcome outcome =
                    preset == null
                            ? new com.multiship.backend.util.PackagingValidator.Outcome(java.util.List.of())
                            : com.multiship.backend.util.PackagingValidator.validateAll(
                                    preset,
                                    shipmentRequest.getWeight(), shipmentRequest.getWeightUnit(),
                                    shipmentRequest.getLength(), shipmentRequest.getWidth(),
                                    shipmentRequest.getHeight(), shipmentRequest.getDimUnit(),
                                    shipmentRequest.getPackages());
            // Sprint 48 freight/LTL: parcel-eligibility check.
            String svcCode = resolvedService != null ? resolvedService.getServiceCode()
                    : shipmentRequest.getServiceType();
            com.multiship.backend.model.CarrierShippingLimit svcLimit = carrierLimitService.resolveLimit(
                    connector.getCarrierCode(), svcCode, isInternational(order));
            outcome = outcome.merge(com.multiship.backend.util.PackagingValidator.validateParcelLimits(
                    connector.getCarrierCode(), svcCode, shipmentRequest.getPackages(),
                    shipmentRequest.getWeight(), shipmentRequest.getWeightUnit(),
                    shipmentRequest.getLength(), shipmentRequest.getWidth(),
                    shipmentRequest.getHeight(), shipmentRequest.getDimUnit(),
                    svcLimit == null ? null : svcLimit.getMaxTotalWeightLb()));
            if (outcome.hardBlock()) {
                throw new com.multiship.backend.exception.CarrierConnectionException(
                        outcome.combinedMessage()
                                + " Adjust packaging or set the preset enforcement to SOFT.");
            }
            if (!outcome.violations().isEmpty()) {
                log.warn("Packaging soft-warning on order {} (preset={}): {}",
                        order.getOrderNo(),
                        preset != null ? preset.getName() : "(none)",
                        outcome.combinedMessage());
            }
        }

        return connector.createShipment(shipmentRequest, accessToken,
                firstNonBlank(res.environment(), carrierProperties.getDefaultEnvironment()));
    }

    /** True when the order's ship-to country differs from the platform shipper's origin. */
    private boolean isInternational(Order order) {
        String origin = carrierProperties.getShipper().getCountryCode();
        String dest = order.getShiptoCountryCd();
        return StringUtils.hasText(origin) && StringUtils.hasText(dest)
                && !origin.trim().equalsIgnoreCase(dest.trim());
    }

    /**
     * The platform (house) account for a carrier to fall back to when a client
     * account fails — the newest complete, active platform account that isn't the
     * one that just failed. Null when there's no distinct platform account.
     */
    private AccountResolution platformFallback(String carrierCode, String failedAccountNumber) {
        String canonical = resolveCanonicalCarrierCode(carrierCode);
        return carrierAccountRefRepository.findPlatformAccountsByCarrier(canonical).stream()
                .filter(a -> failedAccountNumber == null || !failedAccountNumber.equalsIgnoreCase(a.getAccountNumber()))
                .findFirst()
                .map(a -> AccountResolution.of(AccountResolution.SCENARIO_PLATFORM_FALLBACK,
                        resolveCanonicalCarrierCode(firstNonBlank(a.getCarrierCode(), carrierCode)),
                        a.getAccountNumber(), a.getClientId(), a.getClientSecret(),
                        firstNonBlank(a.getEnvironment(), carrierProperties.getDefaultEnvironment()),
                        a.getAccountName()))
                .orElse(null);
    }

    /** Marks an order's tracking row ERROR with the failure reason (retryable from the Labels page). */
    private void markTrackingError(Order order, String message) {
        OrderTracking tracking = orderTrackingRepository.findByOrderNo(order.getOrderNo()).orElseGet(OrderTracking::new);
        tracking.setOrderNo(order.getOrderNo());
        tracking.setOrderSuffix(order.getOrderSuffix());
        tracking.setShipViaCd(order.getShipviaCd());
        tracking.setIsLabelGenerated(false);
        tracking.setStatus("ERROR");
        tracking.setErrorMessage(truncate(message, 255));
        tracking.setCreatedAt(tracking.getCreatedAt() != null ? tracking.getCreatedAt() : LocalDateTime.now());
        tracking.setUpdatedAt(LocalDateTime.now());
        orderTrackingRepository.save(tracking);
    }

    /**
     * The client's three-scenario account resolution:
     * 1. ORDER — the order carries complete carrier credentials; use them directly.
     * 2. REFERENCE — the order carries an account number that matches a complete
     *    entry in the carrier_account_ref book.
     * 3. NEEDS_DETAILS — the order carries partial details; the user must fill the rest.
     * 4. DEFAULT — the order carries nothing; use the admin's global default account.
     * 5. NO_DEFAULT — nothing on the order and no global default configured.
     */
    private AccountResolution resolveAccountForOrder(Order order) {
        return resolveAccountForOrderWithDetails(order,
                orderCarrierDetailsRepository.findByOrderNo(order.getOrderNo()).orElse(null));
    }

    /**
     * Sprint 49 Tier 3 Fix 2 — the resolve logic below re-parameterised
     * to accept a pre-fetched {@link OrderCarrierDetails} so the batch
     * caller can prefetch all rows in one query and drop N per-order
     * SELECTs.
     */
    private AccountResolution resolveAccountForOrderWithDetails(Order order, OrderCarrierDetails details) {
        // Canonical vocabulary at the edge: the order's raw ship-via code
        // (P80/F77/L01) never leaks into resolutions or responses.
        String carrierCode = resolveCanonicalCarrierCode(firstNonBlank(
                details != null ? details.getCarrierCode() : null,
                order.getShipviaCd(),
                carrierProperties.getDefaultCarrierCode()));

        if (details != null && details.isComplete()) {
            return AccountResolution.of(AccountResolution.SCENARIO_ORDER, carrierCode,
                    details.getAccountNumber(), details.getClientId(), details.getClientSecret(),
                    firstNonBlank(details.getEnvironment(), carrierProperties.getDefaultEnvironment()), null);
        }

        if (details != null && details.hasAnyDetail()) {
            // Try to complete the picture from the reference book by account number.
            CarrierAccountRef ref = StringUtils.hasText(details.getAccountNumber())
                    ? carrierAccountRefRepository
                            .findFirstByAccountNumberIgnoreCaseOrderByUpdatedAtDesc(details.getAccountNumber().trim())
                            .filter(candidate -> !Boolean.FALSE.equals(candidate.getActive()))
                            .orElse(null)
                    : null;

            if (ref != null && ref.isComplete()) {
                return AccountResolution.of(AccountResolution.SCENARIO_REFERENCE,
                        resolveCanonicalCarrierCode(firstNonBlank(ref.getCarrierCode(), carrierCode)),
                        ref.getAccountNumber(), ref.getClientId(), ref.getClientSecret(),
                        firstNonBlank(ref.getEnvironment(), carrierProperties.getDefaultEnvironment()),
                        ref.getAccountName());
            }

            List<String> missing = new ArrayList<>();
            if (!StringUtils.hasText(details.getAccountNumber())) {
                missing.add("accountNumber");
            }
            if (!StringUtils.hasText(details.getClientId()) && (ref == null || !StringUtils.hasText(ref.getClientId()))) {
                missing.add("clientId");
            }
            missing.add("clientSecret");

            return AccountResolution.needsDetails(carrierCode,
                    details.getAccountNumber(),
                    firstNonBlank(details.getClientId(), ref != null ? ref.getClientId() : null),
                    firstNonBlank(details.getEnvironment(), carrierProperties.getDefaultEnvironment()),
                    missing);
        }

        // Client layer: the order must belong to a registered, active client
        // before any default account applies. Unregistered client codes block
        // generation with a prompt to add the client first.
        String clientCode = firstNonBlank(order.getTenantId(), order.getCustNo());
        clientCode = StringUtils.hasText(clientCode) ? clientCode.trim().toUpperCase(Locale.ROOT) : null;
        Client client = clientCode != null
                ? clientRepository.findByClientCodeIgnoreCase(clientCode).orElse(null)
                : null;

        if (client == null) {
            return AccountResolution.of(AccountResolution.SCENARIO_CLIENT_MISSING, carrierCode,
                    null, null, null, null, null);
        }

        if (!client.isActive()) {
            return AccountResolution.of(AccountResolution.SCENARIO_CLIENT_INACTIVE, carrierCode,
                    null, null, null, null, null);
        }

        // Sprint 50 Tier 1 (finding #19) — the "always ask" invariant is now
        // upheld: no matter how many of the client's own accounts exist on
        // this carrier (1, 2, or 10), the operator picks one at generation
        // time. Previously a single account silently auto-picked, which
        // contradicted the same method's earlier comment ("A client with
        // several UPS accounts must always be asked which UPS account to
        // bill"). Uniformity trumps convenience: a "single-account shortcut"
        // is a per-client opt-in setting, not a silent code default.
        //
        // Zero client accounts on this carrier still falls through to the
        // platform (house) account so unonboarded carriers keep shipping;
        // any failure at the carrier surfaces as CLIENT_CARRIER_AUTH_FAILED
        // (finding #3) rather than a silent platform-bill.
        String carrier = carrierCode;
        List<CarrierAccountRef> clientCarrierAccounts = carrierAccountRefRepository
                .findByCustomerNoIgnoreCaseOrderByClientDefaultDescUpdatedAtDesc(clientCode).stream()
                .filter(a -> !Boolean.FALSE.equals(a.getActive()))
                .filter(CarrierAccountRef::isComplete)
                .filter(a -> carrier.equalsIgnoreCase(
                        resolveCanonicalCarrierCode(firstNonBlank(a.getCarrierCode(), carrier))))
                .toList();

        if (clientCarrierAccounts.isEmpty()) {
            // No account for this client on this carrier → ship on the PLATFORM
            // (house) account automatically, in the background. Platform accounts
            // are never offered in the picker — the operator only sees the
            // client's own accounts.
            CarrierAccountRef platform = carrierAccountRefRepository
                    .findPlatformAccountsByCarrier(carrier).stream().findFirst().orElse(null);
            if (platform != null) {
                return AccountResolution.of(AccountResolution.SCENARIO_DEFAULT,
                        resolveCanonicalCarrierCode(firstNonBlank(platform.getCarrierCode(), carrier)),
                        platform.getAccountNumber(), platform.getClientId(), platform.getClientSecret(),
                        firstNonBlank(platform.getEnvironment(), carrierProperties.getDefaultEnvironment()),
                        platform.getAccountName());
            }
            // Nothing for this client and no platform account → nothing to ship with.
            return AccountResolution.of(AccountResolution.SCENARIO_CHOOSE_ACCOUNT, carrier,
                    null, null, null, null, null);
        }

        // 1+ of the CLIENT's own accounts on this carrier → the operator picks
        // one of them (Sprint 50 Tier 1 removed the 1-account auto-pick shortcut).
        // The client's per-carrier default rides along (in the accountNumber
        // slot) so the picker can pre-select it — for a lone account that
        // pre-selection is the entire pick.
        String suggested = clientCarrierAccounts.stream()
                .filter(a -> Boolean.TRUE.equals(a.getClientDefault()))
                .map(CarrierAccountRef::getAccountNumber)
                .findFirst()
                // No explicit default flag — pre-select the only/first account.
                .orElseGet(() -> clientCarrierAccounts.get(0).getAccountNumber());
        return AccountResolution.of(AccountResolution.SCENARIO_CHOOSE_ACCOUNT, carrier,
                suggested, null, null, null, null);
    }

    @Override
    @Transactional(readOnly = true)
    public ApiResponse<List<OrderAccountResolutionDTO>> resolveOrderAccounts(List<Integer> orderNos) {
        if (CollectionUtils.isEmpty(orderNos)) {
            return success("No orders to resolve.", List.of());
        }

        // Sprint 49 Tier 3 Fix 2 — batch-preload orders and carrier-detail
        // rows. Previously called findByOrderNo + findByOrderNo per order,
        // then resolveAccountForOrder did another N lookups internally.
        // A 100-row page issued ~400 queries; this drops the outer loop to 2.
        List<Integer> distinctNos = orderNos.stream().distinct().toList();
        java.util.Map<Integer, Order> orders = orderRepository.findByOrderNoIn(distinctNos).stream()
                .collect(java.util.stream.Collectors.toMap(Order::getOrderNo, o -> o, (a, b) -> a));
        java.util.Map<Integer, OrderCarrierDetails> detailsByOrder =
                orderCarrierDetailsRepository.findByOrderNoIn(distinctNos).stream()
                        .collect(java.util.stream.Collectors.toMap(
                                OrderCarrierDetails::getOrderNo, d -> d, (a, b) -> a));

        List<OrderAccountResolutionDTO> resolutions = new java.util.ArrayList<>(distinctNos.size());
        for (Integer orderNo : distinctNos) {
            Order order = orders.get(orderNo);
            if (order == null) continue;  // skip missing orders — same as prior .filter(nonNull)
            // Sprint 50 Tier 0.5 PR G — per-order tenant guard. A scoped
            // USER passing a foreign orderNo in the batch aborts the whole
            // resolve (fail-fast). Operators pass through unchanged.
            tenantScope.requireTenantMatch(
                    StringUtils.hasText(order.getTenantId()) ? order.getTenantId() : order.getCustNo());
            AccountResolution resolution = resolveAccountForOrderWithDetails(
                    order, detailsByOrder.get(orderNo));
            resolutions.add(OrderAccountResolutionDTO.builder()
                    .orderNo(orderNo)
                    .scenario(resolution.scenario())
                    .carrierCode(resolution.carrierCode())
                    .accountNumber(resolution.accountNumber())
                    .accountName(resolution.accountName())
                    .environment(resolution.environment())
                    .missingFields(resolution.missingFields())
                    .prefillClientId(resolution.clientId())
                    .build());
        }

        return success("Order account resolutions computed successfully.", resolutions);
    }

    /** Internal carrier-account resolution result for one order. */
    private record AccountResolution(
            String scenario,
            String carrierCode,
            String accountNumber,
            String clientId,
            String clientSecret,
            String environment,
            String accountName,
            List<String> missingFields
    ) {
        static final String SCENARIO_ORDER = "ORDER";
        static final String SCENARIO_REFERENCE = "REFERENCE";
        /**
         * Retained for wire-format compatibility with older clients that
         * may still branch on the string. Sprint 50 Tier 1 (finding #19)
         * stopped emitting it from resolveAccountForOrderWithDetails: a
         * single client-owned account no longer auto-picks; the shipper
         * always chooses (CHOOSE_ACCOUNT) so the "always ask" invariant
         * is uniform.
         */
        static final String SCENARIO_CLIENT_DEFAULT = "CLIENT_DEFAULT";
        static final String SCENARIO_MANUAL = "MANUAL";
        static final String SCENARIO_PLATFORM_FALLBACK = "PLATFORM_FALLBACK";
        static final String SCENARIO_CHOOSE_ACCOUNT = "CHOOSE_ACCOUNT";
        static final String SCENARIO_DEFAULT = "DEFAULT";
        static final String SCENARIO_CLIENT_MISSING = "CLIENT_MISSING";
        static final String SCENARIO_CLIENT_INACTIVE = "CLIENT_INACTIVE";
        static final String SCENARIO_NEEDS_DETAILS = "NEEDS_DETAILS";
        static final String SCENARIO_NO_DEFAULT = "NO_DEFAULT";

        static AccountResolution of(String scenario, String carrierCode, String accountNumber,
                                    String clientId, String clientSecret, String environment, String accountName) {
            return new AccountResolution(scenario, carrierCode, accountNumber, clientId, clientSecret,
                    environment, accountName, null);
        }

        static AccountResolution needsDetails(String carrierCode, String accountNumber, String clientId,
                                              String environment, List<String> missingFields) {
            return new AccountResolution(SCENARIO_NEEDS_DETAILS, carrierCode, accountNumber, clientId, null,
                    environment, null, missingFields);
        }

        static AccountResolution noDefault(String carrierCode) {
            return new AccountResolution(SCENARIO_NO_DEFAULT, carrierCode, null, null, null, null, null, null);
        }

        String sourceDescription() {
            return switch (scenario) {
                case SCENARIO_ORDER -> "carrier details on the order";
                case SCENARIO_REFERENCE -> "saved reference account " + accountNumber;
                case SCENARIO_CLIENT_DEFAULT -> "client default account " + accountNumber;
                case SCENARIO_MANUAL -> "manually selected account " + accountNumber;
                case SCENARIO_DEFAULT -> "default account " + accountNumber;
                default -> "carrier account";
            };
        }
    }

    @Override
    public CarrierConnector getCarrierConnector(String carrierCode) {
        if (!StringUtils.hasText(carrierCode)) {
            throw new CarrierConnectionException("Carrier code is required.");
        }

        String canonicalCarrierCode = resolveCanonicalCarrierCode(carrierCode);
        return carrierConnectors.stream()
                .filter(connector -> connector.getCarrierCode().equalsIgnoreCase(canonicalCarrierCode))
                .findFirst()
                .orElseThrow(() -> new CarrierConnectionException("Unsupported carrier: " + carrierCode));
    }

    private User resolveUser(UserDetails userDetails) {
        if (userDetails == null || !StringUtils.hasText(userDetails.getUsername())) {
            throw new CarrierConnectionException("Authenticated user is required.");
        }

        return userRepository.findByUsername(userDetails.getUsername())
                .orElseThrow(() -> new CarrierConnectionException("User not found: " + userDetails.getUsername()));
    }

    private ShipVia resolveShipVia(String carrierCode) {
        return shipViaRepository.findByShipviaCdIgnoreCase(carrierCode)
                .orElseThrow(() -> new CarrierConnectionException("ShipVia row not found for carrier " + carrierCode));
    }

    private String resolveCarrierCode(User user) {
        if (StringUtils.hasText(user.getPreferredCarrier())) {
            return user.getPreferredCarrier();
        }

        return carrierProperties.getDefaultCarrierCode();
    }

    /**
     * The API speaks ONE carrier vocabulary: UPS / FEDEX / USPS. Legacy
     * ship-via codes (P80/F77/L01) are tolerated on input for back-compat
     * but are never emitted by the API.
     */
    private String resolveCanonicalCarrierCode(String carrierCode) {
        return switch (carrierCode.toUpperCase(Locale.ROOT)) {
            case "P80" -> "UPS";
            case "F77" -> "FEDEX";
            case "L01" -> "USPS";
            default -> carrierCode.toUpperCase(Locale.ROOT);
        };
    }

    /**
     * Maps a canonical carrier code to the client's internal ship-via code
     * (the ship_vias table key). Persistence detail only — never exposed.
     */
    private String toShipViaCode(String canonicalCarrierCode) {
        return switch (canonicalCarrierCode.toUpperCase(Locale.ROOT)) {
            case "UPS" -> "P80";
            case "FEDEX" -> "F77";
            case "USPS" -> "L01";
            default -> canonicalCarrierCode.toUpperCase(Locale.ROOT);
        };
    }

    private String resolveConnectorName(String carrierCode) {
        return getCarrierConnector(carrierCode).getCarrierName();
    }

    private CarrierConnector.CarrierConfiguration resolveCarrierConfiguration(String carrierCode) {
        return getCarrierConnector(carrierCode).getConfiguration();
    }

    private void persistCarrierDetails(
            User user,
            String preferredCarrier,
            String carrierClientId,
            String carrierClientSecret,
            String carrierAccountNumber,
            String carrierAccessToken,
            LocalDateTime carrierTokenExpiresAt,
            boolean carrierConnected,
            LocalDateTime carrierConnectedAt,
            String carrierEnvironment
    ) {
        userRepository.updateCarrierDetails(
                user.getId(),
                truncate(preferredCarrier, 50),
                truncate(carrierClientId, 255),
                truncate(carrierClientSecret, 255),
                truncate(carrierAccountNumber, 100),
                carrierAccessToken,
                carrierTokenExpiresAt,
                carrierConnected,
                carrierConnectedAt,
                truncate(carrierEnvironment, 20)
        );
    }

    private String truncate(String value, int maxLength) {
        if (!StringUtils.hasText(value) || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private CarrierListResponse toCarrierListResponse(CarrierConnector connector) {
        CarrierConnector.CarrierConfiguration configuration = connector.getConfiguration();
        return CarrierListResponse.builder()
                .carrierCode(connector.getCarrierCode())
                .carrierName(connector.getCarrierName())
                .description(configuration.connectionGuide())
                .active(configuration.active())
                .environment(configuration.defaultEnvironment())
                .logoUrl(configuration.logoUrl())
                .documentationUrl(configuration.documentationUrl())
                .connectionGuide(configuration.connectionGuide())
                .build();
    }

    private ShipmentRequestDTO buildShipmentRequest(Order order, CarrierConfig config, CarrierConnector connector) {
        return buildShipmentRequest(order, config.getAccountNumber(), connector);
    }

    private ShipmentRequestDTO buildShipmentRequest(Order order, String accountNumber, CarrierConnector connector) {
        CarrierProperties.ShipperDefaults shipper = carrierProperties.getShipper();

        // Service: the ship-method RULE engine (client + destination aware,
        // most-specific wins) → enabled catalog service; the old connector
        // default is only the last-resort fallback. International here =
        // COUNTRY difference (service level, not customs).
        boolean international = order.getShiptoCountryCd() != null && shipper.getCountryCode() != null
                && !order.getShiptoCountryCd().trim().equalsIgnoreCase(shipper.getCountryCode().trim());
        String orderClient = firstNonBlank(order.getTenantId(), order.getCustNo());
        // Origin warehouse: use the client's default attachment when we can
        // find one, otherwise null (unrestricted rules still match). This is
        // what feeds ShipMethodRuleWarehouse-based rule filtering.
        Long originWarehouseId = orderClient != null
                ? resolutionService.resolveWarehouse(orderClient, null)
                        .map(com.multiship.backend.model.Warehouse::getId).orElse(null)
                : null;
        com.multiship.backend.model.ShippingService resolvedService = shippingConfigService
                .resolveService(connector.getCarrierCode(), orderClient, order.getShipviaCd(),
                        order.getShiptoCountryCd(), international, shipper.getCountryCode(), originWarehouseId)
                .orElse(null);
        String serviceType = resolvedService != null ? resolvedService.getServiceCode()
                : firstNonBlank(connector.getConfiguration().defaultServiceType(), "GROUND");

        // Package: auto-picked from the service's linked packages (smallest
        // box whose max weight fits the order), falling back to the global
        // default preset. Weight = order weight + the box's tare.
        com.multiship.backend.model.PackagePreset preset = shippingConfigService
                .pickPackage(resolvedService != null ? resolvedService.getId() : null, order.getWeight())
                .map(ShippingConfigService.PickedPackage::preset)
                .orElse(null);
        String packageType = preset != null
                ? ("CARRIER".equalsIgnoreCase(preset.getKind()) ? preset.getCarrierPackageCode() : "YOUR_PACKAGING")
                : firstNonBlank(connector.getConfiguration().defaultPackageType(), "YOUR_PACKAGING");
        // BILLABLE weight: max(actual + tare, dimensional weight) — what the
        // carrier actually charges for; flat-rate packaging skips DIM.
        BigDecimal weight = com.multiship.backend.util.PackageMath.billableWeight(preset, order.getWeight());

        // International metadata: look up the order's customs declaration and
        // the client's customs profile for this destination. Both are optional;
        // when both are absent we skip the intl block (domestic shipment). The
        // populator merges order values over profile values so per-shipment
        // overrides win — profile values only fill blanks.
        com.multiship.backend.model.OrderCustoms customs = order.getOrderNo() == null
                ? null
                : orderCustomsRepository.findByOrderNoIgnoreCase(String.valueOf(order.getOrderNo())).orElse(null);
        com.multiship.backend.model.ClientCustomsProfile profile =
                orderClient != null && order.getShiptoCountryCd() != null
                        ? clientCustomsProfileRepository
                                .findByClientAndCountry(orderClient, order.getShiptoCountryCd())
                                .orElse(null)
                        : null;
        // Customs boundary: a country mismatch alone isn't enough — customs
        // unions (EU, EAEU, GCC, SACU) treat their members as one territory,
        // so a FR→DE parcel crosses a country border but NOT a customs
        // border. CustomsTerritories.sameTerritory() suppresses the intl
        // block for those pairs so we don't build an unnecessary invoice.
        boolean sameCustomsTerritory = com.multiship.backend.util.CustomsTerritories
                .sameTerritory(shipper.getCountryCode(), order.getShiptoCountryCd());
        boolean customsRequired = international && !sameCustomsTerritory;
        com.multiship.backend.dto.IntlShipmentBlockDTO intlBlock = buildIntlBlock(customsRequired, customs, profile);

        // Currency for declared value: prefer customs currency (OrderCustoms /
        // profile default), fall back to null → FedEx defaults to USD, matching
        // the pre-fix behavior for domestic-only accounts.
        String declaredValueCurrency = intlBlock != null ? intlBlock.getCustomsCurrency() : null;

        // Weight/dim unit: from the customs declaration when available, else
        // LB/IN (the historical default the connectors assumed).
        // TODO: Sprint 50 Tier 2 finding #16 — source from Client.defaultWeightUnit
        // when customs.weightUnit is absent, instead of hardcoded LB. The API
        // boundary in ExternalApiService already fails loud when a caller supplies
        // weight without a unit; this internal path defaults for legacy domestic flows.
        String weightUnit = customs != null && customs.getWeightUnit() != null ? customs.getWeightUnit() : "LB";
        String dimUnit = "IN"; // Package presets standardize on inches; a per-preset unit lands with the address model rework.

        return ShipmentRequestDTO.builder()
                .carrierCode(connector.getCarrierCode())
                .accountNumber(firstNonBlank(accountNumber, "ACCOUNT"))
                .serviceType(serviceType)
                .packageType(packageType)
                .length(preset != null ? preset.getLength() : null)
                .width(preset != null ? preset.getWidth() : null)
                .height(preset != null ? preset.getHeight() : null)
                .weight(weight)
                .weightUnit(weightUnit)
                .dimUnit(dimUnit)
                .shipperName(shipper.getName())
                .shipperPhone(shipper.getPhone())
                .shipperAddressLine1(shipper.getAddressLine1())
                .shipperAddressLine2(shipper.getAddressLine2())
                .shipperCity(shipper.getCity())
                .shipperState(shipper.getState())
                .shipperPostalCode(shipper.getPostalCode())
                .shipperCountryCode(shipper.getCountryCode())
                .recipientName(firstNonBlank(order.getShipName(), order.getShipAttn(), order.getCustNo()))
                .recipientPhone(firstNonBlank(order.getPhone(), "0000000000"))
                .recipientAddressLine1(firstNonBlank(order.getShipAddr1(), order.getLocation(), ""))
                .recipientAddressLine2(null)
                .recipientCity(firstNonBlank(order.getShiptoCity(), ""))
                .recipientState(firstNonBlank(order.getShiptoState(), ""))
                .recipientPostalCode(firstNonBlank(order.getShiptoZip(), ""))
                .recipientCountryCode(firstNonBlank(order.getShiptoCountryCd(), "US"))
                .referenceNumber(order.getOrderNo() != null ? String.valueOf(order.getOrderNo()) : null)
                .specialInstructions(firstNonBlank(order.getGoodsDesc(), order.getShipVia()))
                .declaredValue(order.getPrice())
                .declaredValueCurrency(declaredValueCurrency)
                .intl(intlBlock)
                // Sprint 25 — thread the Order entity's isReturn flag so
                // ERP-side return orders get the carrier return-label wire
                // format on the automatic label path too (not just manual).
                // Order.isReturn is a legacy 'Y'/'N' column; anything starting
                // with Y (case-insensitive) counts as a return.
                .isReturn("Y".equalsIgnoreCase(
                        order.getIsReturn() == null ? "" : order.getIsReturn().trim()))
                .build();
    }

    /**
     * Build the international shipment block from the order's customs
     * declaration and the client's customs profile. Returns null when the
     * shipment is domestic OR there's no customs data on either side.
     *
     * <p>Merge order: {@code customs} (order-level) → {@code profile}
     * (client-level default) → null. First non-null wins. Commodity list
     * comes only from the order (profile can't hold line items).
     */
    private com.multiship.backend.dto.IntlShipmentBlockDTO buildIntlBlock(
            boolean international,
            com.multiship.backend.model.OrderCustoms customs,
            com.multiship.backend.model.ClientCustomsProfile profile) {
        if (!international) return null;
        if (customs == null && profile == null) return null;

        java.util.List<com.multiship.backend.dto.CustomsCommodityDTO> commodities =
                customs == null || customs.getItems() == null
                        ? java.util.List.of()
                        : customs.getItems().stream()
                                .map(i -> com.multiship.backend.dto.CustomsCommodityDTO.builder()
                                        .description(i.getDescription())
                                        .hsCode(i.getHsCode())
                                        .countryOfOrigin(i.getCountryOfOrigin())
                                        .quantity(i.getQuantity())
                                        .unitValue(i.getUnitValue())
                                        .unitWeight(i.getWeight())
                                        .sku(i.getSku())
                                        .boxSeq(i.getBoxSeq())  // Sprint 48 B11
                                        .build())
                                .toList();

        BigDecimal customsTotal = commodities.stream()
                .map(com.multiship.backend.dto.CustomsCommodityDTO::lineTotalValue)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return com.multiship.backend.dto.IntlShipmentBlockDTO.builder()
                .international(true)
                .incoterms(firstNonBlank(customs == null ? null : customs.getIncoterms(),
                        profile == null ? null : profile.getIncoterms()))
                .reasonForExport(firstNonBlank(customs == null ? null : customs.getReasonForExport(),
                        profile == null ? null : profile.getReasonForExport()))
                .customsCurrency(firstNonBlank(customs == null ? null : customs.getCurrency(),
                        profile == null ? null : profile.getCurrency()))
                .customsTotalValue(customsTotal.signum() == 0 ? null : customsTotal)
                .weightUnit(customs == null ? null : customs.getWeightUnit())
                .dimUnit(null)
                .importerType(profile == null ? null : profile.getImporterType())
                .importerName(firstNonBlank(
                        customs != null && customs.getImporterAddress() != null ? customs.getImporterAddress().getName() : null,
                        profile == null ? null : profile.getImporterName()))
                .importerContact(profile == null ? null : profile.getImporterContact())
                .importerCompany(firstNonBlank(customs == null ? null : customs.getImporterCompany(),
                        profile == null ? null : profile.getImporterName()))
                .importerAddressLine1(firstNonBlank(
                        customs != null && customs.getImporterAddress() != null ? customs.getImporterAddress().getLine1() : null,
                        profile == null ? null : profile.getImporterAddress1()))
                .importerAddressLine2(firstNonBlank(
                        customs != null && customs.getImporterAddress() != null ? customs.getImporterAddress().getLine2() : null,
                        profile == null ? null : profile.getImporterAddress2()))
                .importerCity(firstNonBlank(
                        customs != null && customs.getImporterAddress() != null ? customs.getImporterAddress().getCity() : null,
                        profile == null ? null : profile.getImporterCity()))
                .importerState(firstNonBlank(
                        customs != null && customs.getImporterAddress() != null ? customs.getImporterAddress().getState() : null,
                        profile == null ? null : profile.getImporterState()))
                .importerPostcode(firstNonBlank(
                        customs != null && customs.getImporterAddress() != null ? customs.getImporterAddress().getZip() : null,
                        profile == null ? null : profile.getImporterPostcode()))
                .importerCountry(firstNonBlank(
                        customs != null && customs.getImporterAddress() != null ? customs.getImporterAddress().getCountry() : null,
                        profile == null ? null : profile.getImporterCountry()))
                .importerPhone(firstNonBlank(
                        customs != null && customs.getImporterAddress() != null ? customs.getImporterAddress().getPhone() : null,
                        profile == null ? null : profile.getImporterPhone()))
                .importerTaxId(firstNonBlank(customs == null ? null : customs.getImporterTaxId(),
                        profile == null ? null : profile.getImporterTaxId()))
                .importerTaxIdType(profile == null ? null : profile.getImporterTaxIdType())
                .importerVat(customs == null ? null : customs.getImporterVat())
                .importerEori(firstNonBlank(customs == null ? null : customs.getImporterEori(),
                        profile == null ? null : profile.getImporterEori()))
                .importerIoss(profile == null ? null : profile.getImporterIoss())
                .importerCompanyReg(profile == null ? null : profile.getImporterCompanyReg())
                .importerIec(profile == null ? null : profile.getImporterIec())
                .importerGstin(profile == null ? null : profile.getImporterGstin())
                .brokerName(profile == null ? null : profile.getBrokerName())
                .brokerCompany(profile == null ? null : profile.getBrokerCompany())
                .brokerAddressLine1(profile == null ? null : profile.getBrokerAddress1())
                .brokerAddressLine2(profile == null ? null : profile.getBrokerAddress2())
                .brokerCity(profile == null ? null : profile.getBrokerCity())
                .brokerState(profile == null ? null : profile.getBrokerState())
                .brokerPostcode(profile == null ? null : profile.getBrokerPostcode())
                .brokerCountry(profile == null ? null : profile.getBrokerCountry())
                .brokerPhone(profile == null ? null : profile.getBrokerPhone())
                .brokerId(profile == null ? null : profile.getBrokerId())
                .brokerLicense(profile == null ? null : profile.getBrokerLicense())
                .dutyBillTo(profile == null ? null : profile.getDutiesBillTo())
                .dutyAccount(profile == null ? null : profile.getDutiesAccount())
                .commodities(commodities)
                .build();
    }

    private CarrierConfig getDefaultTenantCarrierConfig(String tenantId) {
        return carrierConfigRepository.findFirstByTenantIdAndIsDefaultTrueOrderByUpdatedAtDesc(tenantId)
                .or(() -> carrierConfigRepository.findFirstByTenantIdOrderByUpdatedAtDesc(tenantId))
                .orElseThrow(() -> new CarrierConnectionException("No carrier account found for tenant " + tenantId + "."));
    }

    private void refreshTenantCarrierTokenIfNeeded(CarrierConfig config, CarrierConnector connector) {
        if (StringUtils.hasText(config.getAccessToken()) && config.getTokenExpiresAt() != null
                && LocalDateTime.now().isBefore(config.getTokenExpiresAt())) {
            return;
        }

        String clientId = config.getClientId();
        String clientSecret = config.getClientSecret();
        if (!StringUtils.hasText(clientId) || !StringUtils.hasText(clientSecret)) {
            throw new CarrierConnectionException(
                    "Carrier credentials are missing for tenant " + config.getTenantId() + " and carrier " + config.getCarrierCode() + "."
            );
        }

        connector.validateCredentials(clientId, clientSecret);
        String token = connector.getAccessToken(clientId, clientSecret, config.getAccountNumber());
        LocalDateTime expiresAt = LocalDateTime.now().plusHours(1);
        config.setAccessToken(token);
        config.setTokenExpiresAt(expiresAt);
        config.setActive(true);
        carrierConfigRepository.save(config);
    }

    private CarrierAccountDTO toCarrierAccountDTO(CarrierConfig config) {
        return CarrierAccountDTO.builder()
                .id(config.getId())
                .tenantId(config.getTenantId())
                .carrierCode(config.getCarrierCode())
                .carrierName(config.getCarrierName())
                .accountNumber(config.getAccountNumber())
                .accountCode(config.getAccountCode())
                .isDefault(Boolean.TRUE.equals(config.getIsDefault()))
                .active(config.getActive())
                .environment(config.getEnvironment())
                .shipViaCd(config.getShipVia() != null ? config.getShipVia().getShipviaCd() : null)
                .shipViaDescription(config.getShipVia() != null ? config.getShipVia().getShipviaDesc() : null)
                .createdAt(config.getCreatedAt())
                .updatedAt(config.getUpdatedAt())
                .build();
    }

    private String normalizeEnvironment(String environment, String defaultEnvironment) {
        return StringUtils.hasText(environment) ? environment.toUpperCase(Locale.ROOT) : defaultEnvironment;
    }

    private String maskToken(String token) {
        if (!StringUtils.hasText(token) || token.length() <= 8) {
            return token;
        }
        return token.substring(0, 4) + "..." + token.substring(token.length() - 4);
    }

    /**
     * When the order ships across a border (destination country != origin
     * country) and has no customs declaration with at least one line item,
     * returns a message explaining the block; otherwise returns null (proceed).
     * Origin = the client's ship-from country, else the warehouse default.
     */
    private String requireCustomsIfInternational(Order order, String clientCode) {
        String shipToCountry = order.getShiptoCountryCd() != null ? order.getShiptoCountryCd().trim() : "";
        if (shipToCountry.isEmpty()) {
            return null;
        }

        String originCountry = carrierProperties.getShipper().getCountryCode();
        if (clientCode != null && !clientCode.isBlank()) {
            com.multiship.backend.model.Client client =
                    clientRepository.findByClientCodeIgnoreCase(clientCode.trim().toUpperCase()).orElse(null);
            if (client != null && client.getShipFrom() != null && client.getShipFrom().hasValue()
                    && client.getShipFrom().getCountry() != null && !client.getShipFrom().getCountry().isBlank()) {
                originCountry = client.getShipFrom().getCountry();
            }
        }

        // Customs applies only across a CUSTOMS border — a different country in
        // the same customs union (e.g. DE→FR inside the EU) ships like domestic:
        // no declaration, no importer, no broker, so no profile required.
        boolean international = originCountry != null
                && !com.multiship.backend.util.CustomsTerritories.sameTerritory(originCountry, shipToCountry);
        if (!international) {
            return null;
        }

        // Fully automated: the precondition is a USABLE Importer/Broker profile
        // covering (client, destination country) — one with a real importer
        // name + address, not just a saved row. Set up once in Settings →
        // Importer/Broker, then every shipment to those countries resolves
        // automatically.
        // RECEIVER (DAP) profiles pass without a fixed importer — the order's
        // consignee IS the importer of record and the carrier collects their
        // KYC. BUSINESS (DDP) profiles must carry a usable importer identity.
        boolean hasProfile = clientCode != null && !clientCode.isBlank()
                && clientCustomsProfileRepository
                        .findByClientAndCountry(clientCode.trim().toUpperCase(), shipToCountry.toUpperCase())
                        .filter(p -> "RECEIVER".equalsIgnoreCase(p.getImporterType())
                                || (org.springframework.util.StringUtils.hasText(p.getImporterName())
                                        && org.springframework.util.StringUtils.hasText(p.getImporterAddress1())
                                        && org.springframework.util.StringUtils.hasText(p.getImporterCity())))
                        .isPresent();
        if (hasProfile) {
            return null;
        }

        return "Order " + order.getOrderNo() + " ships internationally to " + shipToCountry.toUpperCase()
                + " — set up a complete Importer/Broker profile (importer name + address) for "
                + (clientCode != null ? clientCode : "the client") + " covering " + shipToCountry.toUpperCase()
                + " in Settings → Importer/Broker before generating.";
    }

    private <T> ApiResponse<T> success(String message, T data) {
        return ApiResponse.<T>builder()
                .status("success")
                .code(HttpStatus.OK.value())
                .message(message)
                .timestamp(LocalDateTime.now())
                .data(data)
                .build();
    }

    private <T> ApiResponse<T> failure(HttpStatus status, ErrorCode errorCode, String message) {
        return failure(status, errorCode, message, null);
    }

    /**
     * Failure that still carries a structured payload — used for 422
     * (needs-details prefill) and 409 (existing tracking details) so clients
     * can act on the body after branching on the status/errorCode.
     */
    private <T> ApiResponse<T> failure(HttpStatus status, ErrorCode errorCode, String message, T data) {
        return ApiResponse.<T>builder()
                .status("error")
                .code(status.value())
                .errorCode(errorCode.name())
                .message(message)
                .timestamp(LocalDateTime.now())
                .data(data)
                .build();
    }

    @SafeVarargs
    private final <T> T firstNonBlank(T... values) {
        for (T value : values) {
            if (value instanceof String stringValue) {
                if (StringUtils.hasText(stringValue)) {
                    return value;
                }
            } else if (value != null) {
                return value;
            }
        }
        return null;
    }

    private <T> T firstNonNull(T first, T second) {
        return first != null ? first : second;
    }

    /**
     * Convert a resolver exception into the manual-shipment
     * {@link ApiResponse} shape. WAREHOUSE_ATTACH_FORBIDDEN maps to 403
     * (a specific tenancy boundary was crossed); everything else 422.
     */
    private <T> ApiResponse<T> toResolutionFailure(ShipmentResolutionException e) {
        HttpStatus status = e.getErrorCode() == ErrorCode.WAREHOUSE_ATTACH_FORBIDDEN
                ? HttpStatus.FORBIDDEN
                : HttpStatus.UNPROCESSABLE_ENTITY;
        return failure(status, e.getErrorCode(), e.getMessage());
    }

    /**
     * Override the caller-supplied {@code from} block with a resolved
     * warehouse's address. Preserves non-address fields (email) from the
     * original when the warehouse doesn't provide them.
     */
    private com.multiship.backend.dto.ManualShipmentRequest.Address mergeFromWarehouse(
            com.multiship.backend.dto.ManualShipmentRequest.Address original,
            Warehouse warehouse) {
        com.multiship.backend.model.Address a = warehouse.getAddress();
        if (a == null) return original;
        com.multiship.backend.dto.ManualShipmentRequest.Address merged =
                new com.multiship.backend.dto.ManualShipmentRequest.Address();
        merged.setName(firstNonBlank(a.getName(), original != null ? original.getName() : null));
        // Warehouse address has no distinct company field — reuse name, then
        // fall back to whatever the caller sent.
        merged.setCompany(firstNonBlank(a.getName(), original != null ? original.getCompany() : null));
        merged.setPhone(firstNonBlank(a.getPhone(), original != null ? original.getPhone() : null));
        merged.setEmail(original != null ? original.getEmail() : null);
        merged.setAddressLine1(firstNonBlank(a.getLine1(), original != null ? original.getAddressLine1() : null));
        merged.setAddressLine2(firstNonBlank(a.getLine2(), original != null ? original.getAddressLine2() : null));
        merged.setCity(firstNonBlank(a.getCity(), original != null ? original.getCity() : null));
        merged.setState(firstNonBlank(a.getState(), original != null ? original.getState() : null));
        merged.setPostalCode(firstNonBlank(a.getZip(), original != null ? original.getPostalCode() : null));
        merged.setCountryCode(firstNonBlank(a.getCountry(), original != null ? original.getCountryCode() : null));
        return merged;
    }
}
