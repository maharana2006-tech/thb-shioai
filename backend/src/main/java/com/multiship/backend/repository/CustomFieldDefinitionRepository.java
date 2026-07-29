package com.multiship.backend.repository;

import com.multiship.backend.model.CustomFieldDefinition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CustomFieldDefinitionRepository extends JpaRepository<CustomFieldDefinition, Long> {

    /**
     * Every definition applicable to a tenant: the tenant's own rows
     * PLUS platform-wide (tenantId=null) rows. Ordered by position so
     * the form renders in the intended sequence.
     */
    @Query("""
        SELECT d FROM CustomFieldDefinition d
        WHERE d.active = true
          AND ((:tenantId IS NULL AND d.tenantId IS NULL)
               OR (:tenantId IS NOT NULL
                   AND (d.tenantId = :tenantId OR d.tenantId IS NULL)))
        ORDER BY d.position ASC, d.id ASC
    """)
    List<CustomFieldDefinition> findApplicable(@Param("tenantId") String tenantId);

    /** All definitions for a tenant, active + inactive, for the settings page. */
    @Query("""
        SELECT d FROM CustomFieldDefinition d
        WHERE (:tenantId IS NULL AND d.tenantId IS NULL)
           OR (:tenantId IS NOT NULL AND d.tenantId = :tenantId)
        ORDER BY d.position ASC, d.id ASC
    """)
    List<CustomFieldDefinition> findAllForTenant(@Param("tenantId") String tenantId);

    @Query("""
        SELECT d FROM CustomFieldDefinition d
        WHERE d.fieldKey = :fieldKey
          AND ((:tenantId IS NULL AND d.tenantId IS NULL)
               OR (:tenantId IS NOT NULL AND d.tenantId = :tenantId))
    """)
    Optional<CustomFieldDefinition> findByTenantAndKey(
            @Param("tenantId") String tenantId,
            @Param("fieldKey") String fieldKey);
}
