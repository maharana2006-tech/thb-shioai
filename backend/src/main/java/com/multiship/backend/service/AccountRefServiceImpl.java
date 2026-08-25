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
import com.multiship.backend.service.events.CarrierConfigChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
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
    /** Sprint 49 Tier 3 Fix 1 — publish rate-cache invalidation on account writes. */
    private final ApplicationEventPublisher eventPublisher;

    /** Sprint 50 Tier 0.5 PR H — clamp tenant on carrier-account writes/reads so a
     *  scoped USER cannot verify, re-default, toggle, delete, or hijack another
     *  tenant's carrier account. Optional so unit tests that construct the
     *  service via the RequiredArgsConstructor still compile. */
    @Autowired(required = false)
    private TenantScopeEnforcer tenantScope;

    /** Null-safe wrapper around {@link TenantScopeEnforcer#clampClientCode(String)}.
     *  Returns the input unchanged when the enforcer isn't wired (tests). */
    private String clamp(String requested) {
        return tenantScope == null ? requested : tenantScope.clampClientCode(requested);
    }

    /** Null-safe wrapper around {@link TenantScopeEnforcer#requireTenantMatch(String)}.
     *  No-op when the enforcer isn't wired (tests). */
    private void requireMatch(String rowClientCode) {
        if (tenantScope != null) tenantScope.requireTenantMatch(rowClientCode);
    }

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

        // Sprint 50 Tier 0.5 PR H — a scoped USER must not verify a foreign
        // tenant's carrier account (IDOR by numeric id enumeration).
        requireMatch(account.getCustomerNo());

        if (!account.isComplete()) {
            return failure(HttpStatus.BAD_REQUEST, ErrorCode.ACCOUNT_INCOMPLETE,
                    "Account " + account.getAccountNumber() + " has no credentials to verify.");
        }

        CredentialCheckDTO check = runCredentialCheck(account.getCarrierCode(), account.getClientId(),
                account.getClientSecret(), account.getAccountNumber(), account.getEnvironment());
        account.setVerified(check.getVerified());
        account.setLastVerifiedAt(check.getCheckedAt());
        carrierAccountRefRepository.save(account);

        // Sprint 49 Tier 3 Fix 1 — clear stale rate cache for this carrier;
        // a verify may flip an unverified account to verified, changing the
        // set of accounts rate-shop can call under.
        eventPublisher.publishEvent(
                new CarrierConfigChangedEvent(account.getCarrierCode(), "account-verified"));

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

            // On failure, ask the connector WHY it fell back to a local token so
            // the operator gets an actionable reason (invalid UPS ClientId, a
            // non-GUID Stamps IntegrationID, env mismatch) instead of a generic
            // "rejected the credentials".
            String detail = realToken ? null : connector.consumeAuthFailureDetail();
            String message = realToken
                    ? "Credentials verified — " + connector.getCarrierName() + " issued a live token."
                    : StringUtils.hasText(detail)
                            ? connector.getCarrierName() + " rejected the credentials — " + detail
                            : connector.getCarrierName() + " rejected the credentials.";

            return CredentialCheckDTO.builder()
                    .verified(realToken)
                    .checkedAt(now)
                    .message(message)
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

        // Sprint 50 Tier 0.5 PR H — clamp the requested customerNo to the
        // caller's tenant. A scoped USER cannot upsert an account for another
        // tenant; a null/blank customerNo is forced to their own tenant.
        // Operators are pass-through so PLATFORM upserts (blank customerNo)
        // still work through the ADMIN branch.
        request.setCustomerNo(clamp(request.getCustomerNo()));

        // Match strictly on (accountNumber, carrierCode) — the table's unique
        // constraint. An account_number-only fallback would let, e.g., adding
        // a FedEx account overwrite an existing UPS row that happens to share
        // the number, silently reassigning the row across carriers.
        CarrierAccountRef account = carrierAccountRefRepository
                .findFirstByAccountNumberIgnoreCaseAndCarrierCodeIgnoreCase(accountNumber, carrierCode)
                .orElseGet(CarrierAccountRef::new);

        boolean isNewAccount = account.getId() == null;
        // Sprint 50 Tier 0.5 PR H — natural-key hijack guard: if the existing
        // (accountNumber, carrierCode) row belongs to a DIFFERENT tenant, a
        // scoped USER must not be able to reassign it. Operators are silent.
        if (!isNewAccount && StringUtils.hasText(account.getCustomerNo())) {
            String existing = account.getCustomerNo();
            String incoming = request.getCustomerNo();
            if (incoming != null && !existing.equalsIgnoreCase(incoming.trim())) {
                throw new AccessDeniedException(
                        ErrorCode.CROSS_TENANT_ACCESS_DENIED.name()
                                + ": carrier account " + carrierCode + "/" + accountNumber
                                + " already belongs to another tenant.");
            }
        }
        account.setAccountNumber(accountNumber);
        account.setCarrierCode(carrierCode);

        // Credentials are now optional on update — blank means "keep the
        // persisted value". A CREATE with blank credentials still 422s.
        String rawClientId = request.getClientId();
        String rawClientSecret = request.getClientSecret();
        boolean clientIdProvided = StringUtils.hasText(rawClientId);
        boolean clientSecretProvided = StringUtils.hasText(rawClientSecret);
        if (isNewAccount && (!clientIdProvided || !clientSecretProvided)) {
            return failure(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCode.VALIDATION_ERROR,
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

        // International-shipment defaults — both nullable. Non-null string
        // (including blank after trim) writes through; null in the request =
        // "keep the persisted value" (client omitted the field entirely).
        // Frontend explicitly sends null when clearing, which arrives here as
        // a null Java reference — same effect as omission: keep. If the
        // operator actually cleared it, we still want to clear it. To make
        // "explicit null = clear" work, differentiate: any request that
        // reaches this method with the field set to an EMPTY STRING means
        // "clear" (we normalize to null); a null reference means "unchanged".
        String purpose = request.getShippingPurpose();
        if (purpose != null) {
            String p = purpose.trim();
            account.setShippingPurpose(p.isEmpty() ? null : p.toUpperCase(Locale.ROOT));
        }
        String clearance = request.getClearanceOption();
        if (clearance != null) {
            String c = clearance.trim();
            account.setClearanceOption(c.isEmpty() ? null : c.toUpperCase(Locale.ROOT));
        }

        // F6-B2 — per-account currency override. Same null-vs-empty-string
        // semantics as purpose/clearance: null in request = keep persisted;
        // empty string = clear (revert to carrier default); non-empty =
        // normalise + persist. ISO 4217 is always uppercase.
        String currency = request.getCurrency();
        if (currency != null) {
            String cc = currency.trim();
            account.setCurrency(cc.isEmpty() ? null : cc.toUpperCase(Locale.ROOT));
        }

        // FDX-H1 — per-account pickupType default. Same null-vs-empty-string
        // semantics. Uppercased on persist because the FedEx enum is
        // strict. Only FedEx maps this to the wire; other connectors
        // ignore the field.
        String pickupType = request.getPickupType();
        if (pickupType != null) {
            String pt = pickupType.trim();
            account.setPickupType(pt.isEmpty() ? null : pt.toUpperCase(Locale.ROOT));
        }

        // Third-party billing defaults. Same null-vs-empty-string semantics:
        // null in request = keep persisted; non-null (incl. empty) = write
        // through with empty normalized to null. Country is upper-cased to
        // match the ISO-2 convention we apply to the client's Ship From.
        applyTrimmable(request.getThirdPartyAccount(), account::setThirdPartyAccount, false);
        applyTrimmable(request.getThirdPartyName(),    account::setThirdPartyName,    false);
        applyTrimmable(request.getThirdPartyAddress1(),account::setThirdPartyAddress1,false);
        applyTrimmable(request.getThirdPartyCity(),    account::setThirdPartyCity,    false);
        applyTrimmable(request.getThirdPartyState(),   account::setThirdPartyState,   false);
        applyTrimmable(request.getThirdPartyPostcode(),account::setThirdPartyPostcode,false);
        applyTrimmable(request.getThirdPartyCountry(), account::setThirdPartyCountry, true);

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

        // Sprint 49 Tier 3 Fix 1 — a new/changed account changes which
        // credentials rate-shop authenticates with; drop stale prices.
        eventPublisher.publishEvent(
                new CarrierConfigChangedEvent(carrierCode, "account-upsert"));

        return success("Carrier account saved to the reference book.", toDTO(account));
    }

    @Override
    @Transactional
    public ApiResponse<CarrierAccountRefDTO> setClientDefault(Long accountId) {
        CarrierAccountRef account = carrierAccountRefRepository.findById(accountId).orElse(null);

        if (account == null) {
            return failure(HttpStatus.NOT_FOUND, ErrorCode.ACCOUNT_NOT_FOUND, "Carrier account " + accountId + " was not found.");
        }

        // Sprint 50 Tier 0.5 PR H — critical: without this a scoped USER
        // could re-point another tenant's default account at their own
        // client via IDOR on the numeric accountId.
        requireMatch(account.getCustomerNo());

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

        // Sprint 50 Tier 0.5 PR H — scoped USER cannot activate/deactivate
        // a foreign tenant's carrier account.
        requireMatch(account.getCustomerNo());

        boolean nextActive = Boolean.FALSE.equals(account.getActive());
        account.setActive(nextActive);

        carrierAccountRefRepository.save(account);
        auditService.record(AuditService.TOGGLE_ACTIVE, AuditService.CARRIER_ACCOUNT,
                account.getId(),
                account.getCarrierCode() + " · " + account.getAccountNumber(),
                java.util.Map.of("active", nextActive, "customerNo", account.getCustomerNo()),
                (nextActive ? "Carrier account activated: " : "Carrier account deactivated: ")
                        + account.getCarrierCode() + "/" + account.getAccountNumber());

        // Sprint 49 Tier 3 Fix 1 — activating/deactivating an account can
        // change which credentials rate-shop picks up.
        eventPublisher.publishEvent(new CarrierConfigChangedEvent(
                account.getCarrierCode(), nextActive ? "account-activated" : "account-deactivated"));

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
                .shippingPurpose(account.getShippingPurpose())
                .clearanceOption(account.getClearanceOption())
                .currency(account.getCurrency())
                .pickupType(account.getPickupType())
                .thirdPartyAccount(account.getThirdPartyAccount())
                .thirdPartyName(account.getThirdPartyName())
                .thirdPartyAddress1(account.getThirdPartyAddress1())
                .thirdPartyCity(account.getThirdPartyCity())
                .thirdPartyState(account.getThirdPartyState())
                .thirdPartyPostcode(account.getThirdPartyPostcode())
                .thirdPartyCountry(account.getThirdPartyCountry())
                .createdAt(account.getCreatedAt())
                .updatedAt(account.getUpdatedAt())
                .build();
    }

    /**
     * Null-safe field setter helper for the upsert flow — null value = "keep
     * persisted" (client didn't send this field), non-null = write through
     * (empty string clears the DB column). Reused for every third-party
     * billing field so the null / clear semantics stay consistent.
     */
    private static void applyTrimmable(String value, java.util.function.Consumer<String> setter, boolean upper) {
        if (value == null) return;
        String v = value.trim();
        if (v.isEmpty()) { setter.accept(null); return; }
        setter.accept(upper ? v.toUpperCase(Locale.ROOT) : v);
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
                .map(account -> {
                    // Sprint 49 Tier 1: never return the plaintext client_secret.
                    // The UI shows the mask and (future) triggers a server-side
                    // copy flow when the admin picks "use platform credentials".
                    String secret = account.getClientSecret();
                    boolean hasSecret = secret != null && !secret.isBlank();
                    String masked = hasSecret ? maskSecret(secret) : "";
                    return success("Platform credentials loaded.", PlatformCredentialsDTO.builder()
                            .carrierCode(canonical)
                            .clientId(account.getClientId())
                            .clientSecretMasked(masked)
                            .hasClientSecret(hasSecret)
                            .found(true)
                            .build());
                })
                .orElseGet(() -> success("No platform account for this carrier yet.",
                        PlatformCredentialsDTO.builder().carrierCode(canonical).found(false).build()));
    }

    private static String maskSecret(String s) {
        if (s == null || s.length() <= 4) return "****";
        return "****" + s.substring(s.length() - 4);
    }

    @Override
    @Transactional
    public ApiResponse<Void> deleteAccount(Long accountId) {
        CarrierAccountRef account = carrierAccountRefRepository.findById(accountId).orElse(null);
        if (account == null) {
            return failure(HttpStatus.NOT_FOUND, ErrorCode.ACCOUNT_NOT_FOUND,
                    "Carrier account " + accountId + " was not found.");
        }
        // Sprint 50 Tier 0.5 PR H — scoped USER cannot delete a foreign
        // tenant's carrier account.
        requireMatch(account.getCustomerNo());
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

        // Sprint 49 Tier 3 Fix 1 — drop this carrier's cached prices.
        eventPublisher.publishEvent(
                new CarrierConfigChangedEvent(deletedCarrier, "account-deleted"));

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
