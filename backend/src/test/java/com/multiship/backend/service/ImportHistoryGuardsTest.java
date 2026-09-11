package com.multiship.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.dto.OrderImportRowDTO;
import com.multiship.backend.model.ImportBatch;
import com.multiship.backend.repository.ImportBatchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Import history: actions the import's state doesn't allow are refused with a clear reason. */
class ImportHistoryGuardsTest {

    private final ObjectMapper json = new ObjectMapper();
    private CarrierService carrierService;
    private ImportBatchRepository repo;
    private OrderImportServiceImpl service;

    @BeforeEach
    void setUp() {
        carrierService = mock(CarrierService.class);
        repo = mock(ImportBatchRepository.class);
        service = new OrderImportServiceImpl(carrierService);
        ReflectionTestUtils.setField(service, "importBatchRepository", repo);
        ReflectionTestUtils.setField(service, "importObjectMapper", json);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static OrderImportRowDTO row(int n, String ref) {
        return OrderImportRowDTO.builder().rowNumber(n).orderRef(ref).clientCode("ACME")
                .recipientName("Jane").recipientPhone("2125550100").addressLine1("1 Broadway").city("New York")
                .state("NY").postalCode("10001").countryCode("US").carrierCode("UPS").accountNumber("A12345")
                .weight(new BigDecimal("2")).weightUnit("LB").build();
    }

    private ImportBatch batch(long id, String status, List<OrderImportRowDTO> rows) throws Exception {
        ImportBatch b = new ImportBatch();
        b.setId(id);
        b.setStatus(status);
        b.setFileName("f.csv");
        b.setRowsJson(json.writeValueAsString(rows));
        when(repo.findById(id)).thenReturn(Optional.of(b));
        return b;
    }

    private int status(Runnable call) {
        return assertThrows(OrderImportServiceImpl.ImportBatchStateException.class, call::run).getStatus();
    }

    @Test
    void noEditsOrSingleRowLabelsWhileAnImportIsGenerating() throws Exception {
        batch(1, "IN_PROGRESS", List.of(row(1, "A")));
        assertEquals(409, status(() -> service.updateBatchRow(1L, 1, row(1, "A"), "alice")));
        assertEquals(409, status(() -> service.generateLabelForRow(1L, 1, "alice", false)));
        assertEquals(409, status(() -> service.setBillingMode(1L, "PLATFORM", "alice")));
        assertEquals(409, status(() -> service.softDeleteBatch(1L, "alice")));
        verify(carrierService, never()).generateManualLabel(any(), any(), any());
    }

    @Test
    void anImportInTrashCantBeEditedOrGenerated() throws Exception {
        ImportBatch b = batch(2, "INITIATE", List.of(row(1, "A")));
        b.setDeletedAt(LocalDateTime.now());
        assertEquals(409, status(() -> service.updateBatchRow(2L, 1, row(1, "A"), "alice")));
        assertEquals(409, status(() -> service.generateLabelForRow(2L, 1, "alice", false)));
        assertEquals(409, status(() -> service.generateLabelsForBatch(2L, "alice", false, false, false)));
    }

    @Test
    void labelledOrMissingRowsAreRefusedNotSilentlyIgnored() throws Exception {
        OrderImportRowDTO done = row(1, "A");
        done.setGeneratedStatus("GENERATED");
        done.setGeneratedOrderNo(900001);
        batch(3, "COMPLETE", List.of(done));
        OrderImportServiceImpl.ImportBatchStateException e = assertThrows(
                OrderImportServiceImpl.ImportBatchStateException.class, () -> service.updateBatchRow(3L, 1, row(1, "A"), "alice"));
        assertEquals(409, e.getStatus());
        assertTrue(e.getMessage().contains("order #900001"), e.getMessage());
        assertEquals(404, status(() -> service.updateBatchRow(3L, 99, row(99, "Z"), "alice")));
    }

    @Test
    void generateNeverResendsLabelledRows_andSaysWhenThereIsNothingToDo() throws Exception {
        OrderImportRowDTO done = row(1, "A");
        done.setGeneratedStatus("GENERATED");
        done.setGeneratedOrderNo(900001);
        OrderImportRowDTO broken = row(2, "B");
        broken.setErrors(new ArrayList<>(List.of("postalCode is required")));
        batch(4, "PARTIAL_COMPLETE", List.of(done, broken));
        OrderImportServiceImpl.ImportBatchStateException e = assertThrows(OrderImportServiceImpl.ImportBatchStateException.class,
                () -> service.generateLabelsForBatch(4L, "alice", false, false, false));
        assertEquals(422, e.getStatus());
        assertTrue(e.getMessage().contains("1 order needs fixes"), e.getMessage());

        OrderImportRowDTO done2 = row(1, "A");
        done2.setGeneratedStatus("GENERATED");
        done2.setGeneratedOrderNo(900002);
        batch(5, "COMPLETE", List.of(done2));
        e = assertThrows(OrderImportServiceImpl.ImportBatchStateException.class,
                () -> service.generateLabelsForBatch(5L, "alice", false, false, false));
        assertTrue(e.getMessage().contains("already labelled"), e.getMessage());
        verify(carrierService, never()).generateManualLabel(any(), any(), any());
    }

    @Test
    void singleRowGenerateNamesTheFixesAnOrderNeeds() throws Exception {
        OrderImportRowDTO broken = row(1, "B");
        broken.setErrors(new ArrayList<>(List.of("postalCode is required")));
        batch(6, "DRAFT", List.of(broken));
        OrderImportServiceImpl.ImportBatchStateException e = assertThrows(OrderImportServiceImpl.ImportBatchStateException.class,
                () -> service.generateLabelForRow(6L, 1, "alice", false));
        assertEquals(422, e.getStatus());
        assertTrue(e.getMessage().contains("Order B needs fixes") && e.getMessage().contains("postalCode is required"), e.getMessage());
    }

    @Test
    void billingModeMustBeAKnownValue() throws Exception {
        batch(7, "INITIATE", List.of(row(1, "A")));
        assertEquals(400, status(() -> service.setBillingMode(7L, "SOMETHING", "alice")));
        assertEquals("PLATFORM", service.setBillingMode(7L, "platform", "alice").getBillingMode());
    }

    @Test
    void restoringIsRefusedWhileIdenticalContentIsLive() throws Exception {
        ImportBatch trashed = batch(8, "INITIATE", List.of(row(1, "A")));
        trashed.setDeletedAt(LocalDateTime.now());
        trashed.setContentHash("h1");
        ImportBatch live = new ImportBatch();
        live.setId(9L);
        live.setFileName("f.csv");
        when(repo.findFirstByContentHashAndDeletedAtIsNullOrderByIdDesc("h1")).thenReturn(Optional.of(live));
        OrderImportServiceImpl.ImportBatchStateException e = assertThrows(
                OrderImportServiceImpl.ImportBatchStateException.class, () -> service.restoreBatch(8L));
        assertEquals(409, e.getStatus());
        assertTrue(e.getMessage().contains("#9"), e.getMessage());

        when(repo.findFirstByContentHashAndDeletedAtIsNullOrderByIdDesc(any())).thenReturn(Optional.empty());
        service.restoreBatch(8L);
        assertEquals(null, trashed.getDeletedAt(), "restores once nothing live duplicates it");
        verify(repo, never()).deleteById(anyLong());
    }

    @Test
    void apiRowEditsMergeOntoTheStoredRow_andBadBodiesAre400Not500() throws Exception {
        batch(10, "INITIATE", List.of(row(1, "A"), row(2, "C")));
        assertEquals(404, status(() -> service.updateBatchRowJson(10L, 999, "{}", "alice")));
        assertEquals(404, status(() -> service.updateBatchRowJson(10L, 999, null, "alice")));
        assertEquals(400, status(() -> service.updateBatchRowJson(10L, 1, null, "alice")));
        assertEquals(400, status(() -> service.updateBatchRowJson(10L, 1, "{not json", "alice")));

        OrderImportRowDTO edited = service.updateBatchRowJson(10L, 1, "{\"city\":\"Brooklyn\"}", "alice").getRows().get(0);
        assertEquals("Brooklyn", edited.getCity());
        assertEquals("1 Broadway", edited.getAddressLine1(), "fields the body doesn't name are kept");
        assertEquals(1, edited.getRowNumber());
    }

    @Test
    void aCleanLineOfAnOrderThatNeedsFixesIsNotCountedReady() {
        OrderImportRowDTO b1 = row(1, "B");
        b1.setErrors(new ArrayList<>(List.of("hsCode needs at least 9 digits")));
        Integer ready = ReflectionTestUtils.invokeMethod(OrderImportServiceImpl.class, "readyRowCount",
                List.of(b1, row(2, "B"), row(3, "C")));
        assertEquals(1, ready);
    }
}
