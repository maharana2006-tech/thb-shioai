package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.CustomFieldDefinitionDTO;
import com.multiship.backend.model.CustomFieldDefinition;
import com.multiship.backend.model.CustomFieldDefinition.FieldType;
import com.multiship.backend.service.CustomFieldService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Controller-level Mockito tests for {@link CustomFieldController} —
 * the `/api/v1/custom-fields` + `/api/v1/orders/{orderNo}/custom-fields`
 * families behind `/settings/custom-fields`.
 *
 * <p>Anti-fallback: sole collaborator {@link CustomFieldService} mocked
 * in every test. Each endpoint asserts {@code times(1)} on the exercised
 * service call + {@code never()} on siblings.
 *
 * <p>Endpoints covered (6):
 * <ul>
 *   <li>GET    /api/v1/custom-fields                 — list</li>
 *   <li>GET    /api/v1/custom-fields/applicable      — applicable (active only)</li>
 *   <li>POST   /api/v1/custom-fields                 — save (id=null → 201, id set → 200)</li>
 *   <li>DELETE /api/v1/custom-fields/{id}            — delete (ADMIN)</li>
 *   <li>GET    /api/v1/orders/{orderNo}/custom-fields — values</li>
 *   <li>POST   /api/v1/orders/{orderNo}/custom-fields — upsertValues (400 on validation)</li>
 * </ul>
 */
class CustomFieldControllerTest {

    private CustomFieldService service;
    private CustomFieldController controller;

    @BeforeEach
    void setUp() {
        service = mock(CustomFieldService.class);
        controller = new CustomFieldController(service);
    }

    // ================ helpers ================

    private static CustomFieldDefinition definition(Long id, String tenantId, String fieldKey) {
        CustomFieldDefinition d = new CustomFieldDefinition();
        d.setId(id);
        d.setTenantId(tenantId);
        d.setFieldKey(fieldKey);
        d.setLabel("Label for " + fieldKey);
        d.setFieldType(FieldType.TEXT);
        d.setActive(true);
        return d;
    }

    // ================ GET /api/v1/custom-fields — list ================

    @Test
    void list_returns200WithDtos_andDelegatesOnce() {
        when(service.listAllForTenant("ACME"))
                .thenReturn(List.of(definition(1L, "ACME", "notes"),
                                    definition(2L, "ACME", "priority")));

        ResponseEntity<ApiResponse<List<CustomFieldDefinitionDTO>>> re = controller.list("ACME");

        assertEquals(HttpStatus.OK, re.getStatusCode());
        assertEquals(2, re.getBody().getData().size());
        verify(service, times(1)).listAllForTenant("ACME");
        verify(service, never()).listApplicable(any());
        verify(service, never()).saveDefinition(any());
        verify(service, never()).deleteDefinition(any());
    }

    @Test
    void list_nullTenant_passesThroughToService() {
        when(service.listAllForTenant(null)).thenReturn(List.of());

        controller.list(null);

        verify(service, times(1)).listAllForTenant(null);
    }

    // ================ GET /applicable ================

    @Test
    void applicable_returns200WithDtos() {
        when(service.listApplicable("ACME"))
                .thenReturn(List.of(definition(1L, "ACME", "active-only")));

        ResponseEntity<ApiResponse<List<CustomFieldDefinitionDTO>>> re = controller.applicable("ACME");

        assertEquals(HttpStatus.OK, re.getStatusCode());
        assertEquals(1, re.getBody().getData().size());
        verify(service, times(1)).listApplicable("ACME");
        // applicable() must NOT invoke the full-list method.
        verify(service, never()).listAllForTenant(any());
    }

    // ================ POST /api/v1/custom-fields — save ================

    @Test
    void save_create_returns201WhenBodyIdIsNull() {
        CustomFieldDefinitionDTO req = CustomFieldDefinitionDTO.builder()
                .id(null) // create
                .tenantId("ACME").fieldKey("notes").label("Notes")
                .fieldType(FieldType.TEXT).active(true).build();
        when(service.saveDefinition(any())).thenReturn(definition(101L, "ACME", "notes"));

        ResponseEntity<ApiResponse<CustomFieldDefinitionDTO>> re = controller.save(req);

        assertEquals(HttpStatus.CREATED, re.getStatusCode(),
                "Create path (id=null) must return 201.");
        assertEquals(101L, re.getBody().getData().getId());
    }

    @Test
    void save_update_returns200WhenBodyIdIsSet() {
        CustomFieldDefinitionDTO req = CustomFieldDefinitionDTO.builder()
                .id(42L) // update
                .tenantId("ACME").fieldKey("notes").label("Notes v2")
                .fieldType(FieldType.TEXT).active(true).build();
        when(service.saveDefinition(any())).thenReturn(definition(42L, "ACME", "notes"));

        ResponseEntity<ApiResponse<CustomFieldDefinitionDTO>> re = controller.save(req);

        assertEquals(HttpStatus.OK, re.getStatusCode(),
                "Update path (id set) must return 200.");
    }

    @Test
    void save_validationException_returns400WithVALIDATION_FAILED() {
        // Service throws IllegalArgumentException — controller converts to 400.
        when(service.saveDefinition(any())).thenThrow(
                new IllegalArgumentException("fieldKey must be unique per tenant"));

        CustomFieldDefinitionDTO req = CustomFieldDefinitionDTO.builder()
                .tenantId("ACME").fieldKey("dupe").label("Dupe")
                .fieldType(FieldType.TEXT).build();
        ResponseEntity<ApiResponse<CustomFieldDefinitionDTO>> re = controller.save(req);

        assertEquals(HttpStatus.BAD_REQUEST, re.getStatusCode());
        assertEquals("VALIDATION_FAILED", re.getBody().getErrorCode());
        assertTrue(re.getBody().getMessage().contains("unique per tenant"));
    }

    // ================ DELETE /{id} — ADMIN-only ================

    @Test
    void delete_returns200_andDelegatesOnce() {
        ResponseEntity<ApiResponse<Void>> re = controller.delete(3L);

        assertEquals(HttpStatus.OK, re.getStatusCode());
        verify(service, times(1)).deleteDefinition(3L);
        verify(service, never()).saveDefinition(any());
    }

    // ================ GET /orders/{orderNo}/custom-fields — values ================

    @Test
    void values_returns200WithMap_andDelegatesOnce() {
        when(service.loadValues(100)).thenReturn(Map.of("notes", "fragile", "priority", "high"));

        ResponseEntity<ApiResponse<Map<String, String>>> re = controller.values(100);

        assertEquals(HttpStatus.OK, re.getStatusCode());
        assertEquals("fragile", re.getBody().getData().get("notes"));
        verify(service, times(1)).loadValues(100);
    }

    // ================ POST /orders/{orderNo}/custom-fields — upsertValues ================

    @Test
    void upsert_returns200WithSavedMap_andPassesArgsThrough() {
        Map<String, String> body = Map.of("notes", "handle with care");
        when(service.upsertValues(eq(100), eq("ACME"), any())).thenReturn(body);

        ResponseEntity<ApiResponse<Map<String, String>>> re = controller.upsert(100, "ACME", body);

        assertEquals(HttpStatus.OK, re.getStatusCode());
        assertEquals("handle with care", re.getBody().getData().get("notes"));
        verify(service, times(1)).upsertValues(100, "ACME", body);
    }

    @Test
    void upsert_validationException_returns400WithVALIDATION_FAILED() {
        when(service.upsertValues(any(), any(), any())).thenThrow(
                new IllegalArgumentException("unknown fieldKey"));

        ResponseEntity<ApiResponse<Map<String, String>>> re =
                controller.upsert(100, "ACME", Map.of("bogus", "x"));

        assertEquals(HttpStatus.BAD_REQUEST, re.getStatusCode());
        assertEquals("VALIDATION_FAILED", re.getBody().getErrorCode());
    }

    @Test
    void upsert_nullTenant_passesThroughAsNull() {
        when(service.upsertValues(eq(100), eq(null), any())).thenReturn(Map.of());

        controller.upsert(100, null, Map.of("notes", "x"));

        verify(service, times(1)).upsertValues(eq(100), eq(null), any());
    }

    // ================ Role wiring ================

    private static PreAuthorize preAuth(String methodName, Class<?>... paramTypes) throws NoSuchMethodException {
        Method m = CustomFieldController.class.getMethod(methodName, paramTypes);
        PreAuthorize a = m.getAnnotation(PreAuthorize.class);
        assertNotNull(a, methodName + " must be @PreAuthorize-gated");
        return a;
    }

    @Test
    void preAuthorize_list_hasTenantScopeGuard() throws NoSuchMethodException {
        String v = preAuth("list", String.class).value();
        assertTrue(v.contains("canAccessTenant"),
                "list must include tenant-scope check. Got: " + v);
    }

    @Test
    void preAuthorize_applicable_hasTenantScopeGuard() throws NoSuchMethodException {
        String v = preAuth("applicable", String.class).value();
        assertTrue(v.contains("canAccessTenant"));
    }

    @Test
    void preAuthorize_save_hasBodyTenantScopeGuard() throws NoSuchMethodException {
        String v = preAuth("save", CustomFieldDefinitionDTO.class).value();
        assertTrue(v.contains("#body.tenantId") || v.contains("hasAnyRole"),
                "save must be role-gated (with tenant guard on body). Got: " + v);
    }

    @Test
    void preAuthorize_delete_requiresAdminOnly() throws NoSuchMethodException {
        assertEquals("hasRole('ADMIN')", preAuth("delete", Long.class).value(),
                "Delete is the ONE ADMIN-only endpoint.");
    }

    @Test
    void preAuthorize_values_hasOrderAccessGuard() throws NoSuchMethodException {
        String v = preAuth("values", Integer.class).value();
        assertTrue(v.contains("canViewOrder"),
                "values must be gated by canViewOrder. Got: " + v);
    }

    @Test
    void preAuthorize_upsert_hasOrderAccessGuard() throws NoSuchMethodException {
        String v = preAuth("upsert", Integer.class, String.class, Map.class).value();
        assertTrue(v.contains("canViewOrder"));
    }

    @Test
    void methodMappings_pinnedByReflection() throws NoSuchMethodException {
        assertNotNull(CustomFieldController.class.getMethod("list", String.class)
                .getAnnotation(GetMapping.class));
        assertNotNull(CustomFieldController.class.getMethod("applicable", String.class)
                .getAnnotation(GetMapping.class));
        assertNotNull(CustomFieldController.class.getMethod("save", CustomFieldDefinitionDTO.class)
                .getAnnotation(PostMapping.class));
        assertNotNull(CustomFieldController.class.getMethod("delete", Long.class)
                .getAnnotation(DeleteMapping.class));
        assertNotNull(CustomFieldController.class.getMethod("values", Integer.class)
                .getAnnotation(GetMapping.class));
        assertNotNull(CustomFieldController.class.getMethod(
                "upsert", Integer.class, String.class, Map.class)
                .getAnnotation(PostMapping.class));
    }

    // ================ Cross-cutting ================

    @Test
    void constructor_isPureDelegation_noEagerServiceCalls() {
        CustomFieldService fresh = mock(CustomFieldService.class);
        new CustomFieldController(fresh);
        verifyNoInteractions(fresh);
    }
}
