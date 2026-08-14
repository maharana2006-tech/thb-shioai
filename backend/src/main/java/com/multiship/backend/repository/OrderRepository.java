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
     * Sprint 49 Tier 3 Fix 2 — batch fetch for the order-list resolve
     * loop. Replaces N individual {@code findByOrderNo} calls per page
     * render (~100 orders × ~4 lookups each = 400 queries) with a
     * single {@code IN (:orderNos)}.
     */
    List<Order> findByOrderNoIn(java.util.Collection<Integer> orderNos);

    /**
     * Sprint 48 B10 — allocates the next order_no for a manual shipment
     * from a Postgres sequence. Replaces the racy {@code MAX(order_no) + 1}
     * pattern that let two concurrent createManualShipment calls compute
     * the same id and collide on the PK.
     *
     * <p>The sequence is created + seeded on startup by
     * {@code OrderNoSequenceInitializer}. Under contention, Postgres
     * serialises {@code nextval()} without any lock the application can
     * see, so there is no thread coordination needed here.
     */
    @Query(value = "SELECT nextval('label_batch_order_no_seq')", nativeQuery = true)
    Long nextManualOrderNoRaw();

    /** Convenience wrapper — sequences are bigint on the wire but our
     *  order_no column is INTEGER, so the value always fits. */
    default int nextManualOrderNo() {
        Long v = nextManualOrderNoRaw();
        return v == null ? 0 : v.intValue();
    }

    /** Highest import batch id in the table (0 when empty) — a bulk CSV/XLSX import takes max+1. */
    @Query("SELECT COALESCE(MAX(o.batchId), 0) FROM Order o")
    Integer findMaxBatchId();

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
            s.shipvia_desc,
            COALESCE(b.order_source, CASE WHEN b.is_manual = 'Y' THEN 'MANUAL' ELSE 'ERP' END) as order_source,
            b.batch_id
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

    /**
     * Sprint 50 Tier 0.5 PR E — tenant-scoped variant of {@link #getDashboardStats()}.
     * Callers filter through {@code TenantScopeEnforcer.resolveScope()}: platform
     * operators keep the org-wide query; scoped USER-with-clientCode / TENANT / API
     * key get their own tenant's stats only.
     */
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
        WHERE UPPER(COALESCE(b.tenant_id, b.cust_no)) = UPPER(:tenantId)
    """, nativeQuery = true)
    List<Object[]> getDashboardStatsForTenant(@Param("tenantId") String tenantId);

    // Sprint 51 BP-M8 — cap the city-distribution result set. Pre-BP-M8
    // both queries emitted every distinct shipto_city (thousands on a
    // populated platform), transferred them all to Java, and rendered the
    // top few in the dashboard chart. LIMIT 25 keeps the "top cities"
    // chart intact while dropping ~99% of the row transfer + Java-side
    // aggregation.
    @Query(value = """
        SELECT shipto_city, COUNT(*) as count
        FROM label_batch
        GROUP BY shipto_city
        ORDER BY count DESC
        LIMIT 25
    """, nativeQuery = true)
    List<Object[]> getCityDistribution();

    /** Sprint 50 Tier 0.5 PR E — tenant-scoped variant of {@link #getCityDistribution()}. */
    @Query(value = """
        SELECT shipto_city, COUNT(*) as count
        FROM label_batch
        WHERE UPPER(COALESCE(tenant_id, cust_no)) = UPPER(:tenantId)
        GROUP BY shipto_city
        ORDER BY count DESC
        LIMIT 25
    """, nativeQuery = true)
    List<Object[]> getCityDistributionForTenant(@Param("tenantId") String tenantId);

    @Query(value = "SELECT AVG(weight) FROM label_batch WHERE weight IS NOT NULL", nativeQuery = true)
    List<Object[]> getAverageWeight();

    /** Sprint 50 Tier 0.5 PR E — tenant-scoped variant of {@link #getAverageWeight()}. */
    @Query(value = """
        SELECT AVG(weight) FROM label_batch
        WHERE weight IS NOT NULL
          AND UPPER(COALESCE(tenant_id, cust_no)) = UPPER(:tenantId)
    """, nativeQuery = true)
    List<Object[]> getAverageWeightForTenant(@Param("tenantId") String tenantId);

    /**
     * Pending (unlabelled) order count for a client — powers the
     * client-disable cascade guard. Match by tenant_id when set,
     * else cust_no (mirrors the resolution in every other order query).
     */
    @Query(value = """
        SELECT COUNT(*) FROM label_batch b
        WHERE UPPER(COALESCE(b.tenant_id, b.cust_no)) = UPPER(:clientCode)
          AND (b.is_label_generated IS NULL OR b.is_label_generated = false)
    """, nativeQuery = true)
    long countPendingByClient(@org.springframework.data.repository.query.Param("clientCode") String clientCode);

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
     * Auto-resolves without a prompt: the client has ZERO accounts on the
     * carrier but a platform (house) account covers it. Every other case
     * where the client has any of their own accounts on the carrier — even
     * exactly one — now requires the shipper to choose which one to bill
     * (Sprint 50 Tier 1 finding #19 removed the 1-account silent auto-pick
     * so the "always ask" invariant on {@link
     * com.multiship.backend.service.CarrierServiceImpl#resolveAccountForOrderWithDetails}
     * holds uniformly).
     */
    String CLIENT_AUTO_ACCOUNT_SQL = "(" + CLIENT_CARRIER_ACCOUNT_COUNT_SQL + " = 0 AND "
            + PLATFORM_CARRIER_ACCOUNT_SQL + ")";

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
            s.shipvia_desc,
            COALESCE(b.order_source, CASE WHEN b.is_manual = 'Y' THEN 'MANUAL' ELSE 'ERP' END) as order_source,
            b.batch_id
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

    /**
     * Sprint 50 Tier 0.5 PR E — tenant-scoped variant of {@link #getQueueStats()}.
     * Scoped USER-with-clientCode / TENANT / API key sees only their own tenant's
     * queue counts; platform operators use the org-wide {@link #getQueueStats()}.
     */
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
        WHERE UPPER(COALESCE(b.tenant_id, b.cust_no)) = UPPER(:tenantId)
    """, nativeQuery = true)
    List<Object[]> getQueueStatsForTenant(@Param("tenantId") String tenantId);
}
