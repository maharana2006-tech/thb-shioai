package com.multiship.backend.config;

import com.multiship.backend.model.CarrierAccountRef;
import com.multiship.backend.model.CarrierConfig;
import com.multiship.backend.repository.CarrierAccountRefRepository;
import com.multiship.backend.repository.CarrierConfigRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Sprint 49 Tier 1 — one-shot migration to encrypt existing plaintext
 * client_secret values on {@link CarrierConfig} and
 * {@link CarrierAccountRef}.
 *
 * <p>Detects unmigrated rows via a native query filtering for values that
 * don't carry the {@code enc:v1:} sentinel prefix. For each such row it
 * loads the entity (converter passes the plaintext through) and re-saves
 * it (converter prepends the prefix + AES-GCM ciphertext). Idempotent:
 * subsequent startups find zero rows and log a skip.
 *
 * <p>Guards: skips entirely when
 * {@link CryptoService#isAvailable()} is false so dev environments that
 * haven't set {@code SECRETS_ENCRYPTION_KEY} still boot without errors.
 */
@Slf4j
@Component
@Order(50)  // run before other CommandLineRunners that touch these tables
@RequiredArgsConstructor
public class ClientSecretEncryptionMigrator implements CommandLineRunner {

    private final CryptoService crypto;
    private final CarrierConfigRepository carrierConfigRepository;
    private final CarrierAccountRefRepository carrierAccountRefRepository;

    @PersistenceContext
    private EntityManager em;

    @Override
    public void run(String... args) {
        if (!crypto.isAvailable()) {
            log.warn("ClientSecretEncryptionMigrator: SECRETS_ENCRYPTION_KEY is unset; skipping migration.");
            return;
        }
        int carrierConfigCount = migrateCarrierConfig();
        int accountRefCount = migrateCarrierAccountRef();
        if (carrierConfigCount == 0 && accountRefCount == 0) {
            log.info("ClientSecretEncryptionMigrator: nothing to migrate — all client_secret values already encrypted.");
        } else {
            log.info("ClientSecretEncryptionMigrator: encrypted {} carrier_config + {} carrier_account_ref rows.",
                    carrierConfigCount, accountRefCount);
        }
    }

    @Transactional
    protected int migrateCarrierConfig() {
        List<Long> ids = em.createNativeQuery(
                        "SELECT id FROM carrier_config WHERE client_secret IS NOT NULL "
                                + "AND client_secret <> '' AND client_secret NOT LIKE 'enc:v1:%'")
                .getResultList().stream()
                .map(o -> ((Number) o).longValue())
                .toList();
        for (Long id : ids) {
            CarrierConfig row = carrierConfigRepository.findById(id).orElse(null);
            if (row == null) continue;
            carrierConfigRepository.save(row);  // converter encrypts on write
        }
        return ids.size();
    }

    @Transactional
    protected int migrateCarrierAccountRef() {
        List<Long> ids = em.createNativeQuery(
                        "SELECT id FROM carrier_account_ref WHERE client_secret IS NOT NULL "
                                + "AND client_secret <> '' AND client_secret NOT LIKE 'enc:v1:%'")
                .getResultList().stream()
                .map(o -> ((Number) o).longValue())
                .toList();
        for (Long id : ids) {
            CarrierAccountRef row = carrierAccountRefRepository.findById(id).orElse(null);
            if (row == null) continue;
            carrierAccountRefRepository.save(row);
        }
        return ids.size();
    }
}
