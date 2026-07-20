package com.multiship.backend.repository;

import com.multiship.backend.model.Order;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<Order, Integer> {

    Optional<Order> findByOrderNo(Integer orderNo);

    /**
     * Locks the order row (SELECT ... FOR UPDATE) so concurrent label
     * generations for the same order serialize: the second request waits for
     * the first to commit, then sees the generated label and returns it
     * instead of purchasing a duplicate from the carrier.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Order o WHERE o.orderNo = :orderNo")
    Optional<Order> findByOrderNoForUpdate(@Param("orderNo") Integer orderNo);

    @Query("""
        SELECT DISTINCT o
        FROM Order o
        LEFT JOIN FETCH o.orderLines
        WHERE o.orderNo = :orderNo
    """)
    Optional<Order> findOrderWithLines(@Param("orderNo") Integer orderNo);

    @Query(value = """
        SELECT
            b.order_no,
            b.order_suffix,
            b.order_status,
            b.cust_no,
            b.shipto_city,
            b.shipto_state,
            b.shipto_zip,
            b.shipvia_cd,
            b.weight,
            b.goods_desc,
            b.created_date,
            COALESCE(t.status, 'PENDING') as label_status,
            t.is_label_generated,
            t.tracking_number,
            t.tracking_url,
            t.label_file_path,
            t.error_message,
            t.label_generated_at,
            s.shipvia_desc
        FROM label_batch b
        LEFT JOIN order_label_tracking t ON b.order_no = t.order_no
        LEFT JOIN ship_vias s ON b.shipvia_cd = s.shipvia_cd
        WHERE b.order_no = :orderNo
    """, nativeQuery = true)
    List<Object[]> findOrderWithTracking(@Param("orderNo") Integer orderNo);

    @Query(value = """
        SELECT
            COUNT(*) as total_orders,
            COUNT(CASE WHEN COALESCE(t.status, 'PENDING') = 'PENDING' THEN 1 END) as pending_labels,
            COUNT(CASE WHEN t.status = 'GENERATED' THEN 1 END) as generated_labels,
            COUNT(CASE WHEN t.status = 'ERROR' THEN 1 END) as failed_labels,
            COUNT(CASE WHEN t.is_label_generated = true THEN 1 END) as labels_generated,
            COALESCE(SUM(b.weight), 0) as total_weight
        FROM label_batch b
        LEFT JOIN order_label_tracking t ON b.order_no = t.order_no
    """, nativeQuery = true)
    List<Object[]> getDashboardStats();

    @Query(value = """
        SELECT shipto_city, COUNT(*) as count
        FROM label_batch
        GROUP BY shipto_city
        ORDER BY count DESC
    """, nativeQuery = true)
    List<Object[]> getCityDistribution();

    @Query(value = "SELECT AVG(weight) FROM label_batch WHERE weight IS NOT NULL", nativeQuery = true)
    List<Object[]> getAverageWeight();

    // ===== UNIFIED LIST QUERY =====
    // One server-side query for every order list in the app. Filters use ''
    // as the "not set" sentinel (Postgres cannot type bare null parameters).
    // The resolution filter mirrors CarrierServiceImpl.resolveAccountForOrder
    // in SQL: keep the two in sync when the cascade changes.

    String OCD_COMPLETE_SQL = """
        EXISTS (SELECT 1 FROM order_carrier_details d WHERE d.order_no = b.order_no
            AND COALESCE(d.account_number,'') <> '' AND COALESCE(d.client_id,'') <> '' AND COALESCE(d.client_secret,'') <> '')
    """;

    String OCD_ANY_SQL = """
        EXISTS (SELECT 1 FROM order_carrier_details d WHERE d.order_no = b.order_no
            AND (COALESCE(d.account_number,'') <> '' OR COALESCE(d.client_id,'') <> '' OR COALESCE(d.client_secret,'') <> ''))
    """;

    String BOOK_MATCH_SQL = """
        EXISTS (SELECT 1 FROM order_carrier_details d JOIN carrier_account_ref r
            ON UPPER(TRIM(r.account_number)) = UPPER(TRIM(d.account_number))
            WHERE d.order_no = b.order_no AND COALESCE(d.account_number,'') <> ''
            AND COALESCE(r.active, TRUE) = TRUE
            AND COALESCE(r.account_number,'') <> '' AND COALESCE(r.client_id,'') <> '' AND COALESCE(r.client_secret,'') <> '')
    """;

    /** The order's client exists and is ACTIVE. */
    String CLIENT_OK_SQL = """
        EXISTS (SELECT 1 FROM clients c WHERE UPPER(c.client_code) = UPPER(COALESCE(b.tenant_id, b.cust_no))
            AND UPPER(c.status) = 'ACTIVE')
    """;

    /** The order's canonical carrier from its ship-via code (mirrors resolveCanonicalCarrierCode). */
    String ORDER_CARRIER_SQL = """
        (CASE UPPER(TRIM(COALESCE(b.shipvia_cd,'')))
            WHEN 'P80' THEN 'UPS' WHEN 'F77' THEN 'FEDEX' WHEN 'L01' THEN 'USPS'
            ELSE UPPER(TRIM(COALESCE(b.shipvia_cd,''))) END)
    """;

    /** How many usable accounts the order's client has ON THE ORDER'S CARRIER. */
    String CLIENT_CARRIER_ACCOUNT_COUNT_SQL = """
        (SELECT COUNT(*) FROM carrier_account_ref r
            WHERE UPPER(TRIM(COALESCE(r.customer_no,''))) = UPPER(COALESCE(b.tenant_id, b.cust_no))
            AND COALESCE(r.active, TRUE) = TRUE
            AND COALESCE(r.account_number,'') <> '' AND COALESCE(r.client_id,'') <> '' AND COALESCE(r.client_secret,'') <> ''
            AND UPPER(TRIM(COALESCE(r.carrier_code,''))) = """ + ORDER_CARRIER_SQL + ")";

    /** A usable PLATFORM (house) account exists for the order's carrier. */
    String PLATFORM_CARRIER_ACCOUNT_SQL = """
        EXISTS (SELECT 1 FROM carrier_account_ref r
            WHERE (r.customer_no IS NULL OR TRIM(r.customer_no) = '')
            AND COALESCE(r.active, TRUE) = TRUE
            AND COALESCE(r.account_number,'') <> '' AND COALESCE(r.client_id,'') <> '' AND COALESCE(r.client_secret,'') <> ''
            AND UPPER(TRIM(COALESCE(r.carrier_code,''))) = """ + ORDER_CARRIER_SQL + ")";

    /**
     * Auto-resolves without a prompt: exactly ONE of the client's accounts on
     * the carrier, or NONE but a platform account covers it (used in the
     * background). Only 2+ client accounts require the shipper to choose.
     */
    String CLIENT_AUTO_ACCOUNT_SQL = "((" + CLIENT_CARRIER_ACCOUNT_COUNT_SQL + " = 1) OR ("
            + CLIENT_CARRIER_ACCOUNT_COUNT_SQL + " = 0 AND " + PLATFORM_CARRIER_ACCOUNT_SQL + "))";

    String RESOLUTION_READY_SQL = "(" + OCD_COMPLETE_SQL
            + " OR (" + OCD_ANY_SQL + " AND " + BOOK_MATCH_SQL + ")"
            + " OR (NOT " + OCD_ANY_SQL + " AND " + CLIENT_OK_SQL
            + " AND " + CLIENT_AUTO_ACCOUNT_SQL + "))";

    String RESOLUTION_NEEDS_DETAILS_SQL = "(" + OCD_ANY_SQL
            + " AND NOT " + OCD_COMPLETE_SQL
            + " AND NOT " + BOOK_MATCH_SQL + ")";

    /** Client exists but has 2+ accounts on the carrier (or none and no platform) — the shipper picks one. */
    String RESOLUTION_CHOOSE_ACCOUNT_SQL = "(NOT " + OCD_ANY_SQL + " AND " + CLIENT_OK_SQL
            + " AND NOT " + CLIENT_AUTO_ACCOUNT_SQL + ")";

    /** The order's client is unregistered (or inactive) — generation prompts to add the client. */
    String RESOLUTION_CLIENT_MISSING_SQL = "(NOT " + OCD_ANY_SQL + " AND NOT " + CLIENT_OK_SQL + ")";

    String UNIFIED_FILTER_SQL = """
        WHERE (:status = '' OR COALESCE(t.status, 'PENDING') = :status)
          AND (:tenantId = '' OR UPPER(COALESCE(b.tenant_id, b.cust_no)) = :tenantId)
          AND (:keyword = ''
               OR CAST(b.order_no AS TEXT) LIKE CONCAT('%', :keyword, '%')
               OR LOWER(b.shipto_city) LIKE LOWER(CONCAT('%', :keyword, '%'))
               OR LOWER(b.cust_no) LIKE LOWER(CONCAT('%', :keyword, '%'))
               OR LOWER(COALESCE(t.tracking_number, '')) LIKE LOWER(CONCAT('%', :keyword, '%')))
          AND (:customer = '' OR LOWER(COALESCE(b.tenant_id, b.cust_no)) LIKE LOWER(CONCAT('%', :customer, '%')))
          AND (:city = ''
               OR LOWER(b.shipto_city) LIKE LOWER(CONCAT('%', :city, '%'))
               OR LOWER(COALESCE(b.shipto_state, '')) LIKE LOWER(CONCAT('%', :city, '%')))
          AND (:orderNoLike = '' OR CAST(b.order_no AS TEXT) LIKE CONCAT('%', :orderNoLike, '%'))
          AND (:tracking = '' OR LOWER(COALESCE(t.tracking_number, '')) LIKE LOWER(CONCAT('%', :tracking, '%')))
          AND (:createdFrom = '' OR b.created_date >= TO_DATE(NULLIF(:createdFrom, ''), 'YYYY-MM-DD'))
          AND (:createdTo = '' OR b.created_date <= TO_DATE(NULLIF(:createdTo, ''), 'YYYY-MM-DD'))
          AND (:resolution = ''
               OR (:resolution = 'READY' AND """ + RESOLUTION_READY_SQL + """
               )
               OR (:resolution = 'NEEDS_DETAILS' AND """ + RESOLUTION_NEEDS_DETAILS_SQL + """
               )
               OR (:resolution = 'CHOOSE_ACCOUNT' AND """ + RESOLUTION_CHOOSE_ACCOUNT_SQL + """
               )
               OR (:resolution = 'CLIENT_MISSING' AND """ + RESOLUTION_CLIENT_MISSING_SQL + """
               ))
    """;

    @Query(value = """
        SELECT
            b.order_no,
            b.order_suffix,
            b.order_status,
            b.cust_no,
            b.shipto_city,
            b.shipto_state,
            b.shipto_zip,
            b.shipvia_cd,
            b.weight,
            b.goods_desc,
            b.created_date,
            COALESCE(t.status, 'PENDING') as label_status,
            t.is_label_generated,
            t.tracking_number,
            t.tracking_url,
            t.label_file_path,
            t.error_message,
            t.label_generated_at,
            s.shipvia_desc
        FROM label_batch b
        LEFT JOIN order_label_tracking t ON b.order_no = t.order_no
        LEFT JOIN ship_vias s ON b.shipvia_cd = s.shipvia_cd
    """ + UNIFIED_FILTER_SQL + """
        ORDER BY
            CASE WHEN :sortBy = 'orderNo' AND :sortDirection = 'ASC' THEN b.order_no END ASC,
            CASE WHEN :sortBy = 'orderNo' AND :sortDirection = 'DESC' THEN b.order_no END DESC,
            CASE WHEN :sortBy = 'customer' AND :sortDirection = 'ASC' THEN COALESCE(b.tenant_id, b.cust_no) END ASC,
            CASE WHEN :sortBy = 'customer' AND :sortDirection = 'DESC' THEN COALESCE(b.tenant_id, b.cust_no) END DESC,
            CASE WHEN :sortBy = 'generatedAt' AND :sortDirection = 'ASC' THEN t.label_generated_at END ASC,
            CASE WHEN :sortBy = 'generatedAt' AND :sortDirection = 'DESC' THEN t.label_generated_at END DESC,
            CASE WHEN :sortBy = 'tracking' AND :sortDirection = 'ASC' THEN t.tracking_number END ASC,
            CASE WHEN :sortBy = 'tracking' AND :sortDirection = 'DESC' THEN t.tracking_number END DESC,
            CASE WHEN :sortBy = 'city' AND :sortDirection = 'ASC' THEN b.shipto_city END ASC,
            CASE WHEN :sortBy = 'city' AND :sortDirection = 'DESC' THEN b.shipto_city END DESC,
            CASE WHEN :sortBy = 'weight' AND :sortDirection = 'ASC' THEN b.weight END ASC,
            CASE WHEN :sortBy = 'weight' AND :sortDirection = 'DESC' THEN b.weight END DESC,
            CASE WHEN :sortBy = 'status' AND :sortDirection = 'ASC' THEN COALESCE(t.status, 'PENDING') END ASC,
            CASE WHEN :sortBy = 'status' AND :sortDirection = 'DESC' THEN COALESCE(t.status, 'PENDING') END DESC,
            CASE WHEN :sortBy = 'createdDate' AND :sortDirection = 'ASC' THEN b.created_date END ASC,
            CASE WHEN :sortBy = 'createdDate' AND :sortDirection = 'DESC' THEN b.created_date END DESC
        OFFSET :offset ROWS FETCH NEXT :limit ROWS ONLY
    """, nativeQuery = true)
    List<Object[]> findOrdersUnified(
            @Param("status") String status,
            @Param("tenantId") String tenantId,
            @Param("keyword") String keyword,
            @Param("resolution") String resolution,
            @Param("customer") String customer,
            @Param("city") String city,
            @Param("orderNoLike") String orderNoLike,
            @Param("tracking") String tracking,
            @Param("createdFrom") String createdFrom,
            @Param("createdTo") String createdTo,
            @Param("offset") int offset,
            @Param("limit") int limit,
            @Param("sortBy") String sortBy,
            @Param("sortDirection") String sortDirection
    );

    @Query(value = """
        SELECT COUNT(*)
        FROM label_batch b
        LEFT JOIN order_label_tracking t ON b.order_no = t.order_no
    """ + UNIFIED_FILTER_SQL, nativeQuery = true)
    long countOrdersUnified(
            @Param("status") String status,
            @Param("tenantId") String tenantId,
            @Param("keyword") String keyword,
            @Param("resolution") String resolution,
            @Param("customer") String customer,
            @Param("city") String city,
            @Param("orderNoLike") String orderNoLike,
            @Param("tracking") String tracking,
            @Param("createdFrom") String createdFrom,
            @Param("createdTo") String createdTo
    );

    /** Tab counts for the Labels work queue, computed in one pass. */
    @Query(value = """
        SELECT
            COUNT(*) FILTER (WHERE COALESCE(t.status, 'PENDING') = 'PENDING' AND """ + RESOLUTION_READY_SQL + """
            ) as ready,
            COUNT(*) FILTER (WHERE COALESCE(t.status, 'PENDING') = 'PENDING' AND """ + RESOLUTION_NEEDS_DETAILS_SQL + """
            ) as needs_details,
            COUNT(*) FILTER (WHERE COALESCE(t.status, 'PENDING') = 'PENDING' AND """ + RESOLUTION_CHOOSE_ACCOUNT_SQL + """
            ) as choose_account,
            COUNT(*) FILTER (WHERE COALESCE(t.status, 'PENDING') = 'PENDING' AND """ + RESOLUTION_CLIENT_MISSING_SQL + """
            ) as client_missing,
            COUNT(*) FILTER (WHERE t.status = 'ERROR') as failed,
            COUNT(*) FILTER (WHERE t.status = 'GENERATED') as generated
        FROM label_batch b
        LEFT JOIN order_label_tracking t ON b.order_no = t.order_no
    """, nativeQuery = true)
    List<Object[]> getQueueStats();
}
