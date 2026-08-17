package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ClientCustomsProfileDTO;
import com.multiship.backend.dto.CustomsProfileFilters;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.PageResponseDTO;
import com.multiship.backend.service.ClientCustomsProfileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Controller-level Mockito tests for {@link CustomsProfilesController} —
 * the master `/api/v1/customs-profiles` family that powers `/settings/importer-broker`.
 *
 * <p>Anti-fallback: sole collaborator is {@link ClientCustomsProfileService},
 * mocked in every test. No repository or downstream service is constructed.
 * Every endpoint asserts {@code times(1)} on the exercised service method
 * + {@code never()} on siblings (so the export path can't accidentally invoke
 * the list or stats path).
 *
 * <p>Endpoints covered (3):
 * <ul>
 *   <li>GET  /api/v1/customs-profiles                 — list (filters + paging)</li>
 *   <li>GET  /api/v1/customs-profiles/stats           — aggregate counts</li>
 *   <li>GET  /api/v1/customs-profiles/export.csv      — CSV export (UTF-8 BOM)</li>
 * </ul>
 *
 * <p>Sprint 51 audit pattern pinned: the controller must echo the service's
 * non-200 status code (via {@code ResponseEntity.status(r.getCode()).body(r)}),
 * NOT rewrite to 200. Role wiring pinned via reflection.
 */
class CustomsProfilesControllerTest {

    private ClientCustomsProfileService service;
    private CustomsProfilesController controller;

    @BeforeEach
    void setUp() {
        service = mock(ClientCustomsProfileService.class);
        controller = new CustomsProfilesController(service);
    }

    // ================ helpers ================

    private static <T> ApiResponse<T> ok(T data) {
        return ApiResponse.<T>builder()
                .status("success").code(200).data(data).message("ok")
                .timestamp(LocalDateTime.now()).build();
    }

    private static <T> ApiResponse<T> err(int code, ErrorCode ec, String msg) {
        return ApiResponse.<T>builder()
                .status("error").code(code).errorCode(ec.name()).message(msg)
                .timestamp(LocalDateTime.now()).build();
    }

    private static ClientCustomsProfileDTO dto(long id, String clientCode, String name) {
        return ClientCustomsProfileDTO.builder()
                .id(id).clientCode(clientCode).clientName(name).build();
    }

    // ================ GET / — list ================

    @Test
    void list_returns200WithPage_andDelegatesOnce() {
        PageResponseDTO<ClientCustomsProfileDTO> page = PageResponseDTO.<ClientCustomsProfileDTO>builder()
                .content(List.of(dto(1L, "ACME", "Broker A")))
                .totalElements(1L).totalPages(1).pageNumber(0).pageSize(50).build();
        when(service.listPaginated(any())).thenReturn(ok(page));

        ResponseEntity<ApiResponse<PageResponseDTO<ClientCustomsProfileDTO>>> re =
                controller.list(null, null, null, null, null, "client", "ASC", 0, 50);

        assertEquals(HttpStatus.OK, re.getStatusCode());
        assertEquals(1, re.getBody().getData().getContent().size());
        verify(service, times(1)).listPaginated(any());
        verify(service, never()).getStats();
        verify(service, never()).exportProfilesCsv(any());
    }

    @Test
    void list_passesAllFiltersToService() {
        // Pin the filter-builder wiring — a future field-add on the DTO could
        // shift these silently otherwise.
        PageResponseDTO<ClientCustomsProfileDTO> page = PageResponseDTO.<ClientCustomsProfileDTO>builder()
                .content(List.of()).totalElements(0L).totalPages(0).pageNumber(0).pageSize(50).build();
        when(service.listPaginated(any())).thenReturn(ok(page));

        controller.list("acme", "ACME", "UPS", "BROKER-A",
                List.of("US", "CA"), "broker", "DESC", 2, 100);

        ArgumentCaptor<CustomsProfileFilters> captor = ArgumentCaptor.forClass(CustomsProfileFilters.class);
        verify(service).listPaginated(captor.capture());
        CustomsProfileFilters f = captor.getValue();
        assertEquals("acme", f.getSearch());
        assertEquals("ACME", f.getClientCode());
        assertEquals("UPS", f.getCarrier());
        assertEquals("BROKER-A", f.getBroker());
        assertEquals(List.of("US", "CA"), f.getCountries());
        assertEquals("broker", f.getSortBy());
        assertEquals("DESC", f.getSortDirection());
        assertEquals(2, f.getPage());
        // Size passes through PaginationDefaults.clamp() — 100 stays as 100.
        assertEquals(100, f.getSize());
    }

    @Test
    void list_defaultParams_areSaneWhenAllOptionalOmitted() {
        PageResponseDTO<ClientCustomsProfileDTO> page = PageResponseDTO.<ClientCustomsProfileDTO>builder()
                .content(List.of()).totalElements(0L).totalPages(0).pageNumber(0).pageSize(50).build();
        when(service.listPaginated(any())).thenReturn(ok(page));

        // Endpoint defaults: sortBy=client, sortDirection=ASC, page=0, size=<DEFAULT>.
        controller.list(null, null, null, null, null, "client", "ASC", 0, 50);

        ArgumentCaptor<CustomsProfileFilters> captor = ArgumentCaptor.forClass(CustomsProfileFilters.class);
        verify(service).listPaginated(captor.capture());
        CustomsProfileFilters f = captor.getValue();
        assertEquals("client", f.getSortBy());
        assertEquals("ASC", f.getSortDirection());
        assertEquals(0, f.getPage());
    }

    @Test
    void list_serviceError500_isEchoedNotRewrittenTo200() {
        ApiResponse<PageResponseDTO<ClientCustomsProfileDTO>> resp =
                err(500, ErrorCode.VALIDATION_ERROR, "listing failed");
        when(service.listPaginated(any())).thenReturn(resp);

        ResponseEntity<ApiResponse<PageResponseDTO<ClientCustomsProfileDTO>>> re =
                controller.list(null, null, null, null, null, "client", "ASC", 0, 50);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, re.getStatusCode());
        assertEquals(ErrorCode.VALIDATION_ERROR.name(), re.getBody().getErrorCode());
    }

    // ================ GET /stats ================

    @Test
    void stats_returns200WithMap_andDelegatesOnce() {
        when(service.getStats()).thenReturn(ok(Map.of(
                "profiles", 12L, "destinationsCovered", 34L, "clientsConfigured", 5L)));

        ResponseEntity<ApiResponse<Map<String, Long>>> re = controller.stats();

        assertEquals(HttpStatus.OK, re.getStatusCode());
        assertEquals(12L, re.getBody().getData().get("profiles"));
        verify(service, times(1)).getStats();
        verify(service, never()).listPaginated(any());
        verify(service, never()).exportProfilesCsv(any());
    }

    @Test
    void stats_serviceError500_isEchoed() {
        when(service.getStats()).thenReturn(err(500, ErrorCode.VALIDATION_ERROR, "stats failed"));

        ResponseEntity<ApiResponse<Map<String, Long>>> re = controller.stats();

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, re.getStatusCode());
    }

    // ================ GET /export.csv ================

    @Test
    void exportCsv_returns200WithCsvBody_andCorrectHeaders() {
        when(service.exportProfilesCsv(any())).thenReturn("client,broker\nACME,BROKER-A\n");

        ResponseEntity<byte[]> re = controller.exportCsv(
                null, null, null, null, null, "client", "ASC");

        assertEquals(HttpStatus.OK, re.getStatusCode());
        assertNotNull(re.getBody());
        // UTF-8 BOM prepended so Excel opens with correct encoding.
        assertEquals((byte) 0xEF, re.getBody()[0]);
        assertEquals((byte) 0xBB, re.getBody()[1]);
        assertEquals((byte) 0xBF, re.getBody()[2]);
        // Content-Disposition attachment with a dated filename.
        String cd = re.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertNotNull(cd);
        assertTrue(cd.contains("customs-profiles-" + LocalDate.now() + ".csv"),
                "CSV filename must include today's date. Got: " + cd);
    }

    @Test
    void exportCsv_passesFiltersToServiceButForcesPage0Size1() {
        // The controller intentionally sets page=0 + size=1 in the filters so
        // downstream can't accidentally page-slice the export. Pin this.
        when(service.exportProfilesCsv(any())).thenReturn("client\n");

        controller.exportCsv("acme", "ACME", "UPS", "BROKER-A",
                List.of("US"), "client", "ASC");

        ArgumentCaptor<CustomsProfileFilters> captor = ArgumentCaptor.forClass(CustomsProfileFilters.class);
        verify(service).exportProfilesCsv(captor.capture());
        CustomsProfileFilters f = captor.getValue();
        assertEquals("acme", f.getSearch());
        assertEquals("ACME", f.getClientCode());
        assertEquals("UPS", f.getCarrier());
        assertEquals(0, f.getPage(), "export must force page=0");
        assertEquals(1, f.getSize(), "export must force size=1 (service ignores it and streams the full result)");
    }

    @Test
    void exportCsv_producesTheCsvContentType() {
        when(service.exportProfilesCsv(any())).thenReturn("x\n");

        ResponseEntity<byte[]> re = controller.exportCsv(
                null, null, null, null, null, "client", "ASC");

        assertNotNull(re.getHeaders().getContentType());
        assertTrue(re.getHeaders().getContentType().toString().startsWith(
                        MediaType.parseMediaType(com.multiship.backend.common.CsvMediaType.CSV_UTF8).toString()),
                "Content-Type must be CsvMediaType.CSV_UTF8 (Excel-friendly UTF-8 CSV).");
    }

    // ================ Role wiring (via reflection) ================

    private static PreAuthorize preAuth(String methodName, Class<?>... paramTypes) throws NoSuchMethodException {
        Method m = CustomsProfilesController.class.getMethod(methodName, paramTypes);
        PreAuthorize a = m.getAnnotation(PreAuthorize.class);
        assertNotNull(a, methodName + " must be @PreAuthorize-gated");
        return a;
    }

    @Test
    void preAuthorize_list_requiresAdminOrUser() throws NoSuchMethodException {
        assertEquals("hasAnyRole('ADMIN', 'USER')",
                preAuth("list", String.class, String.class, String.class, String.class,
                        List.class, String.class, String.class, int.class, int.class).value());
    }

    @Test
    void preAuthorize_stats_requiresAdminOrUser() throws NoSuchMethodException {
        assertEquals("hasAnyRole('ADMIN', 'USER')", preAuth("stats").value());
    }

    @Test
    void preAuthorize_exportCsv_requiresAdminOrUser() throws NoSuchMethodException {
        assertEquals("hasAnyRole('ADMIN', 'USER')",
                preAuth("exportCsv", String.class, String.class, String.class, String.class,
                        List.class, String.class, String.class).value());
    }

    @Test
    void classLevelRequestMapping_pinnedToV1CustomsProfiles() {
        assertEquals("/api/v1/customs-profiles",
                CustomsProfilesController.class.getAnnotation(RequestMapping.class).value()[0]);
    }

    @Test
    void methodMappings_pinnedByReflection() throws NoSuchMethodException {
        assertNotNull(CustomsProfilesController.class.getMethod(
                "list", String.class, String.class, String.class, String.class,
                List.class, String.class, String.class, int.class, int.class)
                .getAnnotation(GetMapping.class));
        assertNotNull(CustomsProfilesController.class.getMethod("stats")
                .getAnnotation(GetMapping.class));
        assertNotNull(CustomsProfilesController.class.getMethod(
                "exportCsv", String.class, String.class, String.class, String.class,
                List.class, String.class, String.class)
                .getAnnotation(GetMapping.class));
    }

    // ================ Cross-cutting ================

    @Test
    void constructor_isPureDelegation_noEagerServiceCalls() {
        ClientCustomsProfileService fresh = mock(ClientCustomsProfileService.class);
        new CustomsProfilesController(fresh);
        verifyNoInteractions(fresh);
    }
}
