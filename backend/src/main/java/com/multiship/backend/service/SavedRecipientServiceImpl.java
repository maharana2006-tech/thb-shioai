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
        // A platform operator with no client picked searches the whole book,
        // as the Address book page shows it — otherwise an address saved under
        // a client could be listed there yet never found from a new shipment.
        boolean all = !StringUtils.hasText(customerNo) && tenantScope.isPlatformOperator();
        String scoped = all ? null : tenantScope.clampClientCode(customerNo);
        // Each word must appear somewhere in the entry — see SavedRecipientSearch.
        // Paged at the database rather than loading every match and trimming.
        List<SavedRecipient> hits = repository.findAll(
                SavedRecipientSearch.visibleTo(all, StringUtils.hasText(scoped) ? scoped : null)
                        .and(SavedRecipientSearch.matching(norm)),
                org.springframework.data.domain.PageRequest.of(0, SEARCH_MAX,
                        org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "updatedAt")))
                .getContent();
        return ApiResponse.<List<SavedRecipientDTO>>builder()
                .status("success").code(200)
                .message(hits.isEmpty() ? "No matches." : hits.size() + " match(es).")
                .data(hits.stream().map(SavedRecipientServiceImpl::toDto).toList())
                .build();
    }

    @Override
    public ApiResponse<org.springframework.data.domain.Page<SavedRecipientDTO>> list(String q, String customerNo,
                                                                                     int page, int size) {
        String query = q == null ? null : q.trim();
        // A platform operator with no client picked sees the whole book; a
        // client-scoped user is clamped to their own tenant (and the shared
        // entries), whatever they ask for.
        boolean all = !StringUtils.hasText(customerNo) && tenantScope.isPlatformOperator();
        String owner = all ? null : tenantScope.clampClientCode(customerNo);
        var pageable = org.springframework.data.domain.PageRequest.of(Math.max(0, page),
                Math.min(Math.max(1, size), 100),
                org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "updatedAt"));
        var rows = repository.findAll(
                SavedRecipientSearch.visibleTo(all, owner).and(SavedRecipientSearch.matching(query)), pageable)
                .map(SavedRecipientServiceImpl::toDto);
        return success(rows, rows.getTotalElements() + " saved address(es).");
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
        // An edit that turns this entry into a copy of another one (same
        // name + street + postal code, same owner) would trip the unique
        // index and surface as a bare 500. Say which entry it collides with.
        String newHash = dedupHash(request.getName(), request.getAddressLine1(), request.getPostalCode());
        Optional<SavedRecipient> clash = repository.findExisting(newHash, request.getOwnerCustomerNo());
        if (clash.isPresent() && !clash.get().getId().equals(id)) {
            return failure(HttpStatus.CONFLICT, clash.get().getName() + " at " + clash.get().getAddressLine1()
                    + " is already in the address book — edit that entry, or change the name, street or postal code.");
        }
        applyDto(row, request);
        // Recompute the dedup hash — a name / street / postal edit
        // shifts the dedup identity.
        row.setDedupHash(newHash);
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
