package com.multiship.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.OrderImportRowDTO;
import com.multiship.backend.dto.VoidLabelResponseDTO;
import com.multiship.backend.model.ImportBatch;
import com.multiship.backend.model.OrderTracking;
import com.multiship.backend.repository.ImportBatchRepository;
import com.multiship.backend.repository.OrderTrackingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Bulk Mailer's label rules for a batch: a batch whose labels can still ship
 * can't be deleted (no override), voiding makes it deletable, Empty Trash
 * keeps what still has live labels, the batch page shows a void made
 * anywhere, and a batch void reports each carrier's own answer.
 */
class BulkBatchLabelRulesTest {

    private final ObjectMapper json = new ObjectMapper();
    private ImportBatchRepository repo;
    private OrderTrackingRepository tracking;
    private VoidService voids;
    private OrderImportServiceImpl service;

    @BeforeEach
    void setUp() {
        repo = mock(ImportBatchRepository.class);
        tracking = mock(OrderTrackingRepository.class);
        voids = mock(VoidService.class);
        service = new OrderImportServiceImpl(mock(CarrierService.class));
        ReflectionTestUtils.setField(service, "importBatchRepository", repo);
        ReflectionTestUtils.setField(service, "importObjectMapper", json);
        ReflectionTestUtils.setField(service, "orderTrackingRepository", tracking);
        ReflectionTestUtils.setField(service, "voidService", voids);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(tracking.findByOrderNoIn(anyCollection())).thenReturn(List.of());
    }

    private static OrderImportRowDTO row(int n, Integer orderNo, String status) {
        return OrderImportRowDTO.builder().rowNumber(n).orderRef("PO-" + n).clientCode("ACME")
                .generatedOrderNo(orderNo).generatedStatus(status).build();
    }

    private ImportBatch batch(long id, String status, List<OrderImportRowDTO> rows) throws Exception {
        ImportBatch b = new ImportBatch();
        b.setId(id);
        b.setStatus(status);
        b.setFileName("acme.csv");
        b.setRowsJson(json.writeValueAsString(rows));
        when(repo.findById(id)).thenReturn(Optional.of(b));
        return b;
    }

    private static OrderTracking voided(int orderNo) {
        OrderTracking t = new OrderTracking();
        t.setOrderNo(orderNo);
        t.setStatus("VOIDED");
        return t;
    }

    @Test
    void aBatchWithLiveLabelsCantBeDeleted() throws Exception {
        batch(1, "COMPLETE", List.of(row(1, 5001, "GENERATED"), row(2, 5001, "GENERATED"), row(3, 5002, "GENERATED")));
        var ex = assertThrows(OrderImportServiceImpl.ImportBatchStateException.class,
                () -> service.softDeleteBatch(1L, "alice"));
        assertEquals(409, ex.getStatus());
        assertTrue(ex.getMessage().contains("2 live labels"), ex.getMessage());
        verify(repo, never()).save(any());
    }

    @Test
    void onceEveryLabelIsVoidedTheBatchCanBeDeleted() throws Exception {
        ImportBatch b = batch(2, "COMPLETE", List.of(row(1, 5001, "GENERATED"), row(2, 5002, "GENERATED")));
        when(tracking.findByOrderNoIn(anyCollection())).thenReturn(List.of(voided(5001), voided(5002)));
        service.softDeleteBatch(2L, "alice");
        assertNotNull(b.getDeletedAt());
    }

    @Test
    void aBatchWithNothingLabelledCanBeDeleted() throws Exception {
        ImportBatch b = batch(3, "INITIATE", List.of(row(1, null, null), row(2, 5009, "FAILED")));
        service.softDeleteBatch(3L, "alice");
        assertNotNull(b.getDeletedAt());
    }

    @Test
    void aQueuedUspsLabelCountsAsLive() throws Exception {
        batch(4, "IN_PROGRESS_DONE", List.of(row(1, 6001, "QUEUED_USPS")));
        assertThrows(OrderImportServiceImpl.ImportBatchStateException.class, () -> service.softDeleteBatch(4L, "alice"));
    }

    @Test
    void emptyTrashKeepsBatchesThatStillHaveLiveLabels() throws Exception {
        ImportBatch live = batch(10, "COMPLETE", List.of(row(1, 7001, "GENERATED")));
        ImportBatch done = batch(11, "COMPLETE", List.of(row(1, 7002, "GENERATED")));
        ImportBatch empty = batch(12, "INITIATE", List.of(row(1, null, null)));
        for (ImportBatch b : List.of(live, done, empty)) b.setDeletedAt(LocalDateTime.now());
        when(repo.findAllByDeletedAtIsNotNullOrderByIdDesc()).thenReturn(List.of(live, done, empty));
        when(tracking.findByOrderNoIn(anyCollection())).thenAnswer(inv -> {
            java.util.Collection<?> nos = inv.getArgument(0);
            return nos.contains(7002) ? List.of(voided(7002)) : List.of();
        });

        var result = service.purgeTrashChecked("alice");

        assertEquals(2, result.purged());
        assertEquals(1, result.keptWithLiveLabels());
        verify(repo).deleteAll(List.of(done, empty));
    }

    @Test
    void theBatchPageShowsAVoidMadeFromAnywhere() throws Exception {
        batch(20, "COMPLETE", List.of(row(1, 8001, "GENERATED"), row(2, 8002, "GENERATED")));
        when(tracking.findByOrderNoIn(anyCollection())).thenReturn(List.of(voided(8002)));
        var rows = service.historyDetail(20L).getRows();
        assertEquals("GENERATED", rows.get(0).getGeneratedStatus());
        assertEquals("VOIDED", rows.get(1).getGeneratedStatus());
    }

    @Test
    void aBatchVoidReportsEachOrderAndCountsARefusalAsARefusal() throws Exception {
        batch(30, "COMPLETE", List.of(row(1, 9001, "GENERATED"), row(2, 9001, "GENERATED"),
                row(3, 9002, "GENERATED"), row(4, 9003, "GENERATED"), row(5, null, "FAILED")));
        when(tracking.findByOrderNoIn(anyCollection())).thenReturn(List.of(voided(9003)));   // already voided
        when(voids.voidLabel(9001)).thenReturn(ApiResponse.<VoidLabelResponseDTO>builder()
                .data(VoidLabelResponseDTO.builder().orderNo(9001).voided(true).message("Voided with UPS.").build()).build());
        when(voids.voidLabel(9002)).thenReturn(ApiResponse.<VoidLabelResponseDTO>builder()
                .data(VoidLabelResponseDTO.builder().orderNo(9002).voided(false).message("FedEx: already picked up.").build()).build());

        var r = service.voidBatchLabels(30L, List.of());

        assertEquals(1, r.voided());
        assertEquals(1, r.refused());
        assertEquals(2, r.orders().size(), "9003 was already voided; rows 1 and 2 are one order");
        assertEquals(List.of(1, 2), r.orders().get(0).rowNumbers());
        assertFalse(r.orders().get(1).voided());
        assertEquals("FedEx: already picked up.", r.orders().get(1).message());
        verify(voids, times(1)).voidLabel(9001);
    }

    @Test
    void voidingChosenRowsOnlyTouchesTheirOrders() throws Exception {
        batch(31, "COMPLETE", List.of(row(1, 9101, "GENERATED"), row(2, 9102, "GENERATED")));
        when(voids.voidLabel(9102)).thenReturn(ApiResponse.<VoidLabelResponseDTO>builder()
                .data(VoidLabelResponseDTO.builder().orderNo(9102).voided(true).build()).build());
        var r = service.voidBatchLabels(31L, List.of(2));
        assertEquals(1, r.voided());
        verify(voids, never()).voidLabel(9101);
    }

    @Test
    void noVoidWhileTheBatchIsGenerating() throws Exception {
        batch(32, "IN_PROGRESS", List.of(row(1, 9201, "GENERATED")));
        assertEquals(409, assertThrows(OrderImportServiceImpl.ImportBatchStateException.class,
                () -> service.voidBatchLabels(32L, List.of())).getStatus());
    }
}
