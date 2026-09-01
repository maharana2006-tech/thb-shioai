package com.multiship.backend.repository;

import com.multiship.backend.model.LabelPackage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LabelPackageRepository extends JpaRepository<LabelPackage, Long> {

    List<LabelPackage> findByOrderNoOrderBySequenceNumberAsc(Integer orderNo);

    Optional<LabelPackage> findByOrderNoAndSequenceNumber(Integer orderNo, Integer sequenceNumber);

    /**
     * Per-piece webhook routing — carriers push per-piece status
     * updates keyed by that piece's tracking number. Multi-package
     * orders have N rows; this finds the exact box for updating.
     * Returns empty when the tracking is the shipment master or
     * belongs to a shipment predating per-piece persistence.
     */
    Optional<LabelPackage> findByTrackingNumber(String trackingNumber);

    /**
     * Bulk DELETE (see {@link ShipmentBatchRepository#deleteByOrderNo}) — clears
     * a reused order's prior per-piece rows on regenerate, immediately and
     * without tripping Hibernate's flush ordering against the fresh inserts.
     */
    @Modifying
    @Query("delete from LabelPackage p where p.orderNo = :orderNo")
    void deleteByOrderNo(@Param("orderNo") Integer orderNo);
}
