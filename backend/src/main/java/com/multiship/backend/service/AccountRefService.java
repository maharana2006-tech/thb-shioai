package com.multiship.backend.service;

import com.multiship.backend.dto.AccountRefUpsertRequest;
import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.CarrierAccountRefDTO;
import com.multiship.backend.dto.CredentialCheckDTO;
import com.multiship.backend.dto.PlatformCredentialsDTO;
import com.multiship.backend.dto.VerifyCredentialsRequest;

import java.util.List;

public interface AccountRefService {

    ApiResponse<List<CarrierAccountRefDTO>> listAccounts();

    ApiResponse<CarrierAccountRefDTO> upsertAccount(AccountRefUpsertRequest request);

    ApiResponse<CarrierAccountRefDTO> setClientDefault(Long accountId);

    ApiResponse<CarrierAccountRefDTO> toggleActive(Long accountId);

    /** Live credential check for a saved account; stamps verified + lastVerifiedAt. */
    ApiResponse<CarrierAccountRefDTO> verifyAccount(Long accountId);

    /** Stateless credential check (add-account drawer, before saving). */
    ApiResponse<CredentialCheckDTO> verifyCredentials(VerifyCredentialsRequest request);

    /** Platform-account credentials for a carrier, to pre-fill the add-account drawer. */
    ApiResponse<PlatformCredentialsDTO> getPlatformCredentials(String carrierCode);
}
