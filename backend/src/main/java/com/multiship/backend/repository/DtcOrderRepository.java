package com.multiship.backend.repository;

import com.multiship.backend.model.DtcOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * PostgreSQL DTC Order Repository.
 * Manages synced DTC orders from Oracle.
 */
@Repository
public interface DtcOrderRepository extends JpaRepository<DtcOrder, Long> {

    /**
     * Find a DTC order by batch ID (Oracle external ID).
     */
    Optional<DtcOrder> findByBatchId(Long batchId);

    /**
     * Find all DTC orders by tenant.
     */
    List<DtcOrder> findByTenantId(String tenantId);

    /**
     * Find DTC orders by order number.
     */
    List<DtcOrder> findByOrderNo(Integer orderNo);

    /**
     * Check if a DTC order already exists (idempotency check).
     */
    boolean existsByBatchId(Long batchId);

    /**
     * Count pending DTC orders for a tenant.
     */
    @Query("""
        SELECT COUNT(d) FROM DtcOrder d
        WHERE d.tenantId = :tenantId
    """)
    long countByTenantId(@Param("tenantId") String tenantId);
}
