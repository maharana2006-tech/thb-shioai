package com.multiship.backend.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

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

    public String generateToken(String username, String role) {
        Date now = new Date();

        return Jwts.builder()
                .subject(username)
                .claim("role", role)
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
