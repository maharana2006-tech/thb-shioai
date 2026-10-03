package com.multiship.backend.repository;

import com.multiship.backend.model.AlertHistoryEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AlertHistoryRepository extends JpaRepository<AlertHistoryEntity, Long> {

    /** Filter is optional across all four fields. CAST guards the pgjdbc
     *  bytea-null bug (see feedback_hibernate_null_string_bytea). */
    @Query("SELECT a FROM AlertHistoryEntity a "
            + "WHERE (CAST(:source AS string) IS NULL OR a.source = :source) "
            + "AND (CAST(:tenantCode AS string) IS NULL OR a.tenantCode = :tenantCode) "
            + "AND (:orderNo IS NULL OR a.targetOrderNo = :orderNo)")
    Page<AlertHistoryEntity> search(
            @Param("source") String source,
            @Param("tenantCode") String tenantCode,
            @Param("orderNo") Long orderNo,
            Pageable pageable);
}
