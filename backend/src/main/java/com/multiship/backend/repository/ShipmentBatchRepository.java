package com.multiship.backend.repository;

import com.multiship.backend.model.ShipmentBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ShipmentBatchRepository extends JpaRepository<ShipmentBatch, Long> {

    List<ShipmentBatch> findByOrderNoOrderByBatchSeqAsc(Integer orderNo);

    void deleteByOrderNo(Integer orderNo);
}
