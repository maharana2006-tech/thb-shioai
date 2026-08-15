package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.CarrierShippingLimitRequest;
import com.multiship.backend.dto.CarrierShippingLimitResponse;
import com.multiship.backend.service.CarrierLimitAdminService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sprint 52 — smoke tests for {@link CarrierLimitAdminController}. Mirrors
 * {@code AdminUserControllerTest} (plain-Mockito, no Spring context) so
 * the whole file runs in milliseconds; wire-level tests would repeat what
 * the framework already guarantees.
 *
 * <p>Covers each verb's OK path plus the 404 shape on missing rows and
 * DELETE. Bean-validation failures are handled by
 * {@link GlobalExceptionHandler}, exercised by other tests.
 */
class CarrierLimitAdminControllerTest {

    private CarrierLimitAdminService service;
    private CarrierLimitAdminController controller;

    @BeforeEach
    void setUp() {
        service = mock(CarrierLimitAdminService.class);
        controller = new CarrierLimitAdminController(service);
    }

    private static CarrierShippingLimitResponse row(long id) {
        return CarrierShippingLimitResponse.builder()
                .id(id)
                .carrierCode("UPS").serviceCode("UPS_GROUND")
                .scope("DOMESTIC").direction("FORWARD")
                .maxPackages(20).maxCommodities(50)
                .active(true)
                .build();
    }

    private static CarrierShippingLimitRequest req() {
        return CarrierShippingLimitRequest.builder()
                .carrierCode("UPS").serviceCode("UPS_GROUND")
                .scope("DOMESTIC").direction("FORWARD")
                .maxPackages(20).maxCommodities(50)
                .active(true)
                .build();
    }

    // ===== list =====

    @Test
    void list_returns200WithRows() {
        when(service.list(0, 50)).thenReturn(List.of(row(1L), row(2L)));

        ResponseEntity<ApiResponse<List<CarrierShippingLimitResponse>>> resp =
                controller.list(0, 50);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals(2, resp.getBody().getData().size());
    }

    @Test
    void list_emptyReturns200EmptyArray() {
        when(service.list(0, 50)).thenReturn(List.of());

        ResponseEntity<ApiResponse<List<CarrierShippingLimitResponse>>> resp =
                controller.list(0, 50);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(0, resp.getBody().getData().size());
    }

    // ===== get =====

    @Test
    void get_ok_returns200() {
        when(service.get(7L)).thenReturn(Optional.of(row(7L)));

        ResponseEntity<ApiResponse<CarrierShippingLimitResponse>> resp = controller.get(7L);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(7L, resp.getBody().getData().getId());
    }

    @Test
    void get_missing_returns404() {
        when(service.get(99L)).thenReturn(Optional.empty());

        ResponseEntity<ApiResponse<CarrierShippingLimitResponse>> resp = controller.get(99L);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertNull(resp.getBody().getData());
    }

    // ===== create =====

    @Test
    void create_returns201WithCreatedRow() {
        when(service.create(any(CarrierShippingLimitRequest.class))).thenReturn(row(42L));

        ResponseEntity<ApiResponse<CarrierShippingLimitResponse>> resp =
                controller.create(req());

        assertEquals(HttpStatus.CREATED, resp.getStatusCode());
        assertEquals(42L, resp.getBody().getData().getId());
    }

    // ===== update =====

    @Test
    void update_ok_returns200() {
        when(service.update(eq(7L), any(CarrierShippingLimitRequest.class)))
                .thenReturn(Optional.of(row(7L)));

        ResponseEntity<ApiResponse<CarrierShippingLimitResponse>> resp =
                controller.update(7L, req());

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(7L, resp.getBody().getData().getId());
    }

    @Test
    void update_missing_returns404() {
        when(service.update(eq(99L), any(CarrierShippingLimitRequest.class)))
                .thenReturn(Optional.empty());

        ResponseEntity<ApiResponse<CarrierShippingLimitResponse>> resp =
                controller.update(99L, req());

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    // ===== delete =====

    @Test
    void delete_ok_returns204() {
        when(service.delete(7L)).thenReturn(true);

        ResponseEntity<ApiResponse<Void>> resp = controller.delete(7L);

        assertEquals(HttpStatus.NO_CONTENT, resp.getStatusCode());
        verify(service).delete(7L);
    }

    @Test
    void delete_missing_returns404() {
        when(service.delete(99L)).thenReturn(false);

        ResponseEntity<ApiResponse<Void>> resp = controller.delete(99L);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }
}
