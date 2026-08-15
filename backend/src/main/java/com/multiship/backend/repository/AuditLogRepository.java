package com.multiship.backend.repository;

import com.multiship.backend.model.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long>, AuditLogRepositoryCustom {

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
