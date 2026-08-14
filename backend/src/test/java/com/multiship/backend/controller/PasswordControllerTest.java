package com.multiship.backend.controller;

import com.multiship.backend.dto.MessageResponse;
import com.multiship.backend.dto.PasswordChangeRequest;
import com.multiship.backend.dto.PasswordForgotRequest;
import com.multiship.backend.dto.PasswordResetRequest;
import com.multiship.backend.service.PasswordService;
import com.multiship.backend.service.SignupRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sprint 51 BS-M4 — Controller-level unit tests for the password endpoints.
 * The wire-level @PreAuthorize gate on /change is validated by Spring at
 * runtime; this suite verifies the controller wiring around outcomes,
 * status codes, and the rate limiter's anti-enumeration hook on /forgot.
 */
class PasswordControllerTest {

    private PasswordService passwordService;
    private SignupRateLimiter rateLimiter;
    private PasswordController controller;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        passwordService = mock(PasswordService.class);
        rateLimiter = mock(SignupRateLimiter.class);
        controller = new PasswordController(passwordService, rateLimiter);
        request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("1.2.3.4");
    }

    /* -------- change -------- */

    @Test
    void change_ok_returns204() {
        Authentication auth = mock(Authentication.class);
        when(auth.getName()).thenReturn("alice");
        when(passwordService.change(anyString(), any(PasswordChangeRequest.class)))
                .thenReturn(PasswordService.ChangeOutcome.OK);

        PasswordChangeRequest req = new PasswordChangeRequest();
        req.setOldPassword("old-pw"); req.setNewPassword("new-pw-1234");
        ResponseEntity<?> resp = controller.change(req, auth);
        assertEquals(HttpStatus.NO_CONTENT, resp.getStatusCode());
    }

    @Test
    void change_wrongOldPassword_returns401() {
        Authentication auth = mock(Authentication.class);
        when(auth.getName()).thenReturn("alice");
        when(passwordService.change(anyString(), any(PasswordChangeRequest.class)))
                .thenReturn(PasswordService.ChangeOutcome.WRONG_OLD_PASSWORD);

        PasswordChangeRequest req = new PasswordChangeRequest();
        req.setOldPassword("bogus"); req.setNewPassword("new-pw-1234");
        ResponseEntity<?> resp = controller.change(req, auth);
        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
    }

    /* -------- forgot -------- */

    @Test
    void forgot_alwaysReturns202_evenWhenEmailUnknown() {
        // rate limit allows, service is called
        when(rateLimiter.isAllowed(anyString(), anyString())).thenReturn(true);
        PasswordForgotRequest req = new PasswordForgotRequest();
        req.setEmail("ghost@example.com");
        ResponseEntity<?> resp = controller.forgot(req, request);
        assertEquals(HttpStatus.ACCEPTED, resp.getStatusCode(),
                "forgot must be 202 regardless of email registration to prevent enumeration");
        verify(passwordService).forgot(req);
        verify(rateLimiter).record("ghost@example.com", "1.2.3.4", true);
    }

    @Test
    void forgot_rateLimited_stillReturns202AndSkipsService() {
        when(rateLimiter.isAllowed(anyString(), anyString())).thenReturn(false);
        PasswordForgotRequest req = new PasswordForgotRequest();
        req.setEmail("target@example.com");
        ResponseEntity<?> resp = controller.forgot(req, request);
        assertEquals(HttpStatus.ACCEPTED, resp.getStatusCode(),
                "rate-limited forgot must still return 202 — a differential 429 would leak enumeration");
        verify(passwordService, never()).forgot(req);
        verify(rateLimiter).record("target@example.com", "1.2.3.4", false);
    }

    /* -------- reset -------- */

    @Test
    void reset_ok_returns204() {
        when(rateLimiter.isAllowed(anyString(), anyString())).thenReturn(true);
        when(passwordService.reset(any(PasswordResetRequest.class)))
                .thenReturn(PasswordService.ChangeOutcome.OK);
        PasswordResetRequest req = new PasswordResetRequest();
        req.setToken("t"); req.setNewPassword("new-pw-1234");
        ResponseEntity<?> resp = controller.reset(req, request);
        assertEquals(HttpStatus.NO_CONTENT, resp.getStatusCode());
    }

    @Test
    void reset_tokenExpired_returns400() {
        when(rateLimiter.isAllowed(anyString(), anyString())).thenReturn(true);
        when(passwordService.reset(any(PasswordResetRequest.class)))
                .thenReturn(PasswordService.ChangeOutcome.TOKEN_EXPIRED);
        PasswordResetRequest req = new PasswordResetRequest();
        req.setToken("t"); req.setNewPassword("new-pw-1234");
        ResponseEntity<?> resp = controller.reset(req, request);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        MessageResponse body = (MessageResponse) resp.getBody();
        assertEquals("Reset token has expired.", body.getMessage());
    }

    @Test
    void reset_tokenReused_returns400TokenInvalid() {
        when(rateLimiter.isAllowed(anyString(), anyString())).thenReturn(true);
        when(passwordService.reset(any(PasswordResetRequest.class)))
                .thenReturn(PasswordService.ChangeOutcome.TOKEN_INVALID);
        PasswordResetRequest req = new PasswordResetRequest();
        req.setToken("used"); req.setNewPassword("new-pw-1234");
        ResponseEntity<?> resp = controller.reset(req, request);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    void reset_rateLimited_returns429() {
        when(rateLimiter.isAllowed(anyString(), anyString())).thenReturn(false);
        PasswordResetRequest req = new PasswordResetRequest();
        req.setToken("t"); req.setNewPassword("new-pw-1234");
        ResponseEntity<?> resp = controller.reset(req, request);
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, resp.getStatusCode());
        verify(passwordService, never()).reset(any());
    }

}
