package com.multiship.backend.repository;

import com.multiship.backend.model.ShipmentDocument;
import com.multiship.backend.service.output.DocType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Sprint 52 — repository for the always-on DB copy of every generated
 * label / CI. Read paths are shipment-id-first (typical retrieval:
 * "give me the label PDF for shipment 42").
 *
 * <p>The {@code metaOnly} projection query intentionally omits the
 * {@code bytes} column so a list-documents-for-a-shipment call doesn't
 * load megabytes of TOASTed data just to render "1 label + 1 CI".
 */
public interface ShipmentDocumentRepository extends JpaRepository<ShipmentDocument, Long> {

    Optional<ShipmentDocument> findFirstByShipmentIdAndDocTypeOrderByIdDesc(
            Long shipmentId, DocType docType);

    List<ShipmentDocument> findByShipmentIdOrderByIdDesc(Long shipmentId);

    List<ShipmentDocument> findByOrderNoOrderByIdDesc(Integer orderNo);

    /**
     * Metadata-only projection — no {@code bytes}. Use for the
     * "list documents for this shipment" table when the UI just needs
     * id/type/size to render a download button.
     */
    @Query("SELECT new com.multiship.backend.repository.ShipmentDocumentRepository$DocumentMetadata("
            + "d.id, d.shipmentId, d.orderNo, d.docType, d.contentType, d.byteSize, d.createdAt) "
            + "FROM ShipmentDocument d WHERE d.shipmentId = :shipmentId ORDER BY d.id DESC")
    List<DocumentMetadata> findMetaByShipmentId(@Param("shipmentId") Long shipmentId);

    /** Projection carrier for metadata-only lookups. */
    record DocumentMetadata(
            Long id,
            Long shipmentId,
            Integer orderNo,
            DocType docType,
            String contentType,
            int byteSize,
            java.time.LocalDateTime createdAt) { }
}
