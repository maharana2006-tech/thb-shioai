package com.multiship.backend.repository;

import com.multiship.backend.model.ShipmentBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ShipmentBatchRepository extends JpaRepository<ShipmentBatch, Long> {

    List<ShipmentBatch> findByOrderNoOrderByBatchSeqAsc(Integer orderNo);

    /**
     * Bulk DELETE (not a derived per-entity delete) so the rows are gone from
     * the DB immediately when called — a regenerate that reuses the order row
     * clears the prior attempt's batches before inserting the new ones, and a
     * bulk delete sidesteps Hibernate's flush ordering (INSERTs before DELETEs)
     * that would otherwise collide on the (order_no, batch_seq) unique key.
     */
    @Modifying
    @Query("delete from ShipmentBatch b where b.orderNo = :orderNo")
    void deleteByOrderNo(@Param("orderNo") Integer orderNo);
}
