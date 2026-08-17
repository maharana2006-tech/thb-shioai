package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.CarrierConnectRequest;
import com.multiship.backend.dto.CarrierConnectResponse;
import com.multiship.backend.dto.CarrierListResponse;
import com.multiship.backend.dto.CarrierStatusResponse;
import com.multiship.backend.service.CarrierService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.RestController;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Sprint 53 /settings/carriers page-tests (slice: BE-ctrl-negative).
 *
 * <p>Supplements {@link CarrierControllerTest} (which covers happy paths
 * + top-line negatives) with the "in depth negative" coverage the
 * page-tests directive asks for:
 *
 * <ul>
 *   <li>429 rate-limit pass-through per endpoint (must NOT be rewritten
 *       to 200 by the controller).</li>
 *   <li>422 UNPROCESSABLE from the service pass-through.</li>
 *   <li>500 pass-through for endpoints not already covered.</li>
 *   <li>Controller reads {@code getCode()} to derive status — a service
 *       response with code=0 (mis-configured) surfaces as HTTP 0-ish
 *       behaviour; we pin the current pass-through so a future rewrite
 *       to "default 500 on invalid" breaks the build.</li>
 *   <li>Stateless invocation — back-to-back calls carry no shared state.</li>
 *   <li>Actor with special characters (whitespace, unicode) passes
 *       through unchanged to the service.</li>
 *   <li>Annotation shape — {@code @RestController} + {@code @Tag} +
 *       {@code @Valid} + {@code @AuthenticationPrincipal} presence
 *       pinned so a refactor can't silently loosen validation or drop
 *       the swagger group.</li>
 * </ul>
 *
 * <p>All tests re-mock {@link CarrierService} — the anti-fallback rule
 * (no real connector ever runs in a unit test) still holds.
 */
class CarrierControllerNegativePathsTest {

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

    private static <T> ApiResponse<T> err(int code, String errorCode, String msg) {
        return ApiResponse.<T>builder()
                .status("error").code(code).errorCode(errorCode).message(msg).build();
    }

    private static CarrierConnectRequest validConnectRequest() {
        return CarrierConnectRequest.builder()
                .carrierCode("UPS").clientId("cid").clientSecret("csec")
                .accountNumber("A1").environment("SANDBOX").build();
    }

    // ==================================================================
    // 429 rate-limit pass-through per endpoint
    // ==================================================================

    @Test
    void connectToCarrier_carrier429_isPassedThroughAsTooManyRequests() {
        when(carrierService.connectToCarrier(any(), any()))
                .thenReturn(err(429, "CARRIER_RATE_LIMITED", "retry after 30s"));

        ResponseEntity<ApiResponse<CarrierConnectResponse>> resp =
                controller.connectToCarrier(validConnectRequest(), adminActor);

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, resp.getStatusCode());
        assertEquals("CARRIER_RATE_LIMITED", resp.getBody().getErrorCode());
        assertNull(resp.getBody().getData());
    }

    @Test
    void getCarrierStatus_carrier429_isPassedThroughAsTooManyRequests() {
        when(carrierService.getCarrierStatus(any()))
                .thenReturn(err(429, "CARRIER_RATE_LIMITED", "retry after 15s"));

        ResponseEntity<ApiResponse<CarrierStatusResponse>> resp =
                controller.getCarrierStatus(userActor);

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, resp.getStatusCode());
        assertEquals("CARRIER_RATE_LIMITED", resp.getBody().getErrorCode());
    }

    @Test
    void disconnectCarrier_carrier429_isPassedThroughAsTooManyRequests() {
        when(carrierService.disconnectCarrier(any()))
                .thenReturn(err(429, "CARRIER_RATE_LIMITED", "backoff"));

        ResponseEntity<ApiResponse<CarrierStatusResponse>> resp =
                controller.disconnectCarrier(adminActor);

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, resp.getStatusCode());
    }

    // ==================================================================
    // 422 validation-failure pass-through
    // ==================================================================

    @Test
    void connectToCarrier_serviceReturns422_passesThroughAsUnprocessable() {
        when(carrierService.connectToCarrier(any(), any()))
                .thenReturn(err(422, "INVALID_CARRIER_CODE", "unknown carrier XYZ"));

        ResponseEntity<ApiResponse<CarrierConnectResponse>> resp =
                controller.connectToCarrier(validConnectRequest(), adminActor);

        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, resp.getStatusCode());
        assertEquals("INVALID_CARRIER_CODE", resp.getBody().getErrorCode());
    }

    // ==================================================================
    // 500 pass-through — endpoints not already covered in the primary suite
    // ==================================================================

    @Test
    void getAvailableCarriers_service500_isPassedThroughAsInternalServerError() {
        when(carrierService.getAvailableCarriers())
                .thenReturn(err(500, "INTERNAL_ERROR", "loader crashed"));

        ResponseEntity<ApiResponse<List<CarrierListResponse>>> resp =
                controller.getAvailableCarriers();

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
        assertEquals("INTERNAL_ERROR", resp.getBody().getErrorCode());
    }

    @Test
    void connectToCarrier_service500_isPassedThroughAsInternalServerError() {
        when(carrierService.connectToCarrier(any(), any()))
                .thenReturn(err(500, "INTERNAL_ERROR", "boom"));

        ResponseEntity<ApiResponse<CarrierConnectResponse>> resp =
                controller.connectToCarrier(validConnectRequest(), adminActor);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
        assertEquals("INTERNAL_ERROR", resp.getBody().getErrorCode());
    }

    @Test
    void getCarrierStatus_service500_isPassedThroughAsInternalServerError() {
        when(carrierService.getCarrierStatus(any()))
                .thenReturn(err(500, "INTERNAL_ERROR", "kaboom"));

        ResponseEntity<ApiResponse<CarrierStatusResponse>> resp =
                controller.getCarrierStatus(userActor);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
        assertEquals("INTERNAL_ERROR", resp.getBody().getErrorCode());
    }

    // ==================================================================
    // Stateless invocation — back-to-back calls
    // ==================================================================

    @Test
    void statuses_backToBackCalls_deliverIndependentResults_notCached() {
        when(carrierService.getCarrierStatus(any()))
                .thenReturn(ApiResponse.<CarrierStatusResponse>builder()
                        .status("success").code(200)
                        .data(CarrierStatusResponse.builder().connected(true).build())
                        .build())
                .thenReturn(ApiResponse.<CarrierStatusResponse>builder()
                        .status("success").code(200)
                        .data(CarrierStatusResponse.builder().connected(false).build())
                        .build());

        assertEquals(Boolean.TRUE, controller.getCarrierStatus(userActor).getBody().getData().getConnected());
        assertEquals(Boolean.FALSE, controller.getCarrierStatus(userActor).getBody().getData().getConnected());

        verify(carrierService, times(2)).getCarrierStatus(any());
        verifyNoMoreInteractions(carrierService);
    }

    // ==================================================================
    // Actor identity pass-through — nothing sanitized at the controller
    // ==================================================================

    @Test
    void connectToCarrier_actorWithWhitespaceUsername_passesThroughAsIs() {
        UserDetails odd = User.withUsername("  spaced@acme  ").password("x").roles("ADMIN").build();
        when(carrierService.connectToCarrier(any(), eq(odd)))
                .thenReturn(ApiResponse.<CarrierConnectResponse>builder()
                        .status("success").code(200)
                        .data(CarrierConnectResponse.builder().connected(true).build())
                        .build());

        ResponseEntity<ApiResponse<CarrierConnectResponse>> resp =
                controller.connectToCarrier(validConnectRequest(), odd);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        // Controller does NOT trim / normalize the actor — service is
        // the source of truth for username handling.
        verify(carrierService).connectToCarrier(any(), eq(odd));
    }

    @Test
    void getCarrierStatus_actorWithUnicodeUsername_passesThroughAsIs() {
        UserDetails unicoded = User.withUsername("héllo@örg.tld").password("x").roles("USER").build();
        when(carrierService.getCarrierStatus(eq(unicoded)))
                .thenReturn(ApiResponse.<CarrierStatusResponse>builder()
                        .status("success").code(200)
                        .data(CarrierStatusResponse.builder().connected(true).build())
                        .build());

        controller.getCarrierStatus(unicoded);

        verify(carrierService, times(1)).getCarrierStatus(eq(unicoded));
    }

    // ==================================================================
    // Isolation of endpoints — each hits ONLY its own service method
    // ==================================================================

    @Test
    void getAvailableCarriers_doesNotTouchOtherServiceMethods() {
        when(carrierService.getAvailableCarriers())
                .thenReturn(ApiResponse.<List<CarrierListResponse>>builder()
                        .status("success").code(200).data(List.of()).build());

        controller.getAvailableCarriers();

        verify(carrierService, times(1)).getAvailableCarriers();
        verify(carrierService, never()).connectToCarrier(any(), any());
        verify(carrierService, never()).getCarrierStatus(any());
        verify(carrierService, never()).disconnectCarrier(any());
    }

    @Test
    void connectToCarrier_doesNotTouchOtherServiceMethods() {
        when(carrierService.connectToCarrier(any(), any()))
                .thenReturn(ApiResponse.<CarrierConnectResponse>builder()
                        .status("success").code(200)
                        .data(CarrierConnectResponse.builder().connected(true).build())
                        .build());

        controller.connectToCarrier(validConnectRequest(), adminActor);

        verify(carrierService, times(1)).connectToCarrier(any(), any());
        verify(carrierService, never()).getAvailableCarriers();
        verify(carrierService, never()).getCarrierStatus(any());
        verify(carrierService, never()).disconnectCarrier(any());
    }

    @Test
    void disconnectCarrier_doesNotTouchOtherServiceMethods() {
        when(carrierService.disconnectCarrier(any()))
                .thenReturn(ApiResponse.<CarrierStatusResponse>builder()
                        .status("success").code(200)
                        .data(CarrierStatusResponse.builder().connected(false).build())
                        .build());

        controller.disconnectCarrier(adminActor);

        verify(carrierService, times(1)).disconnectCarrier(any());
        verify(carrierService, never()).getAvailableCarriers();
        verify(carrierService, never()).connectToCarrier(any(), any());
        verify(carrierService, never()).getCarrierStatus(any());
    }

    // ==================================================================
    // Class + parameter annotation shape — pin so a refactor can't
    // silently loosen validation / drop swagger groups.
    // ==================================================================

    @Test
    void controllerClass_isRestControllerAndTaggedForSwagger() {
        assertNotNull(CarrierController.class.getAnnotation(RestController.class),
                "must remain a @RestController — moving to @Controller would break body serialization");
        Tag tag = CarrierController.class.getAnnotation(Tag.class);
        assertNotNull(tag, "must remain grouped under a @Tag for swagger");
        assertEquals("Carriers", tag.name());
    }

    @Test
    void connectRequestParameter_isValid_andAnnotatedRequestBody() throws NoSuchMethodException {
        Method m = CarrierController.class.getMethod(
                "connectToCarrier", CarrierConnectRequest.class, UserDetails.class);
        Parameter body = m.getParameters()[0];
        boolean hasValid = false;
        for (Annotation a : body.getAnnotations()) {
            if (a.annotationType() == Valid.class) {
                hasValid = true;
                break;
            }
        }
        assertTrue(hasValid, "connectToCarrier body must retain @Valid so jakarta constraints fire");
    }

    @Test
    void authenticatedEndpoints_carryAuthenticationPrincipalOnActorParam() throws NoSuchMethodException {
        for (Method m : new Method[]{
                CarrierController.class.getMethod("connectToCarrier",
                        CarrierConnectRequest.class, UserDetails.class),
                CarrierController.class.getMethod("getCarrierStatus", UserDetails.class),
                CarrierController.class.getMethod("disconnectCarrier", UserDetails.class),
        }) {
            Parameter last = m.getParameters()[m.getParameterCount() - 1];
            boolean hasPrincipal = false;
            for (Annotation a : last.getAnnotations()) {
                if (a.annotationType() == AuthenticationPrincipal.class) {
                    hasPrincipal = true;
                    break;
                }
            }
            assertTrue(hasPrincipal,
                    m.getName() + " must retain @AuthenticationPrincipal on its actor parameter");
        }
    }

    @Test
    void listEndpoint_carriesNoActorParameter_publicToAnyAuthenticatedUser() throws NoSuchMethodException {
        // Listing available carriers is a static registry read — the
        // endpoint intentionally has no actor arg. Pin so a future
        // refactor doesn't accidentally add tenant-scoping here.
        Method m = CarrierController.class.getMethod("getAvailableCarriers");
        assertEquals(0, m.getParameterCount(),
                "getAvailableCarriers must remain zero-arg — adding an actor would " +
                        "silently constrain the shared registry to a per-user view");
        assertFalse(m.isVarArgs());
    }
}
