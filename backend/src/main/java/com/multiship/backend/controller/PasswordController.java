package com.multiship.backend.controller;

import com.multiship.backend.dto.MessageResponse;
import com.multiship.backend.dto.PasswordChangeRequest;
import com.multiship.backend.dto.PasswordForgotRequest;
import com.multiship.backend.dto.PasswordResetRequest;
import com.multiship.backend.service.PasswordService;
import com.multiship.backend.service.SignupRateLimiter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sprint 51 BS-M4 — password change / forgot / reset endpoints.
 *
 * <p>Mounted under {@code /api/v1/auth} which is {@code permitAll} at the
 * Spring Security layer; the individual method annotations (or lack of)
 * are the actual gate:
 * <ul>
 *   <li>{@code /change} — {@code @PreAuthorize("isAuthenticated()")} so an
 *       unauthenticated caller gets 401/403, not a leaked control flow.</li>
 *   <li>{@code /forgot} — public. Rate-limited via {@link SignupRateLimiter}
 *       (same email+IP buckets used by signup). Always returns 202 so an
 *       attacker cannot enumerate registered emails via response codes.</li>
 *   <li>{@code /reset} — public. Consumes the one-shot token from /forgot.
 *       Rate-limited via the same limiter to slow token brute-force.</li>
 * </ul>
 */
@Slf4j
@Tag(name = "Password", description = "Change / forgot / reset — password lifecycle for authenticated + unauthenticated flows")
@RestController
@RequestMapping("/api/v1/auth/password")
@RequiredArgsConstructor
public class PasswordController {

    private final PasswordService passwordService;
    private final SignupRateLimiter rateLimiter;

    @Operation(summary = "Change password (authenticated)",
            description = "Requires a valid session cookie. Verifies the old password via bcrypt "
                    + "then updates the hash and bumps token_version so every outstanding JWT for "
                    + "the caller is invalidated (they will need to sign in again on other devices).")
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/change")
    public ResponseEntity<?> change(@Valid @RequestBody PasswordChangeRequest req,
                                     Authentication auth) {
        PasswordService.ChangeOutcome outcome = passwordService.change(auth.getName(), req);
        return switch (outcome) {
            case OK -> ResponseEntity.noContent().build();
            case WRONG_OLD_PASSWORD -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new MessageResponse("Current password is incorrect."));
            case USER_NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new MessageResponse("User no longer exists."));
            default -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new MessageResponse("Password change failed."));
        };
    }

    @Operation(summary = "Request password reset email (public)",
            description = "Always returns 202 regardless of whether the email is registered — "
                    + "this prevents attackers from enumerating users by response code. Rate-limited "
                    + "by email + IP via the same limiter used by signup.")
    @SecurityRequirements
    @PostMapping("/forgot")
    public ResponseEntity<?> forgot(@Valid @RequestBody PasswordForgotRequest req,
                                     HttpServletRequest request) {
        String ip = resolveClientIp(request);
        String email = req.getEmail() == null ? "" : req.getEmail().trim();
        if (!rateLimiter.isAllowed(email, ip)) {
            // Still return 202 to avoid leaking "email is registered vs not"
            // via a differential 429 vs 202. Record + log so ops sees pressure.
            rateLimiter.record(email, ip, false);
            log.warn("Password forgot rate-limited: email={} ip={}", email, ip);
            return ResponseEntity.accepted().build();
        }
        rateLimiter.record(email, ip, true);
        passwordService.forgot(req);
        return ResponseEntity.accepted().build();
    }

    @Operation(summary = "Consume password reset token (public)",
            description = "Single-use: the token row is deleted on success. Bumps token_version so "
                    + "every outstanding JWT for the owner is invalidated. Rate-limited by "
                    + "email+IP to slow token brute-force.")
    @SecurityRequirements
    @PostMapping("/reset")
    public ResponseEntity<?> reset(@Valid @RequestBody PasswordResetRequest req,
                                    HttpServletRequest request) {
        String ip = resolveClientIp(request);
        // The forgot flow only knows an email, not a token, so we key the
        // reset limiter on IP alone (via a synthetic email sentinel). This
        // slows password-guessing an unknown token from one IP without
        // requiring the caller to also send an email.
        if (!rateLimiter.isAllowed("password-reset", ip)) {
            log.warn("Password reset rate-limited from ip={}", ip);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(new MessageResponse("Too many attempts. Try again later."));
        }
        rateLimiter.record("password-reset", ip, true);

        PasswordService.ChangeOutcome outcome = passwordService.reset(req);
        return switch (outcome) {
            case OK -> ResponseEntity.noContent().build();
            case TOKEN_INVALID -> ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new MessageResponse("Invalid or already-used reset token."));
            case TOKEN_EXPIRED -> ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new MessageResponse("Reset token has expired."));
            case USER_NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new MessageResponse("User no longer exists."));
            default -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new MessageResponse("Password reset failed."));
        };
    }

    /** Best-effort client IP for the rate limiter — same helper as AuthController. */
    private static String resolveClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        return request.getRemoteAddr();
    }
}
