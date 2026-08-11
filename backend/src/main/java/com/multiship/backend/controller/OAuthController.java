package com.multiship.backend.controller;

import com.multiship.backend.config.JwtService;
import com.multiship.backend.model.ApiKey;
import com.multiship.backend.service.ApiKeyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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

    @Operation(summary = "Issue an access token from an API key")
    @PostMapping(value = "/token",
            consumes = { MediaType.APPLICATION_FORM_URLENCODED_VALUE, MediaType.APPLICATION_JSON_VALUE })
    public ResponseEntity<?> token(
            @RequestParam(required = false) MultiValueMap<String, String> form,
            @RequestBody(required = false) TokenRequest body) {
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

        // Rebuild the msk_ token from the OAuth params and re-use the
        // existing verifier so authentication semantics stay identical.
        Optional<ApiKey> verified = tryEnvironments(clientId, clientSecret);
        if (verified.isEmpty()) {
            return err(HttpStatus.UNAUTHORIZED, "invalid_client",
                    "Client credentials did not authenticate");
        }
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
        for (String env : new String[]{"live", "test"}) {
            String synthesised = "msk_" + env + "_" + prefix + "_" + secret;
            Optional<ApiKey> maybe = apiKeyService.authenticate(synthesised);
            if (maybe.isPresent()) return maybe;
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
