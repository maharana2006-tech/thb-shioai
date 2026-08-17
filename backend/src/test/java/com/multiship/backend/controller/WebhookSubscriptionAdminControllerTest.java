package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.ExternalWebhookSubscriptionDTO;
import com.multiship.backend.model.ExternalWebhookSubscription;
import com.multiship.backend.model.ExternalWebhookSubscription.EventType;
import com.multiship.backend.repository.ExternalWebhookSubscriptionRepository;
import com.multiship.backend.service.external.ExternalWebhookDispatcher;
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
 * Audit W2 + W3 — pins the admin controller's new "404 on missing id"
 * behavior + confirms the dispatcher's subscription cache gets
 * invalidated on every write path.
 */
class WebhookSubscriptionAdminControllerTest {

    private ExternalWebhookSubscriptionRepository repo;
    private WebhookUrlValidator urlValidator;
    private ExternalWebhookDispatcher dispatcher;
    private WebhookSubscriptionAdminController controller;

    @BeforeEach
    void setUp() {
        repo = mock(ExternalWebhookSubscriptionRepository.class);
        urlValidator = mock(WebhookUrlValidator.class);
        dispatcher = mock(ExternalWebhookDispatcher.class);
        controller = new WebhookSubscriptionAdminController(repo, urlValidator, dispatcher);
    }

    @Test
    void delete_missingId_returns404_andDoesNotInvalidateCache() {
        when(repo.findById(99L)).thenReturn(Optional.empty());

        ResponseEntity<ApiResponse<Void>> resp = controller.delete(99L);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals(ErrorCode.WEBHOOK_SUBSCRIPTION_NOT_FOUND.name(), resp.getBody().getErrorCode());
        verify(repo, never()).delete(any());
        verify(dispatcher, never()).invalidateSubscriptionCache();
    }

    @Test
    void delete_existingId_returns200_andInvalidatesCache() {
        ExternalWebhookSubscription existing = new ExternalWebhookSubscription();
        existing.setId(7L);
        existing.setApiKeyId(42L);
        when(repo.findById(7L)).thenReturn(Optional.of(existing));

        ResponseEntity<ApiResponse<Void>> resp = controller.delete(7L);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(repo).delete(existing);
        verify(dispatcher).invalidateSubscriptionCache();
    }

    @Test
    void save_create_invalidatesCache() {
        ExternalWebhookSubscriptionDTO body = ExternalWebhookSubscriptionDTO.builder()
                .apiKeyId(42L).event(EventType.LABEL_GENERATED)
                .url("https://partner.example/hook").secret("s3cr3t").active(true).build();
        when(repo.save(any(ExternalWebhookSubscription.class)))
                .thenAnswer(inv -> { ((ExternalWebhookSubscription) inv.getArgument(0)).setId(1L); return inv.getArgument(0); });

        ResponseEntity<ApiResponse<ExternalWebhookSubscriptionDTO>> resp = controller.save(body);

        assertEquals(HttpStatus.CREATED, resp.getStatusCode());
        verify(dispatcher).invalidateSubscriptionCache();
    }
}
