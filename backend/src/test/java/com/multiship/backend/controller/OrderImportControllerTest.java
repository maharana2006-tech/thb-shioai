package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ImportBatchDTO;
import com.multiship.backend.dto.OrderImportPreviewDTO;
import com.multiship.backend.dto.OrderImportRowDTO;
import com.multiship.backend.service.OrderImportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Coverage backfill — OrderImportController was 0-coverage per the
 * test-coverage audit. This is the CSV / XLSX import endpoint; the audit
 * flagged it as data-integrity-critical (bad code path → orders get
 * corrupted or duplicated).
 *
 * <p>Focused on controller-owned logic:
 * <ul>
 *   <li>arg forwarding (multipart file, @RequestBody rows, @PathVariable id,
 *       @RequestParam flags)
 *   <li>principal → username extraction (null-safe fallback to "unknown")
 *   <li>historyDetail + generateForBatch + generateForRow have real
 *       controller if-else / null-check logic (not delegated to service);
 *       tested end-to-end
 *   <li>status-echo on service ApiResponse (matches PickupControllerTest /
 *       VoidControllerTest template)
 * </ul>
 *
 * <p>Import semantics (row validation, carrier-code resolution, label
 * generation) are covered separately by OrderImportServiceImplTest.
 */
class OrderImportControllerTest {

    private OrderImportService orderImportService;
    private OrderImportController controller;
    private UserDetails alice;

    @BeforeEach
    void setUp() {
        orderImportService = mock(OrderImportService.class);
        controller = new OrderImportController(orderImportService);
        alice = User.withUsername("alice").password("x").authorities("ROLE_USER").build();
    }

    // ─── preview: multipart forwarding + status echo ───────────────────────

    @Test
    void preview_forwardsFilenameAndAccountIdToService() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "orders-2026-08.csv", "text/csv", "orderNo,carrier\n1,UPS\n".getBytes());
        ApiResponse<OrderImportPreviewDTO> serviceResp = ApiResponse.<OrderImportPreviewDTO>builder()
                .status("success").code(200).data(new OrderImportPreviewDTO()).build();
        when(orderImportService.preview(anyString(), any(), any())).thenReturn(serviceResp);

        ResponseEntity<ApiResponse<OrderImportPreviewDTO>> resp = controller.preview(file, 77L);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(orderImportService).preview(eq("orders-2026-08.csv"), any(), eq(77L));
    }

    @Test
    void preview_echoesServiceStatusCode_on422ValidationFailure() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "bad.csv", "text/csv", "malformed".getBytes());
        ApiResponse<OrderImportPreviewDTO> serviceResp = ApiResponse.<OrderImportPreviewDTO>builder()
                .status("error").code(422).errorCode("VALIDATION_ERROR")
                .message("Column headers don't match template.")
                .build();
        when(orderImportService.preview(anyString(), any(), any())).thenReturn(serviceResp);

        ResponseEntity<ApiResponse<OrderImportPreviewDTO>> resp = controller.preview(file, null);

        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, resp.getStatusCode());
        assertEquals("VALIDATION_ERROR", resp.getBody().getErrorCode());
    }

    // ─── commit: principal → username extraction ───────────────────────────

    @Test
    void commit_extractsUsernameFromPrincipal_andForwardsRows() {
        List<OrderImportRowDTO> rows = new ArrayList<>();
        rows.add(new OrderImportRowDTO());
        ApiResponse<OrderImportPreviewDTO> serviceResp = ApiResponse.<OrderImportPreviewDTO>builder()
                .status("success").code(200).build();
        when(orderImportService.commit(any(), anyString())).thenReturn(serviceResp);

        controller.commit(rows, alice);

        verify(orderImportService).commit(eq(rows), eq("alice"));
    }

    @Test
    void commit_defaultsUsernameToUnknown_whenPrincipalNull() {
        // Internal / service-to-service caller with no auth principal — the
        // controller must NOT throw NPE; must default to "unknown" so
        // audit trails on import batches still capture something.
        ApiResponse<OrderImportPreviewDTO> serviceResp = ApiResponse.<OrderImportPreviewDTO>builder()
                .status("success").code(200).build();
        when(orderImportService.commit(any(), anyString())).thenReturn(serviceResp);

        controller.commit(Collections.emptyList(), null);

        verify(orderImportService).commit(any(), eq("unknown"));
    }

    // ─── save: principal + fileName forwarding ─────────────────────────────

    @Test
    void save_forwardsFileNameAndUsername() {
        List<OrderImportRowDTO> rows = Collections.singletonList(new OrderImportRowDTO());
        ApiResponse<OrderImportPreviewDTO> serviceResp = ApiResponse.<OrderImportPreviewDTO>builder()
                .status("success").code(200).build();
        when(orderImportService.save(any(), anyString(), any(), any(Boolean.class))).thenReturn(serviceResp);

        controller.save(rows, "batch-1.xlsx", false, alice);

        verify(orderImportService).save(eq(rows), eq("alice"), eq("batch-1.xlsx"), eq(false));
    }

    @Test
    void save_worksWithNullPrincipalAndNullFileName() {
        ApiResponse<OrderImportPreviewDTO> serviceResp = ApiResponse.<OrderImportPreviewDTO>builder()
                .status("success").code(200).build();
        when(orderImportService.save(any(), anyString(), any(), any(Boolean.class))).thenReturn(serviceResp);

        controller.save(Collections.emptyList(), null, false, null);

        verify(orderImportService).save(any(), eq("unknown"), eq(null), eq(false));
    }

    // ─── history: controller wraps service result as 200 ───────────────────

    @Test
    void history_wrapsServiceResultAs200_withMessage() {
        List<ImportBatchDTO> batches = new ArrayList<>();
        batches.add(ImportBatchDTO.builder().id(1L).build());
        when(orderImportService.history()).thenReturn(batches);

        ResponseEntity<ApiResponse<List<ImportBatchDTO>>> resp = controller.history(false);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertSame(batches, resp.getBody().getData());
        assertEquals("Import history loaded.", resp.getBody().getMessage());
    }

    // ─── historyDetail: controller-owned null → 404 branch ─────────────────

    @Test
    void historyDetail_returns404_whenServiceReturnsNull() {
        when(orderImportService.historyDetail(999L)).thenReturn(null);

        ResponseEntity<ApiResponse<ImportBatchDTO>> resp = controller.historyDetail(999L);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
        assertEquals("Import not found.", resp.getBody().getMessage());
    }

    @Test
    void historyDetail_returns200_whenServiceReturnsDto() {
        ImportBatchDTO dto = ImportBatchDTO.builder().id(42L).fileName("batch-42.csv").build();
        when(orderImportService.historyDetail(42L)).thenReturn(dto);

        ResponseEntity<ApiResponse<ImportBatchDTO>> resp = controller.historyDetail(42L);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertSame(dto, resp.getBody().getData());
        assertEquals("Import loaded.", resp.getBody().getMessage());
    }

    // ─── generateForBatch: controller composes a count-based message ───────

    @Test
    void generateForBatch_returns404_whenServiceReturnsNull() {
        when(orderImportService.generateLabelsForBatch(anyLong(), anyString(), any(Boolean.class), any(Boolean.class)))
                .thenReturn(null);

        ResponseEntity<ApiResponse<ImportBatchDTO>> resp =
                controller.generateForBatch(999L, false, false, alice);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
        assertEquals("Import not found.", resp.getBody().getMessage());
    }

    @Test
    void generateForBatch_composesMessageWithGeneratedCountAndStatusLabel() {
        // 2 generated out of 3 rows → "2 of 3 label(s) generated · Partial complete"
        OrderImportRowDTO r1 = new OrderImportRowDTO();
        r1.setGeneratedStatus("GENERATED");
        OrderImportRowDTO r2 = new OrderImportRowDTO();
        r2.setGeneratedStatus("GENERATED");
        OrderImportRowDTO r3 = new OrderImportRowDTO();
        r3.setGeneratedStatus("FAILED");
        List<OrderImportRowDTO> rows = new ArrayList<>();
        rows.add(r1);
        rows.add(r2);
        rows.add(r3);
        ImportBatchDTO dto = ImportBatchDTO.builder()
                .status("PARTIAL_COMPLETE").rows(rows).build();
        when(orderImportService.generateLabelsForBatch(anyLong(), anyString(), any(Boolean.class), any(Boolean.class)))
                .thenReturn(dto);

        ResponseEntity<ApiResponse<ImportBatchDTO>> resp =
                controller.generateForBatch(7L, true, false, alice);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody().getMessage());
        assertTrue(resp.getBody().getMessage().contains("2 of 3 label(s) generated"),
                "expected count-based message; got: " + resp.getBody().getMessage());
        assertTrue(resp.getBody().getMessage().contains("Partial complete"),
                "expected humanised status label; got: " + resp.getBody().getMessage());
    }

    @Test
    void generateForBatch_forwardsOnlyFailedFlag_toService() {
        // Regression guard: Sprint 55 audit #302 F3.2 introduced onlyFailed
        // to prevent duplicate carrier calls on retry. The controller MUST
        // forward the flag as-is, or every retry re-bills already-GENERATED
        // rows.
        ImportBatchDTO dto = ImportBatchDTO.builder().status("COMPLETE").build();
        when(orderImportService.generateLabelsForBatch(anyLong(), anyString(), any(Boolean.class), any(Boolean.class)))
                .thenReturn(dto);

        controller.generateForBatch(7L, true, false, alice);

        ArgumentCaptor<Boolean> onlyFailedFlag = ArgumentCaptor.forClass(Boolean.class);
        ArgumentCaptor<Boolean> usePlatformFlag = ArgumentCaptor.forClass(Boolean.class);
        verify(orderImportService).generateLabelsForBatch(
                eq(7L), eq("alice"), onlyFailedFlag.capture(), usePlatformFlag.capture());
        assertEquals(Boolean.TRUE, onlyFailedFlag.getValue());
        assertEquals(Boolean.FALSE, usePlatformFlag.getValue());
    }

    @Test
    void generateForBatch_handlesNullRowsWithoutNpe() {
        // dto with a status but no rows list — controller's count logic must
        // not NPE (rows == null → gen=0, totalRows=0).
        ImportBatchDTO dto = ImportBatchDTO.builder().status("INITIATE").rows(null).build();
        when(orderImportService.generateLabelsForBatch(anyLong(), anyString(), any(Boolean.class), any(Boolean.class)))
                .thenReturn(dto);

        ResponseEntity<ApiResponse<ImportBatchDTO>> resp =
                controller.generateForBatch(7L, false, false, alice);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertTrue(resp.getBody().getMessage().contains("0 of 0 label(s) generated"),
                "expected 0/0 count on null-rows dto; got: " + resp.getBody().getMessage());
    }

    // ─── generateForRow: same null → 404 pattern ───────────────────────────

    @Test
    void generateForRow_returns404_whenServiceReturnsNull() {
        when(orderImportService.generateLabelForRow(anyLong(), any(Integer.class), anyString()))
                .thenReturn(null);

        ResponseEntity<ApiResponse<ImportBatchDTO>> resp =
                controller.generateForRow(999L, 3, alice);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
        assertEquals("Import not found.", resp.getBody().getMessage());
    }

    @Test
    void generateForRow_composesMessageWithRowNumberAndStatus() {
        ImportBatchDTO dto = ImportBatchDTO.builder().status("COMPLETE").build();
        when(orderImportService.generateLabelForRow(eq(7L), eq(3), eq("alice"))).thenReturn(dto);

        ResponseEntity<ApiResponse<ImportBatchDTO>> resp =
                controller.generateForRow(7L, 3, alice);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertTrue(resp.getBody().getMessage().contains("row 3"),
                "expected row number in message; got: " + resp.getBody().getMessage());
        assertTrue(resp.getBody().getMessage().contains("COMPLETE"),
                "expected batch status in message; got: " + resp.getBody().getMessage());
    }

    // ─── validate / validateAddresses: simple status-echo pass-through ─────

    @Test
    void validate_echoesServiceResponse() {
        ApiResponse<OrderImportPreviewDTO> serviceResp = ApiResponse.<OrderImportPreviewDTO>builder()
                .status("success").code(200).data(new OrderImportPreviewDTO()).build();
        when(orderImportService.validate(any())).thenReturn(serviceResp);

        ResponseEntity<ApiResponse<OrderImportPreviewDTO>> resp =
                controller.validate(Collections.emptyList());

        assertEquals(HttpStatus.OK, resp.getStatusCode());
    }

    @Test
    void validateAddresses_echoesServiceResponse() {
        ApiResponse<OrderImportPreviewDTO> serviceResp = ApiResponse.<OrderImportPreviewDTO>builder()
                .status("success").code(200).data(new OrderImportPreviewDTO()).build();
        when(orderImportService.validateAddresses(any())).thenReturn(serviceResp);

        ResponseEntity<ApiResponse<OrderImportPreviewDTO>> resp =
                controller.validateAddresses(Collections.emptyList());

        assertEquals(HttpStatus.OK, resp.getStatusCode());
    }
}
