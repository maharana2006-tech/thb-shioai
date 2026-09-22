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

    public Optional<ExternalSystemConnection> findByName(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        return connectionRepo.findByName(name);
    }

    public Optional<ExternalSystemConnection> findById(Long id) {
        return connectionRepo.findById(id);
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
     * Decrypted secret value. Empty when the row doesn't exist. Never
     * logs the plaintext.
     */
    public Optional<String> getSecret(Long connectionId, String secretKey) {
        if (connectionId == null || secretKey == null || secretKey.isBlank()) {
            return Optional.empty();
        }
        if (!crypto.isAvailable()) {
            // Refuse to lie: without the key we can't decrypt, so callers
            // must know their secret is currently unreadable rather than
            // getting a false "not configured" signal.
            throw new ExternalSystemException(
                    ExternalSystemException.Kind.SECRET_UNAVAILABLE,
                    /* connectionName */ null,
                    "Encryption key unavailable (SECRETS_ENCRYPTION_KEY unset). Cannot decrypt external-system secrets.");
        }
        return secretRepo.findByConnectionIdAndSecretKey(connectionId, secretKey)
                .map(ExternalSystemSecret::getEncryptedValue)
                .map(crypto::decrypt);
    }

    /**
     * Upsert an encrypted secret. Passing null / blank throws — use
     * {@link #deleteSecret} to remove.
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
        if (!crypto.isAvailable()) {
            throw new ExternalSystemException(
                    ExternalSystemException.Kind.SECRET_UNAVAILABLE,
                    /* connectionName */ null,
                    "Encryption key unavailable — cannot store external-system secrets.");
        }
        String encrypted = crypto.encrypt(plaintext);
        ExternalSystemSecret row = secretRepo.findByConnectionIdAndSecretKey(connectionId, secretKey)
                .orElseGet(() -> {
                    ExternalSystemSecret fresh = new ExternalSystemSecret();
                    fresh.setConnectionId(connectionId);
                    fresh.setSecretKey(secretKey);
                    return fresh;
                });
        row.setEncryptedValue(encrypted);
        row.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        row.setUpdatedBy(actor);
        secretRepo.save(row);
        log.info("external-system-secret: stored connectionId={} key={} actor={}",
                connectionId, secretKey, actor);
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
     * Per-tenant credential override. Returns decrypted {username,
     * password} when a row exists; empty otherwise (caller falls back
     * to the connector's default rule).
     */
    public Optional<ConnectorSecretAccess.ClientLogin> getClientOverride(
            Long connectionId, String clientCode) {
        if (connectionId == null || clientCode == null || clientCode.isBlank()) {
            return Optional.empty();
        }
        if (!crypto.isAvailable()) {
            throw new ExternalSystemException(
                    ExternalSystemException.Kind.SECRET_UNAVAILABLE,
                    /* connectionName */ null,
                    "Encryption key unavailable — cannot decrypt client-login overrides.");
        }
        return overrideRepo.findByConnectionIdAndClientCode(connectionId, clientCode)
                .map(o -> new ConnectorSecretAccess.ClientLogin(
                        o.getUsername(),
                        crypto.decrypt(o.getEncryptedPassword())));
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
        if (!crypto.isAvailable()) {
            throw new ExternalSystemException(
                    ExternalSystemException.Kind.SECRET_UNAVAILABLE,
                    /* connectionName */ null,
                    "Encryption key unavailable — cannot store client-login overrides.");
        }
        ExternalSystemClientLoginOverride row = overrideRepo
                .findByConnectionIdAndClientCode(connectionId, clientCode)
                .orElseGet(() -> {
                    ExternalSystemClientLoginOverride fresh = new ExternalSystemClientLoginOverride();
                    fresh.setConnectionId(connectionId);
                    fresh.setClientCode(clientCode);
                    return fresh;
                });
        row.setUsername(username);
        row.setEncryptedPassword(crypto.encrypt(plaintextPassword));
        row.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        row.setUpdatedBy(actor);
        overrideRepo.save(row);
        log.info("external-system-client-override: stored connectionId={} client={} actor={}",
                connectionId, clientCode, actor);
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
