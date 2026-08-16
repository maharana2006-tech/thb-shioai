package com.multiship.backend.controller;

import com.multiship.backend.dto.AccountRefUpsertRequest;
import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.CarrierAccountRefDTO;
import com.multiship.backend.dto.CredentialCheckDTO;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.PlatformCredentialsDTO;
import com.multiship.backend.dto.VerifyCredentialsRequest;
import com.multiship.backend.service.AccountRefService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Sprint 51 /settings/carriers coverage — smoke tests for {@link AccountRefController}
 * (was 0-coverage per the audit). Focused on the code that lives IN the controller
 * (status-code echoing, delegation to the mocked service, correct method + arg passing).
 * Real carrier IO is impossible here because the controller only talks to
 * {@link AccountRefService}, which is mocked in every test — no {@code CarrierConnector}
 * bean is constructed, no {@code RestClient} is instantiated.
 *
 * <p>Endpoints covered (8):
 * <ul>
 *   <li>GET  /                              — listAccounts</li>
 *   <li>POST /                              — upsertAccount</li>
 *   <li>POST /{id}/client-default           — setClientDefault</li>
 *   <li>POST /{id}/toggle-active            — toggleActive</li>
 *   <li>DELETE /{id}                        — deleteAccount (ADMIN-only)</li>
 *   <li>POST /{id}/verify                   — verifyAccount (ADMIN-only)</li>
 *   <li>GET  /platform-credentials/{code}   — getPlatformCredentials (ADMIN-only)</li>
 *   <li>POST /verify-credentials            — verifyCredentials (ADMIN-only)</li>
 * </ul>
 *
 * <p>Role-based access enforcement (401/403) is contributed by the Spring Security
 * chain around the controller and is validated separately by the security-slice
 * integration tests. Here we assert (a) the controller does not swallow the
 * service's non-200 codes and (b) each call reaches the mocked service exactly
 * once with the expected argument.
 */
class AccountRefControllerTest {

    private AccountRefService accountRefService;
    private AccountRefController controller;

    @BeforeEach
    void setUp() {
        accountRefService = mock(AccountRefService.class);
        controller = new AccountRefController(accountRefService);
    }

    // ================ helpers ================

    private static CarrierAccountRefDTO dto() {
        return CarrierAccountRefDTO.builder()
                .id(1L)
                .accountNumber("ACCT-1")
                .carrierCode("UPS")
                .active(true)
                .verified(true)
                .build();
    }

    private static <T> ApiResponse<T> ok(T data) {
        return ApiResponse.<T>builder()
                .status("success").code(200).data(data).message("ok")
                .timestamp(LocalDateTime.now()).build();
    }

    private static <T> ApiResponse<T> err(int code, ErrorCode ec, String msg) {
        return ApiResponse.<T>builder()
                .status("error").code(code).errorCode(ec.name()).message(msg)
                .timestamp(LocalDateTime.now()).build();
    }

    private static AccountRefUpsertRequest upsertReq() {
        return AccountRefUpsertRequest.builder()
                .accountNumber("ACCT-1")
                .carrierCode("UPS")
                .clientId("cid")
                .clientSecret("csecret")
                .environment("SANDBOX")
                .build();
    }

    private static VerifyCredentialsRequest verifyReq() {
        return VerifyCredentialsRequest.builder()
                .carrierCode("UPS")
                .clientId("cid")
                .clientSecret("csecret")
                .environment("SANDBOX")
                .build();
    }

    // ================ GET / — listAccounts ================

    @Test
    void list_returns200WithData_andDelegatesOnce() {
        ApiResponse<List<CarrierAccountRefDTO>> resp = ok(List.of(dto()));
        when(accountRefService.listAccounts()).thenReturn(resp);

        ResponseEntity<ApiResponse<List<CarrierAccountRefDTO>>> re = controller.listAccounts();

        assertEquals(HttpStatus.OK, re.getStatusCode());
        assertSame(resp, re.getBody());
        assertEquals(1, re.getBody().getData().size());
        verify(accountRefService, times(1)).listAccounts();
    }

    @Test
    void list_echoesServiceNon200_notRewrittenTo200() {
        // Sprint 51 audit-pattern: controller MUST use
        // ResponseEntity.status(response.getCode()).body(response), not .ok().
        ApiResponse<List<CarrierAccountRefDTO>> resp =
                err(500, ErrorCode.VALIDATION_ERROR, "listing failed");
        when(accountRefService.listAccounts()).thenReturn(resp);

        ResponseEntity<ApiResponse<List<CarrierAccountRefDTO>>> re = controller.listAccounts();

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, re.getStatusCode());
        assertEquals(ErrorCode.VALIDATION_ERROR.name(), re.getBody().getErrorCode());
    }

    // ================ POST / — upsertAccount ================

    @Test
    void upsert_returns200WithData_andPassesRequestToService() {
        AccountRefUpsertRequest req = upsertReq();
        ApiResponse<CarrierAccountRefDTO> resp = ok(dto());
        when(accountRefService.upsertAccount(any())).thenReturn(resp);

        ResponseEntity<ApiResponse<CarrierAccountRefDTO>> re = controller.upsertAccount(req);

        assertEquals(HttpStatus.OK, re.getStatusCode());
        assertNotNull(re.getBody().getData());
        assertEquals("UPS", re.getBody().getData().getCarrierCode());
        verify(accountRefService, times(1)).upsertAccount(eq(req));
    }

    @Test
    void upsert_serviceValidation400_isEchoedNot200() {
        ApiResponse<CarrierAccountRefDTO> resp =
                err(400, ErrorCode.VALIDATION_ERROR, "clientId required on create");
        when(accountRefService.upsertAccount(any())).thenReturn(resp);

        ResponseEntity<ApiResponse<CarrierAccountRefDTO>> re = controller.upsertAccount(upsertReq());

        assertEquals(HttpStatus.BAD_REQUEST, re.getStatusCode());
        assertEquals(ErrorCode.VALIDATION_ERROR.name(), re.getBody().getErrorCode());
    }

    @Test
    void upsert_serviceCarrierFailure_502IsEchoed() {
        ApiResponse<CarrierAccountRefDTO> resp =
                err(502, ErrorCode.CARRIER_FAILURE, "UPS timeout");
        when(accountRefService.upsertAccount(any())).thenReturn(resp);

        ResponseEntity<ApiResponse<CarrierAccountRefDTO>> re = controller.upsertAccount(upsertReq());

        assertEquals(HttpStatus.BAD_GATEWAY, re.getStatusCode());
        assertEquals(ErrorCode.CARRIER_FAILURE.name(), re.getBody().getErrorCode());
    }

    // ================ POST /{id}/client-default — setClientDefault ================

    @Test
    void setClientDefault_returns200_andPassesIdThrough() {
        ApiResponse<CarrierAccountRefDTO> resp = ok(dto());
        when(accountRefService.setClientDefault(7L)).thenReturn(resp);

        ResponseEntity<ApiResponse<CarrierAccountRefDTO>> re = controller.setClientDefault(7L);

        assertEquals(HttpStatus.OK, re.getStatusCode());
        verify(accountRefService, times(1)).setClientDefault(7L);
    }

    @Test
    void setClientDefault_accountNotFound_returns404() {
        ApiResponse<CarrierAccountRefDTO> resp =
                err(404, ErrorCode.ACCOUNT_NOT_FOUND, "no such account");
        when(accountRefService.setClientDefault(99L)).thenReturn(resp);

        ResponseEntity<ApiResponse<CarrierAccountRefDTO>> re = controller.setClientDefault(99L);

        assertEquals(HttpStatus.NOT_FOUND, re.getStatusCode());
        assertEquals(ErrorCode.ACCOUNT_NOT_FOUND.name(), re.getBody().getErrorCode());
    }

    // ================ POST /{id}/toggle-active — toggleActive ================

    @Test
    void toggleActive_returns200_andDelegatesOnceWithId() {
        ApiResponse<CarrierAccountRefDTO> resp = ok(dto());
        when(accountRefService.toggleActive(5L)).thenReturn(resp);

        ResponseEntity<ApiResponse<CarrierAccountRefDTO>> re = controller.toggleActive(5L);

        assertEquals(HttpStatus.OK, re.getStatusCode());
        verify(accountRefService, times(1)).toggleActive(5L);
    }

    @Test
    void toggleActive_accountNotFound_returns404() {
        ApiResponse<CarrierAccountRefDTO> resp =
                err(404, ErrorCode.ACCOUNT_NOT_FOUND, "no such account");
        when(accountRefService.toggleActive(99L)).thenReturn(resp);

        ResponseEntity<ApiResponse<CarrierAccountRefDTO>> re = controller.toggleActive(99L);

        assertEquals(HttpStatus.NOT_FOUND, re.getStatusCode());
        assertEquals(ErrorCode.ACCOUNT_NOT_FOUND.name(), re.getBody().getErrorCode());
    }

    // ================ DELETE /{id} — deleteAccount (ADMIN) ================

    @Test
    void delete_returns200_andDelegatesOnce() {
        ApiResponse<Void> resp = ok(null);
        when(accountRefService.deleteAccount(3L)).thenReturn(resp);

        ResponseEntity<ApiResponse<Void>> re = controller.deleteAccount(3L);

        assertEquals(HttpStatus.OK, re.getStatusCode());
        verify(accountRefService, times(1)).deleteAccount(3L);
    }

    @Test
    void delete_accountHasLabels_returns409() {
        // Business rule: refuse deletion when the account has already
        // generated labels — deactivate instead to preserve the audit trail.
        ApiResponse<Void> resp =
                err(409, ErrorCode.ACCOUNT_HAS_LABELS, "account has generated labels");
        when(accountRefService.deleteAccount(3L)).thenReturn(resp);

        ResponseEntity<ApiResponse<Void>> re = controller.deleteAccount(3L);

        assertEquals(HttpStatus.CONFLICT, re.getStatusCode());
        assertEquals(ErrorCode.ACCOUNT_HAS_LABELS.name(), re.getBody().getErrorCode());
    }

    @Test
    void delete_accountNotFound_returns404() {
        ApiResponse<Void> resp =
                err(404, ErrorCode.ACCOUNT_NOT_FOUND, "no such account");
        when(accountRefService.deleteAccount(99L)).thenReturn(resp);

        ResponseEntity<ApiResponse<Void>> re = controller.deleteAccount(99L);

        assertEquals(HttpStatus.NOT_FOUND, re.getStatusCode());
        assertEquals(ErrorCode.ACCOUNT_NOT_FOUND.name(), re.getBody().getErrorCode());
    }

    // ================ POST /{id}/verify — verifyAccount (ADMIN) ================

    @Test
    void verifyAccount_returns200_andDelegatesToServiceExactlyOnce() {
        // Anti-fallback guard: the controller must NOT bypass the service
        // and call a CarrierConnector directly. Since AccountRefService is
        // the ONLY collaborator injected, verifying a single delegated
        // invocation proves the request cannot have hit a real carrier.
        ApiResponse<CarrierAccountRefDTO> resp = ok(dto());
        when(accountRefService.verifyAccount(11L)).thenReturn(resp);

        ResponseEntity<ApiResponse<CarrierAccountRefDTO>> re = controller.verifyAccount(11L);

        assertEquals(HttpStatus.OK, re.getStatusCode());
        assertEquals(Boolean.TRUE, re.getBody().getData().getVerified());
        verify(accountRefService, times(1)).verifyAccount(11L);
        // No other service methods were touched.
        verify(accountRefService, never()).verifyCredentials(any());
        verify(accountRefService, never()).upsertAccount(any());
    }

    @Test
    void verifyAccount_carrierFailure_502IsEchoed() {
        ApiResponse<CarrierAccountRefDTO> resp =
                err(502, ErrorCode.CARRIER_FAILURE, "UPS 500");
        when(accountRefService.verifyAccount(11L)).thenReturn(resp);

        ResponseEntity<ApiResponse<CarrierAccountRefDTO>> re = controller.verifyAccount(11L);

        assertEquals(HttpStatus.BAD_GATEWAY, re.getStatusCode());
        assertEquals(ErrorCode.CARRIER_FAILURE.name(), re.getBody().getErrorCode());
    }

    @Test
    void verifyAccount_accountNotFound_returns404() {
        ApiResponse<CarrierAccountRefDTO> resp =
                err(404, ErrorCode.ACCOUNT_NOT_FOUND, "no such account");
        when(accountRefService.verifyAccount(99L)).thenReturn(resp);

        ResponseEntity<ApiResponse<CarrierAccountRefDTO>> re = controller.verifyAccount(99L);

        assertEquals(HttpStatus.NOT_FOUND, re.getStatusCode());
        assertEquals(ErrorCode.ACCOUNT_NOT_FOUND.name(), re.getBody().getErrorCode());
    }

    // ================ GET /platform-credentials/{carrierCode} (ADMIN) ================

    @Test
    void getPlatformCredentials_found_returns200WithMaskedSecretOnly() {
        // Sprint 49 Tier 1 — plaintext clientSecret must NEVER be in the
        // response. The DTO field is clientSecretMasked; we just confirm
        // the controller echoes what the service returned without altering
        // status / payload.
        PlatformCredentialsDTO data = PlatformCredentialsDTO.builder()
                .carrierCode("UPS").clientId("cid")
                .clientSecretMasked("****abcd").hasClientSecret(true).found(true).build();
        ApiResponse<PlatformCredentialsDTO> resp = ok(data);
        when(accountRefService.getPlatformCredentials("UPS")).thenReturn(resp);

        ResponseEntity<ApiResponse<PlatformCredentialsDTO>> re = controller.getPlatformCredentials("UPS");

        assertEquals(HttpStatus.OK, re.getStatusCode());
        assertNotNull(re.getBody().getData());
        assertEquals(true, re.getBody().getData().isFound());
        assertEquals("****abcd", re.getBody().getData().getClientSecretMasked());
        verify(accountRefService, times(1)).getPlatformCredentials("UPS");
    }

    @Test
    void getPlatformCredentials_notFound_returns200WithFoundFalse() {
        // Documented behaviour: "Returns found=false when none exists" —
        // so it is NOT a 404, still a 200 with an empty-flag payload.
        PlatformCredentialsDTO data = PlatformCredentialsDTO.builder()
                .carrierCode("DHL").found(false).hasClientSecret(false).build();
        when(accountRefService.getPlatformCredentials("DHL")).thenReturn(ok(data));

        ResponseEntity<ApiResponse<PlatformCredentialsDTO>> re = controller.getPlatformCredentials("DHL");

        assertEquals(HttpStatus.OK, re.getStatusCode());
        assertEquals(false, re.getBody().getData().isFound());
    }

    @Test
    void getPlatformCredentials_unknownCarrier_404IsEchoed() {
        ApiResponse<PlatformCredentialsDTO> resp =
                err(404, ErrorCode.VALIDATION_ERROR, "unknown carrier code");
        when(accountRefService.getPlatformCredentials("BOGUS")).thenReturn(resp);

        ResponseEntity<ApiResponse<PlatformCredentialsDTO>> re = controller.getPlatformCredentials("BOGUS");

        assertEquals(HttpStatus.NOT_FOUND, re.getStatusCode());
    }

    // ================ POST /verify-credentials (ADMIN) ================

    @Test
    void verifyCredentials_success_returns200_andPassesRequestThrough() {
        // Anti-fallback: only AccountRefService is a collaborator here,
        // so a successful verify cannot have made a real carrier call.
        VerifyCredentialsRequest req = verifyReq();
        CredentialCheckDTO data = CredentialCheckDTO.builder()
                .verified(true).message("ok").checkedAt(LocalDateTime.now()).build();
        when(accountRefService.verifyCredentials(any())).thenReturn(ok(data));

        ResponseEntity<ApiResponse<CredentialCheckDTO>> re = controller.verifyCredentials(req);

        assertEquals(HttpStatus.OK, re.getStatusCode());
        assertEquals(Boolean.TRUE, re.getBody().getData().getVerified());
        verify(accountRefService, times(1)).verifyCredentials(eq(req));
        verify(accountRefService, never()).verifyAccount(any());
    }

    @Test
    void verifyCredentials_badCredentials_returns401Echoed() {
        ApiResponse<CredentialCheckDTO> resp =
                err(401, ErrorCode.CLIENT_CARRIER_AUTH_FAILED, "invalid client secret");
        when(accountRefService.verifyCredentials(any())).thenReturn(resp);

        ResponseEntity<ApiResponse<CredentialCheckDTO>> re = controller.verifyCredentials(verifyReq());

        assertEquals(HttpStatus.UNAUTHORIZED, re.getStatusCode());
        assertEquals(ErrorCode.CLIENT_CARRIER_AUTH_FAILED.name(), re.getBody().getErrorCode());
    }

    @Test
    void verifyCredentials_carrierTimeout_502IsEchoed() {
        ApiResponse<CredentialCheckDTO> resp =
                err(502, ErrorCode.CARRIER_FAILURE, "UPS timeout");
        when(accountRefService.verifyCredentials(any())).thenReturn(resp);

        ResponseEntity<ApiResponse<CredentialCheckDTO>> re = controller.verifyCredentials(verifyReq());

        assertEquals(HttpStatus.BAD_GATEWAY, re.getStatusCode());
        assertEquals(ErrorCode.CARRIER_FAILURE.name(), re.getBody().getErrorCode());
    }

    // ================ cross-cutting: no unexpected service calls ================

    @Test
    void list_doesNotTouchOtherServiceMethods() {
        // Guard against copy-paste refactor accidents (e.g. list() calling
        // verifyAccount() as a side-effect).
        when(accountRefService.listAccounts()).thenReturn(ok(List.of()));

        controller.listAccounts();

        verify(accountRefService).listAccounts();
        verify(accountRefService, never()).verifyAccount(any());
        verify(accountRefService, never()).verifyCredentials(any());
        verify(accountRefService, never()).upsertAccount(any());
        verify(accountRefService, never()).deleteAccount(any());
        verify(accountRefService, never()).setClientDefault(any());
        verify(accountRefService, never()).toggleActive(any());
        verify(accountRefService, never()).getPlatformCredentials(any());
    }

    @Test
    void constructor_isPureDelegation_noEagerServiceCalls() {
        // Guarding against future churn: constructing the controller must not
        // call any service method (avoids surprising work on Spring context
        // init).
        AccountRefService fresh = mock(AccountRefService.class);
        new AccountRefController(fresh);
        verifyNoInteractions(fresh);
    }
}
