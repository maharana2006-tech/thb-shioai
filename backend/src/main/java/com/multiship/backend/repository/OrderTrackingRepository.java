package com.multiship.backend.repository;

import com.multiship.backend.model.OrderTracking;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
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

    Optional<OrderTracking> findByOrderNoAndOrderSuffix(Integer orderNo, Integer orderSuffix);

    List<OrderTracking> findByStatus(String status);

    List<OrderTracking> findByIsLabelGeneratedFalse();
}