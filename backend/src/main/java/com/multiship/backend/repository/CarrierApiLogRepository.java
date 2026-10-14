package com.multiship.backend.repository;

import com.multiship.backend.model.CarrierApiLogEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CarrierApiLogRepository extends JpaRepository<CarrierApiLogEntity, Long> {

    // BUG-015 — match the carrier filter case-insensitively and as a substring
    // ('ups' finds UPS, partial 'U'/'UP' work), plus an alias so 'USPS' finds the
    // rows Stamps.com logs under STAMPS_COM. Tracking is matched the same way.
    @Query("SELECT l FROM CarrierApiLogEntity l "
            + "WHERE (CAST(:carrier AS string) IS NULL "
            + "   OR LOWER(l.carrier) LIKE LOWER(CONCAT('%', CAST(:carrier AS string), '%')) "
            + "   OR (CAST(:carrierAlias AS string) IS NOT NULL AND LOWER(l.carrier) LIKE LOWER(CONCAT('%', CAST(:carrierAlias AS string), '%')))) "
            + "AND (:orderNo IS NULL OR l.orderNo = :orderNo) "
            + "AND (CAST(:tracking AS string) IS NULL OR LOWER(l.tracking) LIKE LOWER(CONCAT('%', CAST(:tracking AS string), '%')))")
    Page<CarrierApiLogEntity> search(
            @Param("carrier") String carrier,
            @Param("carrierAlias") String carrierAlias,
            @Param("orderNo") Long orderNo,
            @Param("tracking") String tracking,
            Pageable pageable);

    /** Ties the calls one web request made to the order it ended up creating. */
    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE CarrierApiLogEntity l SET l.orderNo = :orderNo, "
            + "l.tracking = COALESCE(l.tracking, :tracking) "
            + "WHERE l.requestId = :requestId AND l.orderNo IS NULL")
    int tagRequest(@Param("requestId") String requestId, @Param("orderNo") Long orderNo,
                   @Param("tracking") String tracking);
}
