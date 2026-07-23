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
    private static final String DEFAULT_SCOPES = "shipments rates tracking void addresses";

    private final ApiKeyRepository apiKeyRepository;
    private final PasswordEncoder passwordEncoder;

    /** The token to hand back to the caller, paired with the persisted record. */
    public record IssuedKey(ApiKey record, String plaintextToken) {}

    /** Create a new key for a client. The plaintext token is returned once and never again. */
    @Transactional
    public IssuedKey issue(String name, String clientCode, String environment, String scopes, String createdBy) {
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
                .build();
        apiKeyRepository.save(key);
        return new IssuedKey(key, token);
    }

    /**
     * Resolve a raw token to its active key, verifying the secret. Best-effort
     * updates {@code lastUsedAt}. Returns empty for any malformed/unknown/revoked
     * token or secret mismatch.
     */
    @Transactional
    public Optional<ApiKey> authenticate(String rawToken) {
        if (!StringUtils.hasText(rawToken)) return Optional.empty();
        String token = rawToken.trim();
        // Expect: msk_<env>_<prefix>_<secret>
        String[] parts = token.split("_", 4);
        if (parts.length != 4 || !"msk".equals(parts[0])) return Optional.empty();
        String prefix = parts[2];
        String secret = parts[3];
        if (!StringUtils.hasText(prefix) || !StringUtils.hasText(secret)) return Optional.empty();

        Optional<ApiKey> found = apiKeyRepository.findByKeyPrefixAndActiveTrue(prefix);
        if (found.isEmpty()) return Optional.empty();
        ApiKey key = found.get();
        if (!passwordEncoder.matches(secret, key.getKeyHash())) return Optional.empty();

        key.setLastUsedAt(LocalDateTime.now());
        apiKeyRepository.save(key);
        return Optional.of(key);
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
