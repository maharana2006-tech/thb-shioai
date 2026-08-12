package com.multiship.backend.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Sprint 49 Tier 1 — one-shot startup hook that delegates to
 * {@link ClientSecretEncryptionExecutor} to encrypt legacy plaintext
 * {@code client_secret} values on {@code carrier_config},
 * {@code carrier_account_ref}, and {@code users.carrier_client_secret}.
 *
 * <p>The actual migration logic lives in the executor bean so Spring's
 * transactional AOP proxy fires (self-invocation from {@code run()}
 * would bypass it) — this is required to co-locate the Postgres
 * advisory-lock acquisition with the migration writes on a single
 * connection. See {@link ClientSecretEncryptionExecutor} for details
 * and failure-mode discussion. (Sprint 50 PR M H4 fix — proper close
 * of the advisory-lock refactor deferred in PR #135.)
 *
 * <p><b>Historical note:</b> pre-H4 fix this class carried the migration
 * inline (three {@code migrateXxx} methods + {@code encryptPlaintextColumn}).
 * They now live on the executor so the {@code @Transactional} annotation
 * doesn't get bypassed by self-invocation.
 *
 * <p>Guards: skips entirely when {@link CryptoService#isAvailable()} is
 * false so dev environments without {@code SECRETS_ENCRYPTION_KEY}
 * still boot cleanly.
 */
@Slf4j
@Component
@Order(50)  // run before other CommandLineRunners that touch these tables
@RequiredArgsConstructor
public class ClientSecretEncryptionMigrator implements CommandLineRunner {

    private final CryptoService crypto;
    private final ClientSecretEncryptionExecutor executor;

    @Override
    public void run(String... args) {
        if (!crypto.isAvailable()) {
            log.warn("ClientSecretEncryptionMigrator: SECRETS_ENCRYPTION_KEY is unset; "
                    + "skipping migration.");
            return;
        }
        executor.migrateAllIfWinner();
    }
}
