package com.multiship.backend.controller;

import com.multiship.backend.config.JwtService;
import com.multiship.backend.model.ApiKey;
import com.multiship.backend.service.ApiKeyService;
import com.multiship.backend.service.ratelimit.AuthFailureLimiter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

/**
 * Sprint 46 — OAuth 2.0 client-credentials token endpoint.
 *
 * <p>Wraps the existing API-key book: an ApiKey is treated as an OAuth
 * client whose {@code client_id} is the token's public prefix (segment
 * 3 of {@code msk_<env>_<prefix>_<secret>}) and whose
 * {@code client_secret} is the secret segment. The returned access
 * token is a JWT issued by {@link JwtService} with {@code role=API}
 * and subject {@code apikey:<id>}, so downstream endpoints authenticate
 * via the existing {@code JwtAuthenticationFilter} without a code path
 * fork.
 *
 * <p>Two body shapes are supported (both RFC 6749 aligned):
 * <ul>
 *   <li>{@code application/x-www-form-urlencoded} — standard OAuth form</li>
 *   <li>{@code application/json} — friendlier for JSON-first callers</li>
 * </ul>
 */
@Tag(name = "OAuth", description = "OAuth 2.0 client-credentials token endpoint (Sprint 46)")
@RestController
@RequestMapping("/api/v1/oauth")
@RequiredArgsConstructor
public class OAuthController {

    private final ApiKeyService apiKeyService;
    private final JwtService jwtService;
    /** Sprint 51 T2 finding #6 — locks out repeated failed OAuth token
     *  attempts on the same (ip, client_id) pair. Same policy as
     *  /auth/login; audit called out that {@code invalid_client} vs
     *  {@code invalid_grant} otherwise makes secret-guessing trivial once
     *  the 8-char client_id prefix is public. */
    private final AuthFailureLimiter authFailureLimiter;

    @Operation(summary = "Issue an access token from an API key",
            description = "429 with error='rate_limited' when the (IP, client_id) pair has hit the "
                    + "auth-failure lockout (Sprint 51 T2). Otherwise 401 invalid_client on a bad secret.")
    @PostMapping(value = "/token",
            consumes = { MediaType.APPLICATION_FORM_URLENCODED_VALUE, MediaType.APPLICATION_JSON_VALUE })
    public ResponseEntity<?> token(
            @RequestParam(required = false) MultiValueMap<String, String> form,
            @RequestBody(required = false) TokenRequest body,
            HttpServletRequest request) {
        String grantType = extract(form, body, "grant_type", TokenRequest::getGrantType);
        String clientId = extract(form, body, "client_id", TokenRequest::getClientId);
        String clientSecret = extract(form, body, "client_secret", TokenRequest::getClientSecret);

        if (!"client_credentials".equals(grantType)) {
            return err(HttpStatus.BAD_REQUEST, "unsupported_grant_type",
                    "grant_type must be 'client_credentials'");
        }
        if (!StringUtils.hasText(clientId) || !StringUtils.hasText(clientSecret)) {
            return err(HttpStatus.BAD_REQUEST, "invalid_request",
                    "client_id and client_secret are required");
        }

        // Sprint 51 T2 finding #6 — brute-force check BEFORE the msk_
        // verifier. Rejecting a locked-out caller without running the
        // bcrypt/hash verifier keeps DDoS amplification off the table.
        String ip = resolveClientIp(request);
        if (authFailureLimiter.isLocked(ip, clientId)) {
            int retry = authFailureLimiter.retryAfterSeconds(ip, clientId);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", String.valueOf(retry))
                    .body(java.util.Map.of(
                            "error", "rate_limited",
                            "error_description", "Too many failed attempts; retry in "
                                    + Math.max(1, retry / 60) + " minute(s)."));
        }

        // Rebuild the msk_ token from the OAuth params and re-use the
        // existing verifier so authentication semantics stay identical.
        Optional<ApiKey> verified = tryEnvironments(clientId, clientSecret);
        if (verified.isEmpty()) {
            authFailureLimiter.recordFailure(ip, clientId);
            return err(HttpStatus.UNAUTHORIZED, "invalid_client",
                    "Client credentials did not authenticate");
        }
        authFailureLimiter.recordSuccess(ip, clientId);
        ApiKey key = verified.get();
        String subject = "apikey:" + key.getId();
        // Sprint 50 Tier 0.5 PR A — API-key tokens carry the key's clientCode
        // as a JWT claim so downstream tenant checks don't hit the DB per call.
        String jwt = jwtService.generateToken(subject, "API", key.getClientCode());

        return ResponseEntity.ok(TokenResponse.builder()
                .accessToken(jwt)
                .tokenType("Bearer")
                .expiresIn(3600L)   // matches JwtService default (informational)
                .scope(key.getScopes())
                .build());
    }

    private Optional<ApiKey> tryEnvironments(String prefix, String secret) {
        // The ApiKeyService verifier takes the full msk_ token; the environment
        // portion is stored on the key. Try 'live' first (most common) then
        // 'test' — verifier ignores unknown envs cleanly.
        // Uses authenticateDetailed (the deprecated `authenticate()` wrapper's
        // usable-or-empty semantics inlined here). Non-AUTHORIZED kinds
        // (EXPIRED / ROTATED_EXPIRED / INVALID) are all treated as "keep
        // trying the next env" for this OAuth token-exchange path.
        for (String env : new String[]{"live", "test"}) {
            String synthesised = "msk_" + env + "_" + prefix + "_" + secret;
            ApiKeyService.AuthResult result = apiKeyService.authenticateDetailed(synthesised);
            if (result.kind() == ApiKeyService.AuthResult.Kind.AUTHORIZED) {
                return Optional.of(result.key());
            }
        }
        return Optional.empty();
    }

    private static String extract(MultiValueMap<String, String> form, TokenRequest body,
                                   String formName, java.util.function.Function<TokenRequest, String> pick) {
        if (form != null) {
            String v = form.getFirst(formName);
            if (StringUtils.hasText(v)) return v;
        }
        return body != null ? pick.apply(body) : null;
    }

    private static ResponseEntity<Object> err(HttpStatus status, String code, String description) {
        return ResponseEntity.status(status).body(java.util.Map.of(
                "error", code,
                "error_description", description
        ));
    }

    /** Sprint 51 T2 finding #6 — mirrors AuthController.resolveClientIp.
     *  Prefers the first hop of X-Forwarded-For; falls back to the socket
     *  peer. Kept static since the controller isn't tenant-scoped. */
    private static String resolveClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        return request.getRemoteAddr();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TokenRequest {
        @com.fasterxml.jackson.annotation.JsonProperty("grant_type")
        private String grantType;
        @com.fasterxml.jackson.annotation.JsonProperty("client_id")
        private String clientId;
        @com.fasterxml.jackson.annotation.JsonProperty("client_secret")
        private String clientSecret;
        private String scope;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TokenResponse {
        @com.fasterxml.jackson.annotation.JsonProperty("access_token")
        private String accessToken;
        @com.fasterxml.jackson.annotation.JsonProperty("token_type")
        private String tokenType;
        @com.fasterxml.jackson.annotation.JsonProperty("expires_in")
        private Long expiresIn;
        private String scope;
    }
}
