package com.multiship.backend.service;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.PickupRequestDTO;
import com.multiship.backend.dto.PickupResponseDTO;
import com.multiship.backend.model.CarrierAccountRef;
import com.multiship.backend.repository.CarrierAccountRefRepository;
import com.multiship.backend.service.carriers.CarrierConnector;
import com.multiship.backend.service.carriers.CarrierConnector.AddressToValidate;
import com.multiship.backend.service.carriers.CarrierConnector.PickupRequest;
import com.multiship.backend.service.carriers.CarrierConnector.PickupResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;

/**
 * Sprint 33 impl. Credential resolution mirrors
 * {@link VoidServiceImpl#resolveAccount}. Never throws.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PickupServiceImpl implements PickupService {

    private final CarrierService carrierService;
    private final CarrierAccountRefRepository carrierAccountRefRepository;

    @Override
    public ApiResponse<PickupResponseDTO> schedule(PickupRequestDTO request) {
        if (request == null) {
            return failure(HttpStatus.BAD_REQUEST, "Request body is required.");
        }
        String carrier = request.getCarrierCode() == null ? null
                : request.getCarrierCode().trim().toUpperCase(Locale.ROOT);
        if (!StringUtils.hasText(carrier)) {
            return failure(HttpStatus.BAD_REQUEST, "carrierCode is required.");
        }

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
            return success(toDto(new PickupResult(carrier, null,
                    request.getPickupDate(),
                    request.getPickupWindowStart(),
                    request.getPickupWindowEnd(),
                    "NOT_SUPPORTED",
                    "No live credentials for " + carrier + " — cannot schedule pickup.",
                    null)));
        }

        String accessToken;
        try {
            accessToken = connector.getAccessToken(
                    account.getClientId(), account.getClientSecret(),
                    account.getAccountNumber(), account.getEnvironment());
        } catch (Exception ex) {
            log.warn("Pickup — token acquisition for {} failed: {}", carrier, ex.getMessage());
            return success(toDto(new PickupResult(carrier, null,
                    request.getPickupDate(),
                    request.getPickupWindowStart(),
                    request.getPickupWindowEnd(),
                    "ERROR",
                    carrier + " token acquisition failed: " + ex.getMessage(),
                    null)));
        }

        AddressToValidate address = new AddressToValidate(
                request.getContactName(), null,
                request.getAddressLine1(),
                request.getAddressLine2(),
                null,
                request.getCity(),
                request.getState(),
                request.getPostalCode(),
                request.getCountryCode());
        PickupRequest connectorRequest = new PickupRequest(
                request.getPickupDate(),
                request.getPickupWindowStart(),
                request.getPickupWindowEnd(),
                address,
                request.getContactName(),
                request.getContactPhone(),
                request.getPackageCount(),
                request.getTotalWeight(),
                request.getWeightUnit(),
                request.getSpecialInstructions());

        PickupResult result;
        try {
            result = connector.schedulePickup(connectorRequest, accessToken, account.getEnvironment());
        } catch (Exception ex) {
            log.warn("Pickup call to {} failed: {}", carrier, ex.getMessage());
            result = new PickupResult(carrier, null,
                    request.getPickupDate(),
                    request.getPickupWindowStart(),
                    request.getPickupWindowEnd(),
                    "ERROR",
                    carrier + " pickup call failed: " + ex.getMessage(),
                    null);
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

    static PickupResponseDTO toDto(PickupResult r) {
        return PickupResponseDTO.builder()
                .carrierCode(r.carrierCode())
                .confirmationNumber(r.confirmationNumber())
                .scheduledDate(r.scheduledDate())
                .pickupWindowStart(r.pickupWindowStart())
                .pickupWindowEnd(r.pickupWindowEnd())
                .status(r.status())
                .message(r.message())
                .build();
    }

    private static ApiResponse<PickupResponseDTO> success(PickupResponseDTO data) {
        return ApiResponse.<PickupResponseDTO>builder()
                .status("success").code(200).message(data.getMessage()).data(data).build();
    }

    private static ApiResponse<PickupResponseDTO> failure(HttpStatus status, String message) {
        return ApiResponse.<PickupResponseDTO>builder()
                .status("error").code(status.value())
                .errorCode(ErrorCode.VALIDATION_ERROR.name())
                .message(message).data(null).build();
    }
}
