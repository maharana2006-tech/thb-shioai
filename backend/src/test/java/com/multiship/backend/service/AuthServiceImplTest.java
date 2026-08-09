package com.multiship.backend.service;

import com.multiship.backend.config.JwtService;
import com.multiship.backend.dto.MessageResponse;
import com.multiship.backend.dto.SignupRequest;
import com.multiship.backend.model.User;
import com.multiship.backend.repository.UserRepository;
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
 */
class AuthServiceImplTest {

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private MessageSource messageSource;
    private JwtService jwtService;
    private AuthServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        messageSource = mock(MessageSource.class);
        jwtService = mock(JwtService.class);
        service = new AuthServiceImpl();

        // The service uses @Autowired field injection — set via reflection
        // to keep this a real unit test (no Spring context).
        injectField(service, "userRepository", userRepository);
        injectField(service, "passwordEncoder", passwordEncoder);
        injectField(service, "messageSource", messageSource);
        injectField(service, "jwtService", jwtService);

        when(passwordEncoder.encode(anyString())).thenReturn("bcrypt-hash");
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("ok");
    }

    private static void injectField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private SignupRequest signup(String username, String role) {
        SignupRequest req = new SignupRequest();
        req.setUsername(username);
        req.setEmail(username + "@example.com");
        req.setPassword("p4ssw0rd!");
        req.setFullName("Test User");
        req.setRole(role);
        return req;
    }

    @Test
    void signupIgnoresAdminRoleFromRequest() {
        // The classic self-elevation attempt: POST { role: "ADMIN" }.
        // Server must save the user as USER, not ADMIN.
        when(userRepository.existsByUsername(anyString())).thenReturn(false);
        when(userRepository.existsByEmail(anyString())).thenReturn(false);

        ResponseEntity<MessageResponse> resp = service.registerUser(signup("alice", "ADMIN"));

        assertEquals(HttpStatus.CREATED, resp.getStatusCode(),
                "signup with role=ADMIN must succeed as USER, not be rejected as forbidden");
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertEquals("USER", captor.getValue().getRole());
    }

    @Test
    void signupIgnoresTenantRoleFromRequest() {
        // TENANT is a real role but public signup no longer grants it.
        when(userRepository.existsByUsername(anyString())).thenReturn(false);
        when(userRepository.existsByEmail(anyString())).thenReturn(false);

        service.registerUser(signup("bob", "TENANT"));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertEquals("USER", captor.getValue().getRole());
    }

    @Test
    void signupAcceptsUserRole() {
        when(userRepository.existsByUsername(anyString())).thenReturn(false);
        when(userRepository.existsByEmail(anyString())).thenReturn(false);

        service.registerUser(signup("carol", "USER"));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertEquals("USER", captor.getValue().getRole());
    }

    @Test
    void signupTreatsMissingRoleAsUser() {
        when(userRepository.existsByUsername(anyString())).thenReturn(false);
        when(userRepository.existsByEmail(anyString())).thenReturn(false);

        service.registerUser(signup("dave", null));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertEquals("USER", captor.getValue().getRole());
    }

    @Test
    void signupIgnoresGarbageRole() {
        when(userRepository.existsByUsername(anyString())).thenReturn(false);
        when(userRepository.existsByEmail(anyString())).thenReturn(false);

        service.registerUser(signup("eve", "SUPER_ADMIN"));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertEquals("USER", captor.getValue().getRole());
    }
}
