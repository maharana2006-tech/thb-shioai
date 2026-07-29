package com.multiship.backend.service;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.SavedRecipientDTO;

import java.util.List;

/**
 * Sprint 38 — address book / saved recipients. CRUD + search. Owner
 * scoping (customerNo) so a 3PL managing multiple clients keeps each
 * client's book separate; null owner = platform-wide.
 */
public interface SavedRecipientService {

    /** Autocomplete search. When {@code customerNo} is set, returns
     *  owner-scoped + platform-wide entries; when null, platform-wide
     *  only. Capped at 25 entries per call. */
    ApiResponse<List<SavedRecipientDTO>> search(String q, String customerNo);

    ApiResponse<SavedRecipientDTO> byId(Long id);

    /**
     * Create or de-dupe. When an existing row matches the (owner,
     * dedupHash) tuple, the existing row is returned unchanged (200
     * with the existing id).
     */
    ApiResponse<SavedRecipientDTO> create(SavedRecipientDTO request);

    ApiResponse<SavedRecipientDTO> update(Long id, SavedRecipientDTO request);

    ApiResponse<Void> delete(Long id);
}
