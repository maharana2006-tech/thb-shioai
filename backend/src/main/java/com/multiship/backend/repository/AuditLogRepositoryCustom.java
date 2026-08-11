package com.multiship.backend.repository;

import com.multiship.backend.model.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;

/**
 * Sprint 50 Tier 0.5 PR H - custom fragment for {@link AuditLogRepository#search}
 * so we can inject {@code TenantScopeEnforcer} at the repository layer without
 * touching the controller signature. Platform operators see every row; a
 * scoped USER sees only rows whose {@code entityKey} matches their tenant or
 * whose {@code actor} matches their own username (their own writes).
 */
public interface AuditLogRepositoryCustom {

    Page<AuditLog> search(
            String actor,
            String entityType,
            String action,
            String entityKey,
            LocalDateTime since,
            LocalDateTime until,
            Pageable pageable);
}
