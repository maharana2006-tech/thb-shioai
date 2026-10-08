package com.multiship.backend.controller;

import com.multiship.backend.model.CarrierAccountRef;
import com.multiship.backend.repository.CarrierAccountRefRepository;
import com.multiship.backend.service.carriers.StampsSeraOAuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Smoke tests for {@link StampsSeraOAuthController}'s disconnect endpoint
 * (PR-T8 item 2). The authorize + callback paths are covered end-to-end
 * via the service-level tests + an integration test; the disconnect path
 * is new wire-up on top of the repository + oauth service, which is the
 * minimum worth exercising.
 */
class StampsSeraOAuthControllerTest {

    private CarrierAccountRefRepository repository;
    private StampsSeraOAuthService oauthService;
    private StampsSeraOAuthController controller;

    @BeforeEach
    void setUp() {
        repository = mock(CarrierAccountRefRepository.class);
        oauthService = mock(StampsSeraOAuthService.class);
        controller = new StampsSeraOAuthController(repository, oauthService);
    }

    @Test
    void disconnect_happyPath_clearsTokenAndFlipsVerifiedAndReturns204() {
        CarrierAccountRef account = new CarrierAccountRef();
        account.setId(42L);
        account.setStampsRefreshToken("rt-live");
        account.setVerified(true);
        when(repository.findById(42L)).thenReturn(Optional.of(account));

        ResponseEntity<Void> resp = controller.disconnect(42L);

        assertEquals(HttpStatus.NO_CONTENT, resp.getStatusCode(),
                "Disconnect must return 204 on success");
        assertNull(account.getStampsRefreshToken(),
                "refresh_token must be nulled on disconnect");
        assertEquals(Boolean.FALSE, account.getVerified(),
                "verified must be flipped to false on disconnect");
        verify(repository).save(account);
        verify(oauthService).clearTokenCache();
    }

    @Test
    void disconnect_unknownAccount_returns404() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        ResponseEntity<Void> resp = controller.disconnect(99L);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode(),
                "Unknown accountId must 404");
        verify(repository, never()).save(any());
        verify(oauthService, never()).clearTokenCache();
    }

    @Test
    void disconnect_alreadyDisconnected_stillReturns204AndSkipsCacheEvict() {
        // Already-disconnected account (no stored refresh_token) must still
        // 204 — idempotent delete. Cache evict is skipped because there's
        // nothing to evict; keeps the admin page safe to double-click.
        CarrierAccountRef account = new CarrierAccountRef();
        account.setId(7L);
        account.setStampsRefreshToken(null);
        account.setVerified(false);
        when(repository.findById(7L)).thenReturn(Optional.of(account));

        ResponseEntity<Void> resp = controller.disconnect(7L);

        assertEquals(HttpStatus.NO_CONTENT, resp.getStatusCode());
        verify(repository).save(account);
        verify(oauthService, never()).clearTokenCache();
    }
}
