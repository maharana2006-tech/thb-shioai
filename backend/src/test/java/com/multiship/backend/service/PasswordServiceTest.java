package com.multiship.backend.service;

import com.multiship.backend.dto.PasswordChangeRequest;
import com.multiship.backend.dto.PasswordForgotRequest;
import com.multiship.backend.dto.PasswordResetRequest;
import com.multiship.backend.model.PasswordResetToken;
import com.multiship.backend.model.User;
import com.multiship.backend.repository.PasswordResetTokenRepository;
import com.multiship.backend.repository.UserRepository;
import com.multiship.backend.service.mail.MailSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sprint 51 BS-M4 — PasswordService change / forgot / reset.
 * <p>Verifies the happy path, wrong-old-password, expired/reused tokens,
 * anti-enumeration on forgot, and that token_version is bumped on any
 * successful change so outstanding JWTs are invalidated.
 */
class PasswordServiceTest {

    private UserRepository userRepo;
    private PasswordResetTokenRepository resetRepo;
    private PasswordEncoder encoder;
    private TokenRevocationService tokenRevocation;
    private MailSender mailer;
    private PasswordService service;

    @BeforeEach
    void setUp() {
        userRepo = mock(UserRepository.class);
        resetRepo = mock(PasswordResetTokenRepository.class);
        encoder = mock(PasswordEncoder.class);
        tokenRevocation = mock(TokenRevocationService.class);
        mailer = mock(MailSender.class);
        service = new PasswordService(userRepo, resetRepo, encoder, tokenRevocation, mailer);
        ReflectionTestUtils.setField(service, "resetLinkBaseUrl", "https://example.test/reset");
        when(encoder.encode(anyString())).thenReturn("bcrypt-new");
    }

    /* ==================== change ==================== */

    @Test
    void change_happyPath_updatesPasswordAndBumpsTokenVersion() {
        User u = user("alice");
        when(userRepo.findByUsername("alice")).thenReturn(Optional.of(u));
        when(encoder.matches("old-pw", "bcrypt-old")).thenReturn(true);

        PasswordChangeRequest req = new PasswordChangeRequest();
        req.setOldPassword("old-pw");
        req.setNewPassword("new-pw-1234");

        PasswordService.ChangeOutcome outcome = service.change("alice", req);

        assertEquals(PasswordService.ChangeOutcome.OK, outcome);
        verify(tokenRevocation).bumpTokenVersion(u);
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepo).save(saved.capture());
        assertEquals("bcrypt-new", saved.getValue().getPassword());
    }

    @Test
    void change_wrongOldPassword_returnsWrongOldPasswordAndDoesNotSave() {
        User u = user("alice");
        when(userRepo.findByUsername("alice")).thenReturn(Optional.of(u));
        when(encoder.matches("bogus", "bcrypt-old")).thenReturn(false);

        PasswordChangeRequest req = new PasswordChangeRequest();
        req.setOldPassword("bogus");
        req.setNewPassword("new-pw-1234");

        PasswordService.ChangeOutcome outcome = service.change("alice", req);

        assertEquals(PasswordService.ChangeOutcome.WRONG_OLD_PASSWORD, outcome);
        verify(userRepo, never()).save(any());
        verify(tokenRevocation, never()).bumpTokenVersion(any());
    }

    @Test
    void change_missingUser_returnsUserNotFound() {
        when(userRepo.findByUsername("ghost")).thenReturn(Optional.empty());
        PasswordChangeRequest req = new PasswordChangeRequest();
        req.setOldPassword("x"); req.setNewPassword("new-pw-1234");

        PasswordService.ChangeOutcome outcome = service.change("ghost", req);
        assertEquals(PasswordService.ChangeOutcome.USER_NOT_FOUND, outcome);
    }

    /* ==================== forgot ==================== */

    @Test
    void forgot_knownEmail_persistsHashedTokenAndSendsMail() {
        User u = user("alice");
        u.setEmail("alice@example.com");
        when(userRepo.findByEmailIgnoreCase("alice@example.com")).thenReturn(Optional.of(u));

        PasswordForgotRequest req = new PasswordForgotRequest();
        req.setEmail("alice@example.com");
        service.forgot(req);

        ArgumentCaptor<PasswordResetToken> tokenCap = ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(resetRepo).save(tokenCap.capture());
        PasswordResetToken row = tokenCap.getValue();
        assertEquals(1L, row.getUserId());
        assertNotNull(row.getTokenHash());
        // SHA-256 hex is 64 chars — anything shorter means we stored plaintext.
        assertEquals(64, row.getTokenHash().length());
        assertTrue(row.getExpiresAt().isAfter(LocalDateTime.now()));

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(mailer).send(eq("alice@example.com"), anyString(), body.capture());
        // The reset link must carry the plaintext token, not the hash.
        assertTrue(body.getValue().contains("https://example.test/reset?token="),
                "reset mail must include the reset link");
        assertNotEquals(true, body.getValue().contains(row.getTokenHash()),
                "the DB hash must NEVER be emailed — only the plaintext");
    }

    @Test
    void forgot_unknownEmail_isSilentNoOpForAntiEnumeration() {
        when(userRepo.findByEmailIgnoreCase(anyString())).thenReturn(Optional.empty());

        PasswordForgotRequest req = new PasswordForgotRequest();
        req.setEmail("ghost@example.com");
        service.forgot(req);

        verify(resetRepo, never()).save(any());
        verify(mailer, never()).send(anyString(), anyString(), anyString());
    }

    /* ==================== reset ==================== */

    @Test
    void reset_happyPath_updatesPasswordBumpsTvAndDeletesTokenRow() {
        User u = user("alice");
        String plaintext = "PLAINTEXT-TOKEN";
        String hash = sha256Hex(plaintext);
        PasswordResetToken row = PasswordResetToken.builder()
                .id(1L).userId(1L).tokenHash(hash)
                .expiresAt(LocalDateTime.now().plusMinutes(5))
                .build();
        when(resetRepo.findByTokenHash(hash)).thenReturn(Optional.of(row));
        when(userRepo.findById(1L)).thenReturn(Optional.of(u));

        PasswordResetRequest req = new PasswordResetRequest();
        req.setToken(plaintext);
        req.setNewPassword("new-pw-1234");

        PasswordService.ChangeOutcome outcome = service.reset(req);

        assertEquals(PasswordService.ChangeOutcome.OK, outcome);
        verify(tokenRevocation).bumpTokenVersion(u);
        verify(userRepo).save(u);
        // Single-use: row must be deleted.
        verify(resetRepo).delete(row);
    }

    @Test
    void reset_expiredToken_returnsExpiredAndDeletesRow() {
        String plaintext = "OLD-TOKEN";
        String hash = sha256Hex(plaintext);
        PasswordResetToken row = PasswordResetToken.builder()
                .id(2L).userId(1L).tokenHash(hash)
                .expiresAt(LocalDateTime.now().minusMinutes(1))
                .build();
        when(resetRepo.findByTokenHash(hash)).thenReturn(Optional.of(row));

        PasswordResetRequest req = new PasswordResetRequest();
        req.setToken(plaintext); req.setNewPassword("new-pw-1234");

        PasswordService.ChangeOutcome outcome = service.reset(req);

        assertEquals(PasswordService.ChangeOutcome.TOKEN_EXPIRED, outcome);
        verify(userRepo, never()).save(any());
        verify(resetRepo).delete(row);
    }

    @Test
    void reset_unknownToken_returnsTokenInvalid() {
        when(resetRepo.findByTokenHash(anyString())).thenReturn(Optional.empty());

        PasswordResetRequest req = new PasswordResetRequest();
        req.setToken("nope-not-real"); req.setNewPassword("new-pw-1234");

        PasswordService.ChangeOutcome outcome = service.reset(req);

        assertEquals(PasswordService.ChangeOutcome.TOKEN_INVALID, outcome);
        verify(userRepo, never()).save(any());
    }

    @Test
    void reset_reusedToken_returnsTokenInvalidOnSecondUse() {
        String plaintext = "USE-ONCE";
        String hash = sha256Hex(plaintext);
        PasswordResetToken row = PasswordResetToken.builder()
                .id(3L).userId(1L).tokenHash(hash)
                .expiresAt(LocalDateTime.now().plusMinutes(5))
                .build();
        User u = user("alice");
        // First call finds the row and consumes it.
        when(resetRepo.findByTokenHash(hash))
                .thenReturn(Optional.of(row))
                .thenReturn(Optional.empty());
        when(userRepo.findById(1L)).thenReturn(Optional.of(u));

        PasswordResetRequest req = new PasswordResetRequest();
        req.setToken(plaintext); req.setNewPassword("new-pw-1234");

        assertEquals(PasswordService.ChangeOutcome.OK, service.reset(req));
        // Second use of the same plaintext should fail as the row is gone.
        assertEquals(PasswordService.ChangeOutcome.TOKEN_INVALID, service.reset(req));
        // Only one delete + one save from the first use.
        verify(resetRepo, times(1)).delete(row);
        verify(userRepo, times(1)).save(u);
    }

    /* -------- helpers -------- */

    private static User user(String username) {
        return User.builder()
                .id(1L).username(username).email(username + "@example.com")
                .password("bcrypt-old").fullName("Alice").role("USER")
                .clientCode("ACME").emailVerified(true).tokenVersion(0L)
                .build();
    }

    private static String sha256Hex(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] out = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(out);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
