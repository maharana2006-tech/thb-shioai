package com.multiship.backend.service.carriers.usps.queue;

import com.multiship.backend.dto.PackageDetailDTO;
import com.multiship.backend.dto.ShipmentRequestDTO;
import com.multiship.backend.service.carriers.usps.queue.UspsLabelQueueService.EnqueueMpsRequest;
import com.multiship.backend.service.carriers.usps.queue.UspsLabelQueueService.EnqueueMpsResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PR-F2 - pure-Mockito tests for {@link UspsMpsSplitterService}. The
 * queue service is mocked, so these assertions pin the splitter's own
 * behaviour (piece-count validation, synthetic-shipmentId derivation,
 * exception propagation) without JPA.
 *
 * <p>Matrix:
 * <table><thead><tr><th>Input</th><th>Expectation</th></tr></thead>
 * <tbody>
 *   <tr><td>single-package DTO</td><td>IAE (splitter is MPS-only)</td></tr>
 *   <tr><td>3-package DTO</td><td>3 pieces, synthetic ids, seq 1..3</td></tr>
 *   <tr><td>1000-package DTO</td><td>1000 pieces, distinct synthetic ids</td></tr>
 *   <tr><td>queue.enqueueMps throws</td><td>propagates verbatim</td></tr>
 *   <tr><td>null DTO / blank tenant / null parent</td><td>IAE</td></tr>
 * </tbody></table>
 */
class UspsMpsSplitterServiceTest {

    private UspsLabelQueueService queue;
    private UspsMpsSplitterService splitter;

    @BeforeEach
    void setUp() {
        queue = mock(UspsLabelQueueService.class);
        splitter = new UspsMpsSplitterService(queue);
    }

    // ================================================================
    // Guard: single-package input rejected
    // ================================================================

    @Test
    void splitAndEnqueue_singlePackageDto_throwsIae() {
        ShipmentRequestDTO singlePkg = dtoWithPackageCount(1);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> splitter.splitAndEnqueue(singlePkg, "ACME", 12345L));
        assertTrue(ex.getMessage().contains("non-MPS"),
                "Guard message must name the MPS-only contract");
        verify(queue, never()).enqueueMps(any());
    }

    @Test
    void splitAndEnqueue_nullDto_throwsIae() {
        assertThrows(IllegalArgumentException.class,
                () -> splitter.splitAndEnqueue(null, "ACME", 12345L));
        verify(queue, never()).enqueueMps(any());
    }

    @Test
    void splitAndEnqueue_zeroPackageDto_synthesisesSingletonThenFailsGuard() {
        // A DTO with null packages + null top-level fields yields a
        // synthetic single-package list from effectivePackages() - which
        // is <2 - so the guard fires.
        ShipmentRequestDTO empty = ShipmentRequestDTO.builder()
                .carrierCode("USPS").accountNumber("acct-1").build();

        assertThrows(IllegalArgumentException.class,
                () -> splitter.splitAndEnqueue(empty, "ACME", 12345L));
        verify(queue, never()).enqueueMps(any());
    }

    // ================================================================
    // Happy path
    // ================================================================

    @Test
    void splitAndEnqueue_threePackageDto_enqueuesThreePieces() {
        ShipmentRequestDTO threePkg = dtoWithPackageCount(3);
        when(queue.enqueueMps(any())).thenReturn(new EnqueueMpsResult(
                12345L, 3,
                LocalDateTime.of(2026, 9, 16, 12, 0),
                LocalDateTime.of(2026, 9, 16, 12, 5)));

        EnqueueMpsResult result = splitter.splitAndEnqueue(threePkg, "ACME", 12345L);

        assertNotNull(result);
        assertEquals(12345L, result.parentOrderNo());
        assertEquals(3, result.enqueuedCount());

        ArgumentCaptor<EnqueueMpsRequest> captor = ArgumentCaptor.forClass(EnqueueMpsRequest.class);
        verify(queue, times(1)).enqueueMps(captor.capture());
        EnqueueMpsRequest req = captor.getValue();
        assertEquals("ACME", req.tenantCode());
        assertEquals(12345L, req.parentOrderNo());
        assertEquals(3, req.pieces().size());
        // Sequence numbers 1..3 in order.
        assertEquals(1, req.pieces().get(0).sequenceNumber());
        assertEquals(2, req.pieces().get(1).sequenceNumber());
        assertEquals(3, req.pieces().get(2).sequenceNumber());
        // Synthetic shipmentId scheme: negative, order+seq encoded.
        for (int i = 0; i < 3; i++) {
            int seq = i + 1;
            long expected = -(12345L * UspsMpsSplitterService.SYNTHETIC_SHIPMENT_ID_MULTIPLIER + seq);
            assertEquals(expected, req.pieces().get(i).shipmentId(),
                    "Piece " + seq + " must use synthetic negative shipmentId");
        }
        // All shipmentIds distinct (no collisions inside the batch).
        Set<Long> unique = new HashSet<>();
        for (EnqueueMpsRequest.PieceRequest p : req.pieces()) unique.add(p.shipmentId());
        assertEquals(3, unique.size(), "shipmentIds must be distinct across pieces");
    }

    @Test
    void splitAndEnqueue_thousandPackageDto_enqueuesThousandDistinctPieces() {
        ShipmentRequestDTO thousandPkg = dtoWithPackageCount(1000);
        when(queue.enqueueMps(any())).thenReturn(new EnqueueMpsResult(
                42L, 1000,
                LocalDateTime.now(), LocalDateTime.now().plusHours(18)));

        EnqueueMpsResult result = splitter.splitAndEnqueue(thousandPkg, "ACME", 42L);

        assertEquals(1000, result.enqueuedCount());
        ArgumentCaptor<EnqueueMpsRequest> captor = ArgumentCaptor.forClass(EnqueueMpsRequest.class);
        verify(queue, times(1)).enqueueMps(captor.capture());
        List<EnqueueMpsRequest.PieceRequest> pieces = captor.getValue().pieces();
        assertEquals(1000, pieces.size());
        // Distinctness across 1000 rows - fast enough with a HashSet.
        Set<Long> unique = new HashSet<>();
        for (EnqueueMpsRequest.PieceRequest p : pieces) unique.add(p.shipmentId());
        assertEquals(1000, unique.size(), "1000 pieces must have 1000 distinct shipmentIds");
        assertEquals(1, pieces.get(0).sequenceNumber());
        assertEquals(1000, pieces.get(999).sequenceNumber());
    }

    // ================================================================
    // Overload
    // ================================================================

    @Test
    void splitAndEnqueueForOrder_rejectsBadInputs() {
        // null parent
        assertThrows(IllegalArgumentException.class,
                () -> splitter.splitAndEnqueueForOrder(null, 3, "ACME"));
        // < 2 pieces
        assertThrows(IllegalArgumentException.class,
                () -> splitter.splitAndEnqueueForOrder(42L, 1, "ACME"));
        // blank tenant
        assertThrows(IllegalArgumentException.class,
                () -> splitter.splitAndEnqueueForOrder(42L, 3, "  "));
        assertThrows(IllegalArgumentException.class,
                () -> splitter.splitAndEnqueueForOrder(42L, 3, null));
        verify(queue, never()).enqueueMps(any());
    }

    @Test
    void splitAndEnqueueForOrder_delegatesToQueueWithSyntheticIds() {
        when(queue.enqueueMps(any())).thenReturn(new EnqueueMpsResult(
                999L, 5,
                LocalDateTime.of(2026, 9, 16, 10, 0),
                LocalDateTime.of(2026, 9, 16, 10, 6)));

        EnqueueMpsResult result = splitter.splitAndEnqueueForOrder(999L, 5, "BETA");

        assertEquals(5, result.enqueuedCount());
        ArgumentCaptor<EnqueueMpsRequest> captor = ArgumentCaptor.forClass(EnqueueMpsRequest.class);
        verify(queue, times(1)).enqueueMps(captor.capture());
        assertEquals("BETA", captor.getValue().tenantCode());
        assertEquals(999L, captor.getValue().parentOrderNo());
        assertEquals(5, captor.getValue().pieces().size());
    }

    // ================================================================
    // Exception propagation
    // ================================================================

    @Test
    void splitAndEnqueue_queueThrows_propagates() {
        ShipmentRequestDTO threePkg = dtoWithPackageCount(3);
        when(queue.enqueueMps(any()))
                .thenThrow(new IllegalStateException(
                        "MPS batch for parentOrderNo=12345 conflicts with an existing queue row"));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> splitter.splitAndEnqueue(threePkg, "ACME", 12345L));
        assertTrue(ex.getMessage().contains("12345"),
                "Underlying queue-service message must survive verbatim");
    }

    @Test
    void splitAndEnqueue_queueThrowsRuntime_propagates() {
        // Any RuntimeException from the queue service (e.g. transient DB
        // failure) must propagate - the transactional boundary on the
        // queue impl rolls back the persist; caller (BulkLabelServiceImpl)
        // catches + falls back to the sync path.
        ShipmentRequestDTO threePkg = dtoWithPackageCount(3);
        when(queue.enqueueMps(any()))
                .thenThrow(new RuntimeException("db down"));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> splitter.splitAndEnqueue(threePkg, "ACME", 12345L));
        assertTrue(ex.getMessage().contains("db down"));
    }

    // ================================================================
    // Synthetic-id helper
    // ================================================================

    @Test
    void syntheticShipmentIdFor_negativeAndOrderSeqEncoded() {
        long id = UspsMpsSplitterService.syntheticShipmentIdFor(12345L, 42);
        assertEquals(-(12345L * UspsMpsSplitterService.SYNTHETIC_SHIPMENT_ID_MULTIPLIER + 42), id);
        assertTrue(id < 0L, "synthetic id must be negative to avoid orderNo collisions");
    }

    // ================================================================
    // helpers
    // ================================================================

    /** Build a ShipmentRequestDTO carrying N packages. Only the count
     *  matters for the splitter; per-piece values are ignored. */
    private static ShipmentRequestDTO dtoWithPackageCount(int n) {
        List<PackageDetailDTO> pkgs = new ArrayList<>(n);
        for (int i = 1; i <= n; i++) {
            pkgs.add(PackageDetailDTO.builder()
                    .sequenceNumber(i)
                    .weight(BigDecimal.valueOf(1))
                    .build());
        }
        return ShipmentRequestDTO.builder()
                .carrierCode("USPS")
                .accountNumber("acct-1")
                .packages(pkgs)
                .build();
    }
}
