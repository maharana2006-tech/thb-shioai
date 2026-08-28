package com.multiship.backend.service;

import com.multiship.backend.dto.AccountRefUpsertRequest;
import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.CarrierAccountRefDTO;
import com.multiship.backend.dto.CredentialCheckDTO;
import com.multiship.backend.dto.PlatformCredentialsDTO;
import com.multiship.backend.dto.SyncEligibleAccountDTO;
import com.multiship.backend.dto.VerifyCredentialsRequest;

import java.util.List;

public interface AccountRefService {

    ApiResponse<List<CarrierAccountRefDTO>> listAccounts();

    /**
     * Active + verified accounts (platform + client) for a carrier, sorted
     * platform-first-newest. Powers the /settings/shipping-catalog sync
     * menu — operator picks env + account before pulling the carrier's
     * service or package catalog. Returns an empty list when no verified
     * accounts exist so the FE can show a "connect an account first"
     * empty state instead of a network error.
     */
    ApiResponse<List<SyncEligibleAccountDTO>> listSyncEligibleAccounts(String carrierCode);

    ApiResponse<CarrierAccountRefDTO> upsertAccount(AccountRefUpsertRequest request);

    ApiResponse<CarrierAccountRefDTO> setClientDefault(Long accountId);

    ApiResponse<CarrierAccountRefDTO> toggleActive(Long accountId);

    /** Live credential check for a saved account; stamps verified + lastVerifiedAt. */
    ApiResponse<CarrierAccountRefDTO> verifyAccount(Long accountId);

    /** Stateless credential check (add-account drawer, before saving). */
    ApiResponse<CredentialCheckDTO> verifyCredentials(VerifyCredentialsRequest request);

    /** Platform-account credentials for a carrier, to pre-fill the add-account drawer. */
    ApiResponse<PlatformCredentialsDTO> getPlatformCredentials(String carrierCode);

    /**
     * Delete a saved account. Refuses (409 ACCOUNT_HAS_LABELS) when the
     * account has already generated any labels — deactivation preserves the
     * audit trail, deletion is only for accidentally-added rows.
     */
    ApiResponse<Void> deleteAccount(Long accountId);
}
