package com.multiship.backend.service;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.VoidLabelResponseDTO;
import com.multiship.backend.model.CarrierAccountRef;
import com.multiship.backend.model.OrderTracking;
import com.multiship.backend.repository.CarrierAccountRefRepository;
import com.multiship.backend.repository.OrderRepository;
import com.multiship.backend.repository.OrderTrackingRepository;
import com.multiship.backend.service.carriers.CarrierConnector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * Sprint 30 implementation of {@link VoidService}. Credential
 * resolution reuses the {@link TrackingServiceImpl} precedent —
 * customer's own account first, then platform (house).
 *
 * <p>DB update on success: {@code status = VOIDED},
 * {@code isLabelGenerated = false}, {@code updatedAt = now()}. The
 * tracking number and label path are kept for audit ("we voided X").
 * When the carrier returns {@code NOT_SUPPORTED} we do NOT update the
 * DB — the label is still live at the carrier.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VoidServiceImpl implements VoidService {

    private final OrderTrackingRepository orderTrackingRepository;
    private final CarrierAccountRefRepository carrierAccountRefRepository;
    private final CarrierService carrierService;
    private final com.multiship.backend.repository.ShipmentBatchRepository shipmentBatchRepository;
    private final com.multiship.backend.config.CarrierProperties carrierProperties;
    /**
     * Sprint 50 Tier 0.5 PR E - tenant guard so a scoped USER cannot void
     * a label that belongs to a foreign tenant. The tenant discriminator
     * (tenant_id / cust_no) lives on label_batch (Order), not on the
     * tracking table.
     */
    private final OrderRepository orderRepository;
    private final TenantScopeEnforcer tenantScope;

    @Override
    public ApiResponse<VoidLabelResponseDTO> voidLabel(Integer orderNo) {
        if (orderNo == null) {
            return failure(HttpStatus.BAD_REQUEST, "Order number is required.");
        }
        // Sprint 50 Tier 0.5 PR E - belt-and-braces tenant guard. The
        // controller SpEL restricts foreign-tenant access, but any
        // internal caller bypassing method security lands here first.
        orderRepository.findByOrderNo(orderNo).ifPresent(o ->
                tenantScope.requireTenantMatch(
                        StringUtils.hasText(o.getTenantId()) ? o.getTenantId() : o.getCustNo()));

        OrderTracking tracking = orderTrackingRepository.findByOrderNo(orderNo).orElse(null);
        if (tracking == null || !StringUtils.hasText(tracking.getTrackingNumber())) {
            return failure(HttpStatus.NOT_FOUND,
                    "Order " + orderNo + " has no tracking number to void.");
        }

        // Idempotent short-circuit.
        if ("VOIDED".equalsIgnoreCase(tracking.getStatus())) {
            return success(dto(orderNo, tracking, true, "ALREADY_VOIDED",
                    "Order " + orderNo + " was already voided."));
        }

        String canonicalCarrier = TrackingServiceImpl.canonicalizeCarrierCode(tracking.getShipViaCd());
        if (!StringUtils.hasText(canonicalCarrier)) {
            return failure(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Order " + orderNo + " has no carrier code; can't resolve credentials.");
        }

        CarrierConnector connector;
        try {
            connector = carrierService.getCarrierConnector(canonicalCarrier);
        } catch (Exception ex) {
            return failure(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Carrier " + canonicalCarrier + " isn't configured on this instance.");
        }

        CarrierAccountRef account = resolveAccount(canonicalCarrier, tracking.getAccountNumber());
        if (account == null || !StringUtils.hasText(account.getClientId())
                || !StringUtils.hasText(account.getClientSecret())) {
            return failure(HttpStatus.UNPROCESSABLE_ENTITY,
                    "No live credentials for " + canonicalCarrier
                            + " — cannot void " + tracking.getTrackingNumber() + ".");
        }

        String accessToken;
        try {
            accessToken = connector.getAccessToken(account.getClientId(), account.getClientSecret(),
                    account.getAccountNumber(), account.getEnvironment());
        } catch (Exception ex) {
            log.warn("Void {} — token acquisition for {} failed: {}",
                    tracking.getTrackingNumber(), canonicalCarrier, ex.getMessage());
            return failure(HttpStatus.BAD_GATEWAY,
                    canonicalCarrier + " token acquisition failed: " + ex.getMessage());
        }

        // Multi-batch void — an order that was auto-split into N carrier
        // shipments has N master tracking numbers in shipment_batch. Void
        // every batch and treat the overall void as successful only when
        // every batch voided. Any failure surfaces the first failing batch's
        // message; partial voids are persisted (order status flips only when
        // ALL batches voided).
        java.util.List<com.multiship.backend.model.ShipmentBatch> batches =
                shipmentBatchRepository.findByOrderNoOrderByBatchSeqAsc(orderNo);
        java.util.List<String> trackingNumbersToVoid = new java.util.ArrayList<>();
        if (batches.isEmpty()) {
            // Legacy path — no batches persisted; void the order-level master.
            trackingNumbersToVoid.add(tracking.getTrackingNumber());
        } else {
            for (com.multiship.backend.model.ShipmentBatch b : batches) {
                if (StringUtils.hasText(b.getMasterTrackingNumber())) {
                    trackingNumbersToVoid.add(b.getMasterTrackingNumber());
                }
            }
        }

        // Sprint 49 Tier 2: pass the real account number + platform shipper
        // country to the connector. FedEx cancel rejects any label whose
        // shipperAccountNumber doesn't match; the placeholder "ACCOUNT" it
        // used to receive made every FedEx void fail silently.
        String voidAccountNumber = account.getAccountNumber();
        String senderCountryCode = carrierProperties.getShipper() != null
                ? carrierProperties.getShipper().getCountryCode()
                : null;

        CarrierConnector.VoidResult result = null;
        java.util.List<CarrierConnector.VoidResult> perBatchResults = new java.util.ArrayList<>();
        for (String trackNo : trackingNumbersToVoid) {
            try {
                CarrierConnector.VoidResult r = connector.voidShipment(trackNo, accessToken,
                        account.getEnvironment(), voidAccountNumber, senderCountryCode);
                perBatchResults.add(r);
                if (result == null) result = r;
            } catch (Exception ex) {
                log.warn("Void {} — carrier call failed at {}: {}", trackNo, canonicalCarrier, ex.getMessage());
                return failure(HttpStatus.BAD_GATEWAY,
                        canonicalCarrier + " void call failed for " + trackNo + ": " + ex.getMessage());
            }
        }
        // If any batch failed to void, surface that. Order status only flips
        // when every batch reports voided=true.
        boolean allVoided = !perBatchResults.isEmpty()
                && perBatchResults.stream().allMatch(CarrierConnector.VoidResult::voided);
        if (!allVoided) {
            CarrierConnector.VoidResult firstFail = perBatchResults.stream()
                    .filter(r -> !r.voided()).findFirst().orElse(result);
            result = firstFail != null ? firstFail : result;
        } else {
            // Reuse a synthetic aggregate so downstream persistence sees success.
            result = new CarrierConnector.VoidResult(
                    tracking.getTrackingNumber(), true, "VOIDED",
                    perBatchResults.size() + " batch(es) voided.", null);
        }

        // Persist the successful void so the order is no longer treated
        // as GENERATED. NOT_SUPPORTED / ERROR leaves the DB untouched —
        // the label is still live at the carrier.
        if (result.voided()) {
            tracking.setStatus("VOIDED");
            tracking.setIsLabelGenerated(false);
            tracking.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
            orderTrackingRepository.save(tracking);
        }

        VoidLabelResponseDTO body = VoidLabelResponseDTO.builder()
                .orderNo(orderNo)
                .trackingNumber(tracking.getTrackingNumber())
                .carrierCode(canonicalCarrier)
                .voided(result.voided())
                .status(result.status())
                .message(result.message())
                .build();
        return success(body);
    }

    /**
     * Resolve the account to authenticate the void call. Same fallback
     * chain as TrackingServiceImpl.
     */
    CarrierAccountRef resolveAccount(String carrierCode, String accountNumber) {
        if (StringUtils.hasText(accountNumber)) {
            Optional<CarrierAccountRef> exact = carrierAccountRefRepository
                    .findFirstByAccountNumberIgnoreCaseAndCarrierCodeIgnoreCase(accountNumber, carrierCode);
            if (exact.isPresent()) return exact.get();
            Optional<CarrierAccountRef> anyCarrier = carrierAccountRefRepository
                    .findFirstByAccountNumberIgnoreCaseOrderByUpdatedAtDesc(accountNumber);
            if (anyCarrier.isPresent()) return anyCarrier.get();
        }
        List<CarrierAccountRef> platform = carrierAccountRefRepository
                .findPlatformAccountsByCarrier(carrierCode);
        return platform.isEmpty() ? null : platform.get(0);
    }

    private static VoidLabelResponseDTO dto(Integer orderNo, OrderTracking t,
                                              boolean voided, String status, String message) {
        return VoidLabelResponseDTO.builder()
                .orderNo(orderNo)
                .trackingNumber(t.getTrackingNumber())
                .carrierCode(t.getShipViaCd())
                .voided(voided)
                .status(status)
                .message(message)
                .build();
    }

    private static ApiResponse<VoidLabelResponseDTO> success(VoidLabelResponseDTO data) {
        return ApiResponse.<VoidLabelResponseDTO>builder()
                .status("success").code(200)
                .message(data.getMessage()).data(data).build();
    }

    private static ApiResponse<VoidLabelResponseDTO> failure(HttpStatus status, String message) {
        return ApiResponse.<VoidLabelResponseDTO>builder()
                .status("error").code(status.value())
                .errorCode(ErrorCode.VALIDATION_ERROR.name())
                .message(message).data(null).build();
    }
}
