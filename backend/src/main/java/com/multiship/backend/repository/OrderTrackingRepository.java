package com.multiship.backend.repository;

import com.multiship.backend.model.OrderTracking;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface OrderTrackingRepository extends JpaRepository<OrderTracking, Long> {

    /** Per-account usage: [account_number, labels_generated, last_used_at]. */
    @Query(value = """
        SELECT account_number, COUNT(*) AS labels, MAX(label_generated_at) AS last_used
        FROM order_label_tracking
        WHERE account_number IS NOT NULL AND is_label_generated = true
        GROUP BY account_number
    """, nativeQuery = true)
    List<Object[]> aggregateUsageByAccount();

    /** Labels-generated count for a given carrier account (used by the delete guard). */
    long countByAccountNumberIgnoreCaseAndIsLabelGeneratedTrue(String accountNumber);

    Optional<OrderTracking> findByOrderNo(Integer orderNo);

    /** Sprint 51 — batch load for the order-list account column (billed account on generated orders). */
    java.util.List<OrderTracking> findByOrderNoIn(java.util.Collection<Integer> orderNos);

    /**
     * Sprint 51 R1 (audit finding #1) — pessimistic-write lookup used by
     * {@code VoidServiceImpl.voidLabel} so two concurrent void requests on
     * the same order serialize on the DB row. Without this, both requests
     * pass the "already VOIDED" short-circuit and both call the carrier —
     * some carriers charge a re-attempt fee on the second void and the
     * audit trail ends up inconsistent. Mirrors the pattern in
     * {@link OrderRepository#findByOrderNoForUpdate(Integer)} (label path).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM OrderTracking t WHERE t.orderNo = :orderNo")
    Optional<OrderTracking> findByOrderNoForUpdate(@Param("orderNo") Integer orderNo);

    Optional<OrderTracking> findByOrderNoAndOrderSuffix(Integer orderNo, Integer orderSuffix);

    List<OrderTracking> findByStatus(String status);

    List<OrderTracking> findByIsLabelGeneratedFalse();

    /** Newest generated labels first — feeds the unified Documents table
     *  (one row per labelled order: tracking + label + invoice + statement). */
    @Query("SELECT t FROM OrderTracking t WHERE t.isLabelGenerated = true "
            + "OR UPPER(t.status) = 'VOIDED' "
            + "ORDER BY t.labelGeneratedAt DESC, t.orderNo DESC")
    List<OrderTracking> findGeneratedNewestFirst(org.springframework.data.domain.Pageable pageable);

    Optional<OrderTracking> findByTrackingNumberIgnoreCase(String trackingNumber);
}