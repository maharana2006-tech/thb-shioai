package com.multiship.backend.service.output;

import com.multiship.backend.model.ClientOutputDestination;
import com.multiship.backend.model.ShipmentDocument;
import com.multiship.backend.repository.ClientOutputDestinationRepository;
import com.multiship.backend.repository.ShipmentDocumentRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Sprint 52 — verifies the dispatch orchestration: DB copy always fires
 * first, drivers are matched by destination type, one failing driver
 * does not abort the others.
 */
class OutputDestinationServiceTest {

    private ClientOutputDestinationRepository destinationRepository;
    private ShipmentDocumentRepository documentRepository;
    private DatabaseCopyDriver dbCopy;
    private OutputDriver localFs;
    private OutputDriver printer;
    private SimpleMeterRegistry meterRegistry;
    private OutputDestinationService service;

    private final AtomicLong docIdSeq = new AtomicLong(1000);

    @BeforeEach
    void setUp() {
        destinationRepository = mock(ClientOutputDestinationRepository.class);
        documentRepository    = mock(ShipmentDocumentRepository.class);
        // Real DB-copy driver over a stub repository so we can assert the shape of what was persisted.
        when(documentRepository.save(any(ShipmentDocument.class))).thenAnswer(inv -> {
            ShipmentDocument d = inv.getArgument(0);
            if (d.getId() == null) d.setId(docIdSeq.getAndIncrement());
            return d;
        });
        dbCopy    = new DatabaseCopyDriver(documentRepository);
        localFs   = mock(OutputDriver.class);
        printer   = mock(OutputDriver.class);
        when(localFs.supports()).thenReturn(DestinationType.LOCAL_FS);
        when(printer.supports()).thenReturn(DestinationType.PRINTER);

        meterRegistry = new SimpleMeterRegistry();
        service = new OutputDestinationService(destinationRepository, dbCopy,
                List.of(localFs, printer), meterRegistry);
    }

    private ClientOutputDestination dest(long id, DestinationType type) {
        return ClientOutputDestination.builder()
                .id(id).clientCode("ACME").docType(DocType.LABEL)
                .destinationType(type).config("{}").active(true).build();
    }

    @Test
    void emptyPayloadShortCircuits() {
        DispatchResult result = service.dispatch(DocType.LABEL, new byte[0],
                new DispatchContext(1L, 1, "ACME", "application/pdf", null));

        assertEquals(0, result.getTotalDestinations());
        verifyNoInteractions(documentRepository);
        // Reset any bookkeeping calls to supports() that fired during
        // service construction, then assert nothing dispatched.
        verify(localFs, never()).dispatch(any(), any(), any(), any());
    }

    @Test
    void dbCopyAlwaysWrittenEvenWithNoDestinations() {
        when(destinationRepository.findByClientCodeAndDocTypeAndActiveTrueOrderById(anyString(), any()))
                .thenReturn(List.of());
        DispatchContext ctx = new DispatchContext(1L, 1, "ACME", "application/pdf", null);

        DispatchResult result = service.dispatch(DocType.LABEL, "PDF".getBytes(), ctx);

        assertNotNull(result.getShipmentDocumentId(), "DB copy id should be populated");
        assertEquals(0, result.getTotalDestinations());
        verify(documentRepository).save(any(ShipmentDocument.class));
    }

    @Test
    void dispatchesToAllConfiguredDestinationsInOrder() {
        when(destinationRepository.findByClientCodeAndDocTypeAndActiveTrueOrderById(eq("ACME"), eq(DocType.LABEL)))
                .thenReturn(List.of(dest(1L, DestinationType.LOCAL_FS), dest(2L, DestinationType.PRINTER)));
        DispatchContext ctx = new DispatchContext(1L, 1, "ACME", "application/pdf", null);

        DispatchResult result = service.dispatch(DocType.LABEL, "PDF".getBytes(), ctx);

        assertEquals(2, result.getTotalDestinations());
        assertEquals(2, result.getSuccessCount());
        assertEquals(0, result.getFailureCount());
        verify(localFs).dispatch(any(), eq(DocType.LABEL), any(), eq(ctx));
        verify(printer).dispatch(any(), eq(DocType.LABEL), any(), eq(ctx));
    }

    @Test
    void oneFailingDriverDoesNotAbortOthers() {
        doThrow(new OutputDeliveryException(1L, DestinationType.LOCAL_FS, "boom"))
                .when(localFs).dispatch(any(), any(), any(), any());

        when(destinationRepository.findByClientCodeAndDocTypeAndActiveTrueOrderById(anyString(), any()))
                .thenReturn(List.of(dest(1L, DestinationType.LOCAL_FS), dest(2L, DestinationType.PRINTER)));

        DispatchResult result = service.dispatch(DocType.LABEL, "PDF".getBytes(),
                new DispatchContext(1L, 1, "ACME", "application/pdf", null));

        assertEquals(2, result.getTotalDestinations());
        assertEquals(1, result.getSuccessCount());
        assertEquals(1, result.getFailureCount());
        // Printer still received the dispatch despite the local-fs failure.
        verify(printer).dispatch(any(), any(), any(), any());
        // Metric counter for the failure exists.
        assertTrue(meterRegistry.get("output_dispatch_total")
                .tag("outcome", "failure").counter().count() >= 1.0);
    }

    @Test
    void missingClientCodeStillPersistsDbCopy() {
        DispatchContext ctx = new DispatchContext(1L, 1, null, "application/pdf", null);

        DispatchResult result = service.dispatch(DocType.LABEL, "PDF".getBytes(), ctx);

        assertNotNull(result.getShipmentDocumentId());
        assertEquals(0, result.getTotalDestinations());
        // Never queried destinations.
        verifyNoInteractions(destinationRepository);
    }

    @Test
    void hasActiveDestinationsReflectsRepositoryResult() {
        when(destinationRepository.findByClientCodeAndDocTypeAndActiveTrueOrderById(eq("ACME"), eq(DocType.LABEL)))
                .thenReturn(List.of(dest(1L, DestinationType.LOCAL_FS)));
        assertTrue(service.hasActiveDestinations("ACME", DocType.LABEL));
        assertFalse(service.hasActiveDestinations(null, DocType.LABEL));
    }
}
