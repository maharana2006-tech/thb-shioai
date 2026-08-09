package com.multiship.backend.service;

import com.multiship.backend.dto.AuthResponse;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.LoginRequest;
import com.multiship.backend.dto.MessageResponse;
import com.multiship.backend.dto.SignupRequest;
import com.multiship.backend.config.JwtService;
import com.multiship.backend.model.User;
import com.multiship.backend.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource; // Added for enterprise string lookups
import org.springframework.context.i18n.LocaleContextHolder; // Automatically tracks runtime system defaults
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

@Service
public class AuthServiceImpl implements AuthService {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private MessageSource messageSource; // 🛠️ Upgraded from Environment for perfect properties resolution

    @Autowired
    private JwtService jwtService;

    @Override
    public ResponseEntity<MessageResponse> registerUser(SignupRequest signupRequest) {

        // 1. Business Logic Check: Prevent Duplicate Usernames (409 — the
        // request is valid, it conflicts with existing state)
        if (userRepository.existsByUsername(signupRequest.getUsername())) {
            String errorMsg = messageSource.getMessage("error.username.taken", null, LocaleContextHolder.getLocale());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new MessageResponse(errorMsg, ErrorCode.USERNAME_TAKEN));
        }

        // 2. Business Logic Check: Prevent Duplicate Emails
        if (userRepository.existsByEmail(signupRequest.getEmail())) {
            String errorMsg = messageSource.getMessage("error.email.taken", null, LocaleContextHolder.getLocale());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new MessageResponse(errorMsg, ErrorCode.EMAIL_TAKEN));
        }

        // 3. Role — Sprint 49 Tier 1: public signup NEVER trusts the browser.
        //    Anonymous /auth/signup always creates a USER account. TENANT and
        //    ADMIN accounts are created via admin-only flows. If the request
        //    tried to smuggle a privileged role, log it (potential probe).
        String requestedRole = signupRequest.getRole() == null ? null
                : signupRequest.getRole().trim().toUpperCase();
        if (requestedRole != null && !requestedRole.isEmpty() && !"USER".equals(requestedRole)) {
            // Never fatal — just ignore and record. An admin creating an
            // account via a separate path is out of scope for this endpoint.
            org.slf4j.LoggerFactory.getLogger(AuthServiceImpl.class)
                    .warn("Signup with non-USER role '{}' ignored; forcing USER. username={}",
                            requestedRole, signupRequest.getUsername());
        }
        String resolvedRole = "USER";

        // 4. Construct Operator and securely hash password using BCrypt
        User user = User.builder()
                .username(signupRequest.getUsername())
                .email(signupRequest.getEmail())
                .password(passwordEncoder.encode(signupRequest.getPassword()))
                .fullName(signupRequest.getFullName())
                .role(resolvedRole)
                .build();

        userRepository.save(user);

        // 4. Return success response pulling text cleanly from messages.properties
        String successMsg = messageSource.getMessage("success.user.registered", null, LocaleContextHolder.getLocale());
        return ResponseEntity.status(HttpStatus.CREATED).body(new MessageResponse(successMsg));
    }

    @Override
    public ResponseEntity<?> loginUser(LoginRequest loginRequest) {
        Optional<User> userOptional = userRepository.findByUsername(loginRequest.getUsername());

        if (userOptional.isPresent() && passwordEncoder.matches(loginRequest.getPassword(), userOptional.get().getPassword())) {
            User user = userOptional.get();
            // Sprint 50 Tier 0.5 PR A — carry the user's clientCode in the JWT.
            // Null for legacy internal ADMIN + USER (org-wide); populated for
            // TENANT + any USER assigned to a client via PR E's admin UI.
            String token = jwtService.generateToken(user.getUsername(), user.getRole(), user.getClientCode());
            return ResponseEntity.ok(new AuthResponse(token, user.getUsername(), user.getRole()));
        }

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new MessageResponse("Error: Invalid username or password!", ErrorCode.INVALID_CREDENTIALS));
    }

    @Override
    public ResponseEntity<MessageResponse> logoutUser(String tokenHeader) {
        // 1. In a production JWT setup, you would parse the token here:
        // String jwt = tokenHeader.substring(7); // Removes "Bearer "
        // tokenBlacklistService.blacklist(jwt);

        // 2. Clear current thread-bound authentication token states
        SecurityContextHolder.clearContext();

        // 3. Resolve the success text cleanly using your MessageSource engine
        String logoutSuccessMsg = messageSource.getMessage("success.user.loggedout", null, LocaleContextHolder.getLocale());

        return ResponseEntity.ok(new MessageResponse(logoutSuccessMsg));
    }
}