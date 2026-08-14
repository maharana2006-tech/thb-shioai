package com.multiship.backend.repository;

import com.multiship.backend.model.Warehouse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WarehouseRepository extends JpaRepository<Warehouse, Long> {

    Optional<Warehouse> findByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCase(String code);

    List<Warehouse> findByOwnerClientCodeIgnoreCaseOrderByCodeAsc(String ownerClientCode);

    /**
     * Sprint 51 BP-M6 — active-warehouses lookup used by the order-import
     * validator + xlsx template builder. Pre-BP-M6 both call sites did a
     * platform-wide {@code findAll()} that leaked warehouses from other
     * tenants into dropdowns AND scanned inactive rows. Now the validator
     * asks for the exact ids it needs; the xlsx builder for the caller's
     * tenant-visible slice.
     */
    List<Warehouse> findByActiveTrue();

    List<Warehouse> findByIdInAndActiveTrue(java.util.Collection<Long> ids);

    /**
     * Paginated filtered list. Blank strings ("") act as the "not set" sentinel
     * so we can pass every filter as a String and let the SQL short-circuit.
     * Sort keys handled server-side via a CASE ladder — the sorted set is small
     * (dozens of warehouses even at scale) so index-only ordering isn't worth
     * dedicated indexes yet.
     */
    @Query(value = """
        SELECT w FROM Warehouse w
        WHERE (:keyword = ''
               OR UPPER(w.code) LIKE CONCAT('%', UPPER(:keyword), '%')
               OR UPPER(w.name) LIKE CONCAT('%', UPPER(:keyword), '%')
               OR UPPER(COALESCE(w.address.city, '')) LIKE CONCAT('%', UPPER(:keyword), '%'))
          AND (:ownerType = '' OR UPPER(w.ownerType) = :ownerType)
          AND (:ownerClientCode = ''
               OR UPPER(COALESCE(w.ownerClientCode, '')) = UPPER(:ownerClientCode))
          AND (:activeFilter = ''
               OR (:activeFilter = 'YES' AND w.active = TRUE)
               OR (:activeFilter = 'NO' AND w.active = FALSE))
        ORDER BY
            CASE WHEN :sortBy = 'code' AND :sortDirection = 'ASC' THEN w.code END ASC,
            CASE WHEN :sortBy = 'code' AND :sortDirection = 'DESC' THEN w.code END DESC,
            CASE WHEN :sortBy = 'name' AND :sortDirection = 'ASC' THEN w.name END ASC,
            CASE WHEN :sortBy = 'name' AND :sortDirection = 'DESC' THEN w.name END DESC,
            CASE WHEN :sortBy = 'owner' AND :sortDirection = 'ASC' THEN w.ownerType END ASC,
            CASE WHEN :sortBy = 'owner' AND :sortDirection = 'DESC' THEN w.ownerType END DESC,
            CASE WHEN :sortBy = 'created' AND :sortDirection = 'ASC' THEN w.createdAt END ASC,
            CASE WHEN :sortBy = 'created' AND :sortDirection = 'DESC' THEN w.createdAt END DESC,
            w.code ASC
    """)
    Page<Warehouse> filter(
            @Param("keyword") String keyword,
            @Param("ownerType") String ownerType,
            @Param("ownerClientCode") String ownerClientCode,
            @Param("activeFilter") String activeFilter,
            @Param("sortBy") String sortBy,
            @Param("sortDirection") String sortDirection,
            Pageable pageable
    );
}
