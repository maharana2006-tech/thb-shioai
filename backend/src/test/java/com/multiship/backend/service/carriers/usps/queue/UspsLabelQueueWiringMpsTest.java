package com.multiship.backend.service.carriers.usps.queue;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.LabelGenerationResponse;
import com.multiship.backend.model.UspsLabelQueueItem;
import com.multiship.backend.service.CarrierService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PR-F2.5 — targeted tests for the MPS branch of
 * {@link UspsLabelQueueWiring#processQueueItem(UspsLabelQueueItem)}.
 *
 * <p>Non-MPS ({@code parentOrderNo == null}) rows go through the
 * pre-existing single-label path (already covered by PR-F1's tests);
 * MPS ({@code parentOrderNo != null}) rows must delegate to
 * {@link UspsMpsPieceDispatcher} and never touch
 * {@link CarrierService#generateLabel(Long, org.springframework.security.core.userdetails.UserDetails, String, Long)}.
 */
class UspsLabelQueueWiringMpsTest {

    private UspsLabelQueueProcessor processor;
    private CarrierService carrierService;
    private UspsMpsPieceDispatcher dispatcher;
    private UspsLabelQueueWiring wiring;

    @BeforeEach
    void setUp() {
        processor = mock(UspsLabelQueueProcessor.class);
        carrierService = mock(CarrierService.class);
        dispatcher = mock(UspsMpsPieceDispatcher.class);
        wiring = new UspsLabelQueueWiring(processor, carrierService, dispatcher);
    }

    // ================================================================
    // MPS routing
    // ================================================================

    @Test
    void processQueueItem_mpsPiece_delegatesToDispatcher() throws Exception {
        UspsLabelQueueItem item = mpsItem(1001L, 42L, 3);
        when(dispatcher.dispatchPiece(item)).thenReturn("9400111899223197428347");

        String tracking = wiring.processQueueItem(item);

        assertEquals("9400111899223197428347", tracking);
        verify(dispatcher, times(1)).dispatchPiece(item);
        verify(carrierService, never())
                .generateLabel(anyLong(), any(), anyString(), any());
    }

    @Test
    void processQueueItem_mpsPiece_dispatcherThrows_propagates() throws Exception {
        UspsLabelQueueItem item = mpsItem(1002L, 42L, 5);
        when(dispatcher.dispatchPiece(item))
                .thenThrow(new IllegalStateException("USPS 429 rate-limited"));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> wiring.processQueueItem(item));
        assertTrue(ex.getMessage().contains("429"),
                "Dispatcher failure must surface verbatim so the queue processor "
                        + "records FAILED with the original error");
        verify(dispatcher, times(1)).dispatchPiece(item);
        verify(carrierService, never())
                .generateLabel(anyLong(), any(), anyString(), any());
    }

    // ================================================================
    // Non-MPS routing (single-label path unchanged)
    // ================================================================

    @Test
    void processQueueItem_singleLabel_usesCarrierServiceGenerateLabel() throws Exception {
        UspsLabelQueueItem item = singleLabelItem(2001L, 12345L);
        LabelGenerationResponse resp = LabelGenerationResponse.builder()
                .orderNo(12345L)
                .trackingNumber("9400-single")
                .status("GENERATED")
                .build();
        ApiResponse<LabelGenerationResponse> apiResp = ApiResponse.<LabelGenerationResponse>builder()
                .status("SUCCESS")
                .code(200)
                .message("ok")
                .data(resp)
                .build();
        when(carrierService.generateLabel(eq(12345L), any(), anyString(), isNull()))
                .thenReturn(apiResp);

        String tracking = wiring.processQueueItem(item);

        assertEquals("9400-single", tracking);
        verify(carrierService, times(1))
                .generateLabel(eq(12345L), any(), anyString(), isNull());
        verify(dispatcher, never()).dispatchPiece(any());
    }

    // ================================================================
    // Guard
    // ================================================================

    @Test
    void processQueueItem_missingShipmentId_throwsIae() {
        UspsLabelQueueItem item = UspsLabelQueueItem.builder()
                .id(1L).tenantCode("ACME").shipmentId(null).priority(100)
                .status(UspsLabelQueueItem.Status.PROCESSING).retryCount(0)
                .build();

        assertThrows(IllegalArgumentException.class,
                () -> wiring.processQueueItem(item));
    }

    // ================================================================
    // helpers
    // ================================================================

    private static UspsLabelQueueItem mpsItem(long id, long parentOrderNo, int seq) {
        long syntheticShipmentId = -(parentOrderNo * 100_000L + seq);
        return UspsLabelQueueItem.builder()
                .id(id)
                .tenantCode("ACME")
                .shipmentId(syntheticShipmentId)
                .parentOrderNo(parentOrderNo)
                .sequenceNumber(seq)
                .priority(100)
                .status(UspsLabelQueueItem.Status.PROCESSING)
                .retryCount(0)
                .build();
    }

    private static UspsLabelQueueItem singleLabelItem(long id, long shipmentId) {
        return UspsLabelQueueItem.builder()
                .id(id)
                .tenantCode("ACME")
                .shipmentId(shipmentId)
                .parentOrderNo(null)
                .sequenceNumber(null)
                .priority(100)
                .status(UspsLabelQueueItem.Status.PROCESSING)
                .retryCount(0)
                .build();
    }
}
