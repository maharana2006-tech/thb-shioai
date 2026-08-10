package com.multiship.backend.service;

import com.multiship.backend.config.JwtService;
import com.multiship.backend.dto.MessageResponse;
import com.multiship.backend.dto.SignupRequest;
import com.multiship.backend.model.User;
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
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sprint 49 Tier 1 — public signup never trusts a browser-supplied role.
 * Previously an anonymous POST /auth/signup with {@code "role": "ADMIN"}
 * or {@code "role": "TENANT"} could self-elevate. Now the server hardcodes
 * USER regardless of the payload.
 *
 * <p>Sprint 50 Tier 0.5 PR D — {@code publicSignupEnabled} flag flipped
 * on for these tests since the goal is to guard the role-smuggling
 * behavior when signup IS allowed. Rate-limit tests + gate-off tests
 * live in {@link AuthServiceImplPRDTest}.
 */
class AuthServiceImplTest {

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

        // The service uses @Autowired field injection — set via reflection
        // to keep this a real unit test (no Spring context).
        injectField("userRepository", userRepository);
        injectField("passwordEncoder", passwordEncoder);
        injectField("messageSource", messageSource);
        injectField("jwtService", jwtService);
        injectField("rateLimiter", rateLimiter);
        injectField("inviteService", inviteService);
        injectField("clientRepository", clientRepository);
        injectField("mailSender", mailSender);
        // Sprint 50 PR D — flag on for the role-smuggling tests. Rate-limit
        // + gate-off tests set this false / flip explicitly.
        injectField("publicSignupEnabled", true);
        injectField("emailVerifyTtlHours", 24);

        when(passwordEncoder.encode(anyString())).thenReturn("bcrypt-hash");
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("ok");
        // Default allow-all rate limit + client-exists — PR D-specific tests override.
        when(rateLimiter.isAllowed(anyString(), anyString())).thenReturn(true);
        when(clientRepository.existsByClientCodeIgnoreCase(anyString())).thenReturn(true);
    }

    private void injectField(String name, Object value) throws Exception {
        Field f = AuthServiceImpl.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(service, value);
    }

    private SignupRequest signup(String username, String role) {
        SignupRequest req = new SignupRequest();
        req.setUsername(username);
        req.setEmail(username + "@example.com");
        req.setPassword("p4ssw0rd!");
        req.setFullName("Test User");
        req.setRole(role);
        req.setClientCode("ACME");  // Sprint 50 PR D — required post-D
        return req;
    }

    @Test
    void signupIgnoresAdminRoleFromRequest() {
        when(userRepository.existsByUsername(anyString())).thenReturn(false);
        when(userRepository.existsByEmail(anyString())).thenReturn(false);

        ResponseEntity<MessageResponse> resp = service.registerUser(signup("alice", "ADMIN"), "1.2.3.4");

        assertEquals(HttpStatus.CREATED, resp.getStatusCode(),
                "signup with role=ADMIN must succeed as USER, not be rejected as forbidden");
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertEquals("USER", captor.getValue().getRole());
    }

    @Test
    void signupIgnoresTenantRoleFromRequest() {
        when(userRepository.existsByUsername(anyString())).thenReturn(false);
        when(userRepository.existsByEmail(anyString())).thenReturn(false);

        service.registerUser(signup("bob", "TENANT"), "1.2.3.4");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertEquals("USER", captor.getValue().getRole());
    }

    @Test
    void signupAcceptsUserRole() {
        when(userRepository.existsByUsername(anyString())).thenReturn(false);
        when(userRepository.existsByEmail(anyString())).thenReturn(false);

        service.registerUser(signup("carol", "USER"), "1.2.3.4");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertEquals("USER", captor.getValue().getRole());
    }

    @Test
    void signupTreatsMissingRoleAsUser() {
        when(userRepository.existsByUsername(anyString())).thenReturn(false);
        when(userRepository.existsByEmail(anyString())).thenReturn(false);

        service.registerUser(signup("dave", null), "1.2.3.4");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertEquals("USER", captor.getValue().getRole());
    }

    @Test
    void signupIgnoresGarbageRole() {
        when(userRepository.existsByUsername(anyString())).thenReturn(false);
        when(userRepository.existsByEmail(anyString())).thenReturn(false);

        service.registerUser(signup("eve", "SUPER_ADMIN"), "1.2.3.4");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertEquals("USER", captor.getValue().getRole());
    }
}
