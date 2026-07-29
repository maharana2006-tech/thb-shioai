package com.multiship.backend.service;

import com.multiship.backend.dto.AddressValidationRequestDTO;
import com.multiship.backend.dto.AddressValidationResponseDTO;
import com.multiship.backend.dto.AddressValidationResponseDTO.SuggestedAddress;
import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.model.CarrierAccountRef;
import com.multiship.backend.repository.CarrierAccountRefRepository;
import com.multiship.backend.service.carriers.CarrierConnector;
import com.multiship.backend.service.carriers.CarrierConnector.AddressToValidate;
import com.multiship.backend.service.carriers.CarrierConnector.AddressValidationResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Sprint 31 impl. Credential resolution mirrors
 * {@link VoidServiceImpl#resolveAccount}: customer's default carrier
 * account first (when {@code customerNo} is set), platform fallback
 * second. Never throws — connector failures come back as the
 * response's {@code matchLevel=ERROR} entry.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AddressValidationServiceImpl implements AddressValidationService {

    private final CarrierService carrierService;
    private final CarrierAccountRefRepository carrierAccountRefRepository;

    @Override
    public ApiResponse<AddressValidationResponseDTO> validate(AddressValidationRequestDTO request) {
        if (request == null) {
            return failure(HttpStatus.BAD_REQUEST, "Request body is required.");
        }
        if (!StringUtils.hasText(request.getCarrierCode())) {
            return failure(HttpStatus.BAD_REQUEST, "carrierCode is required.");
        }
        if (!StringUtils.hasText(request.getPostalCode())
                || !StringUtils.hasText(request.getCountryCode())) {
            return failure(HttpStatus.BAD_REQUEST,
                    "postalCode + countryCode are required for address validation.");
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
            return success(dtoFrom(carrier, new AddressValidationResult(false, "NOT_SUPPORTED",
                    "UNKNOWN", null, List.of(),
                    "No live credentials for " + carrier
                            + " — cannot call address validation.", null)));
        }

        String accessToken;
        try {
            accessToken = connector.getAccessToken(account.getClientId(), account.getClientSecret(),
                    account.getAccountNumber(), account.getEnvironment());
        } catch (Exception ex) {
            log.warn("Address validation — token acquisition for {} failed: {}",
                    carrier, ex.getMessage());
            return success(dtoFrom(carrier, new AddressValidationResult(false, "ERROR", "UNKNOWN",
                    null, List.of(),
                    carrier + " token acquisition failed: " + ex.getMessage(), null)));
        }

        AddressToValidate address = new AddressToValidate(
                request.getName(),
                request.getCompany(),
                request.getAddressLine1(),
                request.getAddressLine2(),
                request.getAddressLine3(),
                request.getCity(),
                request.getState(),
                request.getPostalCode(),
                request.getCountryCode());

        AddressValidationResult result;
        try {
            result = connector.validateAddress(address, accessToken);
        } catch (Exception ex) {
            log.warn("Address validation call to {} failed: {}", carrier, ex.getMessage());
            result = new AddressValidationResult(false, "ERROR", "UNKNOWN", null, List.of(),
                    carrier + " address validation call failed: " + ex.getMessage(), null);
        }

        return success(dtoFrom(carrier, result));
    }

    CarrierAccountRef resolveAccount(String carrierCode, String customerNo) {
        if (StringUtils.hasText(customerNo)) {
            List<CarrierAccountRef> ownedDefaults = carrierAccountRefRepository
                    .findByCustomerNoIgnoreCaseAndClientDefaultTrue(customerNo);
            for (CarrierAccountRef ref : ownedDefaults) {
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

    static AddressValidationResponseDTO dtoFrom(String carrier, AddressValidationResult r) {
        SuggestedAddress suggested = null;
        if (r.suggested() != null) {
            AddressToValidate s = r.suggested();
            suggested = SuggestedAddress.builder()
                    .name(s.name())
                    .addressLine1(s.addressLine1())
                    .addressLine2(s.addressLine2())
                    .addressLine3(s.addressLine3())
                    .city(s.city())
                    .state(s.state())
                    .postalCode(s.postalCode())
                    .countryCode(s.countryCode())
                    .build();
        }
        return AddressValidationResponseDTO.builder()
                .carrierCode(carrier)
                .valid(r.valid())
                .matchLevel(r.matchLevel())
                .classification(Optional.ofNullable(r.classification()).orElse("UNKNOWN"))
                .suggested(suggested)
                .warnings(r.warnings() == null ? List.of() : r.warnings())
                .message(r.message())
                .build();
    }

    private static ApiResponse<AddressValidationResponseDTO> success(AddressValidationResponseDTO data) {
        return ApiResponse.<AddressValidationResponseDTO>builder()
                .status("success").code(200).message(data.getMessage()).data(data).build();
    }

    private static ApiResponse<AddressValidationResponseDTO> failure(HttpStatus status, String message) {
        return ApiResponse.<AddressValidationResponseDTO>builder()
                .status("error").code(status.value())
                .errorCode(ErrorCode.VALIDATION_ERROR.name())
                .message(message).data(null).build();
    }
}
