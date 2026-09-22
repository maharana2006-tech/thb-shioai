package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.service.externalsystems.ExternalSystemException;
import com.multiship.backend.service.externalsystems.ExternalSystemException.Kind;
import com.multiship.backend.service.ndsshipment.NdsShipmentLookupService;
import com.multiship.backend.service.ndsshipment.NdsShipmentPrefill;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Verifies the HTTP status-code contract of the lookup endpoint —
 * 200 / 404 / 422 / 503 branching. Delegates all business logic
 * assertions to {@code NdsShipmentLookupServiceTest}.
 */
@ExtendWith(MockitoExtension.class)
class NdsShipmentLookupControllerTest {

    @Mock NdsShipmentLookupService service;
    @InjectMocks NdsShipmentLookupController controller;

    @Test
    void returns200OnSuccess() {
        NdsShipmentPrefill sample = new NdsShipmentPrefill(
                NdsShipmentPrefill.Status.OK, List.of(),
                NdsShipmentPrefill.Scope.DIRECT, ".X1", "ACME", null,
                List.of(), null, null, List.of(), null, null, List.of());
        when(service.lookup(anyString())).thenReturn(Optional.of(sample));
        ResponseEntity<ApiResponse<NdsShipmentPrefill>> resp = controller.lookup(".X1");
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals(NdsShipmentPrefill.Status.OK, resp.getBody().getData().status());
    }

    @Test
    void returns404WhenServiceReturnsEmpty() {
        when(service.lookup(anyString())).thenReturn(Optional.empty());
        ResponseEntity<ApiResponse<NdsShipmentPrefill>> resp = controller.lookup(".X0");
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
        assertEquals("NOT_FOUND", resp.getBody().getErrorCode());
    }

    @Test
    void returns422OnUnparseableScan() {
        when(service.lookup(anyString()))
                .thenThrow(new IllegalArgumentException("Scan must start with .X or .Y"));
        ResponseEntity<ApiResponse<NdsShipmentPrefill>> resp = controller.lookup(".Z");
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, resp.getStatusCode());
        assertTrue(resp.getBody().getMessage().contains(".X or .Y"));
    }

    @Test
    void returns503WhenNdsUnreachable() {
        when(service.lookup(anyString()))
                .thenThrow(new ExternalSystemException(Kind.UNREACHABLE, "nds-default",
                        "TNS-12541"));
        ResponseEntity<ApiResponse<NdsShipmentPrefill>> resp = controller.lookup(".X1");
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, resp.getStatusCode());
        assertTrue(resp.getBody().getMessage().contains("NDS is unavailable"));
    }
}
