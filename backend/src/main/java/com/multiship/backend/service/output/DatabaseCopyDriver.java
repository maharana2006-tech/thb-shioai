package com.multiship.backend.service.output;

import com.multiship.backend.model.ShipmentDocument;
import com.multiship.backend.repository.ShipmentDocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Sprint 52 — always-on driver that persists a copy of every generated
 * document to {@code shipment_document}. Invoked BEFORE the external
 * drivers so if a per-destination driver blows up we still have the
 * bytes retrievable via the retrieve endpoint. Never registered as a
 * "supports" driver in the resolver: the service invokes it directly
 * before iterating configured destinations.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DatabaseCopyDriver {

    private final ShipmentDocumentRepository documentRepository;

    public ShipmentDocument persist(DocType docType, byte[] payload, DispatchContext ctx) {
        if (payload == null || payload.length == 0) {
            log.debug("DatabaseCopyDriver: skipping empty {} for shipment={}",
                    docType, ctx.shipmentId());
            return null;
        }
        ShipmentDocument doc = ShipmentDocument.builder()
                .shipmentId(ctx.shipmentId() == null ? 0L : ctx.shipmentId())
                .orderNo(ctx.orderNo())
                .clientCode(ctx.clientCode())
                .docType(docType)
                .contentType(ctx.contentType())
                .bytes(payload)
                .byteSize(payload.length)
                .build();
        return documentRepository.save(doc);
    }
}
