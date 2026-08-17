package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiKeyIssueRequest;
import com.multiship.backend.dto.ApiKeyResponse;
import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.model.ApiKey;
import com.multiship.backend.repository.ApiKeyRepository;
import com.multiship.backend.repository.ClientRepository;
import com.multiship.backend.service.ApiKeyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sprint 51 AC-M6 — smoke tests for ApiKeyController (was 0-coverage).
 * Exercises the branches that live IN the controller: platform-wide vs
 * client-scoped issue path, rotate/revoke 404/409 shaping.
 */
class ApiKeyControllerTest {

    private ApiKeyService apiKeyService;
    private ApiKeyRepository apiKeyRepository;
    private ClientRepository clientRepository;
    private ApiKeyController controller;
    private UserDetails admin;

    @BeforeEach
    void setUp() {
        apiKeyService = mock(ApiKeyService.class);
        apiKeyRepository = mock(ApiKeyRepository.class);
        clientRepository = mock(ClientRepository.class);
        controller = new ApiKeyController(apiKeyService, apiKeyRepository, clientRepository);
        admin = User.withUsername("admin").password("x").roles("ADMIN").build();
    }

    // ===== issue =====

    @Test
    void issue_unknownClient_returns404ClientNotFound() {
        ApiKeyIssueRequest req = new ApiKeyIssueRequest();
        req.setName("Acme prod");
        req.setClientCode("MISSING");
        when(clientRepository.existsByClientCodeIgnoreCase("MISSING")).thenReturn(false);

        ResponseEntity<ApiResponse<ApiKeyResponse>> resp = controller.issue(req, admin);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals(ErrorCode.CLIENT_NOT_FOUND.name(), resp.getBody().getErrorCode());
    }

    @Test
    void issue_platformWide_blankClient_returns201WithToken() {
        ApiKeyIssueRequest req = new ApiKeyIssueRequest();
        req.setName("Platform WMS");
        req.setClientCode("");  // Blank → platform-wide, skips client existence check.
        ApiKey persisted = ApiKey.builder().id(7L).name("Platform WMS").clientCode("*")
                .environment("live").keyPrefix("abcd1234").active(true).build();
        ApiKeyService.IssuedKey issued = new ApiKeyService.IssuedKey(persisted, "msk_live_abcd1234_secret");
        when(apiKeyService.issue(eq("Platform WMS"), eq("*"), any(), any(), eq("admin"))).thenReturn(issued);
        when(apiKeyService.maskedToken(persisted)).thenReturn("msk_live_abcd1234_••••••");

        ResponseEntity<ApiResponse<ApiKeyResponse>> resp = controller.issue(req, admin);

        assertEquals(HttpStatus.CREATED, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertNotNull(resp.getBody().getData());
        assertEquals("msk_live_abcd1234_secret", resp.getBody().getData().getToken());
    }

    @Test
    void issue_knownClient_returns201() {
        ApiKeyIssueRequest req = new ApiKeyIssueRequest();
        req.setName("Acme prod");
        req.setClientCode("ACME");
        req.setEnvironment("live");
        when(clientRepository.existsByClientCodeIgnoreCase("ACME")).thenReturn(true);
        ApiKey persisted = ApiKey.builder().id(9L).name("Acme prod").clientCode("ACME")
                .environment("live").keyPrefix("aaaa1111").active(true).build();
        when(apiKeyService.issue(eq("Acme prod"), eq("ACME"), eq("live"), any(), eq("admin")))
                .thenReturn(new ApiKeyService.IssuedKey(persisted, "msk_live_aaaa1111_zzz"));
        when(apiKeyService.maskedToken(persisted)).thenReturn("msk_live_aaaa1111_••••••");

        ResponseEntity<ApiResponse<ApiKeyResponse>> resp = controller.issue(req, admin);

        assertEquals(HttpStatus.CREATED, resp.getStatusCode());
        assertEquals("ACME", resp.getBody().getData().getClientCode());
    }

    // ===== list =====

    @Test
    void list_returnsMaskedTokens() {
        ApiKey k = ApiKey.builder().id(1L).name("k").clientCode("ACME")
                .environment("live").keyPrefix("aaaa").active(true).createdAt(LocalDateTime.now()).build();
        when(apiKeyRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(k));
        when(apiKeyService.maskedToken(k)).thenReturn("msk_live_aaaa_••••••");

        ResponseEntity<ApiResponse<List<ApiKeyResponse>>> resp = controller.list();

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(1, resp.getBody().getData().size());
        // Sprint 51 AC-M6 — token MUST be null on list (only shown on issue/rotate).
        assertEquals(null, resp.getBody().getData().get(0).getToken());
    }

    // ===== rotate =====

    @Test
    void rotate_unknownId_returns404() {
        when(apiKeyService.rotate(eq(42L), anyString())).thenReturn(Optional.empty());
        when(apiKeyRepository.findById(42L)).thenReturn(Optional.empty());

        ResponseEntity<ApiResponse<ApiKeyResponse>> resp = controller.rotate(42L, admin);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
        // Audit B5 — canonical missing-key code (was ORDER_NOT_FOUND copy-paste).
        assertEquals(ErrorCode.API_KEY_NOT_FOUND.name(), resp.getBody().getErrorCode());
    }

    @Test
    void rotate_revokedKey_returns409() {
        ApiKey revoked = ApiKey.builder().id(42L).active(false).build();
        when(apiKeyService.rotate(eq(42L), anyString())).thenReturn(Optional.empty());
        when(apiKeyRepository.findById(42L)).thenReturn(Optional.of(revoked));

        ResponseEntity<ApiResponse<ApiKeyResponse>> resp = controller.rotate(42L, admin);

        assertEquals(HttpStatus.CONFLICT, resp.getStatusCode());
        // Audit B5 — dedicated code so callers can differentiate revoked
        // from generic 409 validation errors.
        assertEquals(ErrorCode.API_KEY_ALREADY_REVOKED.name(), resp.getBody().getErrorCode());
    }

    @Test
    void rotate_ok_returns201WithNewToken() {
        ApiKey newKey = ApiKey.builder().id(43L).name("k (rotated)").clientCode("ACME")
                .environment("live").keyPrefix("bbbb").active(true).build();
        when(apiKeyService.rotate(eq(42L), anyString()))
                .thenReturn(Optional.of(new ApiKeyService.IssuedKey(newKey, "msk_live_bbbb_new")));
        when(apiKeyService.maskedToken(newKey)).thenReturn("msk_live_bbbb_••••••");

        ResponseEntity<ApiResponse<ApiKeyResponse>> resp = controller.rotate(42L, admin);

        assertEquals(HttpStatus.CREATED, resp.getStatusCode());
        assertEquals("msk_live_bbbb_new", resp.getBody().getData().getToken());
    }

    // ===== revoke =====

    @Test
    void revoke_unknownId_returns404() {
        when(apiKeyService.revoke(42L)).thenReturn(false);
        when(apiKeyRepository.findById(42L)).thenReturn(Optional.empty());

        ResponseEntity<ApiResponse<Void>> resp = controller.revoke(42L);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
        // Audit B5 — canonical missing-key code.
        assertEquals(ErrorCode.API_KEY_NOT_FOUND.name(), resp.getBody().getErrorCode());
    }

    @Test
    void revoke_alreadyRevoked_returns409() {
        ApiKey already = ApiKey.builder().id(42L).active(false).build();
        when(apiKeyService.revoke(42L)).thenReturn(false);
        when(apiKeyRepository.findById(42L)).thenReturn(Optional.of(already));

        ResponseEntity<ApiResponse<Void>> resp = controller.revoke(42L);

        assertEquals(HttpStatus.CONFLICT, resp.getStatusCode());
        // Audit B5 — dedicated already-revoked code.
        assertEquals(ErrorCode.API_KEY_ALREADY_REVOKED.name(), resp.getBody().getErrorCode());
    }

    @Test
    void revoke_ok_returns200() {
        when(apiKeyService.revoke(42L)).thenReturn(true);

        ResponseEntity<ApiResponse<Void>> resp = controller.revoke(42L);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(apiKeyService).revoke(42L);
    }
}
