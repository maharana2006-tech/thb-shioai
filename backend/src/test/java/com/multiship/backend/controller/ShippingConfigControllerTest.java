package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.model.PackagePreset;
import com.multiship.backend.model.ServicePackage;
import com.multiship.backend.model.ShipViaMapping;
import com.multiship.backend.model.ShippingService;
import com.multiship.backend.service.ShippingConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Controller-level Mockito tests for {@link ShippingConfigController} — the
 * `/api/v1/shipping-services`, `/api/v1/ship-method-rules`, and
 * `/api/v1/package-presets` endpoint families feeding the /settings/shipping-catalog page.
 *
 * <p>Anti-fallback: the sole collaborator injected here is {@link ShippingConfigService},
 * mocked in every test. No {@code CarrierConnector} is constructed, no {@code RestTemplate}
 * or {@code RestClient} is instantiated. Every endpoint asserts {@code times(1)} on the
 * exercised service method and (where the endpoint could conceivably branch)
 * {@code never()} on sibling service methods.
 *
 * <p>Endpoints covered (12 handler methods, 14 routes):
 * <ul>
 *   <li>GET    /shipping-services                  — catalog</li>
 *   <li>POST   /shipping-services/sync             — sync (ADMIN)</li>
 *   <li>PATCH  /shipping-services/{id}             — setEnabled</li>
 *   <li>PUT    /ship-method-rules                  — upsertRule</li>
 *   <li>DELETE /ship-method-rules/{id}             — deleteRule</li>
 *   <li>PUT    /shipping-services/{id}/packages    — setServicePackages</li>
 *   <li>GET    /package-presets                    — listPresets</li>
 *   <li>POST   /package-presets/sync               — syncPackages (ADMIN)</li>
 *   <li>POST   /package-presets                    — createPreset</li>
 *   <li>PUT    /package-presets/{id}               — updatePreset</li>
 *   <li>PUT    /package-presets/{id}/default       — setDefault</li>
 *   <li>DELETE /package-presets/{id}               — deletePreset</li>
 * </ul>
 *
 * <p>Role-based 401/403 checks (Spring Security chain) are covered by security-slice
 * integration tests. Here we assert (a) the controller does not swallow the service's
 * non-200 codes (Sprint 51 audit pattern:
 * {@code ResponseEntity.status(response.getCode()).body(response)}), and (b) each
 * {@code @PreAuthorize} annotation on every handler is pinned via reflection so a
 * future refactor that drops/widens a role guard fails loudly.
 */
class ShippingConfigControllerTest {

    private ShippingConfigService service;
    private ShippingConfigController controller;

    @BeforeEach
    void setUp() {
        service = mock(ShippingConfigService.class);
        controller = new ShippingConfigController(service);
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

    private static ShippingService service(Long id, boolean enabled) {
        ShippingService s = new ShippingService();
        s.setId(id);
        s.setCarrier("UPS");
        s.setServiceCode("GROUND");
        s.setEnabled(enabled);
        return s;
    }

    private static PackagePreset preset(Long id, boolean isDefault) {
        PackagePreset p = new PackagePreset();
        p.setId(id);
        p.setName("Small box");
        p.setCarrier("UPS");
        p.setIsDefault(isDefault);
        return p;
    }

    // ================ GET /shipping-services — catalog ================

    @Test
    void catalog_returns200WithMap_andDelegatesOnceWithOrigin() {
        Map<String, Object> data = new HashMap<>();
        data.put("services", List.of(service(1L, true)));
        ApiResponse<Map<String, Object>> resp = ok(data);
        when(service.catalog("US")).thenReturn(resp);

        ResponseEntity<ApiResponse<Map<String, Object>>> re = controller.catalog("US");

        assertEquals(HttpStatus.OK, re.getStatusCode());
        assertSame(resp, re.getBody());
        verify(service, times(1)).catalog("US");
    }

    @Test
    void catalog_nullOrigin_isPassedThroughToService() {
        // The controller passes null through — filtering is a service concern.
        when(service.catalog(null)).thenReturn(ok(Map.of("services", List.of())));

        ResponseEntity<ApiResponse<Map<String, Object>>> re = controller.catalog(null);

        assertEquals(HttpStatus.OK, re.getStatusCode());
        verify(service, times(1)).catalog(isNull());
    }

    @Test
    void catalog_serviceError500_isEchoedNot200() {
        ApiResponse<Map<String, Object>> resp =
                err(500, ErrorCode.VALIDATION_ERROR, "catalog build failed");
        when(service.catalog("US")).thenReturn(resp);

        ResponseEntity<ApiResponse<Map<String, Object>>> re = controller.catalog("US");

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, re.getStatusCode());
        assertEquals(ErrorCode.VALIDATION_ERROR.name(), re.getBody().getErrorCode());
    }

    // ================ POST /shipping-services/sync — syncFromCarrier (ADMIN) ================

    @Test
    void sync_returns200_andPassesCarrierAndOriginToService() {
        // Anti-fallback: the controller must delegate to the service. A test
        // that reaches a real CarrierConnector would fail here because
        // ShippingConfigService is the only injected dependency.
        Map<String, Object> data = new HashMap<>();
        data.put("added", 3);
        // Sprint 51 catalog sync — controller now calls the 3-arg overload
        // with accountId (null when the operator hasn't picked one from
        // the sync menu).
        when(service.syncFromCarrier("UPS", "US", null)).thenReturn(ok(data));

        ResponseEntity<ApiResponse<Map<String, Object>>> re = controller.sync("UPS", "US", null);

        assertEquals(HttpStatus.OK, re.getStatusCode());
        verify(service, times(1)).syncFromCarrier(eq("UPS"), eq("US"), org.mockito.ArgumentMatchers.isNull());
        verify(service, never()).syncPackagesFromCarrier(any(), any(), any());
    }

    @Test
    void sync_unknownCarrier_422IsEchoed() {
        ApiResponse<Map<String, Object>> resp =
                err(422, ErrorCode.VALIDATION_ERROR, "unknown carrier");
        when(service.syncFromCarrier("BOGUS", "US", null)).thenReturn(resp);

        ResponseEntity<ApiResponse<Map<String, Object>>> re = controller.sync("BOGUS", "US", null);

        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, re.getStatusCode());
        assertEquals(ErrorCode.VALIDATION_ERROR.name(), re.getBody().getErrorCode());
    }

    @Test
    void sync_withAccountId_flowsThroughToService() {
        // Sprint 51 — operator-picked account flows through to the service
        // layer as-is; the connector-level env routing (SANDBOX vs PROD)
        // happens inside syncFromCarrier by looking up the account.
        Map<String, Object> data = new HashMap<>();
        data.put("added", 1);
        when(service.syncFromCarrier("UPS", "US", 42L)).thenReturn(ok(data));

        ResponseEntity<ApiResponse<Map<String, Object>>> re = controller.sync("UPS", "US", 42L);

        assertEquals(HttpStatus.OK, re.getStatusCode());
        verify(service, times(1)).syncFromCarrier(eq("UPS"), eq("US"), eq(42L));
    }

    // ================ PATCH /shipping-services/{id} — setEnabled ================

    @Test
    void setEnabled_true_returns200_andPassesTrueToService() {
        when(service.setServiceEnabled(5L, true)).thenReturn(ok(service(5L, true)));

        ResponseEntity<ApiResponse<ShippingService>> re =
                controller.setEnabled(5L, Map.of("enabled", true));

        assertEquals(HttpStatus.OK, re.getStatusCode());
        assertTrue(re.getBody().getData().isEnabled());
        verify(service, times(1)).setServiceEnabled(5L, true);
    }

    @Test
    void setEnabled_false_returns200_andPassesFalseToService() {
        when(service.setServiceEnabled(5L, false)).thenReturn(ok(service(5L, false)));

        ResponseEntity<ApiResponse<ShippingService>> re =
                controller.setEnabled(5L, Map.of("enabled", false));

        assertEquals(HttpStatus.OK, re.getStatusCode());
        assertFalse(re.getBody().getData().isEnabled());
        verify(service, times(1)).setServiceEnabled(5L, false);
    }

    @Test
    void setEnabled_missingFlag_defaultsToFalse() {
        // Boolean.TRUE.equals(null) is false — the controller treats a
        // body without the flag as an explicit disable request.
        when(service.setServiceEnabled(5L, false)).thenReturn(ok(service(5L, false)));

        ResponseEntity<ApiResponse<ShippingService>> re =
                controller.setEnabled(5L, Map.of());

        assertEquals(HttpStatus.OK, re.getStatusCode());
        verify(service, times(1)).setServiceEnabled(5L, false);
    }

    @Test
    void setEnabled_serviceNotFound_returns404() {
        ApiResponse<ShippingService> resp =
                err(404, ErrorCode.VALIDATION_ERROR, "no such service");
        when(service.setServiceEnabled(99L, true)).thenReturn(resp);

        ResponseEntity<ApiResponse<ShippingService>> re =
                controller.setEnabled(99L, Map.of("enabled", true));

        assertEquals(HttpStatus.NOT_FOUND, re.getStatusCode());
    }

    // ================ PUT /ship-method-rules — upsertRule ================

    @Test
    void upsertRule_returns200_andPassesAll8FieldsPositionally() {
        // Pin the positional 8-arg dispatch so a future field-add on
        // ShipViaMapping doesn't silently shift arguments.
        ShipViaMapping in = new ShipViaMapping();
        in.setId(7L);
        in.setShipviaCd("GROUND");
        in.setClientCode("C001");
        in.setDestType("COUNTRY");
        in.setDestValue("US");
        in.setServiceId(42L);
        in.setAllowedPresetIds(List.of(1L, 2L));
        in.setWarehouseIds(List.of(10L));
        when(service.upsertRule(7L, "GROUND", "C001", "COUNTRY", "US", 42L,
                List.of(1L, 2L), List.of(10L))).thenReturn(ok(in));

        ResponseEntity<ApiResponse<ShipViaMapping>> re = controller.upsertRule(in);

        assertEquals(HttpStatus.OK, re.getStatusCode());
        assertNotNull(re.getBody().getData());
        verify(service, times(1)).upsertRule(
                eq(7L), eq("GROUND"), eq("C001"), eq("COUNTRY"),
                eq("US"), eq(42L), eq(List.of(1L, 2L)), eq(List.of(10L)));
    }

    @Test
    void upsertRule_validation422_isEchoed() {
        ShipViaMapping in = new ShipViaMapping();
        in.setShipviaCd("GROUND");
        when(service.upsertRule(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(err(422, ErrorCode.VALIDATION_ERROR, "clientCode required"));

        ResponseEntity<ApiResponse<ShipViaMapping>> re = controller.upsertRule(in);

        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, re.getStatusCode());
    }

    // ================ DELETE /ship-method-rules/{id} — deleteRule ================

    @Test
    void deleteRule_returns200_andDelegatesOnce() {
        when(service.deleteRule(3L)).thenReturn(ok(null));

        ResponseEntity<ApiResponse<Void>> re = controller.deleteRule(3L);

        assertEquals(HttpStatus.OK, re.getStatusCode());
        verify(service, times(1)).deleteRule(3L);
    }

    @Test
    void deleteRule_serviceError500_isEchoed() {
        when(service.deleteRule(3L)).thenReturn(err(500, ErrorCode.VALIDATION_ERROR, "boom"));

        ResponseEntity<ApiResponse<Void>> re = controller.deleteRule(3L);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, re.getStatusCode());
    }

    // ================ PUT /shipping-services/{id}/packages — setServicePackages ================

    @Test
    void setServicePackages_returns200_andPassesIdAndLinksThrough() {
        List<ServicePackage> links = List.of(new ServicePackage(), new ServicePackage());
        when(service.setServicePackages(5L, links)).thenReturn(ok(links));

        ResponseEntity<ApiResponse<List<ServicePackage>>> re =
                controller.setServicePackages(5L, links);

        assertEquals(HttpStatus.OK, re.getStatusCode());
        assertEquals(2, re.getBody().getData().size());
        verify(service, times(1)).setServicePackages(eq(5L), eq(links));
    }

    @Test
    void setServicePackages_serviceNotFound_returns404() {
        when(service.setServicePackages(anyLong(), any()))
                .thenReturn(err(404, ErrorCode.VALIDATION_ERROR, "service not found"));

        ResponseEntity<ApiResponse<List<ServicePackage>>> re =
                controller.setServicePackages(99L, List.of());

        assertEquals(HttpStatus.NOT_FOUND, re.getStatusCode());
    }

    // ================ GET /package-presets — listPresets ================

    @Test
    void listPresets_returns200_andDelegatesOnce() {
        when(service.listPresets()).thenReturn(ok(List.of(preset(1L, true))));

        ResponseEntity<ApiResponse<List<PackagePreset>>> re = controller.listPresets();

        assertEquals(HttpStatus.OK, re.getStatusCode());
        assertEquals(1, re.getBody().getData().size());
        verify(service, times(1)).listPresets();
    }

    @Test
    void listPresets_serviceError500_isEchoed() {
        when(service.listPresets()).thenReturn(err(500, ErrorCode.VALIDATION_ERROR, "listing failed"));

        ResponseEntity<ApiResponse<List<PackagePreset>>> re = controller.listPresets();

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, re.getStatusCode());
    }

    // ================ POST /package-presets/sync — syncPackages (ADMIN) ================

    @Test
    void syncPackages_returns200_andPassesCarrierAndOrigin() {
        // Sprint 51 catalog sync — controller now calls the 3-arg overload
        // with accountId (null when the operator hasn't picked one).
        when(service.syncPackagesFromCarrier("UPS", "US", null)).thenReturn(ok(Map.of("added", 5)));

        ResponseEntity<ApiResponse<Map<String, Object>>> re = controller.syncPackages("UPS", "US", null);

        assertEquals(HttpStatus.OK, re.getStatusCode());
        verify(service, times(1)).syncPackagesFromCarrier(eq("UPS"), eq("US"), org.mockito.ArgumentMatchers.isNull());
        verify(service, never()).syncFromCarrier(any(), any(), any());
    }

    @Test
    void syncPackages_unknownCarrier_422IsEchoed() {
        when(service.syncPackagesFromCarrier("BOGUS", "US", null))
                .thenReturn(err(422, ErrorCode.VALIDATION_ERROR, "unknown carrier"));

        ResponseEntity<ApiResponse<Map<String, Object>>> re = controller.syncPackages("BOGUS", "US", null);

        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, re.getStatusCode());
    }

    @Test
    void syncPackages_withAccountId_flowsThroughToService() {
        // Sprint 51 — operator-picked account flows through to the service
        // layer as-is.
        when(service.syncPackagesFromCarrier("UPS", "US", 42L)).thenReturn(ok(Map.of("added", 1)));

        ResponseEntity<ApiResponse<Map<String, Object>>> re = controller.syncPackages("UPS", "US", 42L);

        assertEquals(HttpStatus.OK, re.getStatusCode());
        verify(service, times(1)).syncPackagesFromCarrier(eq("UPS"), eq("US"), eq(42L));
    }

    // ================ POST /package-presets — createPreset ================

    @Test
    void createPreset_passesNullIdToServiceForNewPresets() {
        // Sprint-catalog pattern: create endpoint hands the service (null, request);
        // the service allocates the id. Pin this so a future refactor that shifts
        // id sourcing (e.g. from path variable) doesn't silently break.
        PackagePreset in = preset(null, false);
        PackagePreset saved = preset(101L, false);
        when(service.savePreset(isNull(), any())).thenReturn(ok(saved));

        ResponseEntity<ApiResponse<PackagePreset>> re = controller.createPreset(in);

        assertEquals(HttpStatus.OK, re.getStatusCode());
        assertEquals(101L, re.getBody().getData().getId());
        verify(service, times(1)).savePreset(isNull(), eq(in));
    }

    @Test
    void createPreset_validation422_isEchoed() {
        when(service.savePreset(isNull(), any()))
                .thenReturn(err(422, ErrorCode.VALIDATION_ERROR, "name required"));

        ResponseEntity<ApiResponse<PackagePreset>> re = controller.createPreset(preset(null, false));

        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, re.getStatusCode());
    }

    // ================ PUT /package-presets/{id} — updatePreset ================

    @Test
    void updatePreset_returns200_andPassesPathIdToService() {
        // The controller uses the path id, NOT the body id — pin this so a
        // future request-body-only refactor doesn't open an update-anyone hole.
        PackagePreset body = preset(999L, false); // body id ignored
        PackagePreset saved = preset(42L, false);
        when(service.savePreset(42L, body)).thenReturn(ok(saved));

        ResponseEntity<ApiResponse<PackagePreset>> re = controller.updatePreset(42L, body);

        assertEquals(HttpStatus.OK, re.getStatusCode());
        verify(service, times(1)).savePreset(eq(42L), eq(body));
    }

    @Test
    void updatePreset_notFound_returns404() {
        when(service.savePreset(anyLong(), any()))
                .thenReturn(err(404, ErrorCode.VALIDATION_ERROR, "preset not found"));

        ResponseEntity<ApiResponse<PackagePreset>> re =
                controller.updatePreset(99L, preset(99L, false));

        assertEquals(HttpStatus.NOT_FOUND, re.getStatusCode());
    }

    // ================ PUT /package-presets/{id}/default — setDefault ================

    @Test
    void setDefault_returns200_andDelegatesOnce() {
        when(service.setDefaultPreset(3L)).thenReturn(ok(preset(3L, true)));

        ResponseEntity<ApiResponse<PackagePreset>> re = controller.setDefault(3L);

        assertEquals(HttpStatus.OK, re.getStatusCode());
        assertEquals(Boolean.TRUE, re.getBody().getData().getIsDefault());
        verify(service, times(1)).setDefaultPreset(3L);
    }

    @Test
    void setDefault_notFound_returns404() {
        when(service.setDefaultPreset(99L))
                .thenReturn(err(404, ErrorCode.VALIDATION_ERROR, "preset not found"));

        ResponseEntity<ApiResponse<PackagePreset>> re = controller.setDefault(99L);

        assertEquals(HttpStatus.NOT_FOUND, re.getStatusCode());
    }

    // ================ DELETE /package-presets/{id} — deletePreset ================

    @Test
    void deletePreset_returns200_andDelegatesOnce() {
        when(service.deletePreset(3L)).thenReturn(ok(null));

        ResponseEntity<ApiResponse<Void>> re = controller.deletePreset(3L);

        assertEquals(HttpStatus.OK, re.getStatusCode());
        verify(service, times(1)).deletePreset(3L);
    }

    @Test
    void deletePreset_defaultRow_409IsEchoed() {
        // Business rule (pinned in ShippingConfigService): cannot delete a
        // default preset — a 409 must be echoed, not rewritten to 200.
        when(service.deletePreset(3L))
                .thenReturn(err(409, ErrorCode.VALIDATION_ERROR, "cannot delete default preset"));

        ResponseEntity<ApiResponse<Void>> re = controller.deletePreset(3L);

        assertEquals(HttpStatus.CONFLICT, re.getStatusCode());
    }

    // ==================================================================
    // Role gating — @PreAuthorize on every handler is pinned via reflection.
    // A future refactor that drops or widens a role guard fails loudly here
    // rather than silently opening the endpoint.
    // ==================================================================

    private static PreAuthorize preAuth(String methodName, Class<?>... paramTypes) throws NoSuchMethodException {
        Method m = ShippingConfigController.class.getMethod(methodName, paramTypes);
        PreAuthorize a = m.getAnnotation(PreAuthorize.class);
        assertNotNull(a, methodName + " must be @PreAuthorize-gated");
        return a;
    }

    @Test
    void preAuthorize_catalog_requiresAdminOrUser() throws NoSuchMethodException {
        assertEquals("hasAnyRole('ADMIN', 'USER')", preAuth("catalog", String.class).value());
    }

    @Test
    void preAuthorize_sync_requiresAdminOnly() throws NoSuchMethodException {
        // Sprint 51 catalog sync — signature now includes accountId (Long).
        assertEquals("hasRole('ADMIN')",
                preAuth("sync", String.class, String.class, Long.class).value());
    }

    @Test
    void preAuthorize_setEnabled_requiresAdminOrUser() throws NoSuchMethodException {
        assertEquals("hasAnyRole('ADMIN', 'USER')", preAuth("setEnabled", Long.class, Map.class).value());
    }

    @Test
    void preAuthorize_upsertRule_requiresAdminOrUser() throws NoSuchMethodException {
        assertEquals("hasAnyRole('ADMIN', 'USER')", preAuth("upsertRule", ShipViaMapping.class).value());
    }

    @Test
    void preAuthorize_deleteRule_requiresAdminOrUser() throws NoSuchMethodException {
        assertEquals("hasAnyRole('ADMIN', 'USER')", preAuth("deleteRule", Long.class).value());
    }

    @Test
    void preAuthorize_setServicePackages_requiresAdminOrUser() throws NoSuchMethodException {
        assertEquals("hasAnyRole('ADMIN', 'USER')",
                preAuth("setServicePackages", Long.class, List.class).value());
    }

    @Test
    void preAuthorize_listPresets_requiresAdminOrUser() throws NoSuchMethodException {
        assertEquals("hasAnyRole('ADMIN', 'USER')", preAuth("listPresets").value());
    }

    @Test
    void preAuthorize_syncPackages_requiresAdminOnly() throws NoSuchMethodException {
        // Sprint 51 catalog sync — signature now includes accountId (Long).
        assertEquals("hasRole('ADMIN')",
                preAuth("syncPackages", String.class, String.class, Long.class).value());
    }

    @Test
    void preAuthorize_createPreset_requiresAdminOrUser() throws NoSuchMethodException {
        assertEquals("hasAnyRole('ADMIN', 'USER')",
                preAuth("createPreset", PackagePreset.class).value());
    }

    @Test
    void preAuthorize_updatePreset_requiresAdminOrUser() throws NoSuchMethodException {
        assertEquals("hasAnyRole('ADMIN', 'USER')",
                preAuth("updatePreset", Long.class, PackagePreset.class).value());
    }

    @Test
    void preAuthorize_setDefault_requiresAdminOrUser() throws NoSuchMethodException {
        assertEquals("hasAnyRole('ADMIN', 'USER')", preAuth("setDefault", Long.class).value());
    }

    @Test
    void preAuthorize_deletePreset_requiresAdminOrUser() throws NoSuchMethodException {
        assertEquals("hasAnyRole('ADMIN', 'USER')", preAuth("deletePreset", Long.class).value());
    }

    @Test
    void classLevelRequestMapping_isPinnedToV1() {
        // Also pins the base path so we don't silently move the URL family.
        assertEquals("/api/v1",
                ShippingConfigController.class.getAnnotation(RequestMapping.class).value()[0]);
    }

    @Test
    void methodMappings_pinnedByReflection() throws NoSuchMethodException {
        // Pin each handler's HTTP verb + relative path so a rename can't
        // silently move an endpoint (which would break the FE).
        assertTrue(ShippingConfigController.class.getMethod("catalog", String.class)
                .getAnnotation(GetMapping.class).value()[0].equals("/shipping-services"));
        assertTrue(ShippingConfigController.class.getMethod("sync", String.class, String.class, Long.class)
                .getAnnotation(PostMapping.class).value()[0].equals("/shipping-services/sync"));
        assertTrue(ShippingConfigController.class.getMethod("setEnabled", Long.class, Map.class)
                .getAnnotation(PatchMapping.class).value()[0].equals("/shipping-services/{id}"));
        assertTrue(ShippingConfigController.class.getMethod("upsertRule", ShipViaMapping.class)
                .getAnnotation(PutMapping.class).value()[0].equals("/ship-method-rules"));
        assertTrue(ShippingConfigController.class.getMethod("deleteRule", Long.class)
                .getAnnotation(DeleteMapping.class).value()[0].equals("/ship-method-rules/{id}"));
        assertTrue(ShippingConfigController.class.getMethod("setServicePackages", Long.class, List.class)
                .getAnnotation(PutMapping.class).value()[0].equals("/shipping-services/{id}/packages"));
        assertTrue(ShippingConfigController.class.getMethod("listPresets")
                .getAnnotation(GetMapping.class).value()[0].equals("/package-presets"));
        assertTrue(ShippingConfigController.class.getMethod("syncPackages", String.class, String.class, Long.class)
                .getAnnotation(PostMapping.class).value()[0].equals("/package-presets/sync"));
        assertTrue(ShippingConfigController.class.getMethod("createPreset", PackagePreset.class)
                .getAnnotation(PostMapping.class).value()[0].equals("/package-presets"));
        assertTrue(ShippingConfigController.class.getMethod("updatePreset", Long.class, PackagePreset.class)
                .getAnnotation(PutMapping.class).value()[0].equals("/package-presets/{id}"));
        assertTrue(ShippingConfigController.class.getMethod("setDefault", Long.class)
                .getAnnotation(PutMapping.class).value()[0].equals("/package-presets/{id}/default"));
        assertTrue(ShippingConfigController.class.getMethod("deletePreset", Long.class)
                .getAnnotation(DeleteMapping.class).value()[0].equals("/package-presets/{id}"));
    }

    // ==================================================================
    // Cross-cutting anti-fallback guards.
    // ==================================================================

    @Test
    void catalog_doesNotTouchAnyOtherServiceMethod() {
        // Guard against copy-paste refactor accidents (e.g. catalog() side-firing sync()).
        when(service.catalog(any())).thenReturn(ok(Map.of("services", List.of())));

        controller.catalog(null);

        verify(service).catalog(any());
        verify(service, never()).syncFromCarrier(any(), any());
        verify(service, never()).syncPackagesFromCarrier(any(), any());
        verify(service, never()).setServiceEnabled(anyLong(), anyBoolean());
        verify(service, never()).upsertRule(any(), any(), any(), any(), any(), any(), any(), any());
        verify(service, never()).deleteRule(any());
        verify(service, never()).setServicePackages(any(), any());
        verify(service, never()).listPresets();
        verify(service, never()).savePreset(any(), any());
        verify(service, never()).setDefaultPreset(any());
        verify(service, never()).deletePreset(any());
    }

    @Test
    void constructor_isPureDelegation_noEagerServiceCalls() {
        // Constructing the controller must not call any service method
        // (avoids surprising work on Spring context init).
        ShippingConfigService fresh = mock(ShippingConfigService.class);
        new ShippingConfigController(fresh);
        verifyNoInteractions(fresh);
    }
}
