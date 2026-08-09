package com.multiship.backend.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Sprint 48 B10 — creates and seeds the Postgres sequence that hands out
 * order numbers for manual shipments. Fixes the race where
 * {@code findMaxOrderNo() + 1} let two concurrent createManualShipment
 * calls compute the same order_no and collide on the PK.
 *
 * <p>Idempotent — runs on every startup:
 * <ul>
 *   <li>{@code CREATE SEQUENCE IF NOT EXISTS} — creates it on first deploy,
 *       no-op afterwards.</li>
 *   <li>{@code SETVAL(seq, GREATEST(MAX(order_no), 900000))} — pushes the
 *       counter above any existing rows so the next {@code nextval()}
 *       cannot collide with a legacy row.</li>
 * </ul>
 *
 * <p>Runs BEFORE {@link ShippingConfigSeeder} / {@link CarrierLimitSeeder}
 * via {@code @Order(0)} — those seeders don't depend on it, but keeping
 * infra init first is a good habit.
 */
@Slf4j
@Component
@Order(0)
@RequiredArgsConstructor
public class OrderNoSequenceInitializer implements CommandLineRunner {

    private static final String SEQ_NAME = "label_batch_order_no_seq";
    /** Manual orders start at 900001 to keep a floor above ERP-imported IDs. */
    private static final int MANUAL_FLOOR = 900000;

    private final JdbcTemplate jdbc;

    @Override
    public void run(String... args) {
        // Postgres-only. Idempotent CREATE, no error if it already exists.
        jdbc.execute("CREATE SEQUENCE IF NOT EXISTS " + SEQ_NAME
                + " AS INTEGER START WITH " + (MANUAL_FLOOR + 1)
                + " INCREMENT BY 1 NO MAXVALUE CACHE 1");

        // Seed the sequence above the current max so the first nextval()
        // after a legacy deploy cannot collide with an existing row.
        // GREATEST guards against an empty table (max is null) and against
        // a table whose max is below the floor.
        Integer max = jdbc.queryForObject(
                "SELECT GREATEST(COALESCE(MAX(order_no), 0), " + MANUAL_FLOOR + ") FROM label_batch",
                Integer.class);
        int seedTo = max != null ? max : MANUAL_FLOOR;
        jdbc.queryForObject("SELECT setval('" + SEQ_NAME + "', ?)", Long.class, seedTo);
        log.info("Order-no sequence '{}' initialised; seeded to {} (next order_no will be {}).",
                SEQ_NAME, seedTo, seedTo + 1);
    }
}
