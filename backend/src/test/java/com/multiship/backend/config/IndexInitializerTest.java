package com.multiship.backend.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 48 — regression guard on the index catalog. Unit test asserts
 * every expected index is scheduled in {@code IndexInitializer.INDEX_STATEMENTS}
 * with the right shape:
 * <ul>
 *   <li>{@code CREATE INDEX CONCURRENTLY IF NOT EXISTS} — safe for prod
 *       deploys during business hours + idempotent on restart.</li>
 *   <li>Names follow {@code idx_<table>_<cols>[_trgm]} convention so
 *       ops can identify what the index covers from the name alone.</li>
 * </ul>
 *
 * <p>Runtime verification (needs a running Postgres): after the app
 * starts, {@code \d label_batch} in psql should show every non-pg_trgm
 * index. Trigram indexes only appear when the pg_trgm extension is
 * available in the target DB.
 */
class IndexInitializerTest {

    @Test
    void everyExpectedIndexIsScheduled() {
        Set<String> names = IndexInitializer.INDEX_STATEMENTS.stream()
                .map(IndexInitializer.IndexSpec::name)
                .collect(Collectors.toSet());

        // High-value indexes on the hot path (order-list query).
        assertTrue(names.contains("idx_label_batch_tenant_created"),
                "functional composite on (UPPER(COALESCE(tenant_id, cust_no)), created_date DESC)");
        assertTrue(names.contains("idx_order_label_tracking_order_no"),
                "JOIN key + findByOrderNo hot path");
        assertTrue(names.contains("idx_order_label_tracking_status"),
                "status filter on order-list queries");

        // Medium-value indexes.
        assertTrue(names.contains("idx_label_batch_created_date"),
                "date-range filter without tenant (dashboards)");
        assertTrue(names.contains("idx_label_batch_cust_no"),
                "customer filter + tenant fallback");
        assertTrue(names.contains("idx_label_batch_shipvia_cd"),
                "JOIN to ship_vias");

        // pg_trgm indexes for %keyword% LIKE queries.
        assertTrue(names.contains("idx_label_batch_shipto_city_trgm"),
                "trigram index for city LIKE search");
        assertTrue(names.contains("idx_label_batch_cust_no_trgm"),
                "trigram index for customer LIKE search");
        assertTrue(names.contains("idx_order_label_tracking_tracking_number_trgm"),
                "trigram index for tracking-number LIKE search");
    }

    @Test
    void everyStatementUsesConcurrentlyAndIfNotExists() {
        List<IndexInitializer.IndexSpec> specs = IndexInitializer.INDEX_STATEMENTS;
        for (IndexInitializer.IndexSpec spec : specs) {
            String sql = spec.sql().toUpperCase();
            // Plain indexes: CREATE INDEX CONCURRENTLY. Unique constraints
            // (uk_-named) use CREATE UNIQUE INDEX CONCURRENTLY — same lock
            // guarantees, extra uniqueness.
            assertTrue(sql.contains("CREATE INDEX CONCURRENTLY")
                            || sql.contains("CREATE UNIQUE INDEX CONCURRENTLY"),
                    "MUST use CONCURRENTLY to avoid table locks in prod: " + spec.name());
            assertTrue(sql.contains("IF NOT EXISTS"),
                    "MUST use IF NOT EXISTS for idempotency across restarts: " + spec.name());
        }
    }

    @Test
    void indexNamesFollowConvention() {
        // Convention: idx_<table>_<cols>[_trgm] for plain indexes,
        // uk_<table>_<cols> for unique constraints. Guards ops's ability to
        // reason about what an index covers from its name.
        for (IndexInitializer.IndexSpec spec : IndexInitializer.INDEX_STATEMENTS) {
            boolean unique = spec.sql().toUpperCase().contains("CREATE UNIQUE INDEX");
            assertTrue(unique ? spec.name().startsWith("uk_") : spec.name().startsWith("idx_"),
                    "index name must start with idx_ (or uk_ for unique): " + spec.name());
            if (spec.needsPgTrgm()) {
                assertTrue(spec.name().endsWith("_trgm"),
                        "trigram indexes should end with _trgm: " + spec.name());
            } else {
                assertFalse(spec.name().endsWith("_trgm"),
                        "non-trigram indexes should NOT end with _trgm: " + spec.name());
            }
        }
    }

    @Test
    void trigramIndexesAreFlaggedAsNeedingPgTrgm() {
        // Runtime: when pg_trgm can't be installed (limited-privilege user),
        // trigram indexes are skipped. This flag drives that skip; assert it
        // matches the SQL body's use of USING gin ... gin_trgm_ops.
        for (IndexInitializer.IndexSpec spec : IndexInitializer.INDEX_STATEMENTS) {
            boolean sqlUsesTrgm = spec.sql().contains("gin_trgm_ops");
            assertEquals(sqlUsesTrgm, spec.needsPgTrgm(),
                    "needsPgTrgm flag must match SQL use of gin_trgm_ops: " + spec.name());
        }
    }
}
