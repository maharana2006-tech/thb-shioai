package com.multiship.backend.repository.oracle;

import com.multiship.backend.model.oracle.OracleDtcOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Oracle DTC Order Repository.
 * Queries the TB_SHIPX_DTC_UVW view from Oracle NDS.
 *
 * This is READ-ONLY — no modifications to Oracle.
 */
@Repository
public interface OracleDtcOrderRepository extends JpaRepository<OracleDtcOrder, Long> {

    /**
     * Fetch all pending DTC orders from Oracle.
     * Filters:
     *   - BATCH_ID != 0
     *   - TOTE_NUMBER != 0
     *   - ORDER_SUFFIX = '0'
     *
     * Command timeout: 300 seconds (5 min)
     *
     * @param tenantId the tenant/client code
     * @return list of pending DTC orders
     */
    @Query("""
        SELECT o FROM OracleDtcOrder o
        WHERE o.batchId != 0
          AND o.toteNumber != 0
          AND COALESCE(o.orderSuffix, 0) = 0
          AND (:tenantId = '' OR o.tenantId = :tenantId)
        ORDER BY o.batchId DESC
    """)
    List<OracleDtcOrder> findPendingDtcOrders(@Param("tenantId") String tenantId);

    /**
     * Fetch all pending DTC orders for all tenants.
     */
    @Query("""
        SELECT o FROM OracleDtcOrder o
        WHERE o.batchId != 0
          AND o.toteNumber != 0
          AND COALESCE(o.orderSuffix, 0) = 0
        ORDER BY o.batchId DESC
    """)
    List<OracleDtcOrder> findAllPendingDtcOrders();

    /**
     * Fetch pending orders by tenant.
     */
    @Query("""
        SELECT o FROM OracleDtcOrder o
        WHERE o.tenantId = :tenantId
          AND o.batchId != 0
          AND o.toteNumber != 0
          AND COALESCE(o.orderSuffix, 0) = 0
        ORDER BY o.batchId DESC
    """)
    List<OracleDtcOrder> findPendingOrdersByTenant(@Param("tenantId") String tenantId);

    /**
     * Fetch pending orders by batch ID.
     */
    @Query("""
        SELECT o FROM OracleDtcOrder o
        WHERE o.batchId = :batchId
          AND o.toteNumber != 0
        ORDER BY o.toteNumber
    """)
    List<OracleDtcOrder> findByBatchId(@Param("batchId") Long batchId);

    /**
     * Fetch specific order by order number.
     */
    @Query("""
        SELECT o FROM OracleDtcOrder o
        WHERE o.orderNo = :orderNo
          AND o.batchId != 0
    """)
    List<OracleDtcOrder> findByOrderNo(@Param("orderNo") Integer orderNo);

    /**
     * Count pending DTC orders for a tenant.
     */
    @Query("""
        SELECT COUNT(o) FROM OracleDtcOrder o
        WHERE o.tenantId = :tenantId
          AND o.batchId != 0
          AND o.toteNumber != 0
          AND COALESCE(o.orderSuffix, 0) = 0
    """)
    long countPendingByTenant(@Param("tenantId") String tenantId);
}
