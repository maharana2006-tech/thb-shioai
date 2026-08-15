package com.multiship.backend.service;

import com.multiship.backend.dto.InvitePreviewResponse;
import com.multiship.backend.dto.MessageResponse;
import com.multiship.backend.model.UserInvite;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.Field;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Sprint 51 User↔Client linkage re-audit item #1 — service-layer coverage
 * for {@link AuthServiceImpl#previewInvite}. Verifies each of the four
 * InviteStatus branches maps to the intended HTTP status + errorCode and
 * that the VALID branch never calls consume (the preview must be
 * idempotent so the SPA can refresh without burning the token).
 */
class AuthServiceImplPreviewInviteTest {

    private UserInviteService inviteService;
    private AuthServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        inviteService = mock(UserInviteService.class);
        service = new AuthServiceImpl();
        injectField("inviteService", inviteService);
    }

    private void injectField(String name, Object value) throws Exception {
        Field f = AuthServiceImpl.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(service, value);
    }

    private UserInvite invite() {
        UserInvite i = new UserInvite();
        i.setEmail("bob@example.com");
        i.setClientCode("ACME");
        i.setRole("USER");
        i.setExpiresAt(LocalDateTime.now().plusDays(3));
        return i;
    }

    @Test
    void previewInvite_returns200AndDtoOnValid() {
        UserInvite inv = invite();
        when(inviteService.check("tok")).thenReturn(
                new UserInviteService.InviteCheckResult(
                        UserInviteService.InviteStatus.VALID, inv));

        ResponseEntity<?> resp = service.previewInvite("tok");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        InvitePreviewResponse body = assertInstanceOf(InvitePreviewResponse.class, resp.getBody());
        assertEquals("bob@example.com", body.getEmail());
        assertEquals("ACME", body.getClientCode());
        assertEquals("USER", body.getRole());
        // Idempotent — consume must NEVER be called on preview.
        verify(inviteService).check("tok");
        verifyNoMoreInteractions(inviteService);
    }

    @Test
    void previewInvite_returns404OnNotFound() {
        when(inviteService.check("bogus")).thenReturn(
                new UserInviteService.InviteCheckResult(
                        UserInviteService.InviteStatus.NOT_FOUND, null));

        ResponseEntity<?> resp = service.previewInvite("bogus");

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
        MessageResponse body = assertInstanceOf(MessageResponse.class, resp.getBody());
        assertEquals("INVITE_NOT_FOUND", body.getErrorCode());
    }

    @Test
    void previewInvite_returns410OnExpired() {
        UserInvite inv = invite();
        inv.setExpiresAt(LocalDateTime.now().minusDays(1));
        when(inviteService.check("old")).thenReturn(
                new UserInviteService.InviteCheckResult(
                        UserInviteService.InviteStatus.EXPIRED, inv));

        ResponseEntity<?> resp = service.previewInvite("old");

        assertEquals(HttpStatus.GONE, resp.getStatusCode());
        MessageResponse body = assertInstanceOf(MessageResponse.class, resp.getBody());
        assertEquals("INVITE_EXPIRED", body.getErrorCode());
    }

    @Test
    void previewInvite_returns409OnAlreadyUsed() {
        UserInvite inv = invite();
        inv.setConsumedAt(LocalDateTime.now().minusMinutes(5));
        when(inviteService.check("used")).thenReturn(
                new UserInviteService.InviteCheckResult(
                        UserInviteService.InviteStatus.ALREADY_USED, inv));

        ResponseEntity<?> resp = service.previewInvite("used");

        assertEquals(HttpStatus.CONFLICT, resp.getStatusCode());
        MessageResponse body = assertInstanceOf(MessageResponse.class, resp.getBody());
        assertEquals("INVITE_ALREADY_USED", body.getErrorCode());
    }

    @Test
    void previewInvite_rejectsBlankToken() {
        ResponseEntity<?> resp = service.previewInvite("   ");

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        MessageResponse body = assertInstanceOf(MessageResponse.class, resp.getBody());
        assertEquals("VALIDATION_ERROR", body.getErrorCode());
        // Never reached the invite service.
        verifyNoMoreInteractions(inviteService);
    }
}
