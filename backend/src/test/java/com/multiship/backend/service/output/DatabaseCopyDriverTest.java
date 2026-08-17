package com.multiship.backend.service.output;

import com.multiship.backend.model.ShipmentDocument;
import com.multiship.backend.repository.ShipmentDocumentRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Sprint 52 — asserts that the always-on DB copy captures the doc-type,
 * byte-size and shipment/order linkage.
 */
class DatabaseCopyDriverTest {

    @Test
    void persistWritesShipmentDocumentWithByteSize() {
        ShipmentDocumentRepository repo = mock(ShipmentDocumentRepository.class);
        when(repo.save(any(ShipmentDocument.class))).thenAnswer(inv -> inv.getArgument(0));
        DatabaseCopyDriver driver = new DatabaseCopyDriver(repo);

        DispatchContext ctx = new DispatchContext(42L, 100, "ACME", "application/pdf", null);
        byte[] payload = "PDF-BYTES".getBytes();

        ShipmentDocument saved = driver.persist(DocType.LABEL, payload, ctx);

        ArgumentCaptor<ShipmentDocument> cap = ArgumentCaptor.forClass(ShipmentDocument.class);
        verify(repo).save(cap.capture());
        ShipmentDocument written = cap.getValue();
        assertEquals(DocType.LABEL, written.getDocType());
        assertEquals(42L, written.getShipmentId());
        assertEquals(100, written.getOrderNo());
        assertEquals("ACME", written.getClientCode());
        assertEquals(payload.length, written.getByteSize());
        assertNotNull(saved);
    }

    @Test
    void persistSkipsEmptyPayload() {
        ShipmentDocumentRepository repo = mock(ShipmentDocumentRepository.class);
        DatabaseCopyDriver driver = new DatabaseCopyDriver(repo);

        ShipmentDocument saved = driver.persist(DocType.LABEL, new byte[0],
                new DispatchContext(1L, 1, "ACME", null, null));

        assertNull(saved);
        verifyNoInteractions(repo);
    }

    @Test
    void persistHandlesNullShipmentId() {
        ShipmentDocumentRepository repo = mock(ShipmentDocumentRepository.class);
        when(repo.save(any(ShipmentDocument.class))).thenAnswer(inv -> inv.getArgument(0));
        DatabaseCopyDriver driver = new DatabaseCopyDriver(repo);

        ShipmentDocument saved = driver.persist(DocType.COMMERCIAL_INVOICE, "x".getBytes(),
                new DispatchContext(null, 999, "ACME", null, null));

        ArgumentCaptor<ShipmentDocument> cap = ArgumentCaptor.forClass(ShipmentDocument.class);
        verify(repo).save(cap.capture());
        assertEquals(0L, cap.getValue().getShipmentId(), "null shipmentId falls back to 0");
        assertNotNull(saved);
    }
}
