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
    @Query("""
        SELECT a FROM AuditLog a
        WHERE (:actor = '' OR LOWER(a.actor) LIKE CONCAT('%', LOWER(:actor), '%'))
          AND (:entityType = '' OR a.entityType = :entityType)
          AND (:action = '' OR a.action = :action)
          AND (:entityKey = '' OR LOWER(a.entityKey) LIKE CONCAT('%', LOWER(:entityKey), '%'))
          AND (:since IS NULL OR a.createdAt >= :since)
          AND (:until IS NULL OR a.createdAt <= :until)
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
