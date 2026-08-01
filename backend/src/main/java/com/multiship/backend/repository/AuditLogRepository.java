package com.multiship.backend.repository;

import com.multiship.backend.model.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    /**
     * Paginated + filtered list for the settings UI. Empty-string
     * sentinels mean "no filter on this axis" — same pattern as
     * ClientRepository.search() to sidestep Postgres bytea-null-typing
     * on nullable String params.
     */
    /**
     * Timestamp bounds are always non-null — caller substitutes
     * LocalDateTime.MIN / MAX when the user leaves the filter blank.
     * Nullable LocalDateTime params bind as {@code bytea} under
     * Postgres + Hibernate and blow up with "cannot determine data
     * type of parameter" — same class of bug as
     * {@code LabelTemplateRepository.search}.
     */
    @Query("""
        SELECT a FROM AuditLog a
        WHERE (:actor = '' OR LOWER(COALESCE(a.actor, '')) LIKE CONCAT('%', LOWER(:actor), '%'))
          AND (:entityType = '' OR a.entityType = :entityType)
          AND (:action = '' OR a.action = :action)
          AND (:entityKey = '' OR LOWER(COALESCE(a.entityKey, '')) LIKE CONCAT('%', LOWER(:entityKey), '%'))
          AND a.createdAt >= :since
          AND a.createdAt <= :until
    """)
    Page<AuditLog> search(
            @Param("actor") String actor,
            @Param("entityType") String entityType,
            @Param("action") String action,
            @Param("entityKey") String entityKey,
            @Param("since") LocalDateTime since,
            @Param("until") LocalDateTime until,
            Pageable pageable);

    /**
     * Most-recent CASCADE_DISABLE entry for a given client — powers
     * auto-restore on re-enable so we only re-activate the rows this
     * cascade actually touched.
     */
    Optional<AuditLog> findFirstByEntityTypeAndEntityKeyAndActionOrderByCreatedAtDesc(
            String entityType, String entityKey, String action);
}
