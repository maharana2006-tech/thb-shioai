package com.multiship.backend.repository;

import com.multiship.backend.model.OrderTracking;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
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

    /**
     * PR-P2 (PERF-M14) — paged variant so callers can bound scans of the
     * ever-growing order_label_tracking table by a page size instead of
     * materialising every row in a status. Pre-P2 callers of the
     * unbounded overload should migrate; the unbounded version stays for
     * back-compat with the shipment-lifecycle backfill scripts that
     * genuinely need the full set.
     */
    List<OrderTracking> findByStatus(String status, org.springframework.data.domain.Pageable pageable);

    List<OrderTracking> findByIsLabelGeneratedFalse();

    /**
     * PR-P2 (PERF-M14) — paged variant. Same rationale as
     * {@link #findByStatus(String, org.springframework.data.domain.Pageable)}.
     */
    List<OrderTracking> findByIsLabelGeneratedFalse(org.springframework.data.domain.Pageable pageable);

    /** Newest generated labels first — feeds the unified Documents table
     *  (one row per labelled order: tracking + label + invoice + statement). */
    @Query("SELECT t FROM OrderTracking t WHERE t.isLabelGenerated = true "
            + "OR UPPER(t.status) = 'VOIDED' "
            + "ORDER BY t.labelGeneratedAt DESC, t.orderNo DESC")
    List<OrderTracking> findGeneratedNewestFirst(org.springframework.data.domain.Pageable pageable);

    Optional<OrderTracking> findByTrackingNumberIgnoreCase(String trackingNumber);

    /**
     * PR-D USPS_DIRECT — VOIDED USPS shipments in the given window that
     * have not yet been reconciled against USPS's eVS Refund report.
     * Feeds {@code UspsRefundCsvExporter}: every row in this list is a
     * candidate for the PS 3533 CSV the platform admin uploads to the
     * USPS Business Customer Gateway.
     *
     * <p>Filters:
     * <ul>
     *   <li>{@code status = 'VOIDED'} — only optimistic voids are
     *       refund-eligible; generated labels stay off the report.</li>
     *   <li>{@code void_reconciliation_status IS NULL} — already-
     *       reconciled rows stay off (APPROVED means USPS already refunded,
     *       DENIED means the row is now VOID_FAILED and mustn't be re-billed).</li>
     *   <li>{@code label_generated_at BETWEEN :from AND :to} — mirrors
     *       the date-range filter USPS's report itself uses.</li>
     *   <li>{@code UPPER(ship_via_cd) LIKE 'USPS%'} — USPS carrier scope.
     *       Uses ship_via_cd because carrier_code isn't populated on the
     *       tracking row; the LIKE catches USPS, USPS_GROUND_ADVANTAGE etc.</li>
     * </ul>
     */
    @Query("""
        SELECT t FROM OrderTracking t
        WHERE UPPER(t.status) = 'VOIDED'
          AND t.voidReconciliationStatus IS NULL
          AND t.labelGeneratedAt BETWEEN :from AND :to
          AND UPPER(COALESCE(t.shipViaCd, '')) LIKE 'USPS%'
          AND t.trackingNumber IS NOT NULL AND TRIM(t.trackingNumber) <> ''
        ORDER BY t.labelGeneratedAt DESC, t.id DESC
    """)
    List<OrderTracking> findVoidedUnreconciledUspsBetween(
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);
}
