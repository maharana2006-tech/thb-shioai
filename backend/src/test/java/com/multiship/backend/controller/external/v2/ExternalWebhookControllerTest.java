package com.multiship.backend.controller.external.v2;

import com.multiship.backend.config.ApiKeyPrincipal;
import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.ExternalWebhookSubscriptionDTO;
import com.multiship.backend.model.ExternalWebhookSubscription;
import com.multiship.backend.repository.ExternalWebhookSubscriptionRepository;
import com.multiship.backend.service.external.WebhookUrlValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sprint 51 AC-M3 / AC-L4 — regression tests for the split "not found vs
 * cross-tenant vs OK" behaviour on the v2 webhook subscription CRUD.
 * Before this PR, {@code delete} silently 200'd for both missing and
 * cross-tenant ids, and {@code save} update-branch returned 400
 * VALIDATION_FAILED for both.
 */
class ExternalWebhookControllerTest {

    private ExternalWebhookSubscriptionRepository repo;
    private WebhookUrlValidator urlValidator;
    private ExternalWebhookController controller;

    private static final Long MY_KEY = 42L;
    private static final Long OTHER_KEY = 99L;

    @BeforeEach
    void setUp() {
        repo = mock(ExternalWebhookSubscriptionRepository.class);
        urlValidator = mock(WebhookUrlValidator.class);
        controller = new ExternalWebhookController(repo, urlValidator);
    }

    // ===== delete =====

    @Test
    void delete_missingId_returns404WithWebhookNotFoundErrorCode() {
        when(repo.findById(1L)).thenReturn(Optional.empty());

        ResponseEntity<ApiResponse<Void>> resp = controller.delete(1L, caller(MY_KEY));

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals(ErrorCode.WEBHOOK_SUBSCRIPTION_NOT_FOUND.name(), resp.getBody().getErrorCode());
        verify(repo, never()).delete(any());
    }

    @Test
    void delete_otherTenantId_returns403CrossTenantAccessDenied() {
        ExternalWebhookSubscription other = new ExternalWebhookSubscription();
        other.setId(2L);
        other.setApiKeyId(OTHER_KEY);
        when(repo.findById(2L)).thenReturn(Optional.of(other));

        ResponseEntity<ApiResponse<Void>> resp = controller.delete(2L, caller(MY_KEY));

        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals(ErrorCode.CROSS_TENANT_ACCESS_DENIED.name(), resp.getBody().getErrorCode());
        verify(repo, never()).delete(any());
    }

    @Test
    void delete_ownedId_returns200AndCallsRepoDelete() {
        ExternalWebhookSubscription owned = new ExternalWebhookSubscription();
        owned.setId(3L);
        owned.setApiKeyId(MY_KEY);
        when(repo.findById(3L)).thenReturn(Optional.of(owned));

        ResponseEntity<ApiResponse<Void>> resp = controller.delete(3L, caller(MY_KEY));

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(repo).delete(owned);
    }

    // ===== save (update branch) =====

    @Test
    void save_updateWithUnknownId_returns404() {
        ExternalWebhookSubscriptionDTO body = ExternalWebhookSubscriptionDTO.builder()
                .id(1L).event(com.multiship.backend.model.ExternalWebhookSubscription.EventType.LABEL_GENERATED).url("https://x.example/hook").secret("s").build();
        when(repo.findById(1L)).thenReturn(Optional.empty());

        ResponseEntity<ApiResponse<ExternalWebhookSubscriptionDTO>> resp = controller.save(body, caller(MY_KEY));

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals(ErrorCode.WEBHOOK_SUBSCRIPTION_NOT_FOUND.name(), resp.getBody().getErrorCode());
    }

    @Test
    void save_updateWithOtherTenantId_returns403() {
        ExternalWebhookSubscription other = new ExternalWebhookSubscription();
        other.setId(2L);
        other.setApiKeyId(OTHER_KEY);
        ExternalWebhookSubscriptionDTO body = ExternalWebhookSubscriptionDTO.builder()
                .id(2L).event(com.multiship.backend.model.ExternalWebhookSubscription.EventType.LABEL_GENERATED).url("https://x.example/hook").secret("s").build();
        when(repo.findById(2L)).thenReturn(Optional.of(other));

        ResponseEntity<ApiResponse<ExternalWebhookSubscriptionDTO>> resp = controller.save(body, caller(MY_KEY));

        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals(ErrorCode.CROSS_TENANT_ACCESS_DENIED.name(), resp.getBody().getErrorCode());
    }

    @Test
    void save_missingUrl_returns400WithCanonicalValidationErrorCode() {
        ExternalWebhookSubscriptionDTO body = ExternalWebhookSubscriptionDTO.builder()
                .event(com.multiship.backend.model.ExternalWebhookSubscription.EventType.LABEL_GENERATED).secret("s").build();

        ResponseEntity<ApiResponse<ExternalWebhookSubscriptionDTO>> resp = controller.save(body, caller(MY_KEY));

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertNotNull(resp.getBody());
        // Sprint 51 AC-M2 — was string literal "VALIDATION_FAILED"; now canonical enum.
        assertEquals(ErrorCode.VALIDATION_ERROR.name(), resp.getBody().getErrorCode());
    }

    // ===== helpers =====

    private static ApiKeyPrincipal caller(Long keyId) {
        ApiKeyPrincipal p = mock(ApiKeyPrincipal.class);
        when(p.getApiKeyId()).thenReturn(keyId);
        return p;
    }
}
