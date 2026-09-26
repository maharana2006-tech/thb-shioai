package com.multiship.backend.service.externalsystems;

import com.multiship.backend.config.CryptoService;
import com.multiship.backend.model.ExternalSystemClientLoginOverride;
import com.multiship.backend.model.ExternalSystemConnection;
import com.multiship.backend.model.ExternalSystemSecret;
import com.multiship.backend.repository.ExternalSystemClientLoginOverrideRepository;
import com.multiship.backend.repository.ExternalSystemConnectionRepository;
import com.multiship.backend.repository.ExternalSystemSecretRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * CRUD + secret get/put for the external-systems framework. Wraps
 * three repositories + {@link CryptoService} so callers never touch
 * plaintext-plus-encrypted mixed together.
 *
 * <p>All secret reads decrypt in memory only; never logged.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExternalSystemConfigService {

    private final ExternalSystemConnectionRepository connectionRepo;
    private final ExternalSystemSecretRepository secretRepo;
    private final ExternalSystemClientLoginOverrideRepository overrideRepo;
    private final CryptoService crypto;

    // ─────────────────────────── connections ────────────────────────

    public List<ExternalSystemConnection> listConnections() {
        return connectionRepo.findAll();
    }

    public List<ExternalSystemConnection> listActive() {
        return connectionRepo.findByActiveTrue();
    }

    /**
     * V91 — env-aware name lookup. Returns the DEV row when the PROD row's
     * {@code use_dev} toggle is TRUE and a DEV row exists; otherwise returns
     * the PROD row. Falls back to PROD (with a WARN log) if DEV is requested
     * but not configured — a misconfigured toggle shouldn't nuke live prod.
     */
    public Optional<ExternalSystemConnection> findByName(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        java.util.List<ExternalSystemConnection> rows = connectionRepo.findAllByName(name);
        if (rows.isEmpty()) return Optional.empty();
        ExternalSystemConnection prod = rows.stream()
                .filter(r -> ExternalSystemConnection.ENV_PROD.equalsIgnoreCase(r.getEnvironment()))
                .findFirst().orElse(null);
        ExternalSystemConnection dev = rows.stream()
                .filter(r -> ExternalSystemConnection.ENV_DEV.equalsIgnoreCase(r.getEnvironment()))
                .findFirst().orElse(null);
        if (prod != null && Boolean.TRUE.equals(prod.getUseDev())) {
            if (dev != null) return Optional.of(dev);
            log.warn("external-system '{}': use_dev=true but no DEV row exists — falling back to PROD", name);
        }
        return prod != null ? Optional.of(prod) : Optional.ofNullable(dev);
    }

    /** V91 — env-scoped fetch for admin CRUD (never resolves via use_dev). */
    public Optional<ExternalSystemConnection> findByNameAndEnvironment(String name, String environment) {
        if (name == null || name.isBlank() || environment == null || environment.isBlank()) return Optional.empty();
        return connectionRepo.findByNameAndEnvironment(name.trim(), environment.trim().toUpperCase());
    }

    public Optional<ExternalSystemConnection> findById(Long id) {
        return connectionRepo.findById(id);
    }

    /**
     * S4 helper — the well-known "nds-default" seed row (V78 migration).
     * The FE highlights it on {@code /settings/external-systems};
     * legacy DTC-sync wiring looks it up by name at boot.
     */
    public Optional<ExternalSystemConnection> getDefaultNdsConnection() {
        return findByName("nds-default");
    }

    @Transactional
    public ExternalSystemConnection saveConnection(ExternalSystemConnection c, String actor) {
        if (c.getName() == null || c.getName().isBlank()) {
            throw new IllegalArgumentException("Connection name required.");
        }
        if (c.getSystemType() == null || c.getSystemType().isBlank()) {
            throw new IllegalArgumentException("system_type required.");
        }
        c.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        c.setUpdatedBy(actor);
        if (c.getConfigJson() == null || c.getConfigJson().isBlank()) {
            c.setConfigJson("{}");
        }
        ExternalSystemConnection saved = connectionRepo.save(c);
        log.info("external-system-connection: saved id={} name={} type={} actor={}",
                saved.getId(), saved.getName(), saved.getSystemType(), actor);
        return saved;
    }

    @Transactional
    public void deleteConnection(Long id) {
        connectionRepo.deleteById(id);
        log.info("external-system-connection: deleted id={}", id);
    }

    // ─────────────────────────── secrets ────────────────────────────

    /**
     * Secret value (decrypted if encryption is available, otherwise plain text).
     * Empty when the row doesn't exist. Never logs the plaintext.
     *
     * When SECRETS_ENCRYPTION_KEY is not set, secrets are stored/retrieved as plain text
     * for development convenience. This is NOT recommended for production.
     */
    public Optional<String> getSecret(Long connectionId, String secretKey) {
        if (connectionId == null || secretKey == null || secretKey.isBlank()) {
            return Optional.empty();
        }
        return secretRepo.findByConnectionIdAndSecretKey(connectionId, secretKey)
                .map(ExternalSystemSecret::getEncryptedValue)
                .map(value -> {
                    // If crypto is available, decrypt; otherwise treat as plain text
                    if (crypto.isAvailable()) {
                        return crypto.decrypt(value);
                    }
                    return value;  // Plain text fallback
                });
    }

    /**
     * Upsert a secret (encrypted if key is available, otherwise stored as plain text).
     * Passing null / blank throws — use {@link #deleteSecret} to remove.
     *
     * When SECRETS_ENCRYPTION_KEY is not set, secrets are stored as plain text
     * for development convenience. This is NOT recommended for production.
     */
    @Transactional
    public void putSecret(Long connectionId, String secretKey, String plaintext, String actor) {
        if (connectionId == null || secretKey == null || secretKey.isBlank()) {
            throw new IllegalArgumentException("connectionId + secretKey required.");
        }
        if (plaintext == null || plaintext.isEmpty()) {
            throw new IllegalArgumentException(
                    "Secret value required (use deleteSecret to remove).");
        }
        // Encrypt if key is available; otherwise store as plain text
        String storedValue = crypto.isAvailable() ? crypto.encrypt(plaintext) : plaintext;

        ExternalSystemSecret row = secretRepo.findByConnectionIdAndSecretKey(connectionId, secretKey)
                .orElseGet(() -> {
                    ExternalSystemSecret fresh = new ExternalSystemSecret();
                    fresh.setConnectionId(connectionId);
                    fresh.setSecretKey(secretKey);
                    return fresh;
                });
        row.setEncryptedValue(storedValue);
        row.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        row.setUpdatedBy(actor);
        secretRepo.save(row);
        log.info("external-system-secret: stored connectionId={} key={} actor={} encrypted={}",
                connectionId, secretKey, actor, crypto.isAvailable());
    }

    @Transactional
    public void deleteSecret(Long connectionId, String secretKey) {
        secretRepo.findByConnectionIdAndSecretKey(connectionId, secretKey)
                .ifPresent(row -> {
                    secretRepo.delete(row);
                    log.info("external-system-secret: deleted connectionId={} key={}",
                            connectionId, secretKey);
                });
    }

    // ─────────────────────── client-login overrides ─────────────────

    /**
     * Per-tenant credential override. Returns {username, password} when a row exists;
     * empty otherwise (caller falls back to the connector's default rule).
     *
     * Password is decrypted if encryption is available, otherwise treated as plain text.
     */
    public Optional<ConnectorSecretAccess.ClientLogin> getClientOverride(
            Long connectionId, String clientCode) {
        if (connectionId == null || clientCode == null || clientCode.isBlank()) {
            return Optional.empty();
        }
        return overrideRepo.findByConnectionIdAndClientCode(connectionId, clientCode)
                .map(o -> {
                    String password = crypto.isAvailable()
                            ? crypto.decrypt(o.getEncryptedPassword())
                            : o.getEncryptedPassword();  // Plain text fallback
                    return new ConnectorSecretAccess.ClientLogin(o.getUsername(), password);
                });
    }

    @Transactional
    public void putClientOverride(Long connectionId, String clientCode,
                                  String username, String plaintextPassword,
                                  String actor) {
        if (connectionId == null || clientCode == null || clientCode.isBlank()) {
            throw new IllegalArgumentException("connectionId + clientCode required.");
        }
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username required.");
        }
        if (plaintextPassword == null || plaintextPassword.isEmpty()) {
            throw new IllegalArgumentException(
                    "password required (use deleteClientOverride to remove).");
        }
        // Encrypt if key is available; otherwise store as plain text
        String storedPassword = crypto.isAvailable()
                ? crypto.encrypt(plaintextPassword)
                : plaintextPassword;

        ExternalSystemClientLoginOverride row = overrideRepo
                .findByConnectionIdAndClientCode(connectionId, clientCode)
                .orElseGet(() -> {
                    ExternalSystemClientLoginOverride fresh = new ExternalSystemClientLoginOverride();
                    fresh.setConnectionId(connectionId);
                    fresh.setClientCode(clientCode);
                    return fresh;
                });
        row.setUsername(username);
        row.setEncryptedPassword(storedPassword);
        row.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        row.setUpdatedBy(actor);
        overrideRepo.save(row);
        log.info("external-system-client-override: stored connectionId={} client={} actor={} encrypted={}",
                connectionId, clientCode, actor, crypto.isAvailable());
    }

    @Transactional
    public void deleteClientOverride(Long connectionId, String clientCode) {
        overrideRepo.findByConnectionIdAndClientCode(connectionId, clientCode)
                .ifPresent(row -> {
                    overrideRepo.delete(row);
                    log.info("external-system-client-override: deleted connectionId={} client={}",
                            connectionId, clientCode);
                });
    }

    public List<ExternalSystemClientLoginOverride> listClientOverrides(Long connectionId) {
        return overrideRepo.findByConnectionId(connectionId);
    }
}
