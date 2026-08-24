package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.wms.WmsPullResultDTO;
import com.multiship.backend.service.OrderImportService;
import com.multiship.backend.service.wms.WmsClient;
import com.multiship.backend.service.wms.WmsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Controller-tier unit coverage for {@link WmsController}. Thin
 * delegation over {@link WmsService} plus a {@link WmsClient.WmsException}
 * → 502 translation. The endpoint pair (status, pull) is the operator
 * surface for the WMS-integration audit — tests verify the response
 * shape stays stable and the auth annotations remain in place (Sprint 49
 * regression pattern: @PreAuthorize dropped = tenant leak).
 */
class WmsControllerTest {

    private WmsService wmsService;
    private OrderImportService orderImportService;
    private WmsController controller;

    @BeforeEach
    void setUp() {
        wmsService = mock(WmsService.class);
        orderImportService = mock(OrderImportService.class);
        controller = new WmsController(wmsService, orderImportService);
    }

    private UserDetails alice() {
        return User.withUsername("alice").password("").authorities("ROLE_ADMIN").build();
    }

    // ===== status =====

    @Test
    void status_returns200_withConfiguredFlagFromService() {
        when(wmsService.isConfigured()).thenReturn(true);

        ResponseEntity<ApiResponse<Map<String, Object>>> res = controller.status();

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertEquals(200, res.getBody().getCode());
        assertEquals(true, res.getBody().getData().get("configured"));
    }

    @Test
    void status_returnsFalseFlag_whenServiceUnconfigured() {
        when(wmsService.isConfigured()).thenReturn(false);

        ResponseEntity<ApiResponse<Map<String, Object>>> res = controller.status();

        assertEquals(false, res.getBody().getData().get("configured"));
    }

    // ===== pull =====

    @Test
    void pull_delegatesToService_withCallerUsername_andReturns200WithImportedMessage() {
        WmsPullResultDTO result = WmsPullResultDTO.builder()
                .configured(true).fetched(5).imported(3).skipped(1).failed(1)
                .importedOrderNos(List.of(70001, 70002, 70003))
                .messages(List.of())
                .build();
        when(wmsService.pullShippable("alice")).thenReturn(result);

        ResponseEntity<ApiResponse<WmsPullResultDTO>> res = controller.pull(alice());

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertEquals(200, res.getBody().getCode());
        assertTrue(res.getBody().getMessage().contains("3 order(s) imported"));
        assertTrue(res.getBody().getMessage().contains("1 already present"));
        assertTrue(res.getBody().getMessage().contains("1 failed"));
        assertEquals(3, res.getBody().getData().getImported());
        // Delegation happened with the resolved username.
        verify(wmsService).pullShippable("alice");
    }

    @Test
    void pull_notConfigured_returns200_withInstructionalMessage() {
        WmsPullResultDTO result = WmsPullResultDTO.builder()
                .configured(false).importedOrderNos(List.of())
                .messages(List.of("WMS is not configured. Set WMS_BASE_URL and WMS_API_KEY."))
                .build();
        when(wmsService.pullShippable("alice")).thenReturn(result);

        ResponseEntity<ApiResponse<WmsPullResultDTO>> res = controller.pull(alice());

        assertEquals(HttpStatus.OK, res.getStatusCode(),
                "not-configured is a 200 no-op, not a client error — operator sees a hint");
        assertEquals("WMS not configured — nothing pulled.", res.getBody().getMessage());
    }

    @Test
    void pull_wmsExceptionFromService_translatesTo502WithMessage() {
        // The WMS is unreachable / returned a non-2xx. Controller must
        // surface this as 502 BAD_GATEWAY (external-dependency failure)
        // so alerting can distinguish it from 500 (internal bug).
        when(wmsService.pullShippable("alice"))
                .thenThrow(new WmsClient.WmsException("WMS returned HTTP 503: circuit open"));

        ResponseEntity<ApiResponse<WmsPullResultDTO>> res = controller.pull(alice());

        assertEquals(HttpStatus.BAD_GATEWAY, res.getStatusCode());
        assertEquals(502, res.getBody().getCode());
        assertTrue(res.getBody().getMessage().contains("circuit open"),
                "the exception message must survive to the operator so the failure is diagnosable");
    }

    @Test
    void pull_nullUserDetails_stillPasses_asUnknown_soAuditTrailAlwaysHasARequester() {
        WmsPullResultDTO result = WmsPullResultDTO.builder()
                .configured(true).importedOrderNos(List.of()).messages(List.of())
                .build();
        when(wmsService.pullShippable("unknown")).thenReturn(result);

        ResponseEntity<ApiResponse<WmsPullResultDTO>> res = controller.pull(null);

        assertEquals(HttpStatus.OK, res.getStatusCode());
        verify(wmsService).pullShippable("unknown");
    }

    // ===== @PreAuthorize regression guards =====
    //
    // Sprint 49 lesson (see Audit R2 #351, Sprint 50 PR E) — a dropped
    // @PreAuthorize is a silent tenant-leak vector. Reflection-based
    // guards fail loudly if a future refactor accidentally strips the
    // annotation. Pattern mirrors the controller-coverage suite.

    @Test
    void status_endpoint_carriesPreAuthorizeForAdminOrUser() throws Exception {
        Method m = WmsController.class.getMethod("status");
        String expr = preAuthorizeValue(m);
        assertNotNull(expr, "status must carry @PreAuthorize");
        assertTrue(expr.contains("ADMIN") && expr.contains("USER"),
                "status is read-only — ADMIN or USER can query configured-ness; got: " + expr);
    }

    @Test
    void pull_endpoint_carriesPreAuthorizeForAdminOnly() throws Exception {
        Method m = WmsController.class.getMethod("pull",
                org.springframework.security.core.userdetails.UserDetails.class);
        String expr = preAuthorizeValue(m);
        assertNotNull(expr, "pull must carry @PreAuthorize");
        assertTrue(expr.contains("ADMIN") && !expr.contains("USER"),
                "pull is state-changing (imports orders) — ADMIN only; got: " + expr);
    }

    private static String preAuthorizeValue(Method m) {
        for (Annotation a : m.getAnnotations()) {
            if (a.annotationType().getName()
                    .equals("org.springframework.security.access.prepost.PreAuthorize")) {
                try {
                    return (String) a.annotationType().getMethod("value").invoke(a);
                } catch (Exception e) {
                    return null;
                }
            }
        }
        return null;
    }
}
