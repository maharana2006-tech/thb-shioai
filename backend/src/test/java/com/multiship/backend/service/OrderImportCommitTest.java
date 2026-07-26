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
        when(carrierService.generateManualLabel(any(), any()))
                .thenReturn(ok(1001L, "TN-1"),      // Row 1 OK
                            failed("carrier down"), // Row 2 fails
                            ok(1003L, "TN-3"));     // Row 3 OK

        ApiResponse<OrderImportPreviewDTO> resp = service.commit(
                List.of(validRow(1), validRow(2), validRow(3)), "alice");

        assertEquals(3, resp.getData().getValidRows(),
                "All 3 rows validated OK on the server");
        // Two succeeded, one failed.
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
}
