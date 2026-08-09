package com.multiship.backend.service;

import com.multiship.backend.model.ApiKey;
import com.multiship.backend.repository.ApiKeyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Issues and authenticates API keys for external applications. The full token is
 * returned only once (at issue time); only a bcrypt hash of the secret is stored.
 *
 * Token: {@code msk_<env>_<prefix>_<secret>} — {@code prefix} is a public lookup id,
 * {@code secret} is the sensitive part that is hashed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApiKeyService {

    private static final SecureRandom RANDOM = new SecureRandom();
    // Sprint 50 Tier 0.5 PR B — vocabulary aligned with ApiKeyScope enum tokens.
    // pickups + landed-cost were reachable pre-B but their scopes weren't in the
    // default set; keys minted before PR B without explicit scopes won't have
    // them. Admin can rotate via PR C to widen.
    private static final String DEFAULT_SCOPES =
            "shipments rates tracking void addresses pickups landed-cost";

    /**
     * Sprint 50 Tier 0.5 PR C — default 90-day key lifetime. Configurable
     * per-issue when the admin needs a shorter (or longer) window.
     */
    private static final int DEFAULT_EXPIRY_DAYS = 90;

    /**
     * Sprint 50 Tier 0.5 PR C — how long a rotated key keeps authenticating
     * after rotation. Per RFC 8594 the response also carries {@code Deprecation}
     * + {@code Sunset} headers during this window so the integrator has a
     * loud, machine-readable cue to swap in the new token.
     */
    static final Duration ROTATION_GRACE = Duration.ofHours(24);

    private final ApiKeyRepository apiKeyRepository;
    private final PasswordEncoder passwordEncoder;

    /** The token to hand back to the caller, paired with the persisted record. */
    public record IssuedKey(ApiKey record, String plaintextToken) {}

    /**
     * Sprint 50 Tier 0.5 PR C — rich result so the filter can distinguish
     * expired vs rotated vs invalid and surface a specific 401 body.
     *
     * <p>{@link Kind#AUTHORIZED} → filter sets the principal (adds
     * {@code Deprecation}/{@code Sunset} headers when {@link #deprecated()}).
     * <br>{@link Kind#EXPIRED} → filter returns 401 with
     * {@code errorCode=API_KEY_EXPIRED}.
     * <br>{@link Kind#ROTATED_EXPIRED} → filter returns 401 with
     * {@code errorCode=API_KEY_ROTATED}.
     * <br>{@link Kind#INVALID} → filter leaves context unauthenticated;
     * downstream entry point emits the generic 401.
     */
    public record AuthResult(Kind kind, ApiKey key, boolean deprecated, LocalDateTime sunsetAt) {
        public enum Kind { AUTHORIZED, EXPIRED, ROTATED_EXPIRED, INVALID }

        public static AuthResult invalid() {
            return new AuthResult(Kind.INVALID, null, false, null);
        }
    }

    /** Create a new key for a client. The plaintext token is returned once and never again. */
    @Transactional
    public IssuedKey issue(String name, String clientCode, String environment, String scopes, String createdBy) {
        return issue(name, clientCode, environment, scopes, createdBy,
                LocalDateTime.now().plusDays(DEFAULT_EXPIRY_DAYS));
    }

    /**
     * Sprint 50 Tier 0.5 PR C — explicit-expiry overload. Admin passes
     * {@code null} for a never-expires key (reserved for platform-only
     * keys per policy).
     */
    @Transactional
    public IssuedKey issue(String name, String clientCode, String environment, String scopes,
                            String createdBy, LocalDateTime expiresAt) {
        String env = StringUtils.hasText(environment) ? environment.trim().toLowerCase() : "live";
        String prefix = randomHex(8);   // 16 hex chars — public lookup id
        String secret = randomHex(24);  // 48 hex chars — the sensitive secret
        String token = "msk_" + env + "_" + prefix + "_" + secret;

        ApiKey key = ApiKey.builder()
                .name(name)
                .clientCode(clientCode)
                .environment(env)
                .keyPrefix(prefix)
                .keyHash(passwordEncoder.encode(secret))
                .scopes(StringUtils.hasText(scopes) ? scopes.trim() : DEFAULT_SCOPES)
                .active(true)
                .createdBy(createdBy)
                .expiresAt(expiresAt)
                .build();
        apiKeyRepository.save(key);
        return new IssuedKey(key, token);
    }

    /**
     * Sprint 50 Tier 0.5 PR C — mints a fresh key inheriting the old key's
     * scopes/client/environment, records the parent id on the new row via
     * {@code rotated_from_id}, stamps {@code last_rotated_at} on the old key.
     * The old key stays {@code active=true} for {@link #ROTATION_GRACE};
     * during that window its requests still authenticate but carry
     * {@code Deprecation} + {@code Sunset} headers (RFC 8594) so the
     * integrator has a loud, machine-readable cue to swap tokens.
     *
     * <p>Returns the newly-issued key (plaintext token shown once).
     */
    @Transactional
    public Optional<IssuedKey> rotate(Long oldKeyId, String rotatedBy) {
        Optional<ApiKey> found = apiKeyRepository.findById(oldKeyId);
        if (found.isEmpty()) return Optional.empty();
        ApiKey old = found.get();
        if (!old.isActive()) return Optional.empty();  // already revoked

        // Mint fresh with same client/env/scopes; new 90-day expiry.
        IssuedKey issued = issue(old.getName() + " (rotated)", old.getClientCode(),
                old.getEnvironment(), old.getScopes(), rotatedBy);
        issued.record().setRotatedFromId(old.getId());
        apiKeyRepository.save(issued.record());

        // Old key: mark rotation event; keep active for the grace window.
        old.setLastRotatedAt(LocalDateTime.now());
        apiKeyRepository.save(old);

        return Optional.of(issued);
    }

    /**
     * Sprint 50 Tier 0.5 PR C — resolve + classify a raw token.
     *
     * <p>Returns:
     * <ul>
     *   <li>{@link AuthResult.Kind#AUTHORIZED} when the token is live, not
     *       expired, and (if rotated) still within the grace window.
     *       {@code deprecated=true} when the key has been rotated but is
     *       inside the {@link #ROTATION_GRACE} window.</li>
     *   <li>{@link AuthResult.Kind#EXPIRED} when {@code expiresAt} is set
     *       and now is past it.</li>
     *   <li>{@link AuthResult.Kind#ROTATED_EXPIRED} when the key was rotated
     *       and now is past {@code lastRotatedAt + ROTATION_GRACE}. Old key
     *       is also demoted to {@code active=false} here.</li>
     *   <li>{@link AuthResult.Kind#INVALID} for malformed / unknown /
     *       revoked / secret-mismatch tokens.</li>
     * </ul>
     */
    @Transactional
    public AuthResult authenticateDetailed(String rawToken) {
        if (!StringUtils.hasText(rawToken)) return AuthResult.invalid();
        String token = rawToken.trim();
        String[] parts = token.split("_", 4);
        if (parts.length != 4 || !"msk".equals(parts[0])) return AuthResult.invalid();
        String prefix = parts[2];
        String secret = parts[3];
        if (!StringUtils.hasText(prefix) || !StringUtils.hasText(secret)) return AuthResult.invalid();

        Optional<ApiKey> found = apiKeyRepository.findByKeyPrefixAndActiveTrue(prefix);
        if (found.isEmpty()) return AuthResult.invalid();
        ApiKey key = found.get();
        if (!passwordEncoder.matches(secret, key.getKeyHash())) return AuthResult.invalid();

        LocalDateTime now = LocalDateTime.now();

        // Hard expiry check (independent of rotation).
        if (key.getExpiresAt() != null && now.isAfter(key.getExpiresAt())) {
            return new AuthResult(AuthResult.Kind.EXPIRED, key, false, null);
        }

        // Rotation grace-window check.
        LocalDateTime sunset = null;
        boolean deprecated = false;
        if (key.getLastRotatedAt() != null) {
            sunset = key.getLastRotatedAt().plus(ROTATION_GRACE);
            if (now.isAfter(sunset)) {
                // Grace expired — demote to hard-revoked so future auths are cheap invalids.
                key.setActive(false);
                key.setRevokedAt(now);
                apiKeyRepository.save(key);
                return new AuthResult(AuthResult.Kind.ROTATED_EXPIRED, key, false, sunset);
            }
            deprecated = true;
        }

        key.setLastUsedAt(now);
        apiKeyRepository.save(key);
        return new AuthResult(AuthResult.Kind.AUTHORIZED, key, deprecated, sunset);
    }

    /**
     * @deprecated Sprint 50 Tier 0.5 PR C — prefer {@link #authenticateDetailed}
     * which distinguishes expired vs rotated vs invalid. Kept for OAuthController's
     * usable-or-empty semantics.
     */
    @Deprecated
    public Optional<ApiKey> authenticate(String rawToken) {
        AuthResult r = authenticateDetailed(rawToken);
        return r.kind() == AuthResult.Kind.AUTHORIZED ? Optional.of(r.key()) : Optional.empty();
    }

    /** Masked display form for lists/UI — never reveals the secret. */
    public String maskedToken(ApiKey key) {
        return "msk_" + key.getEnvironment() + "_" + key.getKeyPrefix() + "_" + "••••••";
    }

    @Transactional
    public boolean revoke(Long id) {
        return apiKeyRepository.findById(id).map(key -> {
            if (!key.isActive()) return false;
            key.setActive(false);
            key.setRevokedAt(LocalDateTime.now());
            apiKeyRepository.save(key);
            return true;
        }).orElse(false);
    }

    private static String randomHex(int bytes) {
        byte[] buf = new byte[bytes];
        RANDOM.nextBytes(buf);
        return HexFormat.of().formatHex(buf);
    }
}
