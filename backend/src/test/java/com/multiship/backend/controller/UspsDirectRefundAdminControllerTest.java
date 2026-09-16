package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.UspsVoidReconciliationStatusDTO;
import com.multiship.backend.dto.UspsVoidReconciliationSummaryDTO;
import com.multiship.backend.model.OrderTracking;
import com.multiship.backend.repository.OrderTrackingRepository;
import com.multiship.backend.service.carriers.usps.UspsDirectVoidReconciliationService;
import com.multiship.backend.service.carriers.usps.UspsRefundCsvExporter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PR-D — {@link UspsDirectRefundAdminController} plain-Mockito tests.
 * Mirrors {@link CarrierLimitAdminControllerTest} — no Spring context;
 * @PreAuthorize enforcement is exercised by the framework tests, not
 * repeated here.
 */
class UspsDirectRefundAdminControllerTest {

    private UspsRefundCsvExporter exporter;
    private UspsDirectVoidReconciliationService reconciliation;
    private OrderTrackingRepository trackingRepo;
    private UspsDirectRefundAdminController controller;

    @BeforeEach
    void setUp() {
        exporter = mock(UspsRefundCsvExporter.class);
        reconciliation = mock(UspsDirectVoidReconciliationService.class);
        trackingRepo = mock(OrderTrackingRepository.class);
        controller = new UspsDirectRefundAdminController(
                exporter, reconciliation, trackingRepo);
    }

    // ================================================================
    // GET /refund-export
    // ================================================================

    @Test
    void refundExport_ok_returns200WithCsvBody() {
        byte[] csv = "TrackingNumber,MailerId,CustomerRegistrationId,OriginalPostage,LabelDate,ReasonCode,CustomerReference\r\nTRK-1,MID,CRID,8.85,2026-09-10,UNUSED,1234\r\n"
                .getBytes(StandardCharsets.UTF_8);
        when(exporter.exportCsv(any(), any())).thenReturn(csv);

        ResponseEntity<byte[]> resp = controller.refundExport(
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals(csv.length, resp.getBody().length);
        HttpHeaders h = resp.getHeaders();
        assertEquals(MediaType.parseMediaType("text/csv"), h.getContentType());
        String contentDisp = h.getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertNotNull(contentDisp);
        assertTrue(contentDisp.contains("attachment"),
                "content-disposition should be attachment; got: " + contentDisp);
        assertTrue(contentDisp.contains("ps3533-refund-"),
                "filename should start with ps3533-refund-; got: " + contentDisp);
    }

    @Test
    void refundExport_defaultDates_stillReturns200() {
        when(exporter.exportCsv(any(), any())).thenReturn(new byte[0]);
        ResponseEntity<byte[]> resp = controller.refundExport(null, null);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
    }

    @Test
    void refundExport_badDateRange_returns400() {
        when(exporter.exportCsv(any(), any()))
                .thenThrow(new IllegalArgumentException("startDate must be on or before endDate"));
        ResponseEntity<byte[]> resp = controller.refundExport(
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 9, 1));
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        String body = new String(resp.getBody(), StandardCharsets.UTF_8);
        assertTrue(body.contains("startDate"),
                "400 body should carry validation message; got: " + body);
        assertTrue(body.contains("VALIDATION_ERROR"),
                "400 body should include the machine-readable code; got: " + body);
    }

    // ================================================================
    // POST /void-reconciliation/run
    // ================================================================

    @Test
    void runReconciliation_validCsv_returns200WithSummary() {
        UspsVoidReconciliationSummaryDTO summary =
                new UspsVoidReconciliationSummaryDTO(3, 1, 1, 1, 0, List.of());
        when(reconciliation.reconcile(any(InputStream.class))).thenReturn(summary);
        MockMultipartFile file = new MockMultipartFile(
                "file", "evs.csv", "text/csv",
                "TrackingNumber,RefundStatus\nTRK-1,APPROVED\n".getBytes(StandardCharsets.UTF_8));

        ResponseEntity<ApiResponse<UspsVoidReconciliationSummaryDTO>> resp =
                controller.runReconciliation(file);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals(3, resp.getBody().getData().processedRows());
        assertEquals(1, resp.getBody().getData().reconciledApproved());
    }

    @Test
    void runReconciliation_emptyFile_returns400() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "empty.csv", "text/csv", new byte[0]);
        ResponseEntity<ApiResponse<UspsVoidReconciliationSummaryDTO>> resp =
                controller.runReconciliation(file);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertTrue(resp.getBody().getMessage().toLowerCase().contains("empty"));
    }

    @Test
    void runReconciliation_nullFile_returns400() {
        ResponseEntity<ApiResponse<UspsVoidReconciliationSummaryDTO>> resp =
                controller.runReconciliation(null);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    void runReconciliation_malformedCsv_returns400() {
        when(reconciliation.reconcile(any(InputStream.class)))
                .thenThrow(new IllegalArgumentException(
                        "eVS Refund report CSV is missing required column(s): TrackingNumber, RefundStatus"));
        MockMultipartFile file = new MockMultipartFile(
                "file", "bad.csv", "text/csv",
                "Foo,Bar\ngarbage\n".getBytes(StandardCharsets.UTF_8));

        ResponseEntity<ApiResponse<UspsVoidReconciliationSummaryDTO>> resp =
                controller.runReconciliation(file);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertTrue(resp.getBody().getMessage().contains("required column"));
    }

    // ================================================================
    // GET /void-reconciliation/status
    // ================================================================

    @Test
    void statusFor_ok_returns200WithReconciliationState() {
        OrderTracking t = new OrderTracking();
        t.setTrackingNumber("TRK-1");
        t.setStatus("VOIDED");
        t.setVoidReconciliationStatus("RECONCILED_APPROVED");
        LocalDateTime checkedAt = LocalDateTime.of(2026, 9, 12, 10, 0);
        t.setVoidReconciliationCheckedAt(checkedAt);
        when(trackingRepo.findByTrackingNumberIgnoreCase("TRK-1"))
                .thenReturn(Optional.of(t));

        ResponseEntity<ApiResponse<UspsVoidReconciliationStatusDTO>> resp =
                controller.statusFor("TRK-1");
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        UspsVoidReconciliationStatusDTO dto = resp.getBody().getData();
        assertEquals("TRK-1", dto.trackingNumber());
        assertEquals("VOIDED", dto.orderStatus());
        assertEquals("RECONCILED_APPROVED", dto.reconciliationStatus());
        assertEquals(checkedAt, dto.lastCheckedAt());
    }

    @Test
    void statusFor_unknownTrackingNumber_returns404() {
        when(trackingRepo.findByTrackingNumberIgnoreCase("BOGUS"))
                .thenReturn(Optional.empty());
        ResponseEntity<ApiResponse<UspsVoidReconciliationStatusDTO>> resp =
                controller.statusFor("BOGUS");
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
        assertTrue(resp.getBody().getMessage().contains("BOGUS"));
    }

    @Test
    void statusFor_blankTrackingNumber_returns400() {
        ResponseEntity<ApiResponse<UspsVoidReconciliationStatusDTO>> resp =
                controller.statusFor("");
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    void statusFor_trimsWhitespaceInLookup() {
        OrderTracking t = new OrderTracking();
        t.setTrackingNumber("TRK-1");
        t.setStatus("VOIDED");
        when(trackingRepo.findByTrackingNumberIgnoreCase("TRK-1"))
                .thenReturn(Optional.of(t));

        ResponseEntity<ApiResponse<UspsVoidReconciliationStatusDTO>> resp =
                controller.statusFor("  TRK-1  ");
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("TRK-1", resp.getBody().getData().trackingNumber());
    }
}
