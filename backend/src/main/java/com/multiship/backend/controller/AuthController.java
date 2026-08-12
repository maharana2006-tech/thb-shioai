package com.multiship.backend.controller;

import com.multiship.backend.dto.AcceptInviteRequest;
import com.multiship.backend.dto.LoginRequest;
import com.multiship.backend.dto.MessageResponse;
import com.multiship.backend.dto.SignupRequest;
import com.multiship.backend.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

// Sprint 50 PR N post-audit finding #18 — class-level @CrossOrigin removed.
// Sprint 49 Tier 1 removed @CrossOrigin from every other controller and
// centralised CORS in SecurityConfig.corsConfigurationSource(). AuthController
// was the last hold-out; the annotation silently overrode the global config
// on /auth/**, so a future edit to global CORS (adding a header, tightening
// an origin) wouldn't have applied here. Global config now governs.
@Tag(name = "Auth", description = "Login, signup, logout, invite accept, email verify — the only endpoints that work without a token")
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    @Autowired
    private AuthService authService; // Pure business layer abstraction contract

    /**
     * Sprint 50 Tier 0.5 PR D — public signup gated behind
     * {@code signup.public-enabled}; requires clientCode; rate-limited by
     * email + IP; email-verification required before login.
     */
    @Operation(summary = "Create an account (public signup — may be disabled)",
            description = "Public signup requires signup.public-enabled=true. Payload must include clientCode. "
                    + "403 errorCode SIGNUP_DISABLED when disabled. 429 SIGNUP_RATE_LIMITED when the "
                    + "email or IP window is exhausted. 422 CLIENT_CODE_REQUIRED when missing. "
                    + "409 USERNAME_TAKEN / EMAIL_TAKEN on duplicates.")
    @SecurityRequirements
    @PostMapping("/signup")
    public ResponseEntity<?> registerUser(@Valid @RequestBody SignupRequest signupRequest,
                                           HttpServletRequest request) {
        return authService.registerUser(signupRequest, resolveClientIp(request));
    }

    @Operation(summary = "Log in",
            description = "Returns {token, username, role}. Sprint 50 PR Q1: ALSO sets the JWT as an "
                    + "httpOnly cookie so a subsequent SPA session can bootstrap via /session without JS "
                    + "ever seeing the token. The body `token` field remains populated for one deploy cycle "
                    + "so unmigrated FE builds keep working. The JWT carries the role claim and expires in 24h. "
                    + "401 INVALID_CREDENTIALS on bad credentials. "
                    + "403 EMAIL_NOT_VERIFIED for a public-signup user who has not consumed the verification link.")
    @SecurityRequirements
    @PostMapping("/login")
    public ResponseEntity<?> authenticateUser(@Valid @RequestBody LoginRequest loginRequest,
                                              HttpServletResponse response) {
        return authService.loginUser(loginRequest, response);
    }

    /** Sprint 50 PR Q1 — SPA bootstrap after page refresh. Returns
     *  {username, role, clientCode} from the SecurityContext populated
     *  by the httpOnly cookie (or Authorization header for legacy
     *  callers). 401 if the cookie is missing/expired/invalid. */
    @Operation(summary = "Current session (bootstrap after page refresh)")
    @GetMapping("/session")
    public ResponseEntity<?> currentSession() {
        return authService.currentSession();
    }

    @PostMapping("/logout")
    public ResponseEntity<MessageResponse> logoutUser(
            @RequestHeader(value = "Authorization", required = false) String tokenHeader,
            HttpServletResponse response) {
        return authService.logoutUser(tokenHeader, response);
    }

    /**
     * Sprint 50 Tier 0.5 PR D — invitee posts token + username + password.
     * Server creates the User with the invite's pre-assigned clientCode +
     * role and marks the account email-verified (accepting the invite IS
     * the verification).
     */
    @Operation(summary = "Accept an invite",
            description = "Creates a User pre-scoped to the invite's clientCode + role. "
                    + "404 INVITE_NOT_FOUND / 400 INVITE_EXPIRED / 409 INVITE_ALREADY_USED / "
                    + "409 USERNAME_TAKEN / EMAIL_TAKEN.")
    @SecurityRequirements
    @PostMapping("/accept-invite")
    public ResponseEntity<MessageResponse> acceptInvite(@Valid @RequestBody AcceptInviteRequest request) {
        return authService.acceptInvite(request);
    }

    /**
     * Sprint 50 Tier 0.5 PR D — email verification click-through. Called
     * by the frontend when the user opens the verify link.
     */
    @Operation(summary = "Verify email address")
    @SecurityRequirements
    @PostMapping("/verify-email")
    public ResponseEntity<MessageResponse> verifyEmail(@RequestParam("token") String token) {
        return authService.verifyEmail(token);
    }

    /**
     * Sprint 50 Tier 0.5 PR D — best-effort client IP for the rate limiter.
     * Prefers the standard X-Forwarded-For chain's first hop (the client);
     * falls back to the direct socket peer.
     */
    private static String resolveClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        return request.getRemoteAddr();
    }
}
