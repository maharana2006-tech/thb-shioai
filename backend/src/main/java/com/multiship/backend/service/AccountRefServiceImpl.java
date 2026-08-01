package com.multiship.backend.service;

import com.multiship.backend.dto.AccountRefUpsertRequest;
import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.CarrierAccountRefDTO;
import com.multiship.backend.dto.CredentialCheckDTO;
import com.multiship.backend.dto.PlatformCredentialsDTO;
import com.multiship.backend.dto.VerifyCredentialsRequest;
import com.multiship.backend.model.CarrierAccountRef;
import com.multiship.backend.repository.CarrierAccountRefRepository;
import com.multiship.backend.repository.OrderTrackingRepository;
import com.multiship.backend.service.carriers.CarrierConnector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class AccountRefServiceImpl implements AccountRefService {

    private final CarrierAccountRefRepository carrierAccountRefRepository;
    private final OrderTrackingRepository orderTrackingRepository;
    private final CarrierService carrierService;
    private final AuditService auditService;

    private record Usage(long labels, LocalDateTime lastUsed) {}

    @Override
    @Transactional(readOnly = true)
    public ApiResponse<List<CarrierAccountRefDTO>> listAccounts() {
        Map<String, Usage> usageByAccount = new HashMap<>();
        for (Object[] row : orderTrackingRepository.aggregateUsageByAccount()) {
            String account = (String) row[0];
            long labels = ((Number) row[1]).longValue();
            LocalDateTime lastUsed = row[2] instanceof Timestamp ts ? ts.toLocalDateTime() : null;
            usageByAccount.put(account.toUpperCase(Locale.ROOT), new Usage(labels, lastUsed));
        }

        List<CarrierAccountRefDTO> accounts = carrierAccountRefRepository.findAllByOrderByIsDefaultDescUpdatedAtDesc()
                .stream()
                .map(account -> {
                    CarrierAccountRefDTO dto = toDTO(account);
                    Usage usage = usageByAccount.get(account.getAccountNumber().toUpperCase(Locale.ROOT));
                    dto.setLabelsGenerated(usage != null ? usage.labels() : 0L);
                    dto.setLastUsedAt(usage != null ? usage.lastUsed() : null);
                    return dto;
                })
                .toList();

        return success("Account reference book loaded successfully.", accounts);
    }

    @Override
    @Transactional
    public ApiResponse<CarrierAccountRefDTO> verifyAccount(Long accountId) {
        CarrierAccountRef account = carrierAccountRefRepository.findById(accountId).orElse(null);

        if (account == null) {
            return failure(HttpStatus.NOT_FOUND, ErrorCode.ACCOUNT_NOT_FOUND, "Carrier account " + accountId + " was not found.");
        }

        if (!account.isComplete()) {
            return failure(HttpStatus.BAD_REQUEST, ErrorCode.ACCOUNT_INCOMPLETE,
                    "Account " + account.getAccountNumber() + " has no credentials to verify.");
        }

        CredentialCheckDTO check = runCredentialCheck(account.getCarrierCode(), account.getClientId(),
                account.getClientSecret(), account.getAccountNumber(), account.getEnvironment());
        account.setVerified(check.getVerified());
        account.setLastVerifiedAt(check.getCheckedAt());
        carrierAccountRefRepository.save(account);

        return success(check.getMessage(), toDTO(account));
    }

    @Override
    public ApiResponse<CredentialCheckDTO> verifyCredentials(VerifyCredentialsRequest request) {
        CredentialCheckDTO check = runCredentialCheck(request.getCarrierCode(), request.getClientId(),
                request.getClientSecret(), request.getAccountNumber(), request.getEnvironment());
        return success(check.getMessage(), check);
    }

    /**
     * Requests a live OAuth token from the carrier. Connectors fall back to a
     * local token when the carrier rejects the credentials, so a "-local-"
     * token means the check failed even though no exception was thrown.
     *
     * <p>{@code accountNumber} is forwarded as {@code x-merchant-id} on UPS
     * OAuth; {@code environment} decides SANDBOX vs PRODUCTION UPS host.
     * Other carriers ignore both via the interface defaults.
     */
    private CredentialCheckDTO runCredentialCheck(String carrierCode, String clientId, String clientSecret,
                                                  String accountNumber, String environment) {
        LocalDateTime now = LocalDateTime.now();

        try {
            CarrierConnector connector = carrierService.getCarrierConnector(carrierCode);
            connector.validateCredentials(clientId, clientSecret);
            String token = connector.getAccessToken(clientId, clientSecret, accountNumber, environment);
            boolean realToken = StringUtils.hasText(token) && !token.contains("-local-");

            return CredentialCheckDTO.builder()
                    .verified(realToken)
                    .checkedAt(now)
                    .message(realToken
                            ? "Credentials verified — " + connector.getCarrierName() + " issued a live token."
                            : connector.getCarrierName() + " rejected the credentials.")
                    .build();
        } catch (Exception ex) {
            log.warn("Credential check failed for carrier {}: {}", carrierCode, ex.getMessage());
            return CredentialCheckDTO.builder()
                    .verified(false)
                    .checkedAt(now)
                    .message("Verification failed: " + ex.getMessage())
                    .build();
        }
    }

    @Override
    @Transactional
    public ApiResponse<CarrierAccountRefDTO> upsertAccount(AccountRefUpsertRequest request) {
        String accountNumber = request.getAccountNumber().trim();
        // Canonical vocabulary (UPS/FEDEX/USPS): the connector lookup both
        // normalizes legacy codes (P80/F77/L01) and rejects unknown carriers.
        String carrierCode = carrierService.getCarrierConnector(request.getCarrierCode()).getCarrierCode();

        CarrierAccountRef account = carrierAccountRefRepository
                .findFirstByAccountNumberIgnoreCaseAndCarrierCodeIgnoreCase(accountNumber, carrierCode)
                .or(() -> carrierAccountRefRepository.findFirstByAccountNumberIgnoreCaseOrderByUpdatedAtDesc(accountNumber))
                .orElseGet(CarrierAccountRef::new);

        boolean isNewAccount = account.getId() == null;
        account.setAccountNumber(accountNumber);
        account.setCarrierCode(carrierCode);

        // Credentials are now optional on update — blank means "keep the
        // persisted value". A CREATE with blank credentials still 422s.
        String rawClientId = request.getClientId();
        String rawClientSecret = request.getClientSecret();
        boolean clientIdProvided = StringUtils.hasText(rawClientId);
        boolean clientSecretProvided = StringUtils.hasText(rawClientSecret);
        if (isNewAccount && (!clientIdProvided || !clientSecretProvided)) {
            return failure(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.VALIDATION_ERROR,
                    "Client ID and Client Secret are required when creating a new carrier account.");
        }
        boolean credentialsChanged = false;
        if (clientIdProvided) {
            account.setClientId(rawClientId.trim());
            credentialsChanged = true;
        }
        if (clientSecretProvided) {
            account.setClientSecret(rawClientSecret.trim());
            credentialsChanged = true;
        }
        // Fresh credentials invalidate any previous verification result;
        // untouched credentials keep the verified/lastVerifiedAt stamps.
        if (credentialsChanged) {
            account.setVerified(null);
            account.setLastVerifiedAt(null);
        }

        if (StringUtils.hasText(request.getAccountName())) {
            account.setAccountName(request.getAccountName().trim());
        }
        if (StringUtils.hasText(request.getCustomerNo())) {
            account.setCustomerNo(request.getCustomerNo().trim().toUpperCase(Locale.ROOT));
        }
        if (StringUtils.hasText(request.getEnvironment())) {
            account.setEnvironment(request.getEnvironment().trim().toUpperCase(Locale.ROOT));
        }

        account.setActive(true);

        if (Boolean.TRUE.equals(request.getClientDefault()) && StringUtils.hasText(account.getCustomerNo())) {
            demoteOtherClientDefaults(account);
            account.setClientDefault(true);
        }

        carrierAccountRefRepository.save(account);
        auditService.record(isNewAccount ? AuditService.CREATE : AuditService.UPDATE,
                AuditService.CARRIER_ACCOUNT, account.getId(),
                carrierCode + " · " + accountNumber, toDTO(account),
                (isNewAccount ? "Carrier account created: " : "Carrier account updated: ")
                        + carrierCode + "/" + accountNumber
                        + (StringUtils.hasText(account.getCustomerNo())
                                ? " (client " + account.getCustomerNo() + ")" : " (platform)"));
        return success("Carrier account saved to the reference book.", toDTO(account));
    }

    @Override
    @Transactional
    public ApiResponse<CarrierAccountRefDTO> setClientDefault(Long accountId) {
        CarrierAccountRef account = carrierAccountRefRepository.findById(accountId).orElse(null);

        if (account == null) {
            return failure(HttpStatus.NOT_FOUND, ErrorCode.ACCOUNT_NOT_FOUND, "Carrier account " + accountId + " was not found.");
        }

        if (!StringUtils.hasText(account.getCustomerNo())) {
            return failure(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                    "Account " + account.getAccountNumber() + " is not linked to a client, so it cannot be a client default.");
        }

        if (!account.isComplete()) {
            return failure(HttpStatus.BAD_REQUEST, ErrorCode.ACCOUNT_INCOMPLETE,
                    "Account " + account.getAccountNumber() + " is missing credentials and cannot be the client default.");
        }

        demoteOtherClientDefaults(account);
        account.setClientDefault(true);
        account.setActive(true);
        carrierAccountRefRepository.save(account);

        return success("Client default account updated successfully.", toDTO(account));
    }

    /** At most one client-default per customerNo. */
    private void demoteOtherClientDefaults(CarrierAccountRef account) {
        // The default is per (client, carrier): a client can have one default UPS
        // account AND one default FedEx account. Only demote same-carrier peers.
        String carrier = account.getCarrierCode();
        carrierAccountRefRepository.findByCustomerNoIgnoreCaseAndClientDefaultTrue(account.getCustomerNo()).stream()
                .filter(other -> !java.util.Objects.equals(other.getId(), account.getId()))
                .filter(other -> carrier == null || carrier.equalsIgnoreCase(other.getCarrierCode()))
                .forEach(other -> {
                    other.setClientDefault(false);
                    carrierAccountRefRepository.save(other);
                });
    }

    @Override
    @Transactional
    public ApiResponse<CarrierAccountRefDTO> toggleActive(Long accountId) {
        CarrierAccountRef account = carrierAccountRefRepository.findById(accountId).orElse(null);

        if (account == null) {
            return failure(HttpStatus.NOT_FOUND, ErrorCode.ACCOUNT_NOT_FOUND, "Carrier account " + accountId + " was not found.");
        }

        boolean nextActive = Boolean.FALSE.equals(account.getActive());
        account.setActive(nextActive);

        carrierAccountRefRepository.save(account);
        auditService.record(AuditService.TOGGLE_ACTIVE, AuditService.CARRIER_ACCOUNT,
                account.getId(),
                account.getCarrierCode() + " · " + account.getAccountNumber(),
                java.util.Map.of("active", nextActive, "customerNo", account.getCustomerNo()),
                (nextActive ? "Carrier account activated: " : "Carrier account deactivated: ")
                        + account.getCarrierCode() + "/" + account.getAccountNumber());
        return success(nextActive ? "Account activated." : "Account deactivated.", toDTO(account));
    }

    private void demoteOtherDefaults(CarrierAccountRef keep) {
        carrierAccountRefRepository.findByIsDefaultTrue().stream()
                .filter(existing -> !Objects.equals(existing.getId(), keep.getId()))
                .forEach(existing -> {
                    existing.setIsDefault(false);
                    carrierAccountRefRepository.save(existing);
                });
    }

    private CarrierAccountRefDTO toDTO(CarrierAccountRef account) {
        return CarrierAccountRefDTO.builder()
                .id(account.getId())
                .accountNumber(account.getAccountNumber())
                .carrierCode(account.getCarrierCode())
                .accountName(account.getAccountName())
                .customerNo(account.getCustomerNo())
                .environment(account.getEnvironment())
                .isDefault(Boolean.TRUE.equals(account.getIsDefault()))
                .clientDefault(Boolean.TRUE.equals(account.getClientDefault()))
                .active(!Boolean.FALSE.equals(account.getActive()))
                .complete(account.isComplete())
                .clientIdPreview(maskClientId(account.getClientId()))
                .verified(account.getVerified())
                .lastVerifiedAt(account.getLastVerifiedAt())
                .createdAt(account.getCreatedAt())
                .updatedAt(account.getUpdatedAt())
                .build();
    }

    private String maskClientId(String clientId) {
        if (!StringUtils.hasText(clientId)) {
            return null;
        }
        if (clientId.length() <= 6) {
            return clientId.charAt(0) + "•••";
        }
        return clientId.substring(0, 4) + "•••" + clientId.substring(clientId.length() - 2);
    }

    @Override
    @Transactional(readOnly = true)
    public ApiResponse<PlatformCredentialsDTO> getPlatformCredentials(String carrierCode) {
        // Normalize to the canonical carrier code (also validates the carrier).
        String canonical;
        try {
            canonical = carrierService.getCarrierConnector(carrierCode).getCarrierCode();
        } catch (Exception ex) {
            return success("Unknown carrier.", PlatformCredentialsDTO.builder()
                    .carrierCode(carrierCode).found(false).build());
        }

        return carrierAccountRefRepository.findPlatformAccountsByCarrier(canonical).stream()
                .findFirst()
                .map(account -> success("Platform credentials loaded.", PlatformCredentialsDTO.builder()
                        .carrierCode(canonical)
                        .clientId(account.getClientId())
                        .clientSecret(account.getClientSecret())
                        .found(true)
                        .build()))
                .orElseGet(() -> success("No platform account for this carrier yet.",
                        PlatformCredentialsDTO.builder().carrierCode(canonical).found(false).build()));
    }

    @Override
    @Transactional
    public ApiResponse<Void> deleteAccount(Long accountId) {
        CarrierAccountRef account = carrierAccountRefRepository.findById(accountId).orElse(null);
        if (account == null) {
            return failure(HttpStatus.NOT_FOUND, ErrorCode.ACCOUNT_NOT_FOUND,
                    "Carrier account " + accountId + " was not found.");
        }
        long labels = orderTrackingRepository
                .countByAccountNumberIgnoreCaseAndIsLabelGeneratedTrue(account.getAccountNumber());
        if (labels > 0) {
            return failure(HttpStatus.CONFLICT, ErrorCode.ACCOUNT_HAS_LABELS,
                    "Account " + account.getAccountNumber() + " has generated " + labels
                            + " label(s) — deactivate it instead to preserve the audit trail.");
        }
        Long deletedId = account.getId();
        String deletedCarrier = account.getCarrierCode();
        String deletedNumber = account.getAccountNumber();
        carrierAccountRefRepository.delete(account);
        auditService.record(AuditService.DELETE, AuditService.CARRIER_ACCOUNT,
                deletedId, deletedCarrier + " · " + deletedNumber, null,
                "Carrier account deleted: " + deletedCarrier + "/" + deletedNumber);
        return success("Account " + account.getAccountNumber() + " removed from the account book.", null);
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
        return ApiResponse.<T>builder()
                .status("error")
                .code(status.value())
                .errorCode(errorCode.name())
                .message(message)
                .timestamp(LocalDateTime.now())
                .build();
    }
}
