package com.multiship.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.OrderImportRowDTO;
import com.multiship.backend.dto.StagingUploadDTO;
import com.multiship.backend.model.ImportBatch;
import com.multiship.backend.model.ImportStagingRow;
import com.multiship.backend.model.ImportStagingUpload;
import com.multiship.backend.repository.ImportBatchRepository;
import com.multiship.backend.repository.ImportStagingRowRepository;
import com.multiship.backend.repository.ImportStagingUploadRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Bulk-upload restructure: upload → staging → validate → Save writes only
 * fully valid orders to Import history; invalid orders stay staged and can be
 * downloaded, fixed and saved later.
 */
class OrderImportStagingTest {

    private OrderImportServiceImpl service;
    private final Map<Long, ImportStagingUpload> uploads = new HashMap<>();
    private final List<ImportStagingRow> stagedRows = new ArrayList<>();
    private final List<ImportBatch> savedBatches = new ArrayList<>();
    private ImportBatchRepository batchRepo;

    @BeforeEach
    void setUp() {
        service = new OrderImportServiceImpl(mock(CarrierService.class));
        AtomicLong upSeq = new AtomicLong(), rowSeq = new AtomicLong(), batchSeq = new AtomicLong(76);

        ImportStagingUploadRepository upRepo = mock(ImportStagingUploadRepository.class);
        when(upRepo.save(any())).thenAnswer(inv -> {
            ImportStagingUpload u = inv.getArgument(0);
            if (u.getId() == null) u.setId(upSeq.incrementAndGet());
            uploads.put(u.getId(), u);
            return u;
        });
        when(upRepo.findById(anyLong())).thenAnswer(inv -> Optional.ofNullable(uploads.get((Long) inv.getArgument(0))));
        when(upRepo.findFirstByContentHashAndStatusOrderByIdDesc(any(), any())).thenAnswer(inv -> uploads.values().stream()
                .filter(u -> java.util.Objects.equals(u.getContentHash(), inv.getArgument(0)) && inv.getArgument(1).equals(u.getStatus()))
                .max(Comparator.comparingLong(ImportStagingUpload::getId)));
        when(upRepo.findByCreatedByIgnoreCaseAndStatusOrderByIdDesc(any(), any())).thenAnswer(inv -> uploads.values().stream()
                .filter(u -> ((String) inv.getArgument(0)).equalsIgnoreCase(u.getCreatedBy()) && inv.getArgument(1).equals(u.getStatus()))
                .sorted(Comparator.comparingLong(ImportStagingUpload::getId).reversed()).toList());

        ImportStagingRowRepository rowRepo = mock(ImportStagingRowRepository.class);
        when(rowRepo.saveAll(anyIterable())).thenAnswer(inv -> {
            Iterable<ImportStagingRow> it = inv.getArgument(0);
            List<ImportStagingRow> out = new ArrayList<>();
            for (ImportStagingRow r : it) {
                if (r.getId() == null) { r.setId(rowSeq.incrementAndGet()); stagedRows.add(r); }
                out.add(r);
            }
            return out;
        });
        when(rowRepo.findByUploadIdOrderByRowNoAsc(anyLong())).thenAnswer(inv -> stagedRows.stream()
                .filter(r -> r.getUploadId().equals(inv.getArgument(0)))
                .sorted(Comparator.comparingInt(ImportStagingRow::getRowNo)).toList());

        batchRepo = mock(ImportBatchRepository.class);
        when(batchRepo.findFirstByFileNameIgnoreCaseAndDeletedAtIsNullOrderByIdDesc(any())).thenAnswer(inv -> savedBatches.stream()
                .filter(b -> b.getFileName() != null && b.getFileName().equalsIgnoreCase(inv.getArgument(0)))
                .reduce((first, second) -> second));
        when(batchRepo.save(any())).thenAnswer(inv -> {
            ImportBatch b = inv.getArgument(0);
            b.setId(batchSeq.incrementAndGet());
            savedBatches.add(b);
            return b;
        });

        ReflectionTestUtils.setField(service, "stagingUploadRepository", upRepo);
        ReflectionTestUtils.setField(service, "stagingRowRepository", rowRepo);
        ReflectionTestUtils.setField(service, "importBatchRepository", batchRepo);
        ReflectionTestUtils.setField(service, "importObjectMapper", new ObjectMapper());
    }

    private static Map<String, String> order(String ref, String postalCode) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("orderRef", ref); m.put("clientCode", "ACME");
        m.put("recipientName", "Jane " + ref); m.put("recipientPhone", "2125550100");
        m.put("addressLine1", "1 Broadway"); m.put("city", "New York"); m.put("state", "NY");
        m.put("postalCode", postalCode); m.put("countryCode", "US");
        m.put("carrierCode", "UPS"); m.put("accountNumber", "A12345");
        m.put("weight", "2.5"); m.put("weightUnit", "LB");
        m.put("itemDescription", "Mug"); m.put("itemQuantity", "1");
        return m;
    }

    private static String csv(List<Map<String, String>> lines) {
        StringBuilder sb = new StringBuilder(String.join(",", OrderImportServiceImpl.HEADERS)).append('\n');
        for (Map<String, String> v : lines) {
            sb.append(OrderImportServiceImpl.HEADERS.stream().map(h -> v.getOrDefault(h, "")).collect(Collectors.joining(","))).append('\n');
        }
        return sb.toString();
    }

    private ApiResponse<StagingUploadDTO> stage(String name, String content) {
        return service.stageUpload(name, new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), false, "alice");
    }

    @Test
    void onlyFullyValidOrdersReachImportHistory_andErrorsCanBeFixedAndSavedLater() throws Exception {
        Map<String, String> itemLine = new LinkedHashMap<>();
        itemLine.put("orderRef", "A-1"); itemLine.put("itemDescription", "Cap"); itemLine.put("itemQuantity", "2");
        String file = csv(List.of(order("A-1", "10001"), itemLine, order("B-1", ""), order("C-1", "10001")));

        // 1. upload → staging; nothing saved yet
        ApiResponse<StagingUploadDTO> staged = stage("orders.csv", file);
        assertEquals("success", staged.getStatus(), staged.getMessage());
        StagingUploadDTO s = staged.getData();
        assertEquals(4, s.getTotalRows());
        assertEquals(3, s.getTotalOrders());
        assertEquals(2, s.getValidOrders());
        assertEquals(1, s.getInvalidOrders());
        assertEquals(2, s.getReadyOrders());
        assertTrue(savedBatches.isEmpty(), "upload must not write Import history");

        // 2. error file: only order B-1, with its reason, in template columns + errors
        OrderImportService.StagingErrorFile errCsv = service.stagingErrorFile(s.getId(), "csv", "alice");
        assertNotNull(errCsv);
        String text = new String(errCsv.bytes(), StandardCharsets.UTF_8);
        List<String> lines = text.lines().toList();
        assertTrue(lines.get(0).endsWith(",errors"), lines.get(0));
        assertEquals(2, lines.size(), "header + the one invalid order: " + text);
        assertTrue(lines.get(1).startsWith("B-1,"), lines.get(1));
        assertTrue(lines.get(1).contains("postalCode is required"), lines.get(1));
        OrderImportService.StagingErrorFile errXlsx = service.stagingErrorFile(s.getId(), "xlsx", "alice");
        assertEquals('P', (char) errXlsx.bytes()[0]);
        assertEquals('K', (char) errXlsx.bytes()[1]);

        // 3. Save → only A-1 (both rows) and C-1
        ApiResponse<StagingUploadDTO> saved = service.saveStaging(s.getId(), "alice");
        assertEquals("success", saved.getStatus(), saved.getMessage());
        assertEquals(1, savedBatches.size());
        String json = savedBatches.get(0).getRowsJson();
        assertTrue(json.contains("\"A-1\"") && json.contains("\"C-1\""), json);
        assertFalse(json.contains("\"B-1\""), "an invalid order must not reach Import history");
        assertEquals(2, saved.getData().getSavedOrders());
        assertEquals(0, saved.getData().getReadyOrders());
        assertEquals("OPEN", saved.getData().getStatus());

        // Saving again with nothing new is refused; saved rows are read-only.
        assertEquals(422, service.saveStaging(s.getId(), "alice").getCode());
        int savedRow = saved.getData().getSavedRowNumbers().get(0);
        assertEquals(409, service.updateStagingRow(s.getId(), savedRow, new OrderImportRowDTO(), "alice").getCode());

        // 4. fix B-1 in place → Save writes just B-1 as a second batch
        OrderImportRowDTO b = saved.getData().getRows().stream().filter(r -> "B-1".equals(r.getOrderRef())).findFirst().orElseThrow();
        b.setPostalCode("10001");
        ApiResponse<StagingUploadDTO> fixed = service.updateStagingRow(s.getId(), b.getRowNumber(), b, "alice");
        assertEquals(1, fixed.getData().getReadyOrders(), fixed.getMessage());
        assertNull(service.stagingErrorFile(s.getId(), "csv", "alice"), "no errors left to download");
        ApiResponse<StagingUploadDTO> second = service.saveStaging(s.getId(), "alice");
        assertEquals("success", second.getStatus(), second.getMessage());
        assertEquals(2, savedBatches.size());
        assertTrue(savedBatches.get(1).getRowsJson().contains("\"B-1\""));
        assertFalse(savedBatches.get(1).getRowsJson().contains("\"A-1\""), "already-saved orders are not saved twice");
        assertEquals("SAVED", second.getData().getStatus());
    }

    @Test
    void reUploadingAWaitingFileOffersToContinueIt_evenAfterAPartialSave() {
        String file = csv(List.of(order("A-1", "10001"), order("B-1", "")));
        StagingUploadDTO s = stage("orders.csv", file).getData();
        assertEquals("success", service.saveStaging(s.getId(), "alice").getStatus());   // A-1 now in Import history

        ApiResponse<StagingUploadDTO> again = stage("orders.csv", file);
        assertEquals(409, again.getCode(), again.getMessage());
        StagingUploadDTO waiting = again.getData();
        assertNotNull(waiting, "the 409 must carry the waiting upload so the UI can offer Continue");
        assertEquals(s.getId(), waiting.getId());
        assertEquals(2, waiting.getTotalRows());
        assertEquals(1, waiting.getInvalidOrders());
        assertEquals(0, waiting.getReadyOrders());
        assertTrue(again.getMessage().contains("Continue"), again.getMessage());
        assertEquals(List.of(s.getId()), service.listStaging("alice").stream().map(StagingUploadDTO::getId).toList());
        assertTrue(service.listStaging("bob").isEmpty());

        // Upload anyway → a separate upload.
        ApiResponse<StagingUploadDTO> anyway = service.stageUpload("orders.csv",
                new ByteArrayInputStream(file.getBytes(StandardCharsets.UTF_8)), true, "alice");
        assertEquals("success", anyway.getStatus());
        assertTrue(anyway.getData().getId() > s.getId());
    }

    @Test
    void proceedWithErrorsSavesEveryOrderAsADraftInImportHistory() {
        StagingUploadDTO s = stage("orders.csv", csv(List.of(order("A-1", "10001"), order("B-1", "")))).getData();

        ApiResponse<StagingUploadDTO> res = service.saveStaging(s.getId(), "alice", true);
        assertEquals("success", res.getStatus(), res.getMessage());
        assertTrue(res.getMessage().contains("including 1 with errors"), res.getMessage());
        assertEquals(1, savedBatches.size());
        ImportBatch batch = savedBatches.get(0);
        assertEquals("DRAFT", batch.getStatus(), "a batch carrying errors is parked as a Draft");
        assertTrue(batch.getRowsJson().contains("\"A-1\"") && batch.getRowsJson().contains("\"B-1\""));
        assertEquals("SAVED", res.getData().getStatus());
        assertEquals(0, res.getData().getInvalidOrders());
        assertTrue(service.listStaging("alice").isEmpty(), "a fully saved upload is no longer waiting");
    }

    @Test
    void theErrorFileCanBeUploadedAgain_itsErrorsColumnIsNotACustomField() {
        StagingUploadDTO s = stage("orders.csv", csv(List.of(order("B-1", "")))).getData();
        String errors = new String(service.stagingErrorFile(s.getId(), "csv", "alice").bytes(), StandardCharsets.UTF_8);

        // Uploaded again unchanged, it is the same orders as the waiting upload: offer Continue.
        ApiResponse<StagingUploadDTO> unchanged = stage("orders-errors.csv", errors);
        assertEquals(409, unchanged.getCode(), unchanged.getMessage());
        assertEquals(s.getId(), unchanged.getData().getId());

        // Fixed in the spreadsheet (postal code filled, errors column left as is) it validates cleanly.
        String fixed = errors.replace(",New York,NY,,US,", ",New York,NY,10001,US,");
        assertTrue(!fixed.equals(errors), "fixture: the postal code cell must have been filled");
        ApiResponse<StagingUploadDTO> again = stage("orders-errors.csv", fixed);
        assertEquals("success", again.getStatus(), again.getMessage());
        OrderImportRowDTO row = again.getData().getRows().get(0);
        assertNull(row.getCustomFields(), "the errors column must be ignored on re-upload");
        assertTrue(row.getErrors() == null || row.getErrors().isEmpty(), String.valueOf(row.getErrors()));
        assertEquals(1, again.getData().getReadyOrders());
    }
}
