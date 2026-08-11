package com.multiship.backend.service;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.SavedRecipientDTO;
import com.multiship.backend.model.SavedRecipient;
import com.multiship.backend.repository.SavedRecipientRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Sprint 38 impl. Deduplication keyed on
 * {@code lower(name)|lower(addressLine1)|lower(postalCode)}. Ownership
 * scope enforced at the repository layer.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SavedRecipientServiceImpl implements SavedRecipientService {

    /** Fuzzy-search cap so a stray one-letter query doesn't return
     *  the whole book. */
    private static final int SEARCH_MAX = 25;

    private final SavedRecipientRepository repository;
    /**
     * Sprint 50 Tier 0.5 PR E - clamp customerNo so a scoped USER cannot
     * search/write for a foreign tenant's saved recipients.
     */
    private final TenantScopeEnforcer tenantScope;

    @Override
    public ApiResponse<List<SavedRecipientDTO>> search(String q, String customerNo) {
        String norm = q == null ? null : q.trim();
        // Sprint 50 Tier 0.5 PR E - Pattern A on the caller-supplied
        // customerNo filter. Scoped USER null → own tenant; foreign → 403.
        String scoped = tenantScope.clampClientCode(customerNo);
        List<SavedRecipient> hits = repository.search(
                StringUtils.hasText(scoped) ? scoped : null,
                StringUtils.hasText(norm) ? norm : null);
        if (hits.size() > SEARCH_MAX) hits = hits.subList(0, SEARCH_MAX);
        return ApiResponse.<List<SavedRecipientDTO>>builder()
                .status("success").code(200)
                .message(hits.isEmpty() ? "No matches." : hits.size() + " match(es).")
                .data(hits.stream().map(SavedRecipientServiceImpl::toDto).toList())
                .build();
    }

    @Override
    public ApiResponse<SavedRecipientDTO> byId(Long id) {
        Optional<SavedRecipient> maybe = repository.findById(id);
        if (maybe.isEmpty()) {
            return failure(HttpStatus.NOT_FOUND, "Saved recipient " + id + " not found.");
        }
        // Sprint 50 Tier 0.5 PR G - Pattern B on the loaded row so a
        // scoped USER can't read a foreign tenant's recipient by id.
        tenantScope.requireTenantMatch(maybe.get().getOwnerCustomerNo());
        return success(toDto(maybe.get()), "Found.");
    }

    @Override
    public ApiResponse<SavedRecipientDTO> create(SavedRecipientDTO request) {
        String err = validateRequest(request);
        if (err != null) return failure(HttpStatus.BAD_REQUEST, err);

        // Sprint 50 Tier 0.5 PR E - Pattern A on ownerCustomerNo before
        // persist. Scoped USER null → own tenant; foreign → 403.
        request.setOwnerCustomerNo(tenantScope.clampClientCode(request.getOwnerCustomerNo()));
        String hash = dedupHash(request.getName(), request.getAddressLine1(), request.getPostalCode());
        Optional<SavedRecipient> existing = repository.findExisting(hash, request.getOwnerCustomerNo());
        if (existing.isPresent()) {
            // Return the existing row unchanged — idempotent create.
            return success(toDto(existing.get()),
                    "An entry with the same name + street + postal code already exists.");
        }
        SavedRecipient row = new SavedRecipient();
        applyDto(row, request);
        row.setDedupHash(hash);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        return success(toDto(repository.save(row)), "Recipient saved.");
    }

    @Override
    public ApiResponse<SavedRecipientDTO> update(Long id, SavedRecipientDTO request) {
        String err = validateRequest(request);
        if (err != null) return failure(HttpStatus.BAD_REQUEST, err);

        // Sprint 50 Tier 0.5 PR E - Pattern A on ownerCustomerNo BEFORE the
        // load so the caller can't tell the diff between "row exists but
        // foreign tenant" and "row missing" by touching foreign IDs.
        request.setOwnerCustomerNo(tenantScope.clampClientCode(request.getOwnerCustomerNo()));
        Optional<SavedRecipient> maybe = repository.findById(id);
        if (maybe.isEmpty()) {
            return failure(HttpStatus.NOT_FOUND,
                    "Saved recipient " + id + " not found.");
        }
        SavedRecipient row = maybe.get();
        // Sprint 50 Tier 0.5 PR E - Pattern B on the loaded row: a scoped
        // USER hitting an existing row that belongs to another tenant
        // gets a 403, not a silent overwrite.
        tenantScope.requireTenantMatch(row.getOwnerCustomerNo());
        applyDto(row, request);
        // Recompute the dedup hash — a name / street / postal edit
        // shifts the dedup identity.
        row.setDedupHash(dedupHash(request.getName(), request.getAddressLine1(), request.getPostalCode()));
        row.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        return success(toDto(repository.save(row)), "Recipient updated.");
    }

    @Override
    public ApiResponse<Void> delete(Long id) {
        Optional<SavedRecipient> maybe = repository.findById(id);
        if (maybe.isEmpty()) {
            return ApiResponse.<Void>builder()
                    .status("error").code(404)
                    .errorCode(ErrorCode.VALIDATION_ERROR.name())
                    .message("Saved recipient " + id + " not found.")
                    .data(null).build();
        }
        // Sprint 50 Tier 0.5 PR G - Pattern B on the loaded row so a
        // scoped USER can't delete a foreign tenant's recipient by id.
        tenantScope.requireTenantMatch(maybe.get().getOwnerCustomerNo());
        repository.deleteById(id);
        return ApiResponse.<Void>builder()
                .status("success").code(200)
                .message("Recipient deleted.").data(null).build();
    }

    /* -------------------------- helpers -------------------------- */

    private static void applyDto(SavedRecipient row, SavedRecipientDTO dto) {
        row.setOwnerCustomerNo(StringUtils.hasText(dto.getOwnerCustomerNo())
                ? dto.getOwnerCustomerNo() : null);
        row.setName(dto.getName().trim());
        row.setCompany(dto.getCompany());
        row.setPhone(dto.getPhone());
        row.setPhoneCountryCode(dto.getPhoneCountryCode());
        row.setEmail(dto.getEmail());
        row.setAddressLine1(dto.getAddressLine1().trim());
        row.setAddressLine2(dto.getAddressLine2());
        row.setAddressLine3(dto.getAddressLine3());
        row.setCity(dto.getCity().trim());
        row.setState(dto.getState());
        row.setPostalCode(dto.getPostalCode().trim());
        row.setCountryCode(dto.getCountryCode().trim().toUpperCase(Locale.ROOT));
        row.setResidential(dto.getResidential());
        row.setTag(dto.getTag());
    }

    /** Constant-time-adjacent hash used as the unique constraint's
     *  second column. SHA-256 hex, 64 chars. */
    static String dedupHash(String name, String addressLine1, String postalCode) {
        String base = normalize(name) + "|" + normalize(addressLine1) + "|" + normalize(postalCode);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(base.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            // Fallback: uniqueness fails safely toward "please try again".
            return Integer.toHexString(base.hashCode());
        }
    }

    private static String normalize(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private static String validateRequest(SavedRecipientDTO r) {
        if (r == null) return "Request body is required.";
        if (!StringUtils.hasText(r.getName())) return "name is required.";
        if (!StringUtils.hasText(r.getAddressLine1())) return "addressLine1 is required.";
        if (!StringUtils.hasText(r.getCity())) return "city is required.";
        if (!StringUtils.hasText(r.getPostalCode())) return "postalCode is required.";
        if (!StringUtils.hasText(r.getCountryCode())) return "countryCode is required.";
        return null;
    }

    static SavedRecipientDTO toDto(SavedRecipient r) {
        return SavedRecipientDTO.builder()
                .id(r.getId())
                .ownerCustomerNo(r.getOwnerCustomerNo())
                .name(r.getName())
                .company(r.getCompany())
                .phone(r.getPhone())
                .phoneCountryCode(r.getPhoneCountryCode())
                .email(r.getEmail())
                .addressLine1(r.getAddressLine1())
                .addressLine2(r.getAddressLine2())
                .addressLine3(r.getAddressLine3())
                .city(r.getCity())
                .state(r.getState())
                .postalCode(r.getPostalCode())
                .countryCode(r.getCountryCode())
                .residential(r.getResidential())
                .tag(r.getTag())
                .createdAt(r.getCreatedAt())
                .updatedAt(r.getUpdatedAt())
                .build();
    }

    private static <T> ApiResponse<T> success(T data, String message) {
        return ApiResponse.<T>builder()
                .status("success").code(200).message(message).data(data).build();
    }

    private static <T> ApiResponse<T> failure(HttpStatus status, String message) {
        return ApiResponse.<T>builder()
                .status("error").code(status.value())
                .errorCode(ErrorCode.VALIDATION_ERROR.name())
                .message(message).data(null).build();
    }
}
