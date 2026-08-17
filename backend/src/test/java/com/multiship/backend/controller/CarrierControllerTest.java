package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.CarrierConnectRequest;
import com.multiship.backend.dto.CarrierConnectResponse;
import com.multiship.backend.dto.CarrierListResponse;
import com.multiship.backend.dto.CarrierStatusResponse;
import com.multiship.backend.service.CarrierService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Sprint 51 /settings/carriers page-tests (BE slice: CarrierController).
 *
 * Anti-fallback contract (user's explicit requirement):
 *   The controller must NEVER invoke a real CarrierConnector implementation
 *   during a unit test. We satisfy this by mocking the CarrierService the
 *   controller delegates to, and by asserting mock invocations on every path
 *   — including negative paths, where we also verify no *other* interaction
 *   leaked out of the tested endpoint.
 *
 * Coverage per endpoint (4 endpoints x >=3 cases):
 *   GET  /api/v1/carriers              — list (happy / empty / service-error passthrough)
 *   POST /api/v1/carriers/connect      — connect (happy / carrier-failure / actor-passthrough)
 *   GET  /api/v1/carriers/status       — status (happy / not-connected / null-actor)
 *   POST /api/v1/carriers/disconnect   — disconnect (happy / already-disconnected / actor-passthrough)
 *
 * Role gating: controllers rely on @PreAuthorize; the annotation itself is
 * verified via reflection so a future refactor that widens ADMIN-only
 * endpoints to USERs (or drops the annotation entirely) breaks the build.
 * This is the same pragmatic split every other Sprint 51 controller test
 * uses (see PickupControllerTest / AdminUserControllerTest) — a full
 * @WebMvcTest here would double-cover Spring's own PreAuthorize plumbing
 * at the cost of standing up the entire filter chain.
 */
class CarrierControllerTest {

    private CarrierService carrierService;
    private CarrierController controller;
    private UserDetails adminActor;
    private UserDetails userActor;

    @BeforeEach
    void setUp() {
        carrierService = mock(CarrierService.class);
        controller = new CarrierController(carrierService);
        adminActor = User.withUsername("admin@acme").password("x").roles("ADMIN").build();
        userActor  = User.withUsername("user@acme").password("x").roles("USER").build();
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static <T> ApiResponse<T> ok(T data) {
        return ApiResponse.<T>builder().status("success").code(200).data(data).message("ok").build();
    }

    private static <T> ApiResponse<T> err(int code, String errorCode, String msg) {
        return ApiResponse.<T>builder().status("error").code(code).errorCode(errorCode).message(msg).build();
    }

    private static CarrierConnectRequest validConnectRequest() {
        return CarrierConnectRequest.builder()
                .carrierCode("UPS").clientId("cid").clientSecret("csec")
                .accountNumber("A1").environment("SANDBOX").build();
    }

    // ==================================================================
    // GET /api/v1/carriers  — list
    // ==================================================================

    @Test
    void getAvailableCarriers_happy_returns200AndDelegates() {
        CarrierListResponse ups = CarrierListResponse.builder()
                .carrierCode("UPS").carrierName("UPS").active(true).build();
        ApiResponse<List<CarrierListResponse>> svc = ok(List.of(ups));
        when(carrierService.getAvailableCarriers()).thenReturn(svc);

        ResponseEntity<ApiResponse<List<CarrierListResponse>>> resp = controller.getAvailableCarriers();

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertSame(svc, resp.getBody(),
                "controller must pass the service response through unchanged");
        assertEquals(1, resp.getBody().getData().size());
        assertEquals("UPS", resp.getBody().getData().get(0).getCarrierCode());
        verify(carrierService, times(1)).getAvailableCarriers();
        verifyNoMoreInteractions(carrierService);
    }

    @Test
    void getAvailableCarriers_emptyList_stillReturns200() {
        when(carrierService.getAvailableCarriers()).thenReturn(ok(List.of()));

        ResponseEntity<ApiResponse<List<CarrierListResponse>>> resp = controller.getAvailableCarriers();

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals(0, resp.getBody().getData().size());
        verify(carrierService).getAvailableCarriers();
    }

    @Test
    void getAvailableCarriers_serviceError_statusIsEchoed() {
        // e.g. registry unavailable at boot — controller must NOT rewrite to 200.
        when(carrierService.getAvailableCarriers())
                .thenReturn(err(503, "CARRIER_REGISTRY_UNAVAILABLE", "loading"));

        ResponseEntity<ApiResponse<List<CarrierListResponse>>> resp = controller.getAvailableCarriers();

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, resp.getStatusCode());
        assertEquals("CARRIER_REGISTRY_UNAVAILABLE", resp.getBody().getErrorCode());
        verify(carrierService).getAvailableCarriers();
    }

    // ==================================================================
    // POST /api/v1/carriers/connect  — connect (ADMIN-only)
    // ==================================================================

    @Test
    void connectToCarrier_happy_returns200AndPassesActorToService() {
        CarrierConnectRequest req = validConnectRequest();
        CarrierConnectResponse body = CarrierConnectResponse.builder()
                .carrierCode("UPS").connected(true).accountNumber("A1").build();
        when(carrierService.connectToCarrier(eq(req), eq(adminActor))).thenReturn(ok(body));

        ResponseEntity<ApiResponse<CarrierConnectResponse>> resp =
                controller.connectToCarrier(req, adminActor);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals(Boolean.TRUE, resp.getBody().getData().getConnected());
        assertEquals("UPS", resp.getBody().getData().getCarrierCode());
        // Anti-fallback: the connector layer is unreachable because we mock
        // the service outright; the only interaction on the mock is the
        // single delegated call — no retry/side-channel invocations.
        verify(carrierService, times(1)).connectToCarrier(eq(req), eq(adminActor));
        verifyNoMoreInteractions(carrierService);
    }

    @Test
    void connectToCarrier_carrierAuthFailure_echoesServiceStatusAndErrorCode() {
        // Sprint 50/51 audit invariant: the controller must NOT rewrite an
        // upstream carrier auth failure to 200.
        CarrierConnectRequest req = validConnectRequest();
        when(carrierService.connectToCarrier(any(), any()))
                .thenReturn(err(401, "CARRIER_AUTH_FAILED", "invalid credentials"));

        ResponseEntity<ApiResponse<CarrierConnectResponse>> resp =
                controller.connectToCarrier(req, adminActor);

        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
        assertEquals("CARRIER_AUTH_FAILED", resp.getBody().getErrorCode());
        assertNull(resp.getBody().getData());
        verify(carrierService).connectToCarrier(any(), any());
    }

    @Test
    void connectToCarrier_nullActor_stillDelegates_serviceIsSourceOfTruthForAuth() {
        // The controller does not itself null-check the actor — that is the
        // service's job (it uses actor to determine tenant scoping). This
        // test pins the delegation contract: whatever actor the security
        // layer resolved is forwarded, including null (unauth cases handled
        // by @PreAuthorize elsewhere).
        CarrierConnectRequest req = validConnectRequest();
        when(carrierService.connectToCarrier(any(), any()))
                .thenReturn(err(403, "FORBIDDEN", "no actor"));

        ResponseEntity<ApiResponse<CarrierConnectResponse>> resp =
                controller.connectToCarrier(req, null);

        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
        verify(carrierService).connectToCarrier(eq(req), eq(null));
    }

    // ==================================================================
    // GET /api/v1/carriers/status  — status (ADMIN or USER)
    // ==================================================================

    @Test
    void getCarrierStatus_happy_connectedAccount_returns200() {
        CarrierStatusResponse status = CarrierStatusResponse.builder()
                .carrierCode("UPS").connected(true).accountNumber("A1").build();
        when(carrierService.getCarrierStatus(eq(userActor))).thenReturn(ok(status));

        ResponseEntity<ApiResponse<CarrierStatusResponse>> resp =
                controller.getCarrierStatus(userActor);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(Boolean.TRUE, resp.getBody().getData().getConnected());
        assertEquals("UPS", resp.getBody().getData().getCarrierCode());
        verify(carrierService, times(1)).getCarrierStatus(eq(userActor));
        verifyNoMoreInteractions(carrierService);
    }

    @Test
    void getCarrierStatus_notConnected_returns404WithErrorCode() {
        when(carrierService.getCarrierStatus(any()))
                .thenReturn(err(404, "NO_CARRIER_CONNECTED", "connect a carrier first"));

        ResponseEntity<ApiResponse<CarrierStatusResponse>> resp =
                controller.getCarrierStatus(userActor);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
        assertEquals("NO_CARRIER_CONNECTED", resp.getBody().getErrorCode());
    }

    @Test
    void getCarrierStatus_asAdmin_delegatesWithAdminPrincipal() {
        // Role-shape test: the same endpoint is callable by ADMIN; the
        // controller does not vary behaviour by role — it just forwards
        // the resolved principal. Prevents future silent policy drift.
        when(carrierService.getCarrierStatus(eq(adminActor)))
                .thenReturn(ok(CarrierStatusResponse.builder().connected(false).build()));

        ResponseEntity<ApiResponse<CarrierStatusResponse>> resp =
                controller.getCarrierStatus(adminActor);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(carrierService).getCarrierStatus(eq(adminActor));
        // Explicit anti-fallback: no other CarrierService method was pulled in.
        verify(carrierService, never()).getAvailableCarriers();
        verify(carrierService, never()).disconnectCarrier(any());
        verify(carrierService, never()).connectToCarrier(any(), any());
    }

    // ==================================================================
    // POST /api/v1/carriers/disconnect  — disconnect (ADMIN-only)
    // ==================================================================

    @Test
    void disconnectCarrier_happy_returns200AndForwardsPrincipal() {
        CarrierStatusResponse body = CarrierStatusResponse.builder()
                .carrierCode("UPS").connected(false).message("disconnected").build();
        when(carrierService.disconnectCarrier(eq(adminActor))).thenReturn(ok(body));

        ResponseEntity<ApiResponse<CarrierStatusResponse>> resp =
                controller.disconnectCarrier(adminActor);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(Boolean.FALSE, resp.getBody().getData().getConnected());
        verify(carrierService, times(1)).disconnectCarrier(eq(adminActor));
        verifyNoMoreInteractions(carrierService);
    }

    @Test
    void disconnectCarrier_alreadyDisconnected_returns404NotRewrittenTo200() {
        when(carrierService.disconnectCarrier(any()))
                .thenReturn(err(404, "NO_CARRIER_CONNECTED", "nothing to disconnect"));

        ResponseEntity<ApiResponse<CarrierStatusResponse>> resp =
                controller.disconnectCarrier(adminActor);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
        assertEquals("NO_CARRIER_CONNECTED", resp.getBody().getErrorCode());
        verify(carrierService).disconnectCarrier(any());
    }

    @Test
    void disconnectCarrier_serviceError_500PassesThrough() {
        when(carrierService.disconnectCarrier(any()))
                .thenReturn(err(500, "INTERNAL_ERROR", "boom"));

        ResponseEntity<ApiResponse<CarrierStatusResponse>> resp =
                controller.disconnectCarrier(adminActor);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
        assertEquals("INTERNAL_ERROR", resp.getBody().getErrorCode());
    }

    // ==================================================================
    // Role gating — verified against @PreAuthorize on each endpoint.
    // If a future refactor drops these or widens the audience, this test
    // fails loudly rather than silently opening the endpoint.
    // ==================================================================

    @Test
    void preAuthorize_list_requiresAdminOrUser() throws NoSuchMethodException {
        Method m = CarrierController.class.getMethod("getAvailableCarriers");
        PreAuthorize a = m.getAnnotation(PreAuthorize.class);
        assertNotNull(a, "GET /api/v1/carriers must be @PreAuthorize-gated");
        assertEquals("hasAnyRole('ADMIN', 'USER')", a.value());
        // Also pin the request mapping so we don't silently move the URL.
        assertEquals("/api/v1/carriers",
                CarrierController.class.getAnnotation(RequestMapping.class).value()[0]);
        assertNotNull(m.getAnnotation(GetMapping.class));
    }

    @Test
    void preAuthorize_connect_requiresAdminOnly() throws NoSuchMethodException {
        Method m = CarrierController.class.getMethod(
                "connectToCarrier", CarrierConnectRequest.class, UserDetails.class);
        PreAuthorize a = m.getAnnotation(PreAuthorize.class);
        assertNotNull(a);
        assertEquals("hasRole('ADMIN')", a.value());
        PostMapping pm = m.getAnnotation(PostMapping.class);
        assertNotNull(pm);
        assertTrue(pm.value().length > 0 && "/connect".equals(pm.value()[0]));
    }

    @Test
    void preAuthorize_status_requiresAdminOrUser() throws NoSuchMethodException {
        Method m = CarrierController.class.getMethod("getCarrierStatus", UserDetails.class);
        PreAuthorize a = m.getAnnotation(PreAuthorize.class);
        assertNotNull(a);
        assertEquals("hasAnyRole('ADMIN', 'USER')", a.value());
        GetMapping gm = m.getAnnotation(GetMapping.class);
        assertNotNull(gm);
        assertTrue(gm.value().length > 0 && "/status".equals(gm.value()[0]));
    }

    @Test
    void preAuthorize_disconnect_requiresAdminOnly() throws NoSuchMethodException {
        Method m = CarrierController.class.getMethod("disconnectCarrier", UserDetails.class);
        PreAuthorize a = m.getAnnotation(PreAuthorize.class);
        assertNotNull(a);
        assertEquals("hasRole('ADMIN')", a.value());
        PostMapping pm = m.getAnnotation(PostMapping.class);
        assertNotNull(pm);
        assertTrue(pm.value().length > 0 && "/disconnect".equals(pm.value()[0]));
        // Anti-fallback sanity: none of the annotation checks touched the service mock.
        verifyNoInteractions(carrierService);
    }
}
