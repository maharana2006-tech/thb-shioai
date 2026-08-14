package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.UserInviteRequest;
import com.multiship.backend.dto.UserInviteResponse;
import com.multiship.backend.model.UserInvite;
import com.multiship.backend.repository.ClientRepository;
import com.multiship.backend.service.UserInviteService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Sprint 51 AC-M6 — smoke tests for AdminUserInviteController (was 0-coverage).
 * The controller handles two guardrails BEFORE the service is called:
 * ROLE allowlist + client existence. Both branches are covered plus the
 * happy 201 + list read.
 */
class AdminUserInviteControllerTest {

    private UserInviteService inviteService;
    private ClientRepository clientRepository;
    private AdminUserInviteController controller;
    private UserDetails admin;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        inviteService = mock(UserInviteService.class);
        clientRepository = mock(ClientRepository.class);
        controller = new AdminUserInviteController(inviteService, clientRepository);
        admin = User.withUsername("admin").password("x").roles("ADMIN").build();
        request = mock(HttpServletRequest.class);
        when(request.getScheme()).thenReturn("http");
        when(request.getServerName()).thenReturn("localhost");
        when(request.getServerPort()).thenReturn(8080);
    }

    private static UserInviteRequest req(String email, String role, String cc) {
        UserInviteRequest r = new UserInviteRequest();
        r.setEmail(email);
        r.setRole(role);
        r.setClientCode(cc);
        return r;
    }

    // ===== mint =====

    @Test
    void mint_adminRole_rejected400WithVALIDATION_ERROR() {
        // ADMIN is deliberately excluded from INVITABLE_ROLES per doc.
        ResponseEntity<ApiResponse<UserInviteResponse>> resp =
                controller.mint(req("a@x.com", "ADMIN", "ACME"), admin, request);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals(ErrorCode.VALIDATION_ERROR.name(), resp.getBody().getErrorCode());
    }

    @Test
    void mint_unknownClient_returns404CLIENT_NOT_FOUND() {
        when(clientRepository.existsByClientCodeIgnoreCase("MISSING")).thenReturn(false);

        ResponseEntity<ApiResponse<UserInviteResponse>> resp =
                controller.mint(req("a@x.com", "USER", "MISSING"), admin, request);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
        assertEquals(ErrorCode.CLIENT_NOT_FOUND.name(), resp.getBody().getErrorCode());
    }

    @Test
    void mint_userRole_ok_returns201WithAcceptLink() {
        when(clientRepository.existsByClientCodeIgnoreCase("ACME")).thenReturn(true);
        UserInvite persisted = new UserInvite();
        persisted.setId(1L);
        persisted.setToken("tok-abc");
        persisted.setEmail("a@x.com");
        persisted.setClientCode("ACME");
        persisted.setRole("USER");
        persisted.setInvitedBy("admin");
        persisted.setCreatedAt(LocalDateTime.now());
        persisted.setExpiresAt(LocalDateTime.now().plusDays(7));
        when(inviteService.mint(eq("a@x.com"), eq("ACME"), eq("USER"), anyString(), anyString()))
                .thenReturn(persisted);

        ResponseEntity<ApiResponse<UserInviteResponse>> resp =
                controller.mint(req("a@x.com", "USER", "ACME"), admin, request);

        assertEquals(HttpStatus.CREATED, resp.getStatusCode());
        assertNotNull(resp.getBody().getData());
        assertEquals("tok-abc", resp.getBody().getData().getToken());
    }

    @Test
    void mint_tenantRole_alsoAllowed() {
        when(clientRepository.existsByClientCodeIgnoreCase("ACME")).thenReturn(true);
        UserInvite persisted = new UserInvite();
        persisted.setId(2L); persisted.setToken("tt"); persisted.setEmail("t@x.com");
        persisted.setClientCode("ACME"); persisted.setRole("TENANT");
        persisted.setInvitedBy("admin"); persisted.setCreatedAt(LocalDateTime.now());
        persisted.setExpiresAt(LocalDateTime.now().plusDays(7));
        when(inviteService.mint(any(), any(), eq("TENANT"), any(), any())).thenReturn(persisted);

        ResponseEntity<ApiResponse<UserInviteResponse>> resp =
                controller.mint(req("t@x.com", "tenant", "ACME"), admin, request);

        assertEquals(HttpStatus.CREATED, resp.getStatusCode());
    }

    // ===== list =====

    @Test
    void list_returns200WithAcceptLinks() {
        UserInvite i = new UserInvite();
        i.setId(1L); i.setToken("aaa"); i.setEmail("x@y.com");
        i.setClientCode("ACME"); i.setRole("USER"); i.setInvitedBy("admin");
        i.setCreatedAt(LocalDateTime.now()); i.setExpiresAt(LocalDateTime.now().plusDays(1));
        when(inviteService.listAll()).thenReturn(List.of(i));

        ResponseEntity<ApiResponse<List<UserInviteResponse>>> resp = controller.list(request);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(1, resp.getBody().getData().size());
        assertEquals("http://localhost:8080/invite/aaa", resp.getBody().getData().get(0).getAcceptLink());
    }
}
