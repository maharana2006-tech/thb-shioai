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
     * D2C History list — paged, optional tenant filter, and a free-text search
     * over batch, tote, order #, customer PO and ship-to name/city.
     * Empty strings mean "no filter".
     */
    @Query("""
        SELECT d FROM DtcOrder d
        WHERE (:tenantId = '' OR d.tenantId = :tenantId)
          AND (:q = ''
               OR STR(d.batchId) LIKE CONCAT('%', :q, '%')
               OR STR(d.orderNo) LIKE CONCAT('%', :q, '%')
               OR LOWER(d.toteNumber) LIKE LOWER(CONCAT('%', :q, '%'))
               OR LOWER(d.custPo)     LIKE LOWER(CONCAT('%', :q, '%'))
               OR LOWER(d.shipName)   LIKE LOWER(CONCAT('%', :q, '%'))
               OR LOWER(d.shipToCity) LIKE LOWER(CONCAT('%', :q, '%')))
    """)
    org.springframework.data.domain.Page<DtcOrder> search(
            @Param("tenantId") String tenantId,
            @Param("q") String q,
            org.springframework.data.domain.Pageable pageable);

    /** Distinct tenants present, for the page's tenant filter. */
    @Query("SELECT DISTINCT d.tenantId FROM DtcOrder d ORDER BY d.tenantId")
    List<String> findDistinctTenantIds();

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
     * Check if a DTC order already exists by batch ID only.
     * @deprecated Use {@link #existsByBatchIdAndToteNumberAndTenantId} for composite key check
     */
    @Deprecated(since = "2.0", forRemoval = true)
    boolean existsByBatchId(Long batchId);

    /**
     * Check if a DTC order already exists using composite key (batchId + toteNumber + tenantId).
     * This prevents duplicates when the same batchId exists for different totes or tenants.
     *
     * @param batchId the batch identifier
     * @param toteNumber the tote/carton number
     * @param tenantId the tenant/client code
     * @return true if order with this composite key exists, false otherwise
     */
    @Query("""
        SELECT COUNT(d) > 0 FROM DtcOrder d
        WHERE d.batchId = :batchId
          AND d.toteNumber = :toteNumber
          AND d.tenantId = :tenantId
    """)
    boolean existsByBatchIdAndToteNumberAndTenantId(
            @Param("batchId") java.math.BigDecimal batchId,
            @Param("toteNumber") String toteNumber,
            @Param("tenantId") String tenantId);

    /**
     * Count pending DTC orders for a tenant.
     */
    @Query("""
        SELECT COUNT(d) FROM DtcOrder d
        WHERE d.tenantId = :tenantId
    """)
    long countByTenantId(@Param("tenantId") String tenantId);

    // ═════════════ DTC History (V102) — batch summary + lines ═════════════

    /**
     * Distinct (tenant, batch) keys for the Dtcal-style summary page, with
     * optional tenant and ship-date filters. Empty strings mean "no filter".
     */
    @Query(value = """
        SELECT DISTINCT new com.multiship.backend.dto.DtcBatchKey(d.tenantId, d.batchId)
        FROM DtcOrder d
        WHERE (:tenantId = '' OR d.tenantId = :tenantId)
          AND (:shipDate = '' OR d.shipDate = :shipDate)
        ORDER BY d.batchId DESC
        """,
        countQuery = """
        SELECT COUNT(g.batchId) FROM (
            SELECT d.batchId AS batchId FROM DtcOrder d
            WHERE (:tenantId = '' OR d.tenantId = :tenantId)
              AND (:shipDate = '' OR d.shipDate = :shipDate)
            GROUP BY d.tenantId, d.batchId
        ) g
        """)
    org.springframework.data.domain.Page<com.multiship.backend.dto.DtcBatchKey> findBatchKeys(
            @Param("tenantId") String tenantId,
            @Param("shipDate") String shipDate,
            org.springframework.data.domain.Pageable pageable);

    /** Aggregate over one batch — one row, always present when the key is. */
    @Query("""
        SELECT new com.multiship.backend.dto.DtcBatchStats(
            d.tenantId, d.batchId,
            COUNT(d),
            MIN(d.orderNo), MAX(d.orderNo),
            MIN(d.toteNumber), MAX(d.toteNumber),
            MAX(d.shipDate),
            SUM(CASE WHEN d.generatedStatus = 'GENERATED' THEN 1 ELSE 0 END),
            SUM(CASE WHEN d.generatedStatus = 'FAILED' THEN 1 ELSE 0 END),
            SUM(CASE WHEN d.generatedStatus = 'QUEUED_USPS' THEN 1 ELSE 0 END),
            SUM(CASE WHEN d.generatedStatus IS NULL THEN 1 ELSE 0 END),
            MAX(d.createdAt))
        FROM DtcOrder d
        WHERE d.tenantId = :tenantId AND d.batchId = :batchId
        GROUP BY d.tenantId, d.batchId
        """)
    java.util.Optional<com.multiship.backend.dto.DtcBatchStats> summarizeBatch(
            @Param("tenantId") String tenantId,
            @Param("batchId") java.math.BigDecimal batchId);

    /** Paged lines of one batch (the HstDetails-style detail page). */
    org.springframework.data.domain.Page<DtcOrder> findByTenantIdAndBatchId(
            String tenantId, java.math.BigDecimal batchId,
            org.springframework.data.domain.Pageable pageable);

    /** All rows of one batch in stable order — the generation worker's input. */
    java.util.List<DtcOrder> findByTenantIdAndBatchIdOrderByIdAsc(
            String tenantId, java.math.BigDecimal batchId);

    /** Distinct ship dates present (summary-page date filter options). */
    @Query("""
        SELECT DISTINCT d.shipDate FROM DtcOrder d
        WHERE d.shipDate IS NOT NULL AND d.shipDate <> ''
        ORDER BY d.shipDate DESC
        """)
    java.util.List<String> findDistinctShipDates();
}
