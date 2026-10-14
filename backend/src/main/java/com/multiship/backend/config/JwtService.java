package com.multiship.backend.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

/**
 * Issues and validates HS256-signed JWTs. The token carries the username as
 * subject and the role as a claim, so the server never trusts a role sent by
 * the client — it only trusts what it signed itself.
 */
@Service
public class JwtService {

    private final SecretKey signingKey;
    private final long expirationMillis;

    /**
     * Old hardcoded default from application.properties. Fail startup if this
     * value is still in use — anyone with repo access could forge admin tokens.
     */
    static final String COMPROMISED_DEFAULT = "multishipSecretKeyForJWTTokenGeneration2026";

    public JwtService(
            @Value("${jwt.secret:}") String secret,
            @Value("${jwt.expiration}") long expirationMillis
    ) {
        validateSecret(secret);
        // HS256 requires a key of at least 256 bits (32 bytes).
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMillis = expirationMillis;
    }

    static void validateSecret(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "JWT_SECRET environment variable is required — no default is provided. "
                            + "Set to a random string of at least 32 bytes.");
        }
        if (secret.equals(COMPROMISED_DEFAULT)) {
            throw new IllegalStateException(
                    "JWT_SECRET is set to the compromised legacy default — rotate immediately.");
        }
        int byteLength = secret.getBytes(StandardCharsets.UTF_8).length;
        if (byteLength < 32) {
            throw new IllegalStateException(
                    "JWT_SECRET must be at least 32 bytes (256 bits) for HS256; got " + byteLength);
        }
    }

    /**
     * @deprecated Sprint 50 Tier 0.5 PR A — prefer
     * {@link #generateToken(String, String, String)} which carries the
     * caller's clientCode so downstream tenant-scope checks don't hit the
     * DB per request. Kept for backward-compat during rollout; existing
     * callers land in the null-clientCode overload below.
     */
    @Deprecated
    public String generateToken(String username, String role) {
        return generateToken(username, role, null, 0L);
    }

    /**
     * Sprint 50 Tier 0.5 PR A — three-arg overload retained for callers
     * that don't yet carry the user's {@code tokenVersion}. Delegates to
     * the four-arg variant with {@code tv=0}, matching the DB default —
     * a token issued this way stays valid until the user's {@code
     * token_version} row is bumped past 0, at which point revocation
     * kicks in (see {@link JwtAuthenticationFilter}).
     */
    public String generateToken(String username, String role, String clientCode) {
        return generateToken(username, role, clientCode, 0L);
    }

    /**
     * Sprint 51 T2 finding #5 — issues an HS256 JWT with:
     * <ul>
     *   <li>{@code tv}: the user's {@code token_version} at issue time.
     *       {@link JwtAuthenticationFilter} compares against the current
     *       DB value and rejects tokens whose tv &lt; DB.</li>
     *   <li>{@code jti}: a per-token UUID. Enables per-device logout
     *       (Redis blacklist) without a global {@code token_version}
     *       bump — the filter checks the jti set on every request.</li>
     * </ul>
     *
     * <p>Sprint 50 Tier 0.5 PR A — {@code clientCode} MAY be null for
     * legacy internal ADMIN + USER accounts (org-wide operator scope).
     */
    public String generateToken(String username, String role, String clientCode, long tokenVersion) {
        Date now = new Date();

        var builder = Jwts.builder()
                .subject(username)
                .id(UUID.randomUUID().toString())
                .claim("role", role)
                .claim("tv", tokenVersion);
        if (clientCode != null && !clientCode.isBlank()) {
            builder.claim("clientCode", clientCode);
        }
        return builder
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expirationMillis))
                .signWith(signingKey)
                .compact();
    }

    /**
     * Parses and verifies the token signature and expiry.
     *
     * @throws io.jsonwebtoken.JwtException if the token is invalid, tampered
     *                                      with, or expired.
     */
    public Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
