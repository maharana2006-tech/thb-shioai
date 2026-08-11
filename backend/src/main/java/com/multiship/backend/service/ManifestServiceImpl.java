package com.multiship.backend.service;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.ManifestRequestDTO;
import com.multiship.backend.dto.ManifestResponseDTO;
import com.multiship.backend.model.CarrierAccountRef;
import com.multiship.backend.repository.CarrierAccountRefRepository;
import com.multiship.backend.service.carriers.CarrierConnector;
import com.multiship.backend.service.carriers.CarrierConnector.AddressToValidate;
import com.multiship.backend.service.carriers.CarrierConnector.CloseOutRequest;
import com.multiship.backend.service.carriers.CarrierConnector.CloseOutResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/**
 * Sprint 34 impl. Credential resolution mirrors {@link VoidServiceImpl}.
 * Never throws — carrier failures come back as {@code status=ERROR}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ManifestServiceImpl implements ManifestService {

    private final CarrierService carrierService;
    private final CarrierAccountRefRepository carrierAccountRefRepository;
    /**
     * Sprint 50 Tier 0.5 PR E - clamp customerNo to the caller's tenant so
     * a scoped USER cannot close out a foreign tenant's manifest.
     */
    private final TenantScopeEnforcer tenantScope;

    @Override
    public ApiResponse<ManifestResponseDTO> closeOut(ManifestRequestDTO request) {
        if (request == null) {
            return failure(HttpStatus.BAD_REQUEST, "Request body is required.");
        }
        if (!StringUtils.hasText(request.getCarrierCode())) {
            return failure(HttpStatus.BAD_REQUEST, "carrierCode is required.");
        }
        if (request.getTrackingNumbers() == null || request.getTrackingNumbers().isEmpty()) {
            return failure(HttpStatus.BAD_REQUEST,
                    "At least one tracking number is required to close out a day.");
        }
        // Sprint 50 Tier 0.5 PR E - clamp caller-supplied customerNo to
        // caller's own tenant. Platform operators pass through unchanged.
        request.setCustomerNo(tenantScope.clampClientCode(request.getCustomerNo()));
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
            return success(toDto(new CloseOutResult(carrier, null, null, null,
                    request.getTrackingNumbers().size(), "NOT_SUPPORTED",
                    "No live credentials for " + carrier + " — cannot close out.", null)));
        }

        String accessToken;
        try {
            accessToken = connector.getAccessToken(
                    account.getClientId(), account.getClientSecret(),
                    account.getAccountNumber(), account.getEnvironment());
        } catch (Exception ex) {
            log.warn("Close-out — token acquisition for {} failed: {}",
                    carrier, ex.getMessage());
            return success(toDto(new CloseOutResult(carrier, null, null, null,
                    request.getTrackingNumbers().size(), "ERROR",
                    carrier + " token acquisition failed: " + ex.getMessage(), null)));
        }

        AddressToValidate address = null;
        if (StringUtils.hasText(request.getAddressLine1())) {
            address = new AddressToValidate(
                    request.getAddressName(), null,
                    request.getAddressLine1(),
                    request.getAddressLine2(),
                    null,
                    request.getCity(),
                    request.getState(),
                    request.getPostalCode(),
                    request.getCountryCode());
        }

        // Sprint 50 Tier 1 (finding #10) — pass the resolved shipper account
        // number so the FedEx close-out doesn't send the "ACCOUNT" placeholder.
        // Same shape as the Sprint 49 Tier 2 voidShipment fix.
        CloseOutRequest connectorRequest = new CloseOutRequest(
                List.copyOf(request.getTrackingNumbers()),
                request.getCloseDate() == null ? LocalDate.now() : request.getCloseDate(),
                address,
                account.getAccountNumber());

        CloseOutResult result;
        try {
            result = connector.closeOutDay(connectorRequest, accessToken, account.getEnvironment());
        } catch (Exception ex) {
            log.warn("Close-out call to {} failed: {}", carrier, ex.getMessage());
            result = new CloseOutResult(carrier, null, null, null,
                    request.getTrackingNumbers().size(), "ERROR",
                    carrier + " close-out call failed: " + ex.getMessage(), null);
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

    static ManifestResponseDTO toDto(CloseOutResult r) {
        return ManifestResponseDTO.builder()
                .carrierCode(r.carrierCode())
                .manifestId(r.manifestId())
                .manifestPdfUrl(r.manifestPdfUrl())
                .manifestPdfBase64(r.manifestPdfBase64())
                .trackingCount(r.trackingCount())
                .status(r.status())
                .message(r.message())
                .build();
    }

    private static ApiResponse<ManifestResponseDTO> success(ManifestResponseDTO data) {
        return ApiResponse.<ManifestResponseDTO>builder()
                .status("success").code(200).message(data.getMessage()).data(data).build();
    }

    private static ApiResponse<ManifestResponseDTO> failure(HttpStatus status, String message) {
        return ApiResponse.<ManifestResponseDTO>builder()
                .status("error").code(status.value())
                .errorCode(ErrorCode.VALIDATION_ERROR.name())
                .message(message).data(null).build();
    }
}
