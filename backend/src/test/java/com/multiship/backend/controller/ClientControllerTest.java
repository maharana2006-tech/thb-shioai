package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.CarrierAccountRefDTO;
import com.multiship.backend.dto.ClientCascadePreviewDTO;
import com.multiship.backend.dto.ClientDTO;
import com.multiship.backend.dto.ClientListFilters;
import com.multiship.backend.dto.ClientUpsertRequest;
import com.multiship.backend.dto.PageResponseDTO;
import com.multiship.backend.service.ClientService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Coverage backfill — ClientController was 0-coverage per the test-
 * coverage audit. Client master-data CRUD — orders link to clients via
 * their customer code, and the label-generation cascade consults the
 * client's default carrier account before the company default. A silent
 * bug here (e.g. deleteClient accidentally cascades past its 409
 * CLIENT_HAS_ORDERS guard) would corrupt cross-order state fleet-wide.
 *
 * <p>Focus is on controller-owned logic:
 * <ul>
 *   <li>{@code listClients} + {@code exportClientsCsv} — the controller
 *       BUILDS the {@link ClientListFilters} object from 11 query
 *       parameters (search / status / carrier / etc.) and clamps
 *       {@code size} via {@code PaginationDefaults.clamp}. The service
 *       receives an already-built filter object.</li>
 *   <li>{@code exportClientsCsv} — also owns UTF-8 BOM prepend +
 *       filename composition + Content-Disposition header.</li>
 *   <li>Every other endpoint is pure status-echo to the service.</li>
 * </ul>
 *
 * <p>Client CRUD semantics (409 CLIENT_CODE_TAKEN, cascade snapshot,
 * pending-orders block) are covered by ClientServiceImplTest.
 */
class ClientControllerTest {

    private ClientService clientService;
    private ClientController controller;

    @BeforeEach
    void setUp() {
        clientService = mock(ClientService.class);
        controller = new ClientController(clientService);
    }

    // ─── listClients — controller BUILDS the filter object ─────────────────

    @Test
    void listClients_buildsFiltersFromQueryParams_andPassesToService() {
        // Regression guard: 11 query params must all reach the filter
        // object with the right names. A future refactor that drops one
        // (e.g. accidentally leaves out `city`) would silently return
        // unfiltered results.
        PageResponseDTO<ClientDTO> page = PageResponseDTO.<ClientDTO>builder()
                .content(List.of()).totalElements(0L).build();
        ApiResponse<PageResponseDTO<ClientDTO>> serviceResp =
                ApiResponse.<PageResponseDTO<ClientDTO>>builder()
                        .status("success").code(200).data(page).build();
        when(clientService.listClients(any())).thenReturn(serviceResp);

        controller.listClients("acme", "ACTIVE", "UPS", "true",
                "AC", "acme corp", "seattle",
                "name", "DESC", 2, 50);

        ArgumentCaptor<ClientListFilters> filter = ArgumentCaptor.forClass(ClientListFilters.class);
        verify(clientService).listClients(filter.capture());
        ClientListFilters f = filter.getValue();
        assertEquals("acme", f.getSearch());
        assertEquals("ACTIVE", f.getStatus());
        assertEquals("UPS", f.getCarrier());
        assertEquals("true", f.getHasOrders());
        assertEquals("AC", f.getCode());
        assertEquals("acme corp", f.getName());
        assertEquals("seattle", f.getCity());
        assertEquals("name", f.getSortBy());
        assertEquals("DESC", f.getSortDirection());
        assertEquals(2, f.getPage());
        assertEquals(50, f.getSize());
    }

    @Test
    void listClients_clampsExcessiveSize_viaPaginationDefaults() {
        // Regression guard: size > 100 must be clamped to 100 (MAX_SIZE)
        // to prevent a caller from asking for 10,000-row pages. This is
        // controller-owned — the service trusts what it's handed.
        ApiResponse<PageResponseDTO<ClientDTO>> serviceResp =
                ApiResponse.<PageResponseDTO<ClientDTO>>builder().status("success").code(200).build();
        when(clientService.listClients(any())).thenReturn(serviceResp);

        controller.listClients(null, null, null, null, null, null, null,
                "code", "ASC", 0, /* size */ 10_000);

        ArgumentCaptor<ClientListFilters> filter = ArgumentCaptor.forClass(ClientListFilters.class);
        verify(clientService).listClients(filter.capture());
        assertEquals(100, filter.getValue().getSize(), "size must be clamped to MAX_SIZE=100");
    }

    @Test
    void listClients_echoesServiceStatusCode() {
        ApiResponse<PageResponseDTO<ClientDTO>> serviceResp =
                ApiResponse.<PageResponseDTO<ClientDTO>>builder().status("success").code(200).build();
        when(clientService.listClients(any())).thenReturn(serviceResp);

        ResponseEntity<ApiResponse<PageResponseDTO<ClientDTO>>> resp =
                controller.listClients(null, null, null, null, null, null, null,
                        "code", "ASC", 0, 25);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
    }

    // ─── getClient — pure delegation ───────────────────────────────────────

    @Test
    void getClient_echoesServiceStatusCode_onSuccess() {
        ClientDTO data = ClientDTO.builder().clientCode("ACME").name("Acme Corp").status("ACTIVE").build();
        ApiResponse<ClientDTO> serviceResp = ApiResponse.<ClientDTO>builder()
                .status("success").code(200).data(data).build();
        when(clientService.getClient("ACME")).thenReturn(serviceResp);

        ResponseEntity<ApiResponse<ClientDTO>> resp = controller.getClient("ACME");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertSame(data, resp.getBody().getData());
    }

    @Test
    void getClient_echoesServiceStatusCode_onNotFound() {
        ApiResponse<ClientDTO> serviceResp = ApiResponse.<ClientDTO>builder()
                .status("error").code(404).errorCode("VALIDATION_ERROR")
                .message("Client GHOST not found.").build();
        when(clientService.getClient("GHOST")).thenReturn(serviceResp);

        ResponseEntity<ApiResponse<ClientDTO>> resp = controller.getClient("GHOST");

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    // ─── createClient — 409 CLIENT_CODE_TAKEN echo ─────────────────────────

    @Test
    void createClient_echoesServiceStatusCode_onCodeAlreadyTaken() {
        // 409 CLIENT_CODE_TAKEN — the FE uses the errorCode to render
        // "this code is already in use, pick another" inline instead of
        // a generic error toast. Controller must echo the 409 verbatim.
        ApiResponse<ClientDTO> serviceResp = ApiResponse.<ClientDTO>builder()
                .status("error").code(409).errorCode("CLIENT_CODE_TAKEN")
                .message("Client code ACME is already registered.").build();
        when(clientService.createClient(any())).thenReturn(serviceResp);

        ResponseEntity<ApiResponse<ClientDTO>> resp = controller.createClient(new ClientUpsertRequest());

        assertEquals(HttpStatus.CONFLICT, resp.getStatusCode());
        assertEquals("CLIENT_CODE_TAKEN", resp.getBody().getErrorCode());
    }

    // ─── updateClient — path variable forwarded ────────────────────────────

    @Test
    void updateClient_forwardsPathVariableAndBody_toService() {
        ClientUpsertRequest body = new ClientUpsertRequest();
        ApiResponse<ClientDTO> serviceResp = ApiResponse.<ClientDTO>builder().status("success").code(200).build();
        when(clientService.updateClient(anyString(), any())).thenReturn(serviceResp);

        controller.updateClient("ACME", body);

        verify(clientService).updateClient(eq("ACME"), eq(body));
    }

    // ─── toggleActive — pending-orders block ───────────────────────────────

    @Test
    void toggleActive_echoesServiceStatusCode_onPendingOrdersBlock() {
        // Regression guard: 409 CLIENT_HAS_ORDERS is the documented
        // hard-block when DISABLE would strand pending orders. The FE
        // uses the errorCode to render the guidance "clear pending
        // orders first, then disable." Controller must echo 409.
        ApiResponse<ClientDTO> serviceResp = ApiResponse.<ClientDTO>builder()
                .status("error").code(409).errorCode("CLIENT_HAS_ORDERS")
                .message("Client ACME has 12 pending orders.").build();
        when(clientService.toggleActive("ACME")).thenReturn(serviceResp);

        ResponseEntity<ApiResponse<ClientDTO>> resp = controller.toggleActive("ACME");

        assertEquals(HttpStatus.CONFLICT, resp.getStatusCode());
        assertEquals("CLIENT_HAS_ORDERS", resp.getBody().getErrorCode());
    }

    // ─── previewCascade — pure delegation ──────────────────────────────────

    @Test
    void previewCascade_echoesServiceStatusCode() {
        ClientCascadePreviewDTO data = ClientCascadePreviewDTO.builder()
                .clientCode("ACME").build();
        ApiResponse<ClientCascadePreviewDTO> serviceResp =
                ApiResponse.<ClientCascadePreviewDTO>builder()
                        .status("success").code(200).data(data).build();
        when(clientService.previewCascade("ACME")).thenReturn(serviceResp);

        ResponseEntity<ApiResponse<ClientCascadePreviewDTO>> resp = controller.previewCascade("ACME");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertSame(data, resp.getBody().getData());
    }

    // ─── deleteClient — 409 CLIENT_HAS_ORDERS echo (ADMIN only) ────────────

    @Test
    void deleteClient_echoesServiceStatusCode_onPendingOrders() {
        // Same 409 CLIENT_HAS_ORDERS as toggleActive. Documented in the
        // OpenAPI note: "when orders reference the client — deactivate
        // instead." Controller must echo, not swallow to 500.
        ApiResponse<Void> serviceResp = ApiResponse.<Void>builder()
                .status("error").code(409).errorCode("CLIENT_HAS_ORDERS")
                .message("Client ACME has 3 orders — deactivate instead.").build();
        when(clientService.deleteClient("ACME")).thenReturn(serviceResp);

        ResponseEntity<ApiResponse<Void>> resp = controller.deleteClient("ACME");

        assertEquals(HttpStatus.CONFLICT, resp.getStatusCode());
        assertEquals("CLIENT_HAS_ORDERS", resp.getBody().getErrorCode());
    }

    // ─── listClientAccounts — pure delegation ──────────────────────────────

    @Test
    void listClientAccounts_echoesServiceStatusCode() {
        List<CarrierAccountRefDTO> accts = List.of(
                CarrierAccountRefDTO.builder().carrierCode("UPS").isDefault(true).build());
        ApiResponse<List<CarrierAccountRefDTO>> serviceResp =
                ApiResponse.<List<CarrierAccountRefDTO>>builder()
                        .status("success").code(200).data(accts).build();
        when(clientService.listClientAccounts("ACME")).thenReturn(serviceResp);

        ResponseEntity<ApiResponse<List<CarrierAccountRefDTO>>> resp =
                controller.listClientAccounts("ACME");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(1, resp.getBody().getData().size());
    }

    // ─── exportClientsCsv — BOM prepend + filename + Content-Disposition ──

    @Test
    void exportClientsCsv_wrapsServiceOutputWithBomAndAttachmentHeader() {
        // Controller-owned formatting:
        //   1. UTF-8 BOM prepended so Excel opens the CSV in UTF-8
        //      (Excel defaults to Windows-1252 for BOM-less CSVs).
        //   2. Content-Disposition: attachment; filename="clients-YYYY-MM-DD.csv"
        //   3. Content-Type: text/csv; charset=utf-8 (via CsvMediaType)
        String csv = "code,name\nACME,Acme Corp\n";
        when(clientService.exportClientsCsv(any())).thenReturn(csv);

        ResponseEntity<byte[]> resp = controller.exportClientsCsv(
                null, null, null, null, null, null, null, "code", "ASC");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("text", resp.getHeaders().getContentType().getType());
        // BOM prepend — first 3 bytes must be EF BB BF (UTF-8 BOM).
        byte[] body = resp.getBody();
        assertEquals((byte) 0xEF, body[0]);
        assertEquals((byte) 0xBB, body[1]);
        assertEquals((byte) 0xBF, body[2]);
        // Rest of the body is the service's CSV verbatim.
        assertTrue(new String(body, 3, body.length - 3, java.nio.charset.StandardCharsets.UTF_8)
                .startsWith("code,name"));
        // Filename includes today's date.
        String disposition = resp.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertTrue(disposition != null
                && disposition.contains("attachment")
                && disposition.contains("clients-")
                && disposition.contains(".csv"),
                "expected attachment filename with clients-YYYY-MM-DD.csv shape; got: " + disposition);
    }

    @Test
    void exportClientsCsv_forwardsFiltersButOverridesPageAndSize() {
        // Regression guard: the export streams every filtered row, so
        // page/size on the filter object are hard-coded to (0, 1) by
        // the controller — the service ignores them but the shape must
        // still be valid.
        when(clientService.exportClientsCsv(any())).thenReturn("code\n");

        controller.exportClientsCsv("acme", "ACTIVE", null, null, null, null, null, "code", "ASC");

        ArgumentCaptor<ClientListFilters> filter = ArgumentCaptor.forClass(ClientListFilters.class);
        verify(clientService).exportClientsCsv(filter.capture());
        ClientListFilters f = filter.getValue();
        assertEquals("acme", f.getSearch());
        assertEquals("ACTIVE", f.getStatus());
        assertEquals(0, f.getPage());
        assertEquals(1, f.getSize(), "export controller hard-codes size=1 (service ignores)");
    }

    @Test
    void exportClientsCsv_returnsCorrectContentType() {
        when(clientService.exportClientsCsv(any())).thenReturn("");

        ResponseEntity<byte[]> resp = controller.exportClientsCsv(
                null, null, null, null, null, null, null, "code", "ASC");

        MediaType ct = resp.getHeaders().getContentType();
        assertTrue(ct != null && "text".equals(ct.getType()) && "csv".equals(ct.getSubtype()),
                "expected text/csv content type; got: " + ct);
    }
}
