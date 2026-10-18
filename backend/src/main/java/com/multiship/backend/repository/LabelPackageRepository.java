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
     * BUG-010 — tracking-number uniqueness isn't guaranteed across
     * orders (Stamps sandbox emits the same placeholder for every
     * label; in prod, rare but real cross-order collisions happen).
     * Scope by {@code orderNo} so lookups from the void path (which
     * knows the order) remain a point-lookup instead of blowing up
     * with {@code IncorrectResultSizeDataAccessException}.
     */
    Optional<LabelPackage> findByOrderNoAndTrackingNumber(Integer orderNo, String trackingNumber);

    /**
     * R2-BUG-003 (run-4) — the SERA manifest/close-out path sweeps a
     * whole day's labels and resolves each tracking → label_id. It
     * can't tag MDC per tracking the way the void path does, so the
     * {@code findByTrackingNumber(Optional&lt;&gt;)} fallback above
     * throws {@code IncorrectResultSizeDataAccessException} on the
     * sandbox (163 labels share the placeholder tracking) and on any
     * prod collision. Pick the lowest-id row deterministically — on
     * prod trackings are 1:1 so "first == only"; on sandbox any row
     * is acceptable because they all point at the same carrier label
     * id.
     */
    Optional<LabelPackage> findFirstByTrackingNumberOrderByIdAsc(String trackingNumber);

    /**
     * PR-T9-X1 (audit T-MPS3) — the single "lead" piece for a shipment,
     * i.e. the row that represents the whole MPS for customer-facing
     * display. Backed by the V135 partial index
     * {@code ix_label_package_lead}, so this is a point-lookup regardless
     * of shipment size.
     */
    Optional<LabelPackage> findFirstByOrderNoAndIsLeadPieceTrue(Integer orderNo);

    /**
     * Bulk DELETE (see {@link ShipmentBatchRepository#deleteByOrderNo}) — clears
     * a reused order's prior per-piece rows on regenerate, immediately and
     * without tripping Hibernate's flush ordering against the fresh inserts.
     */
    @Modifying
    @Query("delete from LabelPackage p where p.orderNo = :orderNo")
    void deleteByOrderNo(@Param("orderNo") Integer orderNo);
}
