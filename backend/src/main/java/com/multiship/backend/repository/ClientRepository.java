package com.multiship.backend.repository;

import com.multiship.backend.model.Client;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ClientRepository extends JpaRepository<Client, Long> {

    Optional<Client> findByClientCodeIgnoreCase(String clientCode);

    boolean existsByClientCodeIgnoreCase(String clientCode);

    /**
     * Sprint 51 BP-M6 — batched active-clients lookup used by
     * {@link com.multiship.backend.service.OrderImportServiceImpl#validateReferences}.
     * Pre-BP-M6 the validator called {@code findAll()} platform-wide (cross-tenant
     * leak + full scan). Now it pulls only the codes present in the CSV rows
     * plus the caller's own scope via a single IN-list query.
     */
    @Query("select c from Client c where upper(c.clientCode) in :codes and c.status = 'ACTIVE'")
    List<Client> findActiveByClientCodeInIgnoreCase(@Param("codes") java.util.Collection<String> codes);

    @Query("select c from Client c where upper(c.clientCode) in :codes")
    List<Client> findByClientCodeInIgnoreCase(@Param("codes") java.util.Collection<String> codes);

    @Query("""
        SELECT c FROM Client c
        WHERE :keyword = ''
           OR UPPER(c.clientCode) LIKE CONCAT('%', UPPER(:keyword), '%')
           OR UPPER(c.name) LIKE CONCAT('%', UPPER(:keyword), '%')
           OR UPPER(COALESCE(c.shipFrom.city, '')) LIKE CONCAT('%', UPPER(:keyword), '%')
        ORDER BY c.clientCode
    """)
    Page<Client> search(@Param("keyword") String keyword, Pageable pageable);

    // ===== ADVANCED CLIENT LIST =====
    // '' is the "not set" sentinel. Returns client codes (paged/sorted); the
    // service maps each to a full DTO. Filters: status, carrier (has an
    // account with that carrier), hasOrders, plus column contains-filters.

    String CLIENT_FILTER_SQL = """
        WHERE (:keyword = ''
               OR UPPER(c.client_code) LIKE CONCAT('%', UPPER(:keyword), '%')
               OR UPPER(c.name) LIKE CONCAT('%', UPPER(:keyword), '%')
               OR UPPER(COALESCE(c.city,'')) LIKE CONCAT('%', UPPER(:keyword), '%'))
          AND (:status = '' OR UPPER(c.status) = :status)
          AND (:codeLike = '' OR UPPER(c.client_code) LIKE CONCAT('%', UPPER(:codeLike), '%'))
          AND (:nameLike = '' OR UPPER(c.name) LIKE CONCAT('%', UPPER(:nameLike), '%'))
          AND (:cityLike = '' OR UPPER(COALESCE(c.city,'')) LIKE CONCAT('%', UPPER(:cityLike), '%'))
          AND (:carrier = '' OR EXISTS (SELECT 1 FROM carrier_account_ref r
                WHERE UPPER(TRIM(COALESCE(r.customer_no,''))) = UPPER(c.client_code)
                AND UPPER(r.carrier_code) = :carrier))
          AND (:hasOrders = ''
               OR (:hasOrders = 'YES' AND EXISTS (SELECT 1 FROM label_batch b
                     WHERE UPPER(COALESCE(b.tenant_id, b.cust_no)) = UPPER(c.client_code)))
               OR (:hasOrders = 'NO' AND NOT EXISTS (SELECT 1 FROM label_batch b
                     WHERE UPPER(COALESCE(b.tenant_id, b.cust_no)) = UPPER(c.client_code))))
    """;

    @Query(value = """
        SELECT c.client_code FROM clients c
    """ + CLIENT_FILTER_SQL + """
        ORDER BY
            CASE WHEN :sortBy = 'code' AND :sortDirection = 'ASC' THEN c.client_code END ASC,
            CASE WHEN :sortBy = 'code' AND :sortDirection = 'DESC' THEN c.client_code END DESC,
            CASE WHEN :sortBy = 'name' AND :sortDirection = 'ASC' THEN c.name END ASC,
            CASE WHEN :sortBy = 'name' AND :sortDirection = 'DESC' THEN c.name END DESC,
            CASE WHEN :sortBy = 'created' AND :sortDirection = 'ASC' THEN c.created_at END ASC,
            CASE WHEN :sortBy = 'created' AND :sortDirection = 'DESC' THEN c.created_at END DESC,
            CASE WHEN :sortBy = 'orderCount' AND :sortDirection = 'ASC'
                 THEN (SELECT COUNT(*) FROM label_batch b WHERE UPPER(COALESCE(b.tenant_id, b.cust_no)) = UPPER(c.client_code)) END ASC,
            CASE WHEN :sortBy = 'orderCount' AND :sortDirection = 'DESC'
                 THEN (SELECT COUNT(*) FROM label_batch b WHERE UPPER(COALESCE(b.tenant_id, b.cust_no)) = UPPER(c.client_code)) END DESC,
            c.client_code ASC
        OFFSET :offset ROWS FETCH NEXT :limit ROWS ONLY
    """, nativeQuery = true)
    List<String> filterCodes(
            @Param("keyword") String keyword,
            @Param("status") String status,
            @Param("carrier") String carrier,
            @Param("hasOrders") String hasOrders,
            @Param("codeLike") String codeLike,
            @Param("nameLike") String nameLike,
            @Param("cityLike") String cityLike,
            @Param("sortBy") String sortBy,
            @Param("sortDirection") String sortDirection,
            @Param("offset") int offset,
            @Param("limit") int limit
    );

    @Query(value = "SELECT COUNT(*) FROM clients c " + CLIENT_FILTER_SQL, nativeQuery = true)
    long countFiltered(
            @Param("keyword") String keyword,
            @Param("status") String status,
            @Param("carrier") String carrier,
            @Param("hasOrders") String hasOrders,
            @Param("codeLike") String codeLike,
            @Param("nameLike") String nameLike,
            @Param("cityLike") String cityLike
    );
}
