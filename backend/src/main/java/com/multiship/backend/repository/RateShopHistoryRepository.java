package com.multiship.backend.repository;

import com.multiship.backend.model.RateShopHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface RateShopHistoryRepository extends JpaRepository<RateShopHistory, Long> {

    @Query("""
        SELECT h FROM RateShopHistory h
        WHERE (:customerNo IS NULL OR h.customerNo = :customerNo)
          AND (:from IS NULL OR h.createdAt >= :from)
          AND (:to   IS NULL OR h.createdAt <= :to)
        ORDER BY h.createdAt ASC
    """)
    List<RateShopHistory> reportRows(
            @Param("customerNo") String customerNo,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);
}
