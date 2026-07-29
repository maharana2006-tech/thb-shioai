package com.multiship.backend.repository;

import com.multiship.backend.model.LabelTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface LabelTemplateRepository extends JpaRepository<LabelTemplate, Long> {

    /**
     * Fetch the template for a tenant + type. Platform-default rows
     * (tenantId=null) match via {@code IS NULL}; scoped rows match by
     * the exact tenant number.
     */
    @Query("""
        SELECT t FROM LabelTemplate t
        WHERE t.templateType = :templateType
          AND ((:tenantId IS NULL AND t.tenantId IS NULL)
               OR (:tenantId IS NOT NULL AND t.tenantId = :tenantId))
    """)
    Optional<LabelTemplate> findByTenantAndType(
            @Param("tenantId") String tenantId,
            @Param("templateType") String templateType);
}
