package com.multiship.backend.config;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * One-shot idempotent schema housekeeping. Hibernate's {@code
 * ddl-auto=update} adds new columns / tables / indexes but never drops
 * obsolete constraints — leaving behind stale keys from earlier
 * schema revisions that make new inserts fail with 23505.
 *
 * <p>Each rule here uses {@code DROP ... IF EXISTS} so re-runs are
 * safe. Runs before {@link ShippingConfigSeeder} so seed inserts see
 * the healed schema.
 */
@Component
@Order(0)
@RequiredArgsConstructor
public class SchemaHealer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaHealer.class);

    private final EntityManager em;

    @Override
    @Transactional
    public void run(String... args) {
        // shipping_service used to be uniquely keyed on (carrier, service_code).
        // The current entity is keyed on the lane (carrier, service_code,
        // origin_country) via uq_shipping_service_lane, so services can be
        // available FROM multiple origin countries. The old too-narrow
        // constraint makes any second-origin sync throw duplicate-key.
        dropConstraintIfExists("shipping_service", "uq_shipping_service_code");
    }

    private void dropConstraintIfExists(String table, String constraint) {
        try {
            int rows = em.createNativeQuery(
                    "ALTER TABLE " + table + " DROP CONSTRAINT IF EXISTS " + constraint)
                    .executeUpdate();
            log.info("SchemaHealer: dropped {}.{} (rc={}).", table, constraint, rows);
        } catch (Exception e) {
            // Never fatal — log and move on so an unrelated permissions issue
            // doesn't take the whole app down at startup.
            log.warn("SchemaHealer: could not drop {}.{}: {}", table, constraint, e.getMessage());
        }
    }
}
