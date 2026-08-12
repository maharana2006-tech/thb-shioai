package com.multiship.backend.integration;

import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.LoginRequest;
import com.multiship.backend.dto.MessageResponse;
import com.multiship.backend.model.User;
import com.multiship.backend.repository.UserRepository;
import com.multiship.backend.service.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Sprint 50 Tier 0.5 PR E — end-to-end guard on the deactivation
 * login block. Proves that an admin-revoked account:
 *
 * <ul>
 *   <li>is rejected at login with 403 + {@link ErrorCode#ACCOUNT_DEACTIVATED},</li>
 *   <li>gets the deactivation message BEFORE any email-verification
 *       message (PR D added the verified check; PR E ordering guarantees
 *       a deactivated-and-unverified account surfaces the correct reason).</li>
 * </ul>
 *
 * <p>Guarded by {@code INTEGRATION_TESTS=1} via {@link AbstractIntegrationTest}.
 */
class DeactivationLoginIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    @Transactional
    void reset() {
        userRepository.deleteAll();
    }

    @Test
    void deactivatedUserCannotLogIn() {
        userRepository.save(User.builder()
                .username("revoked")
                .email("revoked@local.test")
                .password(passwordEncoder.encode("secret"))
                .fullName("Revoked User")
                .role("USER")
                .emailVerified(true)
                .deactivatedAt(LocalDateTime.now())
                .deactivatedBy("admin")
                .build());

        LoginRequest req = new LoginRequest();
        req.setUsername("revoked");
        req.setPassword("secret");
        ResponseEntity<?> response = authService.loginUser(req, new org.springframework.mock.web.MockHttpServletResponse());

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        MessageResponse body = assertInstanceOf(MessageResponse.class, response.getBody());
        // MessageResponse.errorCode is a String (see the two-arg constructor
        // that stores ErrorCode.name()) — compare against .name() rather
        // than the enum itself.
        assertEquals(ErrorCode.ACCOUNT_DEACTIVATED.name(), body.getErrorCode());
    }

    @Test
    void deactivatedAndUnverifiedShowsDeactivationNotVerifyPrompt() {
        // Ordering guard: PR E's deactivation check runs before PR D's
        // email-verify check. A revoked account that never verified must
        // surface ACCOUNT_DEACTIVATED so the operator hears "your account
        // was revoked" rather than the misleading "verify your email."
        userRepository.save(User.builder()
                .username("unverified-revoked")
                .email("uv-revoked@local.test")
                .password(passwordEncoder.encode("secret"))
                .fullName("Unverified Revoked")
                .role("USER")
                .emailVerified(false)
                .deactivatedAt(LocalDateTime.now())
                .deactivatedBy("admin")
                .build());

        LoginRequest req = new LoginRequest();
        req.setUsername("unverified-revoked");
        req.setPassword("secret");
        ResponseEntity<?> response = authService.loginUser(req, new org.springframework.mock.web.MockHttpServletResponse());

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        MessageResponse body = assertInstanceOf(MessageResponse.class, response.getBody());
        assertEquals(ErrorCode.ACCOUNT_DEACTIVATED.name(), body.getErrorCode(),
                "Deactivation must win over EMAIL_NOT_VERIFIED to avoid misleading operators");
    }
}
