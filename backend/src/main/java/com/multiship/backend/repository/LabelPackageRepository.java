package com.multiship.backend.repository;

import com.multiship.backend.model.LabelPackage;
import org.springframework.data.jpa.repository.JpaRepository;
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

    void deleteByOrderNo(Integer orderNo);
}
