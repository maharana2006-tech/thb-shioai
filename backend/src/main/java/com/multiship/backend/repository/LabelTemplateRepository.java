package com.multiship.backend.repository;

import com.multiship.backend.model.LabelTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    /**
     * List templates filtered by (case-insensitive tenant contains),
     * template type, and logo presence. Any filter argument that is
     * null means "no filter on this axis". Sort + pagination via
     * {@code Pageable}. The list view is operator-only (see
     * {@code LabelTemplateController.list}), so no tenant scoping
     * happens here.
     */
    @Query("""
        SELECT t FROM LabelTemplate t
        WHERE (:search IS NULL OR LOWER(t.tenantId) LIKE LOWER(CONCAT('%', :search, '%')))
          AND (:templateType IS NULL OR t.templateType = :templateType)
          AND (:hasLogo IS NULL
               OR (:hasLogo = TRUE AND t.logoBase64 IS NOT NULL AND LENGTH(t.logoBase64) > 0)
               OR (:hasLogo = FALSE AND (t.logoBase64 IS NULL OR LENGTH(t.logoBase64) = 0)))
    """)
    Page<LabelTemplate> search(
            @Param("search") String search,
            @Param("templateType") String templateType,
            @Param("hasLogo") Boolean hasLogo,
            Pageable pageable);
}
