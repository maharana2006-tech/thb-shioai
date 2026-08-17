package com.multiship.backend.service;

import com.multiship.backend.config.JwtService;
import com.multiship.backend.dto.AcceptInviteRequest;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.MessageResponse;
import com.multiship.backend.dto.SignupRequest;
import com.multiship.backend.model.User;
import com.multiship.backend.model.UserInvite;
import com.multiship.backend.repository.ClientRepository;
import com.multiship.backend.repository.UserRepository;
import com.multiship.backend.service.mail.MailSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sprint 50 Tier 0.5 PR D — signup gate, rate limit, invite accept,
 * email-verify guard on login.
 */
class AuthServiceImplPRDTest {

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private MessageSource messageSource;
    private JwtService jwtService;
    private SignupRateLimiter rateLimiter;
    private UserInviteService inviteService;
    private ClientRepository clientRepository;
    private MailSender mailSender;
    private AuthServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        messageSource = mock(MessageSource.class);
        jwtService = mock(JwtService.class);
        rateLimiter = mock(SignupRateLimiter.class);
        inviteService = mock(UserInviteService.class);
        clientRepository = mock(ClientRepository.class);
        mailSender = mock(MailSender.class);
        service = new AuthServiceImpl();

        injectField("userRepository", userRepository);
        injectField("passwordEncoder", passwordEncoder);
        injectField("messageSource", messageSource);
        injectField("jwtService", jwtService);
        injectField("rateLimiter", rateLimiter);
        injectField("inviteService", inviteService);
        injectField("clientRepository", clientRepository);
        injectField("mailSender", mailSender);
        injectField("emailVerifyTtlHours", 24);
        // Sprint 51 T2 finding #6 — loginUser now checks the auth-failure
        // limiter before running bcrypt. Inject a mock that always allows.
        injectField("authFailureLimiter",
                mock(com.multiship.backend.service.ratelimit.AuthFailureLimiter.class));

        when(passwordEncoder.encode(anyString())).thenReturn("bcrypt-hash");
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("ok");
        when(rateLimiter.isAllowed(anyString(), anyString())).thenReturn(true);
        when(clientRepository.existsByClientCodeIgnoreCase(anyString())).thenReturn(true);
    }

    private void injectField(String name, Object value) throws Exception {
        Field f = AuthServiceImpl.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(service, value);
    }

    private SignupRequest signup(String clientCode) {
        SignupRequest req = new SignupRequest();
        req.setUsername("newuser");
        req.setEmail("new@example.com");
        req.setPassword("p4ssw0rd!");
        req.setFullName("New User");
        req.setClientCode(clientCode);
        return req;
    }

    /* -------- signup.public-enabled gate -------- */

    @Test
    void signupOffByDefaultReturns403Disabled() throws Exception {
        injectField("publicSignupEnabled", false);

        ResponseEntity<MessageResponse> resp = service.registerUser(signup("ACME"), "1.2.3.4");

        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
        assertEquals(ErrorCode.SIGNUP_DISABLED.name(), resp.getBody().getErrorCode());
        verify(userRepository, never()).save(any());
    }

    /* -------- rate limit -------- */

    @Test
    void rateLimitReturns429WithRetryAfter() throws Exception {
        injectField("publicSignupEnabled", true);
        when(rateLimiter.isAllowed(anyString(), anyString())).thenReturn(false);

        ResponseEntity<MessageResponse> resp = service.registerUser(signup("ACME"), "1.2.3.4");

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, resp.getStatusCode());
        assertEquals(ErrorCode.SIGNUP_RATE_LIMITED.name(), resp.getBody().getErrorCode());
        assertNotNull(resp.getHeaders().getFirst("Retry-After"));
        verify(rateLimiter).record("new@example.com", "1.2.3.4", false);
    }

    /* -------- clientCode required -------- */

    @Test
    void missingClientCodeReturns422() throws Exception {
        injectField("publicSignupEnabled", true);

        ResponseEntity<MessageResponse> resp = service.registerUser(signup(null), "1.2.3.4");

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, resp.getStatusCode());
        assertEquals(ErrorCode.CLIENT_CODE_REQUIRED.name(), resp.getBody().getErrorCode());
    }

    @Test
    void unknownClientCodeReturns404() throws Exception {
        injectField("publicSignupEnabled", true);
        when(clientRepository.existsByClientCodeIgnoreCase(anyString())).thenReturn(false);

        ResponseEntity<MessageResponse> resp = service.registerUser(signup("BOGUS"), "1.2.3.4");

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
        assertEquals(ErrorCode.CLIENT_NOT_FOUND.name(), resp.getBody().getErrorCode());
    }

    /* -------- successful public signup creates unverified user + emails -------- */

    @Test
    void successfulSignupCreatesUnverifiedUserAndSendsVerificationEmail() throws Exception {
        injectField("publicSignupEnabled", true);
        when(userRepository.existsByUsername(anyString())).thenReturn(false);
        when(userRepository.existsByEmail(anyString())).thenReturn(false);

        ResponseEntity<MessageResponse> resp = service.registerUser(signup("ACME"), "1.2.3.4");

        assertEquals(HttpStatus.CREATED, resp.getStatusCode());
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        User u = saved.getValue();
        assertEquals("USER", u.getRole());
        assertEquals("ACME", u.getClientCode());
        assertEquals(Boolean.FALSE, u.getEmailVerified(),
                "public-signup users start unverified until the email link is consumed");
        assertNotNull(u.getEmailVerifyToken());
        assertNotNull(u.getEmailVerifyExpiresAt());
        // Rate-limit records the successful attempt so the cap counts probes + real signups.
        verify(rateLimiter).record("new@example.com", "1.2.3.4", true);
        // Verification email fired.
        verify(mailSender).send(anyString(), anyString(), anyString());
    }

    /* -------- login rejects unverified accounts -------- */

    @Test
    void loginRejectsUnverifiedAccount() {
        User u = User.builder()
                .username("newuser").email("new@example.com").password("bcrypt-hash")
                .fullName("New User").role("USER").clientCode("ACME")
                .emailVerified(false).build();
        when(userRepository.findByUsername("newuser")).thenReturn(Optional.of(u));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);

        com.multiship.backend.dto.LoginRequest req = new com.multiship.backend.dto.LoginRequest();
        req.setUsername("newuser");
        req.setPassword("p4ssw0rd!");
        ResponseEntity<?> resp = service.loginUser(req, new org.springframework.mock.web.MockHttpServletResponse());

        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
        MessageResponse body = (MessageResponse) resp.getBody();
        assertEquals(ErrorCode.EMAIL_NOT_VERIFIED.name(), body.getErrorCode());
    }

    /* -------- Sprint 51 BS-M1 — timing side channel on empty-user branch -------- */

    @Test
    void loginUnknownUserStillRunsBcryptCompareToPreventTimingLeak() {
        // Empty user branch previously short-circuited without invoking bcrypt,
        // letting a scripted attacker distinguish "no such user" (fast) from
        // "wrong password" (slow) by response latency alone. The fix runs a
        // dummy compare against a precomputed hash so both branches pay the
        // same CPU cost.
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        com.multiship.backend.dto.LoginRequest req = new com.multiship.backend.dto.LoginRequest();
        req.setUsername("ghost");
        req.setPassword("whatever");
        ResponseEntity<?> resp = service.loginUser(req, new org.springframework.mock.web.MockHttpServletResponse());

        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
        MessageResponse body = (MessageResponse) resp.getBody();
        assertEquals(ErrorCode.INVALID_CREDENTIALS.name(), body.getErrorCode());
        // The critical assertion: bcrypt must have been invoked even for the
        // unknown user so the empty-user branch has the same latency profile
        // as the wrong-password branch.
        verify(passwordEncoder).matches(eq("whatever"), anyString());
    }

    /* -------- accept-invite flow -------- */

    @Test
    void acceptInviteWithExpiredTokenReturns400() {
        when(inviteService.check(anyString())).thenReturn(
                new UserInviteService.InviteCheckResult(UserInviteService.InviteStatus.EXPIRED, null));

        AcceptInviteRequest req = new AcceptInviteRequest();
        req.setToken("tok"); req.setUsername("u"); req.setPassword("password"); req.setFullName("F");
        ResponseEntity<MessageResponse> resp = service.acceptInvite(req);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals(ErrorCode.INVITE_EXPIRED.name(), resp.getBody().getErrorCode());
    }

    @Test
    void acceptInviteWithConsumedTokenReturns409() {
        when(inviteService.check(anyString())).thenReturn(
                new UserInviteService.InviteCheckResult(UserInviteService.InviteStatus.ALREADY_USED, null));

        AcceptInviteRequest req = new AcceptInviteRequest();
        req.setToken("tok"); req.setUsername("u"); req.setPassword("password"); req.setFullName("F");
        ResponseEntity<MessageResponse> resp = service.acceptInvite(req);

        assertEquals(HttpStatus.CONFLICT, resp.getStatusCode());
        assertEquals(ErrorCode.INVITE_ALREADY_USED.name(), resp.getBody().getErrorCode());
    }

    @Test
    void acceptInviteReturns400WhenClientDeletedBetweenMintAndAccept() {
        // Audit B4 — admin minted the invite, then deleted the target client
        // before invitee got around to accepting. Pre-fix, the user was
        // created pointing at a phantom clientCode; nothing surfaced the
        // dangling reference until the user hit a scoped endpoint and got
        // 404s. Now the accept endpoint surfaces it up-front.
        UserInvite invite = new UserInvite();
        invite.setEmail("late@example.com");
        invite.setClientCode("GHOST");
        invite.setRole("TENANT");
        invite.setExpiresAt(LocalDateTime.now().plusDays(1));
        when(inviteService.check(anyString())).thenReturn(
                new UserInviteService.InviteCheckResult(UserInviteService.InviteStatus.VALID, invite));
        // Client deleted between mint and accept.
        when(clientRepository.existsByClientCodeIgnoreCase("GHOST")).thenReturn(false);

        AcceptInviteRequest req = new AcceptInviteRequest();
        req.setToken("tok"); req.setUsername("invitee"); req.setPassword("pw123456"); req.setFullName("Invitee");
        ResponseEntity<MessageResponse> resp = service.acceptInvite(req);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals(ErrorCode.CLIENT_NOT_FOUND.name(), resp.getBody().getErrorCode());
        verify(userRepository, never()).save(any(User.class));
        verify(inviteService, never()).consume(any());
    }

    @Test
    void acceptInviteCreatesUserWithInviteScopeVerified() {
        UserInvite invite = new UserInvite();
        invite.setEmail("inv@example.com");
        invite.setClientCode("ACME");
        invite.setRole("TENANT");
        invite.setExpiresAt(LocalDateTime.now().plusDays(1));
        when(inviteService.check(anyString())).thenReturn(
                new UserInviteService.InviteCheckResult(UserInviteService.InviteStatus.VALID, invite));
        when(userRepository.existsByUsername(anyString())).thenReturn(false);
        when(userRepository.existsByEmail(anyString())).thenReturn(false);

        AcceptInviteRequest req = new AcceptInviteRequest();
        req.setToken("tok"); req.setUsername("invitee"); req.setPassword("pw123456"); req.setFullName("Invitee");
        ResponseEntity<MessageResponse> resp = service.acceptInvite(req);

        assertEquals(HttpStatus.CREATED, resp.getStatusCode());
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        User u = saved.getValue();
        // Server-picked, never trusts the client.
        assertEquals("TENANT", u.getRole());
        assertEquals("ACME", u.getClientCode());
        assertEquals("inv@example.com", u.getEmail());
        assertTrue(Boolean.TRUE.equals(u.getEmailVerified()),
                "invite acceptance IS the verification — no email round-trip");
        // Invite consumed atomically after the user save.
        verify(inviteService).consume(invite);
    }

    /* -------- verify-email endpoint -------- */

    @Test
    void verifyEmailBlankTokenReturns400() {
        ResponseEntity<MessageResponse> resp = service.verifyEmail(null);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    void verifyEmailUnknownTokenReturns404() {
        when(userRepository.findByEmailVerifyToken(anyString())).thenReturn(Optional.empty());
        ResponseEntity<MessageResponse> resp = service.verifyEmail("bogus");
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    @Test
    void verifyEmailExpiredTokenReturns400() {
        User u = User.builder()
                .username("newuser").email("new@example.com").password("bcrypt-hash")
                .fullName("New").role("USER")
                .emailVerified(false)
                .emailVerifyToken("tok")
                .emailVerifyExpiresAt(LocalDateTime.now().minusHours(1))
                .build();
        when(userRepository.findByEmailVerifyToken("tok")).thenReturn(Optional.of(u));

        ResponseEntity<MessageResponse> resp = service.verifyEmail("tok");
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals(ErrorCode.INVITE_EXPIRED.name(), resp.getBody().getErrorCode());
    }

    @Test
    void verifyEmailValidTokenFlipsFlagAndClearsToken() {
        User u = User.builder()
                .username("newuser").email("new@example.com").password("bcrypt-hash")
                .fullName("New").role("USER")
                .emailVerified(false)
                .emailVerifyToken("tok")
                .emailVerifyExpiresAt(LocalDateTime.now().plusHours(1))
                .build();
        when(userRepository.findByEmailVerifyToken("tok")).thenReturn(Optional.of(u));

        ResponseEntity<MessageResponse> resp = service.verifyEmail("tok");
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertTrue(Boolean.TRUE.equals(saved.getValue().getEmailVerified()));
        assertEquals(null, saved.getValue().getEmailVerifyToken(),
                "used token cleared so it can't be replayed");
    }
}
