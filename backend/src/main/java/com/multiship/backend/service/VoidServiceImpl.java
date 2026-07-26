package com.multiship.backend.service;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.VoidLabelResponseDTO;
import com.multiship.backend.model.CarrierAccountRef;
import com.multiship.backend.model.OrderTracking;
import com.multiship.backend.repository.CarrierAccountRefRepository;
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

    @Override
    public ApiResponse<VoidLabelResponseDTO> voidLabel(Integer orderNo) {
        if (orderNo == null) {
            return failure(HttpStatus.BAD_REQUEST, "Order number is required.");
        }
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

        CarrierConnector.VoidResult result;
        try {
            result = connector.voidShipment(tracking.getTrackingNumber(), accessToken);
        } catch (Exception ex) {
            log.warn("Void {} — carrier call failed at {}: {}",
                    tracking.getTrackingNumber(), canonicalCarrier, ex.getMessage());
            return failure(HttpStatus.BAD_GATEWAY,
                    canonicalCarrier + " void call failed: " + ex.getMessage());
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
