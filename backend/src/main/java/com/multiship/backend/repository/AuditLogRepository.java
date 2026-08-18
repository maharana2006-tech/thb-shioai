package com.multiship.backend.repository;

import com.multiship.backend.model.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long>, AuditLogRepositoryCustom {

    /**
     * Audit R2 #356 — nightly retention: null the heavy {@code changes}
     * blob for rows created before {@code cutoff}. Metadata row (actor,
     * action, entity ref, timestamp) stays so auditors still see WHO did
     * WHAT WHEN — the before/after snapshot is what gets reclaimed.
     */
    @Modifying
    @Query("UPDATE AuditLog a SET a.changes = NULL "
            + "WHERE a.createdAt < :cutoff AND a.changes IS NOT NULL")
    int nullifyChangesOlderThan(@Param("cutoff") LocalDateTime cutoff);

    /**
     * Audit R2 #356 — nightly retention: hard-delete rows created before
     * {@code cutoff}. Default window is 7 years (SOX-ish) — enterprise
     * ops with tighter policy can override via {@code retention.audit-log.row-days}.
     */
    @Modifying
    @Query("DELETE FROM AuditLog a WHERE a.createdAt < :cutoff")
    int deleteRowsOlderThan(@Param("cutoff") LocalDateTime cutoff);

    /**
     * Most-recent CASCADE_DISABLE entry for a given client — powers
     * auto-restore on re-enable so we only re-activate the rows this
     * cascade actually touched.
     */
    Optional<AuditLog> findFirstByEntityTypeAndEntityKeyAndActionOrderByCreatedAtDesc(
            String entityType, String entityKey, String action);

    /**
     * Sprint 51 follow-up BS-M3 — direct tenant-scoped listing. Complements
     * the filtered {@link AuditLogRepositoryCustom#search} used by the list
     * endpoint; kept as a plain derived query so callers that only need a
     * raw per-tenant page (repository tests, ad-hoc reporting) don't have
     * to construct the six filter sentinels.
     */
    Page<AuditLog> findByClientCode(String clientCode, Pageable pageable);

    // search(...) — provided by {@link AuditLogRepositoryCustom} + its
    // fragment impl (AuditLogRepositoryCustomImpl). Split out so the
    // tenant-scope predicate can inject SecurityContext-derived filters
    // at query-build time without polluting every @Query-annotated method.
}
