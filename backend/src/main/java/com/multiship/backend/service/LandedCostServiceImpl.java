package com.multiship.backend.service;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.LandedCostRequestDTO;
import com.multiship.backend.dto.LandedCostResponseDTO;
import com.multiship.backend.dto.LandedCostResponseDTO.LandedCostLine;
import com.multiship.backend.model.CarrierAccountRef;
import com.multiship.backend.repository.CarrierAccountRefRepository;
import com.multiship.backend.service.carriers.CarrierConnector;
import com.multiship.backend.service.carriers.CarrierConnector.LandedCostResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;

/**
 * Sprint 32 impl. Credential resolution mirrors
 * {@link RateShopServiceImpl#resolveAccount} — customer's own carrier
 * account first, platform fallback second. Never throws; connector
 * failures come back as {@code source=ERROR}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LandedCostServiceImpl implements LandedCostService {

    private final CarrierService carrierService;
    private final CarrierAccountRefRepository carrierAccountRefRepository;

    @Override
    public ApiResponse<LandedCostResponseDTO> estimate(LandedCostRequestDTO request) {
        if (request == null || request.getShipment() == null) {
            return failure(HttpStatus.BAD_REQUEST, "shipment is required.");
        }
        if (!StringUtils.hasText(request.getCarrierCode())) {
            return failure(HttpStatus.BAD_REQUEST, "carrierCode is required.");
        }
        String carrier = request.getCarrierCode().trim().toUpperCase(Locale.ROOT);

        CarrierConnector connector;
        try {
            connector = carrierService.getCarrierConnector(carrier);
        } catch (Exception ex) {
            return failure(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Carrier " + carrier + " isn't configured on this instance.");
        }

        CarrierAccountRef account = resolveAccount(carrier, request.getCustomerNo());
        if (account == null || !StringUtils.hasText(account.getClientId())
                || !StringUtils.hasText(account.getClientSecret())) {
            return success(toDto(new LandedCostResult(carrier, "NOT_SUPPORTED",
                    null, null, null, null, null, null,
                    List.of(), List.of(),
                    "No live credentials for " + carrier + " — landed cost skipped.", null)));
        }

        String accessToken;
        try {
            accessToken = connector.getAccessToken(account.getClientId(), account.getClientSecret(),
                    account.getAccountNumber(), account.getEnvironment());
        } catch (Exception ex) {
            log.warn("Landed cost — token acquisition for {} failed: {}", carrier, ex.getMessage());
            return success(toDto(new LandedCostResult(carrier, "ERROR",
                    null, null, null, null, null, null,
                    List.of(), List.of(),
                    carrier + " token acquisition failed: " + ex.getMessage(), null)));
        }

        LandedCostResult result;
        try {
            result = connector.estimateLandedCost(request.getShipment(), accessToken);
        } catch (Exception ex) {
            log.warn("Landed cost call to {} failed: {}", carrier, ex.getMessage());
            result = new LandedCostResult(carrier, "ERROR",
                    null, null, null, null, null, null,
                    List.of(), List.of(),
                    carrier + " landed cost call failed: " + ex.getMessage(), null);
        }
        return success(toDto(result));
    }

    CarrierAccountRef resolveAccount(String carrierCode, String customerNo) {
        if (StringUtils.hasText(customerNo)) {
            List<CarrierAccountRef> owned = carrierAccountRefRepository
                    .findByCustomerNoIgnoreCaseAndClientDefaultTrue(customerNo);
            for (CarrierAccountRef ref : owned) {
                if (carrierCode.equalsIgnoreCase(ref.getCarrierCode())
                        && StringUtils.hasText(ref.getClientId())
                        && StringUtils.hasText(ref.getClientSecret())) {
                    return ref;
                }
            }
        }
        List<CarrierAccountRef> platform = carrierAccountRefRepository
                .findPlatformAccountsByCarrier(carrierCode);
        return platform.isEmpty() ? null : platform.get(0);
    }

    static LandedCostResponseDTO toDto(LandedCostResult r) {
        List<LandedCostLine> lines = r.lineItems() == null ? List.of()
                : r.lineItems().stream().map(l -> LandedCostLine.builder()
                        .description(l.description())
                        .hsCode(l.hsCode())
                        .quantity(l.quantity())
                        .declaredValue(l.declaredValue())
                        .dutyAmount(l.dutyAmount())
                        .taxAmount(l.taxAmount())
                        .currency(l.currency())
                        .build()).toList();
        return LandedCostResponseDTO.builder()
                .carrierCode(r.carrierCode())
                .source(r.source())
                .freightAmount(r.freightAmount())
                .dutyTotal(r.dutyTotal())
                .taxTotal(r.taxTotal())
                .otherTotal(r.otherTotal())
                .grandTotal(r.grandTotal())
                .currency(r.currency())
                .lineItems(lines)
                .warnings(r.warnings() == null ? List.of() : r.warnings())
                .message(r.message())
                .build();
    }

    private static ApiResponse<LandedCostResponseDTO> success(LandedCostResponseDTO data) {
        return ApiResponse.<LandedCostResponseDTO>builder()
                .status("success").code(200).message(data.getMessage()).data(data).build();
    }

    private static ApiResponse<LandedCostResponseDTO> failure(HttpStatus status, String message) {
        return ApiResponse.<LandedCostResponseDTO>builder()
                .status("error").code(status.value())
                .errorCode(ErrorCode.VALIDATION_ERROR.name())
                .message(message).data(null).build();
    }
}
