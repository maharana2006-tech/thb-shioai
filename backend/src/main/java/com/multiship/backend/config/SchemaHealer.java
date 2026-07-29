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

        // package_preset used to be uniquely keyed on name (single admin-owned
        // catalog). The current entity has no unique on name — presets are
        // scoped per (carrier, code, origin) for CARRIER kind and per owner
        // for CUSTOM kind, so multiple rows can legitimately share a name
        // (e.g. "FedEx Pak" from US and from CA). The old constraint makes
        // any resync throw duplicate-key on the first collision.
        dropConstraintIfExists("package_preset", "uq_package_preset_name");

        // client_shipvia_code_map / client_service_code_map /
        // client_package_code_map were uniquely keyed on (client_code,
        // erp_code). Aliases now scope by destination (country + region), so
        // the same ERP code can legitimately map to different targets per
        // destination. Drop the old constraints so Hibernate's schema update
        // can widen the keys via service-layer preflight — new rows insert
        // cleanly with dest_country / dest_region set.
        dropConstraintIfExists("client_shipvia_code_map", "uq_client_shipvia_code");
        dropConstraintIfExists("client_service_code_map", "uq_client_service_code");
        dropConstraintIfExists("client_package_code_map", "uq_client_package_code");
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
