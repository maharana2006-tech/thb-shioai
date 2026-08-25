package com.multiship.backend.service;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.LabelGenerationResponse;
import com.multiship.backend.dto.ManualShipmentRequest;
import com.multiship.backend.dto.OrderImportPreviewDTO;
import com.multiship.backend.dto.OrderImportRowDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sprint 41 — commit-path tests. Sprint 40 covered parse + validate;
 * this file covers the commit → generateManualLabel wiring with a
 * mocked CarrierService.
 */
class OrderImportCommitTest {

    private CarrierService carrierService;
    private OrderImportServiceImpl service;

    @BeforeEach
    void setUp() {
        carrierService = mock(CarrierService.class);
        service = new OrderImportServiceImpl(carrierService);
    }

    private static OrderImportRowDTO validRow(int rowNumber) {
        return OrderImportRowDTO.builder()
                .rowNumber(rowNumber)
                // clientCode + weightUnit joined the required-field set in
                // the "new validation and new features changes" push (Sprint 55).
                // Blank clientCode was previously silently accepted and produced
                // orphaned custNo="MANUAL" orders — see OrderImportServiceImpl
                // validateRow doc comment. Fixture updated to keep pace.
                .clientCode("ACME")
                .recipientName("Jane " + rowNumber)
                .addressLine1(rowNumber + " Broadway")
                .city("New York")
                .state("NY")
                .postalCode("10001")
                .countryCode("US")
                .carrierCode("UPS")
                .accountNumber("A12345")
                .weight(new BigDecimal("2.5"))
                .weightUnit("LB")
                .build();
    }

    private static ApiResponse<LabelGenerationResponse> ok(long orderNo, String trackingNumber) {
        return ApiResponse.<LabelGenerationResponse>builder()
                .status("success").code(200)
                .message("ok")
                .data(LabelGenerationResponse.builder()
                        .orderNo(orderNo)
                        .trackingNumber(trackingNumber)
                        .status("GENERATED")
                        .build())
                .build();
    }

    private static ApiResponse<LabelGenerationResponse> failed(String reason) {
        return ApiResponse.<LabelGenerationResponse>builder()
                .status("error").code(502)
                .message(reason)
                .data(LabelGenerationResponse.builder()
                        .trackingNumber(null)
                        .status("FAILED")
                        .message(reason)
                        .build())
                .build();
    }

    /* -------------------------- Happy path -------------------------- */

    @Test
    void commitCallsCarrierServiceForEveryValidRow() {
        when(carrierService.generateManualLabel(any(), any()))
                .thenReturn(ok(1001L, "TN-1"),
                            ok(1002L, "TN-2"),
                            ok(1003L, "TN-3"));

        ApiResponse<OrderImportPreviewDTO> resp = service.commit(
                List.of(validRow(1), validRow(2), validRow(3)), "alice");

        assertEquals("success", resp.getStatus());
        assertEquals(3, resp.getData().getValidRows());
        assertEquals(0, resp.getData().getInvalidRows());
        verify(carrierService, times(3)).generateManualLabel(any(), any());

        // Every row populated with the generated tracking number.
        for (int i = 0; i < 3; i++) {
            OrderImportRowDTO row = resp.getData().getRows().get(i);
            assertEquals("GENERATED", row.getGeneratedStatus());
            assertNotNull(row.getGeneratedTrackingNumber());
            assertNotNull(row.getGeneratedOrderNo());
        }
    }

    @Test
    void commitPassesCarrierAndAccountNumberOntoManualShipmentRequest() {
        when(carrierService.generateManualLabel(any(), any())).thenReturn(ok(1001L, "TN-1"));

        service.commit(List.of(validRow(1)), "alice");

        ArgumentCaptor<ManualShipmentRequest> captor = ArgumentCaptor.forClass(ManualShipmentRequest.class);
        verify(carrierService).generateManualLabel(captor.capture(), any());
        ManualShipmentRequest req = captor.getValue();
        assertEquals("UPS", req.getCarrierCode());
        assertEquals("A12345", req.getAccountNumber());
        assertEquals(0, new BigDecimal("2.5").compareTo(req.getWeight()));
        assertNotNull(req.getRecipient(), "recipient block should be populated from the row");
        assertEquals("Jane 1", req.getRecipient().getName());
        assertEquals("1 Broadway", req.getRecipient().getAddressLine1());
        assertEquals("New York", req.getRecipient().getCity());
        assertEquals("US", req.getRecipient().getCountryCode());
    }

    /* -------------------------- Mixed success/failure -------------------------- */

    @Test
    void commitPerRowFailureDoesNotKillTheJob() {
        // Sprint 50 Tier 1 finding #8 — commit loop now runs on
        // fanOutExecutor, so Mockito's consecutive .thenReturn ordering
        // no longer maps to input row order. Route the response by the
        // request's recipient name (which mirrors the input row) so this
        // test remains deterministic under any thread scheduling.
        when(carrierService.generateManualLabel(any(), any())).thenAnswer(inv -> {
            ManualShipmentRequest req = inv.getArgument(0);
            String name = req.getRecipient() != null ? req.getRecipient().getName() : "";
            return switch (name) {
                case "Jane 2" -> failed("carrier down");
                case "Jane 1" -> ok(1001L, "TN-1");
                case "Jane 3" -> ok(1003L, "TN-3");
                default -> failed("unexpected row");
            };
        });

        ApiResponse<OrderImportPreviewDTO> resp = service.commit(
                List.of(validRow(1), validRow(2), validRow(3)), "alice");

        assertEquals(3, resp.getData().getValidRows(),
                "All 3 rows validated OK on the server");
        // Input row order is preserved (invokeAll returns futures in
        // submission order), so per-row outcomes still line up 1:1.
        List<OrderImportRowDTO> rowsOut = resp.getData().getRows();
        assertEquals("GENERATED", rowsOut.get(0).getGeneratedStatus());
        assertEquals("FAILED", rowsOut.get(1).getGeneratedStatus());
        assertEquals("GENERATED", rowsOut.get(2).getGeneratedStatus());
        // Failure message surfaces on the failed row.
        assertTrue(rowsOut.get(1).getGeneratedMessage().contains("carrier down"));
    }

    @Test
    void commitCarrierExceptionIsCaughtAndMarkedFailed() {
        when(carrierService.generateManualLabel(any(), any()))
                .thenThrow(new RuntimeException("network dead"));

        ApiResponse<OrderImportPreviewDTO> resp = service.commit(
                List.of(validRow(1)), "alice");

        OrderImportRowDTO row = resp.getData().getRows().get(0);
        assertEquals("FAILED", row.getGeneratedStatus());
        assertTrue(row.getGeneratedMessage().contains("network dead"));
    }

    /* -------------------------- Invalid rows -------------------------- */

    @Test
    void commitSkipsInvalidRowsBeforeCallingCarrier() {
        OrderImportRowDTO badRow = validRow(2);
        badRow.setCity(null);  // required
        badRow.setPostalCode(null);
        badRow.setCountryCode(null);

        when(carrierService.generateManualLabel(any(), any())).thenReturn(ok(1001L, "TN-1"));

        ApiResponse<OrderImportPreviewDTO> resp = service.commit(
                List.of(validRow(1), badRow), "alice");

        assertEquals(1, resp.getData().getValidRows());
        assertEquals(1, resp.getData().getInvalidRows());
        // CarrierService called once, not twice.
        verify(carrierService, times(1)).generateManualLabel(any(), any());
        // Bad row has NO generated status set (never got that far).
        OrderImportRowDTO bad = resp.getData().getRows().get(1);
        assertNull(bad.getGeneratedStatus());
    }

    @Test
    void commitZeroValidRowsSurfacesFriendlyMessage() {
        OrderImportRowDTO badRow = OrderImportRowDTO.builder()
                .rowNumber(1).build();
        ApiResponse<OrderImportPreviewDTO> resp = service.commit(
                List.of(badRow), "alice");
        assertEquals("success", resp.getStatus(),
                "Zero-valid-row commit is still a success response, not an error");
        assertTrue(resp.getMessage().contains("skipped"),
                "Message should describe skipped rows");
        assertEquals(1, resp.getData().getInvalidRows());
    }

    /* -------------------------- Test-only no-arg constructor path -------------------------- */

    @Test
    void noArgConstructorReportsWithoutCallingCarrier() {
        OrderImportServiceImpl standalone = new OrderImportServiceImpl();
        ApiResponse<OrderImportPreviewDTO> resp = standalone.commit(
                List.of(validRow(1)), "alice");
        assertEquals("success", resp.getStatus());
        // Row was validated OK but no label generated (carrierService null).
        assertNull(resp.getData().getRows().get(0).getGeneratedStatus());
    }

    /* -------------------------- Sprint 50 Tier 0.5 PR G — tenant clamp/requireMatch -------------------------- */

    /**
     * Helper: seed a scoped-tenant enforcer that rejects every code
     * except the ones we let through (matching-tenant). Wired via
     * reflection because the field is optional-@Autowired.
     */
    private static TenantScopeEnforcer rejectingEnforcer() {
        TenantScopeEnforcer enforcer = mock(TenantScopeEnforcer.class);
        // Foreign clientCode from row throws immediately.
        when(enforcer.clampClientCode("OTHER"))
                .thenThrow(new org.springframework.security.access.AccessDeniedException(
                        "cross tenant"));
        org.mockito.Mockito.doThrow(new org.springframework.security.access.AccessDeniedException(
                        "cross tenant"))
                .when(enforcer).requireTenantMatch("OTHER");
        return enforcer;
    }

    private static OrderImportRowDTO foreignRow(int rowNumber) {
        OrderImportRowDTO row = validRow(rowNumber);
        row.setClientCode("OTHER");
        return row;
    }

    @Test
    void previewClampsForeignClientCode() throws Exception {
        // Uses the full-arg constructor + reflection-wired enforcer so the
        // preview path pulls the row.clientCode through clamp().
        OrderImportServiceImpl scoped = new OrderImportServiceImpl(
                carrierService, null, null, null, null, null, null, null);
        org.springframework.test.util.ReflectionTestUtils.setField(
                scoped, "tenantScope", rejectingEnforcer());

        String csv = "recipientName,addressLine1,city,postalCode,countryCode,weight,clientCode\n"
                + "Jane,42 Broadway,NYC,10001,US,2.5,OTHER\n";
        ApiResponse<OrderImportPreviewDTO> resp = scoped.preview(
                "orders.csv",
                new java.io.ByteArrayInputStream(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        // Preview wraps the AccessDeniedException in a friendly BAD_REQUEST.
        assertEquals("error", resp.getStatus());
        assertTrue(resp.getMessage().toLowerCase().contains("cross tenant"),
                "Expected error message to surface the AccessDenied cause: " + resp.getMessage());
    }

    @Test
    void commitRejectsRowWithForeignClientCode() {
        OrderImportServiceImpl scoped = new OrderImportServiceImpl(carrierService);
        org.springframework.test.util.ReflectionTestUtils.setField(
                scoped, "tenantScope", rejectingEnforcer());

        // AccessDeniedException from clamp propagates out of commit().
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> scoped.commit(List.of(foreignRow(1)), "alice"));
    }

    @Test
    void saveRejectsRowWithForeignClientCode() {
        OrderImportServiceImpl scoped = new OrderImportServiceImpl(carrierService);
        org.springframework.test.util.ReflectionTestUtils.setField(
                scoped, "tenantScope", rejectingEnforcer());

        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> scoped.save(List.of(foreignRow(1)), "alice", "orders.xlsx", false));
    }

    @Test
    void historyDetailRejectsBatchOwnedByForeignTenant() throws Exception {
        OrderImportServiceImpl scoped = new OrderImportServiceImpl(carrierService);
        org.springframework.test.util.ReflectionTestUtils.setField(
                scoped, "tenantScope", rejectingEnforcer());

        // Wire the optional repo + mapper so historyDetail can parse a batch.
        com.multiship.backend.repository.ImportBatchRepository batchRepo =
                mock(com.multiship.backend.repository.ImportBatchRepository.class);
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        org.springframework.test.util.ReflectionTestUtils.setField(
                scoped, "importBatchRepository", batchRepo);
        org.springframework.test.util.ReflectionTestUtils.setField(
                scoped, "importObjectMapper", mapper);

        com.multiship.backend.model.ImportBatch batch =
                new com.multiship.backend.model.ImportBatch();
        batch.setId(11L);
        batch.setRowsJson(mapper.writeValueAsString(List.of(foreignRow(1))));
        when(batchRepo.findById(11L))
                .thenReturn(java.util.Optional.of(batch));

        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> scoped.historyDetail(11L));
    }

    @Test
    void generateLabelsForBatchRejectsForeignTenantBatch() throws Exception {
        OrderImportServiceImpl scoped = new OrderImportServiceImpl(carrierService);
        org.springframework.test.util.ReflectionTestUtils.setField(
                scoped, "tenantScope", rejectingEnforcer());

        com.multiship.backend.repository.ImportBatchRepository batchRepo =
                mock(com.multiship.backend.repository.ImportBatchRepository.class);
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        org.springframework.test.util.ReflectionTestUtils.setField(
                scoped, "importBatchRepository", batchRepo);
        org.springframework.test.util.ReflectionTestUtils.setField(
                scoped, "importObjectMapper", mapper);

        com.multiship.backend.model.ImportBatch batch =
                new com.multiship.backend.model.ImportBatch();
        batch.setId(22L);
        batch.setRowsJson(mapper.writeValueAsString(List.of(foreignRow(1))));
        when(batchRepo.findById(22L))
                .thenReturn(java.util.Optional.of(batch));

        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> scoped.generateLabelsForBatch(22L, "alice"));
        // Refused before any status flip / carrier call.
        verify(batchRepo, org.mockito.Mockito.never()).save(any());
        verify(carrierService, org.mockito.Mockito.never()).generateManualLabel(any(), any());
    }

    @Test
    void generateLabelForRowRejectsForeignTenantBatch() throws Exception {
        OrderImportServiceImpl scoped = new OrderImportServiceImpl(carrierService);
        org.springframework.test.util.ReflectionTestUtils.setField(
                scoped, "tenantScope", rejectingEnforcer());

        com.multiship.backend.repository.ImportBatchRepository batchRepo =
                mock(com.multiship.backend.repository.ImportBatchRepository.class);
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        org.springframework.test.util.ReflectionTestUtils.setField(
                scoped, "importBatchRepository", batchRepo);
        org.springframework.test.util.ReflectionTestUtils.setField(
                scoped, "importObjectMapper", mapper);

        com.multiship.backend.model.ImportBatch batch =
                new com.multiship.backend.model.ImportBatch();
        batch.setId(33L);
        batch.setRowsJson(mapper.writeValueAsString(List.of(foreignRow(1))));
        when(batchRepo.findById(33L))
                .thenReturn(java.util.Optional.of(batch));

        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> scoped.generateLabelForRow(33L, 1, "alice"));
        verify(carrierService, org.mockito.Mockito.never()).generateManualLabel(any(), any());
    }
}
