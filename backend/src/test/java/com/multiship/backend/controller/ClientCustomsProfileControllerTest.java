package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ClientCustomsProfileDTO;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.service.ClientCustomsProfileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Controller-level Mockito tests for {@link ClientCustomsProfileController}
 * — per-client importer/broker profiles at
 * `/api/v1/clients/{clientCode}/customs-profiles`.
 *
 * <p>Anti-fallback: sole collaborator {@link ClientCustomsProfileService}
 * is mocked. No repository / downstream service is constructed. Each
 * endpoint asserts {@code times(1)} on its service call + {@code never()}
 * on siblings.
 *
 * <p>Endpoints covered (5):
 * <ul>
 *   <li>GET    /                — list a client's profiles</li>
 *   <li>GET    /{id}            — get one</li>
 *   <li>POST   /                — create (forces id=null before delegating)</li>
 *   <li>PUT    /{id}            — update (forces id from path)</li>
 *   <li>DELETE /{id}            — delete</li>
 * </ul>
 *
 * <p>Every endpoint is {@code @PreAuthorize} gated with the tenant scope
 * check: {@code hasAnyRole('ADMIN', 'USER') and @accessScope.canAccessTenant(authentication, #clientCode)}.
 */
class ClientCustomsProfileControllerTest {

    private ClientCustomsProfileService service;
    private ClientCustomsProfileController controller;

    @BeforeEach
    void setUp() {
        service = mock(ClientCustomsProfileService.class);
        controller = new ClientCustomsProfileController(service);
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

    private static ClientCustomsProfileDTO dto(Long id, String clientCode) {
        return ClientCustomsProfileDTO.builder()
                .id(id).clientCode(clientCode).countries(List.of("US")).build();
    }

    // ================ GET / — list ================

    @Test
    void list_returns200WithList_andDelegatesOnce() {
        when(service.list("ACME")).thenReturn(ok(List.of(dto(1L, "ACME"), dto(2L, "ACME"))));

        ResponseEntity<ApiResponse<List<ClientCustomsProfileDTO>>> re = controller.list("ACME");

        assertEquals(HttpStatus.OK, re.getStatusCode());
        assertEquals(2, re.getBody().getData().size());
        verify(service, times(1)).list("ACME");
        verify(service, never()).get(any(), any());
        verify(service, never()).upsert(any(), any());
        verify(service, never()).delete(any(), any());
    }

    @Test
    void list_serviceError500_isEchoed() {
        when(service.list("ACME")).thenReturn(err(500, ErrorCode.VALIDATION_ERROR, "boom"));

        ResponseEntity<ApiResponse<List<ClientCustomsProfileDTO>>> re = controller.list("ACME");

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, re.getStatusCode());
    }

    // ================ GET /{id} ================

    @Test
    void get_returns200WithProfile_andDelegatesOnce() {
        when(service.get("ACME", 42L)).thenReturn(ok(dto(42L, "ACME")));

        ResponseEntity<ApiResponse<ClientCustomsProfileDTO>> re = controller.get("ACME", 42L);

        assertEquals(HttpStatus.OK, re.getStatusCode());
        assertEquals(42L, re.getBody().getData().getId());
        verify(service, times(1)).get("ACME", 42L);
    }

    @Test
    void get_profileNotFound_returns404() {
        when(service.get("ACME", 99L)).thenReturn(err(404, ErrorCode.VALIDATION_ERROR, "not found"));

        ResponseEntity<ApiResponse<ClientCustomsProfileDTO>> re = controller.get("ACME", 99L);

        assertEquals(HttpStatus.NOT_FOUND, re.getStatusCode());
    }

    // ================ POST / — create ================

    @Test
    void create_forcesIdToNullBeforeDelegating() {
        // Pin the controller's own null-id enforcement — a body with an
        // id must NOT be able to become an update via this endpoint.
        ClientCustomsProfileDTO req = dto(999L /* attempt an id */, "ACME");
        ClientCustomsProfileDTO saved = dto(101L, "ACME");
        when(service.upsert(eq("ACME"), any())).thenReturn(ok(saved));

        ResponseEntity<ApiResponse<ClientCustomsProfileDTO>> re = controller.create("ACME", req);

        assertEquals(HttpStatus.OK, re.getStatusCode());
        assertEquals(101L, re.getBody().getData().getId());

        ArgumentCaptor<ClientCustomsProfileDTO> captor =
                ArgumentCaptor.forClass(ClientCustomsProfileDTO.class);
        verify(service).upsert(eq("ACME"), captor.capture());
        assertNull(captor.getValue().getId(),
                "Controller must null-out the body id on create — otherwise POST could sneak an update.");
    }

    @Test
    void create_validation422_isEchoed() {
        when(service.upsert(any(), any()))
                .thenReturn(err(422, ErrorCode.VALIDATION_ERROR, "profileName required"));

        ResponseEntity<ApiResponse<ClientCustomsProfileDTO>> re =
                controller.create("ACME", dto(null, "ACME"));

        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, re.getStatusCode());
    }

    // ================ PUT /{id} — update ================

    @Test
    void update_forcesIdFromPath_notBody() {
        // Pin: update path id wins over any body id, so a PUT can't target
        // a different profile than the URL specifies.
        ClientCustomsProfileDTO req = dto(999L /* body id ignored */, "ACME");
        ClientCustomsProfileDTO saved = dto(42L, "ACME");
        when(service.upsert(eq("ACME"), any())).thenReturn(ok(saved));

        ResponseEntity<ApiResponse<ClientCustomsProfileDTO>> re = controller.update("ACME", 42L, req);

        assertEquals(HttpStatus.OK, re.getStatusCode());

        ArgumentCaptor<ClientCustomsProfileDTO> captor =
                ArgumentCaptor.forClass(ClientCustomsProfileDTO.class);
        verify(service).upsert(eq("ACME"), captor.capture());
        assertEquals(42L, captor.getValue().getId(),
                "Controller must set the body id from the path — body-id can't override the URL target.");
    }

    @Test
    void update_notFound_returns404() {
        when(service.upsert(any(), any())).thenReturn(err(404, ErrorCode.VALIDATION_ERROR, "not found"));

        ResponseEntity<ApiResponse<ClientCustomsProfileDTO>> re =
                controller.update("ACME", 99L, dto(99L, "ACME"));

        assertEquals(HttpStatus.NOT_FOUND, re.getStatusCode());
    }

    // ================ DELETE /{id} ================

    @Test
    void delete_returns200_andDelegatesOnce() {
        when(service.delete("ACME", 3L)).thenReturn(ok(null));

        ResponseEntity<ApiResponse<Void>> re = controller.delete("ACME", 3L);

        assertEquals(HttpStatus.OK, re.getStatusCode());
        verify(service, times(1)).delete("ACME", 3L);
        verify(service, never()).list(any());
        verify(service, never()).get(any(), any());
    }

    @Test
    void delete_notFound_returns404() {
        when(service.delete("ACME", 99L)).thenReturn(err(404, ErrorCode.VALIDATION_ERROR, "not found"));

        ResponseEntity<ApiResponse<Void>> re = controller.delete("ACME", 99L);

        assertEquals(HttpStatus.NOT_FOUND, re.getStatusCode());
    }

    // ================ Role wiring ================

    private static PreAuthorize preAuth(String methodName, Class<?>... paramTypes) throws NoSuchMethodException {
        Method m = ClientCustomsProfileController.class.getMethod(methodName, paramTypes);
        PreAuthorize a = m.getAnnotation(PreAuthorize.class);
        assertNotNull(a, methodName + " must be @PreAuthorize-gated");
        return a;
    }

    @Test
    void preAuthorize_every_endpoint_gated_with_tenant_scope_check() throws NoSuchMethodException {
        // The tenant-scope SpEL is what enforces cross-client isolation
        // (a USER for CLIENT-A can't read/write CLIENT-B's profiles).
        String expected = "hasAnyRole('ADMIN', 'USER') and @accessScope.canAccessTenant(authentication, #clientCode)";
        assertEquals(expected, preAuth("list", String.class).value());
        assertEquals(expected, preAuth("get", String.class, Long.class).value());
        assertEquals(expected, preAuth("create", String.class, ClientCustomsProfileDTO.class).value());
        assertEquals(expected, preAuth("update", String.class, Long.class, ClientCustomsProfileDTO.class).value());
        assertEquals(expected, preAuth("delete", String.class, Long.class).value());
    }

    @Test
    void classLevelRequestMapping_pinnedToClientScopedPath() {
        assertEquals("/api/v1/clients/{clientCode}/customs-profiles",
                ClientCustomsProfileController.class.getAnnotation(RequestMapping.class).value()[0]);
    }

    @Test
    void methodMappings_pinnedByReflection() throws NoSuchMethodException {
        assertNotNull(ClientCustomsProfileController.class.getMethod("list", String.class)
                .getAnnotation(GetMapping.class));
        assertNotNull(ClientCustomsProfileController.class.getMethod("get", String.class, Long.class)
                .getAnnotation(GetMapping.class));
        assertNotNull(ClientCustomsProfileController.class.getMethod(
                "create", String.class, ClientCustomsProfileDTO.class)
                .getAnnotation(PostMapping.class));
        assertNotNull(ClientCustomsProfileController.class.getMethod(
                "update", String.class, Long.class, ClientCustomsProfileDTO.class)
                .getAnnotation(PutMapping.class));
        assertNotNull(ClientCustomsProfileController.class.getMethod("delete", String.class, Long.class)
                .getAnnotation(DeleteMapping.class));
    }

    // ================ Cross-cutting ================

    @Test
    void list_doesNotTouchOtherServiceMethods() {
        // Guard against copy-paste refactor accidents.
        when(service.list(any())).thenReturn(ok(List.of()));

        controller.list("ACME");

        verify(service).list("ACME");
        verify(service, never()).get(any(), any());
        verify(service, never()).upsert(any(), any());
        verify(service, never()).delete(any(), any());
    }

    @Test
    void constructor_isPureDelegation_noEagerServiceCalls() {
        ClientCustomsProfileService fresh = mock(ClientCustomsProfileService.class);
        new ClientCustomsProfileController(fresh);
        verifyNoInteractions(fresh);
    }
}
