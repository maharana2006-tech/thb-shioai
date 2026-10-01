package com.multiship.backend.repository;

import com.multiship.backend.model.CarrierApiLogEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CarrierApiLogRepository extends JpaRepository<CarrierApiLogEntity, Long> {

    @Query("SELECT l FROM CarrierApiLogEntity l "
            + "WHERE (CAST(:carrier AS string) IS NULL OR l.carrier = :carrier) "
            + "AND (:orderNo IS NULL OR l.orderNo = :orderNo) "
            + "AND (CAST(:tracking AS string) IS NULL OR l.tracking = :tracking)")
    Page<CarrierApiLogEntity> search(
            @Param("carrier") String carrier,
            @Param("orderNo") Long orderNo,
            @Param("tracking") String tracking,
            Pageable pageable);
}
