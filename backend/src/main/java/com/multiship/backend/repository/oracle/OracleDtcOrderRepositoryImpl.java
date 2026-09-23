package com.multiship.backend.repository.oracle;

import com.multiship.backend.config.OracleDtcConfig;
import com.multiship.backend.model.oracle.OracleDtcOrder;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Custom repository for Oracle DTC orders with dynamic view name.
 *
 * Queries the view configured in application.properties:
 *   - Development: TB_SHIPX_DTC_UVW_TEST (from application.properties)
 *   - Production: TB_SHIPX_DTC_UVW (from application-prod.properties)
 *
 * Activated with: --spring.profiles.active=prod for production
 */
@Slf4j
@Repository
@RequiredArgsConstructor
// Only exists alongside the Oracle EMF: with the sync off (the default) there is no
// "oracleEntityManagerFactory" to inject, and boot would fail on this bean.
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "multiship.oracle.enabled", havingValue = "true")
public class OracleDtcOrderRepositoryImpl {

    @PersistenceContext(unitName = "oracleEntityManagerFactory")
    private EntityManager entityManager;

    private final OracleDtcConfig oracleDtcConfig;

    /**
     * Fetch all pending DTC orders from the configured view.
     * Criteria:
     *   - BATCH_ID != 0
     *   - TOTE_NUMBER is not null/empty (VARCHAR2)
     *   - ORDER_SUFFIX = 0 (main orders only)
     *
     * @return list of pending DTC orders
     */
    public List<OracleDtcOrder> findAllPendingDtcOrders() {
        String viewName = oracleDtcConfig.getDtcViewName();
        log.debug("Querying Oracle DTC orders from view: {}", viewName);

        String sql = "SELECT * FROM " + viewName + " o " +
                     "WHERE o.BATCH_ID != 0 " +
                     "  AND o.TOTE_NUMBER IS NOT NULL " +
                     "  AND COALESCE(o.ORDER_SUFFIX, 0) = 0 " +
                     "ORDER BY o.BATCH_ID DESC";

        Query query = entityManager.createNativeQuery(sql, OracleDtcOrder.class);
        return query.getResultList();
    }

    /**
     * Fetch pending DTC orders for a specific tenant.
     * Criteria:
     *   - TENANT_ID matches
     *   - BATCH_ID != 0
     *   - TOTE_NUMBER is not null/empty (VARCHAR2)
     *   - ORDER_SUFFIX = 0 (main orders only)
     *
     * @param tenantId the tenant/client code
     * @return list of pending DTC orders for the tenant
     */
    public List<OracleDtcOrder> findPendingOrdersByTenant(String tenantId) {
        String viewName = oracleDtcConfig.getDtcViewName();
        log.debug("Querying Oracle DTC orders from view: {} (tenant: {})", viewName, tenantId);

        String sql = "SELECT * FROM " + viewName + " o " +
                     "WHERE o.TENANT_ID = :tenantId " +
                     "  AND o.BATCH_ID != 0 " +
                     "  AND o.TOTE_NUMBER IS NOT NULL " +
                     "  AND TRIM(o.TOTE_NUMBER) != '' " +
                     "  AND COALESCE(o.ORDER_SUFFIX, 0) = 0 " +
                     "ORDER BY o.BATCH_ID DESC";

        Query query = entityManager.createNativeQuery(sql, OracleDtcOrder.class)
                .setParameter("tenantId", tenantId);
        return query.getResultList();
    }

    /**
     * Count pending DTC orders for a specific tenant.
     * Criteria:
     *   - TENANT_ID matches
     *   - BATCH_ID != 0
     *   - TOTE_NUMBER is not null/empty
     *   - ORDER_SUFFIX = 0
     *
     * @param tenantId the tenant/client code
     * @return count of pending orders
     */
    public long countPendingByTenant(String tenantId) {
        String viewName = oracleDtcConfig.getDtcViewName();
        log.debug("Counting Oracle DTC orders from view: {} (tenant: {})", viewName, tenantId);

        String sql = "SELECT COUNT(*) FROM " + viewName + " o " +
                     "WHERE o.TENANT_ID = :tenantId " +
                     "  AND o.BATCH_ID != 0 " +
                     "  AND o.TOTE_NUMBER IS NOT NULL " +
                     "  AND TRIM(o.TOTE_NUMBER) != '' " +
                     "  AND COALESCE(o.ORDER_SUFFIX, 0) = 0";

        Query query = entityManager.createNativeQuery(sql)
                .setParameter("tenantId", tenantId);
        return ((Number) query.getSingleResult()).longValue();
    }

    /**
     * Fetch orders by batch ID.
     * Criteria: batchId matches, toteNumber is not null/empty
     *
     * @param batchId the batch identifier
     * @return list of orders matching the batch
     */
    public List<OracleDtcOrder> findByBatchId(Long batchId) {
        String viewName = oracleDtcConfig.getDtcViewName();

        String sql = "SELECT * FROM " + viewName + " o " +
                     "WHERE o.BATCH_ID = :batchId " +
                     "  AND o.TOTE_NUMBER IS NOT NULL " +
                     "  AND TRIM(o.TOTE_NUMBER) != '' " +
                     "ORDER BY o.TOTE_NUMBER";

        Query query = entityManager.createNativeQuery(sql, OracleDtcOrder.class)
                .setParameter("batchId", batchId);
        return query.getResultList();
    }

    /**
     * Fetch orders by order number.
     * Criteria: orderNo matches, batchId != 0
     *
     * @param orderNo the order number
     * @return list of orders matching the number
     */
    public List<OracleDtcOrder> findByOrderNo(java.math.BigDecimal orderNo) {
        String viewName = oracleDtcConfig.getDtcViewName();

        String sql = "SELECT * FROM " + viewName + " o " +
                     "WHERE o.ORDER_NO = :orderNo " +
                     "  AND o.BATCH_ID != 0";

        Query query = entityManager.createNativeQuery(sql, OracleDtcOrder.class)
                .setParameter("orderNo", orderNo);
        return query.getResultList();
    }
}
