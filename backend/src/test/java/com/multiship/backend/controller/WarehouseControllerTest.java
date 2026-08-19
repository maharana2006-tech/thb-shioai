package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.PageResponseDTO;
import com.multiship.backend.dto.WarehouseDTO;
import com.multiship.backend.dto.WarehouseListFilters;
import com.multiship.backend.dto.WarehouseUpsertRequest;
import com.multiship.backend.service.WarehouseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Coverage backfill — WarehouseController was 0-coverage per the test-
 * coverage audit. Warehouse master data feeds the ship-from cascade
 * used by every label generation; a silent controller bug (e.g. drop
 * the ownerType filter and return PLATFORM warehouses when the caller
 * asked for CLIENT-owned only) would surface as mysterious wrong-
 * warehouse fulfilment across the fleet.
 *
 * <p>Same shape as ClientControllerTest — the filter-build regression
 * guards are the meat; every other endpoint is pure status-echo.
 *
 * <p>Warehouse CRUD semantics (WAREHOUSE_CODE_TAKEN generation,
 * PLATFORM/CLIENT owner-field consistency check, cascade delete of
 * client_warehouse links) are covered by WarehouseServiceImplTest.
 */
class WarehouseControllerTest {

    private WarehouseService warehouseService;
    private WarehouseController controller;

    @BeforeEach
    void setUp() {
        warehouseService = mock(WarehouseService.class);
        controller = new WarehouseController(warehouseService);
    }

    // ─── listWarehouses — controller BUILDS the filter object ──────────────

    @Test
    void listWarehouses_buildsFiltersFromAllQueryParams() {
        // Regression guard: 8 query params must all reach the filter.
        // A future refactor that dropped `ownerType` would silently
        // return every warehouse when the caller asked for PLATFORM-only.
        ApiResponse<PageResponseDTO<WarehouseDTO>> serviceResp =
                ApiResponse.<PageResponseDTO<WarehouseDTO>>builder().status("success").code(200).build();
        when(warehouseService.listWarehouses(any())).thenReturn(serviceResp);

        controller.listWarehouses("seattle", "PLATFORM", "ACME", "true",
                "name", "DESC", 3, 30);

        ArgumentCaptor<WarehouseListFilters> filter = ArgumentCaptor.forClass(WarehouseListFilters.class);
        verify(warehouseService).listWarehouses(filter.capture());
        WarehouseListFilters f = filter.getValue();
        assertEquals("seattle", f.getSearch());
        assertEquals("PLATFORM", f.getOwnerType());
        assertEquals("ACME", f.getOwnerClientCode());
        assertEquals("true", f.getActive());
        assertEquals("name", f.getSortBy());
        assertEquals("DESC", f.getSortDirection());
        assertEquals(3, f.getPage());
        assertEquals(30, f.getSize());
    }

    @Test
    void listWarehouses_clampsExcessiveSize_viaPaginationDefaults() {
        // Same clamp semantics as ClientController — size > 100 collapses
        // to MAX_SIZE=100 so a caller can't ask for 10,000-row pages.
        ApiResponse<PageResponseDTO<WarehouseDTO>> serviceResp =
                ApiResponse.<PageResponseDTO<WarehouseDTO>>builder().status("success").code(200).build();
        when(warehouseService.listWarehouses(any())).thenReturn(serviceResp);

        controller.listWarehouses(null, null, null, null, "code", "ASC", 0, /* size */ 10_000);

        ArgumentCaptor<WarehouseListFilters> filter = ArgumentCaptor.forClass(WarehouseListFilters.class);
        verify(warehouseService).listWarehouses(filter.capture());
        assertEquals(100, filter.getValue().getSize());
    }

    @Test
    void listWarehouses_echoesServiceStatusCode() {
        PageResponseDTO<WarehouseDTO> page = PageResponseDTO.<WarehouseDTO>builder()
                .content(List.of(WarehouseDTO.builder().code("WH-1").name("Warehouse 1").build()))
                .totalElements(1L).build();
        ApiResponse<PageResponseDTO<WarehouseDTO>> serviceResp =
                ApiResponse.<PageResponseDTO<WarehouseDTO>>builder()
                        .status("success").code(200).data(page).build();
        when(warehouseService.listWarehouses(any())).thenReturn(serviceResp);

        ResponseEntity<ApiResponse<PageResponseDTO<WarehouseDTO>>> resp =
                controller.listWarehouses(null, null, null, null, "code", "ASC", 0, 25);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(1, resp.getBody().getData().getContent().size());
    }

    // ─── getWarehouse — pure delegation ────────────────────────────────────

    @Test
    void getWarehouse_echoesServiceStatusCode_onSuccess() {
        WarehouseDTO data = WarehouseDTO.builder().code("WH-1").name("Main DC").build();
        ApiResponse<WarehouseDTO> serviceResp = ApiResponse.<WarehouseDTO>builder()
                .status("success").code(200).data(data).build();
        when(warehouseService.getWarehouse("WH-1")).thenReturn(serviceResp);

        ResponseEntity<ApiResponse<WarehouseDTO>> resp = controller.getWarehouse("WH-1");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertSame(data, resp.getBody().getData());
    }

    @Test
    void getWarehouse_echoesServiceStatusCode_onNotFound() {
        ApiResponse<WarehouseDTO> serviceResp = ApiResponse.<WarehouseDTO>builder()
                .status("error").code(404).errorCode("VALIDATION_ERROR")
                .message("Warehouse GHOST not found.").build();
        when(warehouseService.getWarehouse("GHOST")).thenReturn(serviceResp);

        ResponseEntity<ApiResponse<WarehouseDTO>> resp = controller.getWarehouse("GHOST");

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    // ─── createWarehouse — documented error codes ──────────────────────────

    @Test
    void createWarehouse_echoesServiceStatusCode_onCodeAlreadyTaken() {
        // 409 WAREHOUSE_CODE_TAKEN — FE uses errorCode to render inline
        // "this code is already in use" hint (same pattern as CLIENT_CODE_TAKEN).
        ApiResponse<WarehouseDTO> serviceResp = ApiResponse.<WarehouseDTO>builder()
                .status("error").code(409).errorCode("WAREHOUSE_CODE_TAKEN")
                .message("Warehouse code WH-1 is already registered.").build();
        when(warehouseService.createWarehouse(any())).thenReturn(serviceResp);

        ResponseEntity<ApiResponse<WarehouseDTO>> resp =
                controller.createWarehouse(new WarehouseUpsertRequest());

        assertEquals(HttpStatus.CONFLICT, resp.getStatusCode());
        assertEquals("WAREHOUSE_CODE_TAKEN", resp.getBody().getErrorCode());
    }

    @Test
    void createWarehouse_echoesServiceStatusCode_onOwnerFieldsInvalid() {
        // 400 WAREHOUSE_OWNER_INVALID — PLATFORM warehouse can't carry
        // an ownerClientCode; CLIENT warehouse must. FE uses errorCode
        // to jump to the ownership field on the form.
        ApiResponse<WarehouseDTO> serviceResp = ApiResponse.<WarehouseDTO>builder()
                .status("error").code(400).errorCode("WAREHOUSE_OWNER_INVALID")
                .message("PLATFORM warehouses must not have ownerClientCode.").build();
        when(warehouseService.createWarehouse(any())).thenReturn(serviceResp);

        ResponseEntity<ApiResponse<WarehouseDTO>> resp =
                controller.createWarehouse(new WarehouseUpsertRequest());

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals("WAREHOUSE_OWNER_INVALID", resp.getBody().getErrorCode());
    }

    @Test
    void createWarehouse_echoesServiceStatusCode_onSuccess() {
        WarehouseDTO data = WarehouseDTO.builder().code("WH-NEW").name("New DC").build();
        ApiResponse<WarehouseDTO> serviceResp = ApiResponse.<WarehouseDTO>builder()
                .status("success").code(201).data(data).build();
        when(warehouseService.createWarehouse(any())).thenReturn(serviceResp);

        ResponseEntity<ApiResponse<WarehouseDTO>> resp =
                controller.createWarehouse(new WarehouseUpsertRequest());

        assertEquals(HttpStatus.CREATED, resp.getStatusCode());
        assertEquals("WH-NEW", resp.getBody().getData().getCode());
    }

    // ─── updateWarehouse — path + body forwarding ──────────────────────────

    @Test
    void updateWarehouse_forwardsPathVariableAndBody_toService() {
        WarehouseUpsertRequest body = new WarehouseUpsertRequest();
        ApiResponse<WarehouseDTO> serviceResp = ApiResponse.<WarehouseDTO>builder()
                .status("success").code(200).build();
        when(warehouseService.updateWarehouse(anyString(), any())).thenReturn(serviceResp);

        controller.updateWarehouse("WH-1", body);

        verify(warehouseService).updateWarehouse(eq("WH-1"), eq(body));
    }

    // ─── toggleActive — pure delegation ────────────────────────────────────

    @Test
    void toggleActive_echoesServiceStatusCode() {
        WarehouseDTO data = WarehouseDTO.builder().code("WH-1").active(false).build();
        ApiResponse<WarehouseDTO> serviceResp = ApiResponse.<WarehouseDTO>builder()
                .status("success").code(200).data(data).build();
        when(warehouseService.toggleActive("WH-1")).thenReturn(serviceResp);

        ResponseEntity<ApiResponse<WarehouseDTO>> resp = controller.toggleActive("WH-1");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(Boolean.FALSE, resp.getBody().getData().getActive());
    }

    // ─── deleteWarehouse — ADMIN-only, pure delegation ─────────────────────

    @Test
    void deleteWarehouse_echoesServiceStatusCode_onSuccess() {
        ApiResponse<Void> serviceResp = ApiResponse.<Void>builder()
                .status("success").code(200).message("Warehouse deleted.").build();
        when(warehouseService.deleteWarehouse("WH-1")).thenReturn(serviceResp);

        ResponseEntity<ApiResponse<Void>> resp = controller.deleteWarehouse("WH-1");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(warehouseService).deleteWarehouse("WH-1");
    }

    @Test
    void deleteWarehouse_echoesServiceStatusCode_onDependentRules() {
        // If the service refuses to delete when ship-method rules still
        // reference the warehouse (rather than the documented fall-back
        // to any-warehouse resolution), controller must echo 409 or
        // whichever code the service picks — not swallow.
        ApiResponse<Void> serviceResp = ApiResponse.<Void>builder()
                .status("error").code(409).errorCode("WAREHOUSE_IN_USE")
                .message("Warehouse WH-1 is referenced by 3 active ship-method rules.").build();
        when(warehouseService.deleteWarehouse("WH-1")).thenReturn(serviceResp);

        ResponseEntity<ApiResponse<Void>> resp = controller.deleteWarehouse("WH-1");

        assertEquals(HttpStatus.CONFLICT, resp.getStatusCode());
        assertEquals("WAREHOUSE_IN_USE", resp.getBody().getErrorCode());
    }
}
