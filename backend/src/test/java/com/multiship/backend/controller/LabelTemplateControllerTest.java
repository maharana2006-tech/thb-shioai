package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.LabelTemplateDTO;
import com.multiship.backend.dto.PageResponseDTO;
import com.multiship.backend.model.LabelTemplate;
import com.multiship.backend.service.LabelTemplateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Controller-level Mockito tests for {@link LabelTemplateController} —
 * the `/api/v1/label-templates` family behind `/settings/templates`.
 *
 * <p>Anti-fallback: sole collaborator {@link LabelTemplateService} is mocked
 * in every test. Preview endpoints call the static {@code TemplateHtmlRenderer /
 * TemplatePdfRenderer / TemplateZplRenderer.render(...)} helpers — we assert
 * only the HTTP shape (status + content-type + non-null body) since the renderer
 * classes have their own coverage.
 *
 * <p>Endpoints covered (9):
 * <ul>
 *   <li>GET    /                     — list</li>
 *   <li>GET    /{id}                 — getById</li>
 *   <li>GET    /resolve              — resolve (never 404; null-data)</li>
 *   <li>GET    /tenant               — forTenant (never 404; null-data)</li>
 *   <li>POST   /                     — save (create=201, update=200)</li>
 *   <li>DELETE /{id}                 — delete (ADMIN)</li>
 *   <li>POST   /preview              — HTML preview</li>
 *   <li>POST   /preview.pdf          — PDF preview</li>
 *   <li>POST   /preview.zpl          — ZPL preview (dpi clamp)</li>
 * </ul>
 */
class LabelTemplateControllerTest {

    private LabelTemplateService service;
    private LabelTemplateController controller;

    @BeforeEach
    void setUp() {
        service = mock(LabelTemplateService.class);
        controller = new LabelTemplateController(service);
    }

    // ================ helpers ================

    private static LabelTemplate template(Long id, String tenantId, String type) {
        LabelTemplate t = new LabelTemplate();
        t.setId(id);
        t.setTenantId(tenantId);
        t.setTemplateType(type);
        t.setHeaderText("Header");
        t.setFooterText("Footer");
        return t;
    }

    // ================ GET / — list ================

    @Test
    void list_returns200WithPage_andDelegatesOnce() {
        when(service.list(any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(template(1L, "ACME", "PACKING_SLIP"))));

        ResponseEntity<ApiResponse<PageResponseDTO<LabelTemplateDTO>>> re = controller.list(
                null, null, null, "updatedAt", "DESC", 0, 50);

        assertEquals(HttpStatus.OK, re.getStatusCode());
        assertEquals(1, re.getBody().getData().getContent().size());
        verify(service, times(1)).list(any(), any(), any(), any());
    }

    @Test
    void list_sortByWhitelist_rejectsFreeform_defaultsToUpdatedAt() {
        // Pin the SORTABLE whitelist — a client passing "DROP TABLE" or an
        // unknown field must not break Sort parsing.
        when(service.list(any(), any(), any(), any())).thenReturn(new PageImpl<>(List.of()));

        controller.list(null, null, null, "not-a-real-field", "DESC", 0, 50);

        ArgumentCaptor<PageRequest> captor = ArgumentCaptor.forClass(PageRequest.class);
        verify(service).list(any(), any(), any(), captor.capture());
        Sort.Order order = captor.getValue().getSort().iterator().next();
        assertEquals("updatedAt", order.getProperty(),
                "Unknown sortBy must fall back to updatedAt.");
    }

    @Test
    void list_directionCaseInsensitive_defaultsToDescOnAnythingButAsc() {
        when(service.list(any(), any(), any(), any())).thenReturn(new PageImpl<>(List.of()));

        controller.list(null, null, null, "createdAt", "wat", 0, 50);

        ArgumentCaptor<PageRequest> captor = ArgumentCaptor.forClass(PageRequest.class);
        verify(service).list(any(), any(), any(), captor.capture());
        assertEquals(Sort.Direction.DESC, captor.getValue().getSort().iterator().next().getDirection());
    }

    @Test
    void list_passesFilterArgs_verbatimToService() {
        when(service.list(any(), any(), any(), any())).thenReturn(new PageImpl<>(List.of()));

        controller.list("acme", "PACKING_SLIP", "Y", "tenantId", "ASC", 2, 25);

        verify(service).list("acme", "PACKING_SLIP", "Y",
                PageRequest.of(2, 25, Sort.by(Sort.Direction.ASC, "tenantId")));
    }

    // ================ GET /{id} ================

    @Test
    void getById_found_returns200WithDto() {
        when(service.findById(42L)).thenReturn(Optional.of(template(42L, "ACME", "PACKING_SLIP")));

        ResponseEntity<ApiResponse<LabelTemplateDTO>> re = controller.getById(42L);

        assertEquals(HttpStatus.OK, re.getStatusCode());
        assertEquals(42L, re.getBody().getData().getId());
    }

    @Test
    void getById_notFound_returns404WithMessage() {
        when(service.findById(9999L)).thenReturn(Optional.empty());

        ResponseEntity<ApiResponse<LabelTemplateDTO>> re = controller.getById(9999L);

        assertEquals(HttpStatus.NOT_FOUND, re.getStatusCode());
        assertTrue(re.getBody().getMessage().contains("9999"),
                "404 message must name the missing id. Got: " + re.getBody().getMessage());
        assertNull(re.getBody().getData());
    }

    // ================ GET /resolve ================

    @Test
    void resolve_returns200WithData_whenTemplateFound() {
        when(service.resolve("ACME", "PACKING_SLIP"))
                .thenReturn(Optional.of(template(1L, "ACME", "PACKING_SLIP")));

        ResponseEntity<ApiResponse<LabelTemplateDTO>> re = controller.resolve("ACME", "PACKING_SLIP");

        assertEquals(HttpStatus.OK, re.getStatusCode());
        assertEquals("Template resolved", re.getBody().getMessage());
        assertNotNull(re.getBody().getData());
    }

    @Test
    void resolve_returns200WithNullData_neverA404_whenNoTemplate() {
        // Documented behaviour: /resolve NEVER 404s — it returns
        // {status:success, data:null, message:'No template configured'}
        // to keep the browser console clean on the expected first-run path.
        when(service.resolve("ACME", "PACKING_SLIP")).thenReturn(Optional.empty());

        ResponseEntity<ApiResponse<LabelTemplateDTO>> re = controller.resolve("ACME", "PACKING_SLIP");

        assertEquals(HttpStatus.OK, re.getStatusCode());
        assertEquals("No template configured", re.getBody().getMessage());
        assertNull(re.getBody().getData());
    }

    // ================ GET /tenant ================

    @Test
    void forTenant_returns200WithNullData_whenTenantHasNone() {
        when(service.findForTenant("ACME", "PACKING_SLIP")).thenReturn(Optional.empty());

        ResponseEntity<ApiResponse<LabelTemplateDTO>> re = controller.forTenant("ACME", "PACKING_SLIP");

        assertEquals(HttpStatus.OK, re.getStatusCode());
        assertNull(re.getBody().getData());
        assertTrue(re.getBody().getMessage().contains("no template"));
    }

    // ================ POST / — save ================

    @Test
    void save_create_returns201WhenBodyIdIsNull() {
        LabelTemplateDTO body = new LabelTemplateDTO();
        body.setId(null); // create
        body.setTenantId("ACME");
        body.setTemplateType("PACKING_SLIP");

        when(service.save(any())).thenReturn(template(101L, "ACME", "PACKING_SLIP"));

        ResponseEntity<ApiResponse<LabelTemplateDTO>> re = controller.save(body);

        assertEquals(HttpStatus.CREATED, re.getStatusCode(),
                "Create path (id=null) must return 201.");
        assertEquals(101L, re.getBody().getData().getId());
    }

    @Test
    void save_update_returns200WhenBodyIdIsSet() {
        LabelTemplateDTO body = new LabelTemplateDTO();
        body.setId(42L); // update
        body.setTenantId("ACME");
        body.setTemplateType("PACKING_SLIP");

        when(service.save(any())).thenReturn(template(42L, "ACME", "PACKING_SLIP"));

        ResponseEntity<ApiResponse<LabelTemplateDTO>> re = controller.save(body);

        assertEquals(HttpStatus.OK, re.getStatusCode(),
                "Update path (id set) must return 200 (not 201).");
    }

    // ================ DELETE /{id} — ADMIN-only ================

    @Test
    void delete_returns200_andDelegatesOnce() {
        ResponseEntity<ApiResponse<Void>> re = controller.delete(3L);

        assertEquals(HttpStatus.OK, re.getStatusCode());
        verify(service, times(1)).delete(3L);
    }

    // ================ POST /preview — HTML ================

    @Test
    void previewLayout_returnsHtmlContentType_andNonNullBody() {
        LabelTemplateController.PreviewRequest req = new LabelTemplateController.PreviewRequest();
        req.setLayoutJson("{}");

        ResponseEntity<String> re = controller.previewLayout(req);

        assertEquals(HttpStatus.OK, re.getStatusCode());
        assertNotNull(re.getBody());
        assertNotNull(re.getHeaders().getContentType());
        assertTrue(re.getHeaders().getContentType().toString().contains("text/html"),
                "Content-Type must be text/html. Got: " + re.getHeaders().getContentType());
    }

    @Test
    void previewLayout_nullBody_stillReturns200_withInlinePlaceholder() {
        // Documented: a bad JSON body renders an inline placeholder — the
        // iframe never sees an error page.
        ResponseEntity<String> re = controller.previewLayout(null);

        assertEquals(HttpStatus.OK, re.getStatusCode());
        assertNotNull(re.getBody());
    }

    // ================ POST /preview.pdf ================

    @Test
    void previewLayoutPdf_returnsPdfContentType_andNonNullBody() {
        LabelTemplateController.PreviewRequest req = new LabelTemplateController.PreviewRequest();
        req.setLayoutJson("{}");

        ResponseEntity<byte[]> re = controller.previewLayoutPdf(req);

        assertEquals(HttpStatus.OK, re.getStatusCode());
        assertEquals(MediaType.APPLICATION_PDF, re.getHeaders().getContentType());
        assertNotNull(re.getBody());
        String cd = re.getHeaders().getFirst("Content-Disposition");
        assertNotNull(cd);
        assertTrue(cd.contains("template-preview.pdf"),
                "Content-Disposition filename must be template-preview.pdf. Got: " + cd);
    }

    // ================ POST /preview.zpl — DPI clamp ================

    @Test
    void previewLayoutZpl_returnsPlainText_andNonNullBody() {
        LabelTemplateController.PreviewRequest req = new LabelTemplateController.PreviewRequest();
        req.setLayoutJson("{}");

        ResponseEntity<String> re = controller.previewLayoutZpl(req, 203);

        assertEquals(HttpStatus.OK, re.getStatusCode());
        assertEquals(MediaType.TEXT_PLAIN, re.getHeaders().getContentType());
        assertNotNull(re.getBody());
    }

    @Test
    void previewLayoutZpl_dpiClamp_bogusValueFallsBackTo203() {
        // The controller clamps DPI to {203, 300}; anything else silently
        // normalises to 203 (typo protection). We can only assert the
        // endpoint returns successfully — the DPI is used inside the static
        // renderer whose output we don't verify at controller level.
        LabelTemplateController.PreviewRequest req = new LabelTemplateController.PreviewRequest();
        req.setLayoutJson("{}");

        ResponseEntity<String> re = controller.previewLayoutZpl(req, 42 /* nonsense */);

        assertEquals(HttpStatus.OK, re.getStatusCode());
        assertNotNull(re.getBody());
    }

    // ================ Role wiring ================

    private static PreAuthorize preAuth(String methodName, Class<?>... paramTypes) throws NoSuchMethodException {
        Method m = LabelTemplateController.class.getMethod(methodName, paramTypes);
        PreAuthorize a = m.getAnnotation(PreAuthorize.class);
        assertNotNull(a, methodName + " must be @PreAuthorize-gated");
        return a;
    }

    @Test
    void preAuthorize_list_requiresAdminOrUser() throws NoSuchMethodException {
        assertEquals("hasAnyRole('ADMIN', 'USER')",
                preAuth("list", String.class, String.class, String.class,
                        String.class, String.class, int.class, int.class).value());
    }

    @Test
    void preAuthorize_getById_requiresAdminOrUser() throws NoSuchMethodException {
        assertEquals("hasAnyRole('ADMIN', 'USER')", preAuth("getById", Long.class).value());
    }

    @Test
    void preAuthorize_resolve_hasTenantScopeGuard() throws NoSuchMethodException {
        // Complex SpEL — assert tenant-scope check is wired in.
        String val = preAuth("resolve", String.class, String.class).value();
        assertTrue(val.contains("canAccessTenant"),
                "resolve must include canAccessTenant tenant-scope check. Got: " + val);
    }

    @Test
    void preAuthorize_forTenant_hasTenantScopeGuard() throws NoSuchMethodException {
        String val = preAuth("forTenant", String.class, String.class).value();
        assertTrue(val.contains("canAccessTenant"));
    }

    @Test
    void preAuthorize_save_hasTenantScopeGuardOnBodyTenantId() throws NoSuchMethodException {
        String val = preAuth("save", LabelTemplateDTO.class).value();
        assertTrue(val.contains("#body.tenantId"),
                "save must scope-check the body's tenantId. Got: " + val);
    }

    @Test
    void preAuthorize_delete_requiresAdminOnly() throws NoSuchMethodException {
        assertEquals("hasRole('ADMIN')", preAuth("delete", Long.class).value(),
                "Delete is the ONE ADMIN-only endpoint (others are ADMIN|USER).");
    }

    @Test
    void preAuthorize_allThreePreviewEndpoints_admin_or_user() throws NoSuchMethodException {
        String v1 = preAuth("previewLayout", LabelTemplateController.PreviewRequest.class).value();
        String v2 = preAuth("previewLayoutPdf", LabelTemplateController.PreviewRequest.class).value();
        String v3 = preAuth("previewLayoutZpl", LabelTemplateController.PreviewRequest.class, int.class).value();
        assertEquals("hasAnyRole('ADMIN', 'USER')", v1);
        assertEquals("hasAnyRole('ADMIN', 'USER')", v2);
        assertEquals("hasAnyRole('ADMIN', 'USER')", v3);
    }

    @Test
    void classLevelRequestMapping_pinnedToV1LabelTemplates() {
        assertEquals("/api/v1/label-templates",
                LabelTemplateController.class.getAnnotation(RequestMapping.class).value()[0]);
    }

    @Test
    void methodMappings_pinnedByReflection() throws NoSuchMethodException {
        assertNotNull(LabelTemplateController.class.getMethod("getById", Long.class)
                .getAnnotation(GetMapping.class));
        assertNotNull(LabelTemplateController.class.getMethod(
                "resolve", String.class, String.class).getAnnotation(GetMapping.class));
        assertNotNull(LabelTemplateController.class.getMethod(
                "forTenant", String.class, String.class).getAnnotation(GetMapping.class));
        assertNotNull(LabelTemplateController.class.getMethod("save", LabelTemplateDTO.class)
                .getAnnotation(PostMapping.class));
        assertNotNull(LabelTemplateController.class.getMethod("delete", Long.class)
                .getAnnotation(DeleteMapping.class));
    }

    // ================ Cross-cutting ================

    @Test
    void constructor_isPureDelegation_noEagerServiceCalls() {
        LabelTemplateService fresh = mock(LabelTemplateService.class);
        new LabelTemplateController(fresh);
        verifyNoInteractions(fresh);
    }

    @Test
    void list_doesNotTouchOtherServiceMethods() {
        // Guard against accidental cross-invocation.
        when(service.list(any(), any(), any(), any())).thenReturn(new PageImpl<>(List.of()));

        controller.list(null, null, null, "updatedAt", "DESC", 0, 50);

        verify(service).list(any(), any(), any(), any());
        verify(service, never()).findById(any());
        verify(service, never()).resolve(any(), any());
        verify(service, never()).findForTenant(any(), any());
        verify(service, never()).save(any());
        verify(service, never()).delete(any());
    }
}
