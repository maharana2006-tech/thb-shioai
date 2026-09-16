package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.UspsDirectSubscriptionDTO;
import com.multiship.backend.dto.UspsDirectSubscriptionRequest;
import com.multiship.backend.model.UspsDirectSubscription;
import com.multiship.backend.model.UspsDirectSubscription.Status;
import com.multiship.backend.service.carriers.usps.UspsDirectSubscriptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pure controller tests for
 * {@link UspsDirectSubscriptionAdminController}. No MockMvc, no Spring
 * context — invoke methods directly and assert the {@link ResponseEntity}
 * envelope. Auth (the {@code @PreAuthorize} on the class) is not exercised
 * here — Spring Security is verified end-to-end elsewhere; these tests
 * pin the response shape + delegation to
 * {@link UspsDirectSubscriptionService}.
 */
class UspsDirectSubscriptionAdminControllerTest {

    private UspsDirectSubscriptionService service;
    private UspsDirectSubscriptionAdminController controller;

    @BeforeEach
    void setUp() {
        service = mock(UspsDirectSubscriptionService.class);
        controller = new UspsDirectSubscriptionAdminController(service);
    }

    // -----------------------------------------------------------
    // POST /
    // -----------------------------------------------------------

    @Test
    void create_happyPath_returns201WithDtoAndNoSecretLeak() {
        UspsDirectSubscription saved = UspsDirectSubscription.builder()
                .id(1L).uspsSubscriptionId("sub-abc-123-xyz")
                .filterType("MID").filterValue("901234567")
                .listenerUrl("https://webhook.example.com/hook")
                .environment("PRODUCTION").status(Status.ACTIVE)
                .secretEncrypted("enc:v1:SECRET-DO-NOT-RETURN").build();
        when(service.create(any(UspsDirectSubscriptionRequest.class))).thenReturn(saved);

        UspsDirectSubscriptionRequest body = UspsDirectSubscriptionRequest.builder()
                .filterType("MID").filterValue("901234567")
                .listenerURL("https://webhook.example.com/hook")
                .build();

        ResponseEntity<ApiResponse<UspsDirectSubscriptionDTO>> resp = controller.create(body);

        assertEquals(HttpStatus.CREATED, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals("success", resp.getBody().getStatus());
        UspsDirectSubscriptionDTO dto = resp.getBody().getData();
        assertNotNull(dto);
        assertEquals("sub-abc-123-xyz", dto.getUspsSubscriptionId());
        assertTrue(dto.getHasSecret(), "hasSecret must be true when secret_encrypted is populated");

        // Serialise-safe: the DTO must not expose the ciphertext through
        // any getter.
        assertResponseDoesNotLeak(resp.getBody(), "SECRET-DO-NOT-RETURN");
    }

    @Test
    void create_invalidFilterType_returns400ValidationError() {
        when(service.create(any(UspsDirectSubscriptionRequest.class)))
                .thenThrow(new IllegalArgumentException(
                        "filterType must be one of MID / TRACKING_NUMBERS / STID (got: BOGUS)"));

        UspsDirectSubscriptionRequest body = UspsDirectSubscriptionRequest.builder()
                .filterType("BOGUS").filterValue("x")
                .listenerURL("https://x").build();

        ResponseEntity<ApiResponse<UspsDirectSubscriptionDTO>> resp = controller.create(body);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals(ErrorCode.VALIDATION_ERROR.name(), resp.getBody().getErrorCode());
        assertTrue(resp.getBody().getMessage().contains("filterType"));
    }

    @Test
    void create_nonHttpsListener_returns400ValidationError() {
        when(service.create(any(UspsDirectSubscriptionRequest.class)))
                .thenThrow(new IllegalArgumentException(
                        "listenerURL must use HTTPS (USPS refuses plain HTTP push targets)"));

        UspsDirectSubscriptionRequest body = UspsDirectSubscriptionRequest.builder()
                .filterType("MID").filterValue("901234567")
                .listenerURL("http://webhook.example.com/hook").build();

        ResponseEntity<ApiResponse<UspsDirectSubscriptionDTO>> resp = controller.create(body);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals(ErrorCode.VALIDATION_ERROR.name(), resp.getBody().getErrorCode());
        assertTrue(resp.getBody().getMessage().toLowerCase().contains("https"));
    }

    @Test
    void create_platformCredsMissing_returns502CarrierFailure() {
        when(service.create(any(UspsDirectSubscriptionRequest.class)))
                .thenThrow(new IllegalStateException(
                        "USPS Direct is not configured platform-wide — no OAuth token available. "
                                + "Set USPS_PLATFORM_CLIENT_ID / USPS_PLATFORM_CLIENT_SECRET in /settings/system."));

        UspsDirectSubscriptionRequest body = UspsDirectSubscriptionRequest.builder()
                .filterType("MID").filterValue("901234567")
                .listenerURL("https://webhook.example.com/hook").build();

        ResponseEntity<ApiResponse<UspsDirectSubscriptionDTO>> resp = controller.create(body);

        assertEquals(HttpStatus.BAD_GATEWAY, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals(ErrorCode.CARRIER_FAILURE.name(), resp.getBody().getErrorCode());
        assertTrue(resp.getBody().getMessage().contains("/settings/system"));
    }

    // -----------------------------------------------------------
    // GET /
    // -----------------------------------------------------------

    @Test
    void list_returns200WithDtoList() {
        UspsDirectSubscription a = UspsDirectSubscription.builder()
                .id(1L).uspsSubscriptionId("sub-a")
                .filterType("MID").filterValue("111")
                .listenerUrl("https://x").environment("PRODUCTION")
                .status(Status.ACTIVE).build();
        UspsDirectSubscription b = UspsDirectSubscription.builder()
                .id(2L).uspsSubscriptionId("sub-b")
                .filterType("STID").filterValue("STID-42")
                .listenerUrl("https://y").environment("SANDBOX")
                .status(Status.ACTIVE)
                .secretEncrypted("enc:v1:SECRET-B").build();
        when(service.list()).thenReturn(List.of(a, b));

        ResponseEntity<ApiResponse<List<UspsDirectSubscriptionDTO>>> resp = controller.list();

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertNotNull(resp.getBody().getData());
        assertEquals(2, resp.getBody().getData().size());
        assertEquals("sub-a", resp.getBody().getData().get(0).getUspsSubscriptionId());
        assertFalse(resp.getBody().getData().get(0).getHasSecret(),
                "Row without encrypted secret must report hasSecret=false");
        assertTrue(resp.getBody().getData().get(1).getHasSecret(),
                "Row with encrypted secret must report hasSecret=true");

        assertResponseDoesNotLeak(resp.getBody(), "SECRET-B");
    }

    // -----------------------------------------------------------
    // GET /{id}
    // -----------------------------------------------------------

    @Test
    void getById_existing_returns200() {
        UspsDirectSubscription row = UspsDirectSubscription.builder()
                .id(7L).uspsSubscriptionId("sub-7")
                .filterType("MID").filterValue("111")
                .listenerUrl("https://x").environment("PRODUCTION")
                .status(Status.ACTIVE).build();
        when(service.getById(7L)).thenReturn(Optional.of(row));

        ResponseEntity<ApiResponse<UspsDirectSubscriptionDTO>> resp = controller.getById(7L);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("sub-7", resp.getBody().getData().getUspsSubscriptionId());
    }

    @Test
    void getById_missing_returns404() {
        when(service.getById(99L)).thenReturn(Optional.empty());

        ResponseEntity<ApiResponse<UspsDirectSubscriptionDTO>> resp = controller.getById(99L);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals(ErrorCode.VALIDATION_ERROR.name(), resp.getBody().getErrorCode());
    }

    // -----------------------------------------------------------
    // DELETE /{id}
    // -----------------------------------------------------------

    @Test
    void delete_existing_returns200Ok() {
        UspsDirectSubscription row = UspsDirectSubscription.builder()
                .id(5L).uspsSubscriptionId("sub-live")
                .filterType("MID").filterValue("111")
                .listenerUrl("https://x").environment("PRODUCTION")
                .status(Status.DELETED).build();
        when(service.delete(5L)).thenReturn(Optional.of(row));

        ResponseEntity<ApiResponse<Void>> resp = controller.delete(5L);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals("success", resp.getBody().getStatus());
    }

    @Test
    void delete_missing_returns404() {
        when(service.delete(99L)).thenReturn(Optional.empty());

        ResponseEntity<ApiResponse<Void>> resp = controller.delete(99L);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals(ErrorCode.VALIDATION_ERROR.name(), resp.getBody().getErrorCode());
    }

    @Test
    void delete_uspsFailure_returns502() {
        when(service.delete(6L)).thenThrow(new IllegalStateException(
                "USPS Subscriptions-Tracking delete failed (500): server barf"));

        ResponseEntity<ApiResponse<Void>> resp = controller.delete(6L);

        assertEquals(HttpStatus.BAD_GATEWAY, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals(ErrorCode.CARRIER_FAILURE.name(), resp.getBody().getErrorCode());
        assertTrue(resp.getBody().getMessage().contains("500"));
    }

    // ============================================================
    // helpers
    // ============================================================

    /**
     * Reflectively assert no string field in the ApiResponse envelope
     * (including nested DTOs / lists) contains {@code needle}. Belt-and-
     * braces against a future refactor accidentally piping the ciphertext
     * into a message field.
     */
    private static void assertResponseDoesNotLeak(ApiResponse<?> resp, String needle) {
        if (resp.getMessage() != null) {
            assertFalse(resp.getMessage().contains(needle),
                    "ApiResponse.message leaked the secret");
        }
        if (resp.getData() == null) return;
        if (resp.getData() instanceof Iterable<?> it) {
            for (Object row : it) assertObjectDoesNotLeak(row, needle);
        } else {
            assertObjectDoesNotLeak(resp.getData(), needle);
        }
    }

    private static void assertObjectDoesNotLeak(Object obj, String needle) {
        if (obj == null) return;
        for (var m : obj.getClass().getMethods()) {
            if (!m.getName().startsWith("get") && !m.getName().startsWith("is")) continue;
            if (m.getParameterCount() != 0) continue;
            try {
                Object v = m.invoke(obj);
                if (v instanceof String s) {
                    assertFalse(s.contains(needle),
                            "getter " + m.getName() + " leaked '" + needle + "'");
                }
            } catch (Exception ignore) { /* not a data getter */ }
        }
    }
}
