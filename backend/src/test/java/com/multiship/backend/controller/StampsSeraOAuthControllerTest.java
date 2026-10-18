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
    private com.multiship.backend.service.carriers.StampsConnector stampsConnector;
    private StampsSeraOAuthController controller;

    @BeforeEach
    void setUp() {
        repository = mock(CarrierAccountRefRepository.class);
        oauthService = mock(StampsSeraOAuthService.class);
        stampsConnector = mock(com.multiship.backend.service.carriers.StampsConnector.class);
        controller = new StampsSeraOAuthController(repository, oauthService, stampsConnector);
    }

    /** The screen that started the authorization gets the result — only if it's one of the app's own origins. */
    @Test
    void callback_returnsToTheScreenThatStartedIt_butNeverToAForeignSite() {
        org.springframework.web.cors.CorsConfiguration cors = new org.springframework.web.cors.CorsConfiguration();
        cors.setAllowedOriginPatterns(java.util.List.of("http://localhost:*"));
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "corsConfigurationSource",
                (org.springframework.web.cors.CorsConfigurationSource) r -> cors);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "feReturnUrl", "http://localhost:5173");
        var request = new org.springframework.mock.web.MockHttpServletRequest();

        ResponseEntity<Void> own = controller.callback(null, null, "access_denied", null, "http://localhost:5175", request);
        assertEquals("http://localhost:5175/settings/carriers?seraCallback=error&detail=carrier+returned+access_denied",
                own.getHeaders().getLocation().toString());
        org.junit.jupiter.api.Assertions.assertTrue(own.getHeaders().getFirst("Set-Cookie").contains("Max-Age=0"),
                "the return cookie is cleared");

        ResponseEntity<Void> foreign = controller.callback(null, null, "access_denied", null, "https://evil.example", request);
        org.junit.jupiter.api.Assertions.assertTrue(foreign.getHeaders().getLocation().toString()
                .startsWith("http://localhost:5173/settings/carriers"));
    }

    @Test
    void disconnect_happyPath_clearsTokenAndFlipsVerifiedAndReturns204() {
        CarrierAccountRef account = new CarrierAccountRef();
        account.setId(42L);
        account.setStampsRefreshToken("rt-live");
        account.setVerified(true);
        when(repository.findById(42L)).thenReturn(Optional.of(account));
        var audit = mock(com.multiship.backend.service.AuditService.class);
        var events = mock(org.springframework.context.ApplicationEventPublisher.class);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "auditService", audit);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "eventPublisher", events);

        ResponseEntity<Void> resp = controller.disconnect(42L);

        verify(audit).record(eq("UPDATE"), eq("CARRIER_ACCOUNT"), eq(42L), anyString(), any(),
                org.mockito.ArgumentMatchers.startsWith("Disconnected from Stamps.com"));
        verify(events).publishEvent(any(com.multiship.backend.service.events.CarrierConfigChangedEvent.class));

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
    void disconnect_withoutAStampsToken_returns204AndLeavesTheAccountAlone() {
        // Never authorized, already disconnected, or a USPS Direct account
        // (verified, but nothing from Stamps.com to forget): idempotent 204,
        // and the account is not touched — safe to double-click.
        CarrierAccountRef account = new CarrierAccountRef();
        account.setId(7L);
        account.setStampsRefreshToken(null);
        account.setVerified(true);
        when(repository.findById(7L)).thenReturn(Optional.of(account));

        ResponseEntity<Void> resp = controller.disconnect(7L);

        assertEquals(HttpStatus.NO_CONTENT, resp.getStatusCode());
        assertEquals(Boolean.TRUE, account.getVerified());
        verify(repository, never()).save(any());
        verify(oauthService, never()).clearTokenCache();
    }
}
