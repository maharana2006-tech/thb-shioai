package com.multiship.backend.controller;

import com.multiship.backend.dto.AccountRefUpsertRequest;
import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.CarrierAccountRefDTO;
import com.multiship.backend.dto.CredentialCheckDTO;
import com.multiship.backend.dto.PlatformCredentialsDTO;
import com.multiship.backend.dto.VerifyCredentialsRequest;
import com.multiship.backend.service.AccountRefService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Reference book of carrier accounts used by the label-generation cascade. */
@Tag(name = "Account Book", description = "Reference book of carrier accounts feeding the label-generation cascade")
@RestController
@RequestMapping("/api/v1/carrier-accounts")
@RequiredArgsConstructor
public class AccountRefController {

    private final AccountRefService accountRefService;

    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping
    public ResponseEntity<ApiResponse<List<CarrierAccountRefDTO>>> listAccounts() {
        ApiResponse<List<CarrierAccountRefDTO>> response = accountRefService.listAccounts();
        return ResponseEntity.status(response.getCode()).body(response);
    }

    @Operation(summary = "Create or update an account by (accountNumber, carrier)",
            description = "Fill-once semantics: after saving, every future order referencing this account number generates automatically. New credentials reset the verification state.")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @PostMapping
    public ResponseEntity<ApiResponse<CarrierAccountRefDTO>> upsertAccount(
            @Valid @RequestBody AccountRefUpsertRequest request) {
        ApiResponse<CarrierAccountRefDTO> response = accountRefService.upsertAccount(request);
        return ResponseEntity.status(response.getCode()).body(response);
    }


    @Operation(summary = "Make this account the linked client's default",
            description = "At most one client-default per client; used by the cascade before the company default.")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @PostMapping("/{accountId}/client-default")
    public ResponseEntity<ApiResponse<CarrierAccountRefDTO>> setClientDefault(@PathVariable Long accountId) {
        ApiResponse<CarrierAccountRefDTO> response = accountRefService.setClientDefault(accountId);
        return ResponseEntity.status(response.getCode()).body(response);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{accountId}/toggle-active")
    public ResponseEntity<ApiResponse<CarrierAccountRefDTO>> toggleActive(@PathVariable Long accountId) {
        ApiResponse<CarrierAccountRefDTO> response = accountRefService.toggleActive(accountId);
        return ResponseEntity.status(response.getCode()).body(response);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{accountId}/verify")
    public ResponseEntity<ApiResponse<CarrierAccountRefDTO>> verifyAccount(@PathVariable Long accountId) {
        ApiResponse<CarrierAccountRefDTO> response = accountRefService.verifyAccount(accountId);
        return ResponseEntity.status(response.getCode()).body(response);
    }

    @Operation(summary = "Platform credentials for a carrier (ADMIN)",
            description = "Pre-fills Client ID / Secret in the add-account drawer from the platform account of the given carrier. Returns found=false when none exists.")
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/platform-credentials/{carrierCode}")
    public ResponseEntity<ApiResponse<PlatformCredentialsDTO>> getPlatformCredentials(@PathVariable String carrierCode) {
        ApiResponse<PlatformCredentialsDTO> response = accountRefService.getPlatformCredentials(carrierCode);
        return ResponseEntity.status(response.getCode()).body(response);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/verify-credentials")
    public ResponseEntity<ApiResponse<CredentialCheckDTO>> verifyCredentials(
            @Valid @RequestBody VerifyCredentialsRequest request) {
        ApiResponse<CredentialCheckDTO> response = accountRefService.verifyCredentials(request);
        return ResponseEntity.status(response.getCode()).body(response);
    }
}
