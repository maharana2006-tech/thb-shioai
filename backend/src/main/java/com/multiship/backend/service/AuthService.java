package com.multiship.backend.service;

import com.multiship.backend.dto.AcceptInviteRequest;
import com.multiship.backend.dto.MessageResponse;
import com.multiship.backend.dto.SignupRequest;
import com.multiship.backend.dto.LoginRequest; // Ensure this is imported
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;

public interface AuthService {
    ResponseEntity<MessageResponse> registerUser(SignupRequest signupRequest, String remoteIp);

    /**
     * @deprecated Sprint 51 T2 finding #6 — prefer {@link #loginUser(LoginRequest,
     * HttpServletResponse, String)} which passes the remote IP for the
     * brute-force limiter. Kept for existing tests + internal callers that
     * don't have a servlet request context.
     */
    @Deprecated
    ResponseEntity<?> loginUser(LoginRequest loginRequest, HttpServletResponse response);

    /**
     * Sprint 51 T2 finding #6 — {@code remoteIp} feeds the
     * {@link com.multiship.backend.service.ratelimit.AuthFailureLimiter}
     * so a scripted attacker gets locked out after N failed attempts on
     * the same {@code (ip, username)} pair. Sprint 50 PR Q1 cookie
     * semantics unchanged.
     */
    ResponseEntity<?> loginUser(LoginRequest loginRequest, HttpServletResponse response, String remoteIp);

    /**
     * Sprint 50 PR Q1 — logout clears the auth cookie on {@code response}
     * in addition to clearing the SecurityContext. {@code tokenHeader}
     * is now nullable; a cookie-only client can hit logout without an
     * Authorization header.
     */
    ResponseEntity<MessageResponse> logoutUser(String tokenHeader, HttpServletResponse response);

    /** Sprint 50 PR Q1 — SPA bootstrap after page refresh. Returns the
     *  current caller's username/role/clientCode or 401 if the cookie
     *  is missing/expired/invalid. */
    ResponseEntity<?> currentSession();

    /** Sprint 50 Tier 0.5 PR D — invitee posts token + credentials; server creates verified user. */
    ResponseEntity<MessageResponse> acceptInvite(AcceptInviteRequest request);

    /** Sprint 50 Tier 0.5 PR D — email-verification click-through. */
    ResponseEntity<MessageResponse> verifyEmail(String token);
}