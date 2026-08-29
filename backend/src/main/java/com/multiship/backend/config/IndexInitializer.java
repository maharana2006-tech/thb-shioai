package com.multiship.backend.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Sprint 48 — creates the composite / functional / trigram indexes that
 * make the order-list, dashboard, and search queries scale from small dev
 * data to 50K orders/day × 30-day retention (~1.5M rows). Fixes the
 * "everything is a full table scan" audit finding on {@code label_batch}
 * and {@code order_label_tracking}.
 *
 * <p>Uses {@code CREATE INDEX CONCURRENTLY IF NOT EXISTS} so:
 * <ul>
 *   <li>First deploy on an existing production DB doesn't block writes
 *       during index build (deploys during business hours are safe).</li>
 *   <li>Restarts are no-ops (IF NOT EXISTS).</li>
 * </ul>
 *
 * <p>Because CONCURRENTLY cannot run inside a transaction, we bypass
 * Spring's default JdbcTemplate transaction wrapping and get a raw
 * {@link Connection} with {@code autoCommit(true)}.
 *
 * <p>{@code pg_trgm} extension enables trigram GIN indexes for
 * {@code LIKE '%keyword%'} queries (city/tracking/customer search).
 * The CREATE EXTENSION statement is best-effort — a limited-privilege
 * app user may not have {@code CREATE EXTENSION} privilege; in that
 * case we log a warning, skip the trigram indexes, and continue with
 * the standard B-tree indexes.
 *
 * <p>The static {@link #INDEX_STATEMENTS} list is package-visible so
 * unit tests can assert every expected index is scheduled to run.
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class IndexInitializer implements CommandLineRunner {

    /** One index-creation SQL statement + its name (for logging). Ordered
     *  by table then priority. Every statement uses IF NOT EXISTS so
     *  restarts skip already-created indexes. */
    public record IndexSpec(String name, String sql, boolean needsPgTrgm) {}

    static final List<IndexSpec> INDEX_STATEMENTS = List.of(
            // ---------- label_batch ----------
            new IndexSpec("idx_label_batch_tenant_created",
                    "CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_label_batch_tenant_created " +
                            "ON label_batch (UPPER(COALESCE(tenant_id, cust_no)), created_date DESC)",
                    false),
            new IndexSpec("idx_label_batch_created_date",
                    "CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_label_batch_created_date " +
                            "ON label_batch (created_date DESC)",
                    false),
            new IndexSpec("idx_label_batch_cust_no",
                    "CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_label_batch_cust_no " +
                            "ON label_batch (cust_no)",
                    false),
            new IndexSpec("idx_label_batch_shipvia_cd",
                    "CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_label_batch_shipvia_cd " +
                            "ON label_batch (shipvia_cd)",
                    false),
            // ---------- order_label_tracking ----------
            new IndexSpec("idx_order_label_tracking_order_no",
                    "CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_order_label_tracking_order_no " +
                            "ON order_label_tracking (order_no)",
                    false),
            new IndexSpec("idx_order_label_tracking_status",
                    "CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_order_label_tracking_status " +
                            "ON order_label_tracking (status)",
                    false),
            // ---------- pg_trgm GIN indexes (LIKE %keyword%) ----------
            new IndexSpec("idx_label_batch_shipto_city_trgm",
                    "CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_label_batch_shipto_city_trgm " +
                            "ON label_batch USING gin (LOWER(shipto_city) gin_trgm_ops)",
                    true),
            new IndexSpec("idx_label_batch_cust_no_trgm",
                    "CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_label_batch_cust_no_trgm " +
                            "ON label_batch USING gin (LOWER(cust_no) gin_trgm_ops)",
                    true),
            new IndexSpec("idx_order_label_tracking_tracking_number_trgm",
                    "CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_order_label_tracking_tracking_number_trgm " +
                            "ON order_label_tracking USING gin (LOWER(tracking_number) gin_trgm_ops)",
                    true)
    );

    private final DataSource dataSource;

    @Override
    public void run(String... args) {
        // CREATE EXTENSION is best-effort — needs superuser or the DB owner
        // to have CREATEDB privilege. Log a warning and skip trigram indexes
        // when the extension isn't available.
        boolean pgTrgmAvailable = ensurePgTrgm();

        int created = 0;
        int skipped = 0;
        int failed = 0;
        for (IndexSpec spec : INDEX_STATEMENTS) {
            if (spec.needsPgTrgm() && !pgTrgmAvailable) {
                skipped++;
                continue;
            }
            // Skip indexes that already exist (e.g. created by a schema
            // migration). CREATE INDEX CONCURRENTLY is rejected outright on a
            // partitioned table — regardless of IF NOT EXISTS — so a pre-check
            // avoids a failed statement and log noise for label_batch, which is
            // now LIST-partitioned by order_source.
            if (indexExists(spec.name())) {
                skipped++;
                continue;
            }
            try (Connection conn = dataSource.getConnection()) {
                // CONCURRENTLY cannot run inside a transaction — need autoCommit.
                boolean prevAutoCommit = conn.getAutoCommit();
                conn.setAutoCommit(true);
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute(spec.sql());
                    log.info("Index ensured: {}", spec.name());
                    created++;
                } finally {
                    conn.setAutoCommit(prevAutoCommit);
                }
            } catch (SQLException ex) {
                // Continue on failure — one bad index shouldn't crash startup.
                // Most common failure: an INVALID index from a prior interrupted
                // build (Postgres won't retry it; requires manual DROP INDEX).
                log.warn("Index {} failed: {} — startup continues. Fix with: DROP INDEX IF EXISTS {}; then restart.",
                        spec.name(), ex.getMessage(), spec.name());
                failed++;
            }
        }
        log.info("IndexInitializer: {} ensured, {} skipped (pg_trgm unavailable), {} failed.",
                created, skipped, failed);
    }

    /** True when an index (plain or partitioned) with this name already exists. */
    private boolean indexExists(String indexName) {
        try (Connection conn = dataSource.getConnection();
             java.sql.PreparedStatement ps = conn.prepareStatement(
                     "SELECT 1 FROM pg_class WHERE relname = ? AND relkind IN ('i', 'I')")) {
            ps.setString(1, indexName);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException ex) {
            // On probe failure, fall through and let the CREATE attempt decide.
            return false;
        }
    }

    /** Best-effort {@code CREATE EXTENSION IF NOT EXISTS pg_trgm}.
     *  Returns true when the extension is available (either was already
     *  installed or we successfully installed it), false otherwise. */
    private boolean ensurePgTrgm() {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm");
            log.info("pg_trgm extension is available for trigram GIN indexes.");
            return true;
        } catch (SQLException ex) {
            log.warn("pg_trgm extension unavailable (needs superuser or DB owner privilege): {}. " +
                    "Trigram GIN indexes will be skipped — %keyword% LIKE queries stay full-scan. " +
                    "To enable, run once manually as superuser: CREATE EXTENSION pg_trgm;", ex.getMessage());
            return false;
        }
    }
}
