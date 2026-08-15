package com.multiship.backend.controller;

import com.multiship.backend.dto.AcceptInviteRequest;
import com.multiship.backend.dto.LoginRequest;
import com.multiship.backend.dto.MessageResponse;
import com.multiship.backend.dto.SignupRequest;
import com.multiship.backend.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sprint 51 AC-M6 — smoke tests for AuthController (was 0-coverage).
 * The controller is a thin delegate to AuthService. Cover the delegation +
 * the private resolveClientIp XFF-vs-remote logic exercised via loginUser
 * (which is the only method that passes an IP down).
 */
class AuthControllerTest {

    private AuthService authService;
    private AuthController controller;
    private HttpServletRequest request;
    private HttpServletResponse response;

    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        controller = new AuthController();
        // AuthController uses field @Autowired — inject via reflection to
        // stay compatible with the constructor-less style.
        ReflectionTestUtils.setField(controller, "authService", authService);
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        when(request.getRemoteAddr()).thenReturn("10.0.0.5");
    }

    // ===== signup =====

    @Test
    void registerUser_delegatesToAuthServiceWithResolvedIp() {
        SignupRequest req = new SignupRequest();
        ResponseEntity<MessageResponse> expected = ResponseEntity.status(201)
                .body(new MessageResponse("ok"));
        when(authService.registerUser(eq(req), eq("10.0.0.5"))).thenReturn(
                (ResponseEntity) expected);

        ResponseEntity<?> actual = controller.registerUser(req, request);

        assertSame(expected, actual);
    }

    @Test
    void registerUser_preferrsXForwardedForFirstHop() {
        when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.9, 10.0.0.5");
        SignupRequest req = new SignupRequest();
        when(authService.registerUser(any(), eq("203.0.113.9")))
                .thenReturn((ResponseEntity) ResponseEntity.status(201).body(new MessageResponse("ok")));

        ResponseEntity<?> actual = controller.registerUser(req, request);

        assertEquals(HttpStatus.CREATED, actual.getStatusCode());
        verify(authService).registerUser(any(), eq("203.0.113.9"));
    }

    // ===== login =====

    @Test
    void authenticateUser_delegatesToLoginUserWithIp() {
        LoginRequest req = new LoginRequest();
        ResponseEntity<Object> expected = ResponseEntity.ok(new MessageResponse("ok"));
        when(authService.loginUser(eq(req), eq(response), eq("10.0.0.5"))).thenReturn(
                (ResponseEntity) expected);

        ResponseEntity<?> actual = controller.authenticateUser(req, request, response);

        assertSame(expected, actual);
    }

    // ===== session =====

    @Test
    void currentSession_delegatesToService() {
        ResponseEntity<Object> expected = ResponseEntity.ok().build();
        when(authService.currentSession()).thenReturn((ResponseEntity) expected);

        ResponseEntity<?> actual = controller.currentSession();

        assertSame(expected, actual);
    }

    // ===== logout =====

    @Test
    void logoutUser_passesHeaderThrough() {
        ResponseEntity<MessageResponse> expected = ResponseEntity.ok(new MessageResponse("bye"));
        when(authService.logoutUser(eq("Bearer x"), eq(response))).thenReturn(expected);

        ResponseEntity<MessageResponse> actual = controller.logoutUser("Bearer x", response);

        assertSame(expected, actual);
    }

    // ===== accept-invite =====

    @Test
    void acceptInvite_delegatesToService() {
        AcceptInviteRequest req = new AcceptInviteRequest();
        ResponseEntity<MessageResponse> expected = ResponseEntity.ok(new MessageResponse("welcome"));
        when(authService.acceptInvite(req)).thenReturn(expected);

        ResponseEntity<MessageResponse> actual = controller.acceptInvite(req);

        assertSame(expected, actual);
    }

    // ===== verify-email =====

    @Test
    void verifyEmail_delegatesToService() {
        ResponseEntity<MessageResponse> expected = ResponseEntity.ok(new MessageResponse("verified"));
        when(authService.verifyEmail("tok")).thenReturn(expected);

        ResponseEntity<MessageResponse> actual = controller.verifyEmail("tok");

        assertSame(expected, actual);
    }

    // ===== preview-invite (Sprint 51 User↔Client re-audit item #1) =====

    @Test
    void previewInvite_delegatesToService() {
        ResponseEntity<Object> expected = ResponseEntity.ok(new MessageResponse("preview"));
        when(authService.previewInvite("tok")).thenReturn((ResponseEntity) expected);

        ResponseEntity<?> actual = controller.previewInvite("tok");

        assertSame(expected, actual);
    }
}
