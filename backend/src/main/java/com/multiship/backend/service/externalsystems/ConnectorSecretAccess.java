package com.multiship.backend.service.externalsystems;

import java.util.Optional;

/**
 * Framework-managed helper handed to a connector's
 * {@link ExternalSystemConnector#connect} / {@code healthCheck} calls
 * so connectors read secrets without touching JPA / repositories
 * directly. Backed by {@code ExternalSystemConfigService} —
 * decryption via {@code CryptoService}.
 */
public interface ConnectorSecretAccess {

    /**
     * Decrypted secret for {@code (connection_id, secret_key)}. Empty
     * when no row exists. Connectors decide whether missing = fatal
     * (throw {@link ExternalSystemException}) or missing = optional.
     */
    Optional<String> getSecret(String secretKey);

    /**
     * Per-tenant login override: {@code (username, decrypted-password)}
     * when a row exists in {@code external_system_client_login_override}
     * for the given client code; empty otherwise. Connectors that
     * derive credentials from the client code (e.g. NDS) look here
     * first and fall back to the derived rule.
     */
    Optional<ClientLogin> getClientOverride(String clientCode);

    /** Value shape for {@link #getClientOverride}. */
    record ClientLogin(String username, String password) {}
}
