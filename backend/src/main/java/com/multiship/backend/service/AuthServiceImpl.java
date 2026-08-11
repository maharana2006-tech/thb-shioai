package com.multiship.backend.service;

import com.multiship.backend.dto.AcceptInviteRequest;
import com.multiship.backend.dto.AuthResponse;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.LoginRequest;
import com.multiship.backend.dto.MessageResponse;
import com.multiship.backend.dto.SignupRequest;
import com.multiship.backend.config.JwtService;
import com.multiship.backend.model.User;
import com.multiship.backend.model.UserInvite;
import com.multiship.backend.repository.ClientRepository;
import com.multiship.backend.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource; // Added for enterprise string lookups
import org.springframework.context.i18n.LocaleContextHolder; // Automatically tracks runtime system defaults
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Optional;

@Slf4j
@Service
public class AuthServiceImpl implements AuthService {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private MessageSource messageSource; // 🛠️ Upgraded from Environment for perfect properties resolution

    @Autowired
    private JwtService jwtService;

    /** Sprint 50 Tier 0.5 PR D — signup gating + invite acceptance. */
    @Autowired
    private SignupRateLimiter rateLimiter;

    @Autowired
    private UserInviteService inviteService;

    @Autowired
    private ClientRepository clientRepository;

    @Autowired
    private com.multiship.backend.service.mail.MailSender mailSender;

    @Value("${signup.public-enabled:false}")
    private boolean publicSignupEnabled;

    @Value("${signup.email-verify-ttl-hours:24}")
    private int emailVerifyTtlHours;

    @Override
    @Transactional
    public ResponseEntity<MessageResponse> registerUser(SignupRequest signupRequest, String remoteIp) {

        // Sprint 50 Tier 0.5 PR D — public signup is OFF by default. Deploys
        // that want to allow it must flip signup.public-enabled=true AND
        // (typically) supply a CAPTCHA provider. Invite-accept remains open.
        if (!publicSignupEnabled) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new MessageResponse(
                            "Public signup is disabled. Ask an admin for an invite.",
                            ErrorCode.SIGNUP_DISABLED));
        }

        // Sprint 50 Tier 0.5 PR D — rate-limit per email + per IP over a
        // moving 1h window. The attempt is recorded even on failure so
        // probing counts toward the cap.
        String email = signupRequest.getEmail();
        String ip = remoteIp == null ? "unknown" : remoteIp;
        if (!rateLimiter.isAllowed(email, ip)) {
            rateLimiter.record(email, ip, false);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", "3600")
                    .body(new MessageResponse(
                            "Too many signup attempts. Try again in an hour.",
                            ErrorCode.SIGNUP_RATE_LIMITED));
        }

        // Public signup now REQUIRES clientCode (Sprint 50 PR D). The client
        // must exist + be reachable — no silent auto-creation.
        String clientCode = signupRequest.getClientCode();
        if (clientCode == null || clientCode.isBlank()) {
            rateLimiter.record(email, ip, false);
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(new MessageResponse(
                            "clientCode is required for public signup.",
                            ErrorCode.CLIENT_CODE_REQUIRED));
        }
        String normalizedClient = clientCode.trim();
        if (!clientRepository.existsByClientCodeIgnoreCase(normalizedClient)) {
            rateLimiter.record(email, ip, false);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new MessageResponse(
                            "Client " + normalizedClient + " was not found.",
                            ErrorCode.CLIENT_NOT_FOUND));
        }

        // Duplicate guards (existing behaviour).
        if (userRepository.existsByUsername(signupRequest.getUsername())) {
            rateLimiter.record(email, ip, false);
            String errorMsg = messageSource.getMessage("error.username.taken", null, LocaleContextHolder.getLocale());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new MessageResponse(errorMsg, ErrorCode.USERNAME_TAKEN));
        }
        if (userRepository.existsByEmail(signupRequest.getEmail())) {
            rateLimiter.record(email, ip, false);
            String errorMsg = messageSource.getMessage("error.email.taken", null, LocaleContextHolder.getLocale());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new MessageResponse(errorMsg, ErrorCode.EMAIL_TAKEN));
        }

        // Sprint 49 Tier 1 lock — role always USER on public signup; role
        // in payload is ignored + logged.
        String requestedRole = signupRequest.getRole() == null ? null
                : signupRequest.getRole().trim().toUpperCase();
        if (requestedRole != null && !requestedRole.isEmpty() && !"USER".equals(requestedRole)) {
            log.warn("Signup with non-USER role '{}' ignored; forcing USER. username={}",
                    requestedRole, signupRequest.getUsername());
        }

        // Sprint 50 Tier 0.5 PR D — email verification. Create the User in
        // emailVerified=false state + a one-shot token; send the verification
        // link (or log it in dev). Login will refuse until verified.
        String verifyToken = randomToken();
        LocalDateTime expires = LocalDateTime.now().plusHours(emailVerifyTtlHours);
        User user = User.builder()
                .username(signupRequest.getUsername())
                .email(signupRequest.getEmail())
                .password(passwordEncoder.encode(signupRequest.getPassword()))
                .fullName(signupRequest.getFullName())
                .role("USER")
                .clientCode(normalizedClient.toUpperCase())
                .emailVerified(false)
                .emailVerifyToken(verifyToken)
                .emailVerifyExpiresAt(expires)
                .build();
        userRepository.save(user);
        rateLimiter.record(email, ip, true);

        mailSender.send(user.getEmail(),
                "Verify your Multiship account",
                "Click to verify (expires in " + emailVerifyTtlHours + " hours):\n"
                        + "/auth/verify-email?token=" + verifyToken);

        String successMsg = messageSource.getMessage("success.user.registered", null, LocaleContextHolder.getLocale());
        return ResponseEntity.status(HttpStatus.CREATED).body(new MessageResponse(
                successMsg + " Check your email for the verification link."));
    }

    @Override
    @Transactional
    public ResponseEntity<MessageResponse> acceptInvite(AcceptInviteRequest request) {
        UserInviteService.InviteCheckResult check = inviteService.check(request.getToken());
        switch (check.status()) {
            case NOT_FOUND:
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(new MessageResponse("Invite not found.", ErrorCode.INVITE_NOT_FOUND));
            case EXPIRED:
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(new MessageResponse("Invite expired. Ask the admin for a new one.",
                                ErrorCode.INVITE_EXPIRED));
            case ALREADY_USED:
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(new MessageResponse("Invite already used.",
                                ErrorCode.INVITE_ALREADY_USED));
            default:
                break;
        }

        UserInvite invite = check.invite();
        if (userRepository.existsByUsername(request.getUsername())) {
            String errorMsg = messageSource.getMessage("error.username.taken", null, LocaleContextHolder.getLocale());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new MessageResponse(errorMsg, ErrorCode.USERNAME_TAKEN));
        }
        if (userRepository.existsByEmail(invite.getEmail())) {
            String errorMsg = messageSource.getMessage("error.email.taken", null, LocaleContextHolder.getLocale());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new MessageResponse(errorMsg, ErrorCode.EMAIL_TAKEN));
        }

        User user = User.builder()
                .username(request.getUsername())
                .email(invite.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .fullName(request.getFullName())
                .role(invite.getRole())          // server, not client, chose the role
                .clientCode(invite.getClientCode())  // ditto for clientCode
                .emailVerified(true)             // invite acceptance IS verification
                .build();
        userRepository.save(user);
        inviteService.consume(invite);

        String successMsg = messageSource.getMessage("success.user.registered", null, LocaleContextHolder.getLocale());
        return ResponseEntity.status(HttpStatus.CREATED).body(new MessageResponse(successMsg));
    }

    @Override
    @Transactional
    public ResponseEntity<MessageResponse> verifyEmail(String token) {
        if (token == null || token.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new MessageResponse("Verification token is required.",
                            ErrorCode.VALIDATION_ERROR));
        }
        Optional<User> found = userRepository.findByEmailVerifyToken(token);
        if (found.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new MessageResponse("Verification link is invalid.",
                            ErrorCode.INVITE_NOT_FOUND));
        }
        User user = found.get();
        if (Boolean.TRUE.equals(user.getEmailVerified())) {
            return ResponseEntity.ok(new MessageResponse("Email already verified."));
        }
        if (user.getEmailVerifyExpiresAt() != null
                && LocalDateTime.now().isAfter(user.getEmailVerifyExpiresAt())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new MessageResponse("Verification link expired. Sign up again to get a new one.",
                            ErrorCode.INVITE_EXPIRED));
        }
        user.setEmailVerified(true);
        user.setEmailVerifyToken(null);
        user.setEmailVerifyExpiresAt(null);
        userRepository.save(user);
        return ResponseEntity.ok(new MessageResponse("Email verified. You can now log in."));
    }

    private static String randomToken() {
        byte[] buf = new byte[24];
        RANDOM.nextBytes(buf);
        return HexFormat.of().formatHex(buf);
    }

    @Override
    public ResponseEntity<?> loginUser(LoginRequest loginRequest) {
        Optional<User> userOptional = userRepository.findByUsername(loginRequest.getUsername());

        if (userOptional.isPresent() && passwordEncoder.matches(loginRequest.getPassword(), userOptional.get().getPassword())) {
            User user = userOptional.get();

            // Sprint 50 Tier 0.5 PR E — reject login for an admin-revoked
            // account. Distinct from unverified so the user sees a clear
            // "your account has been deactivated" rather than "verify your
            // email." Check ordered BEFORE the email-verified check because
            // a deactivated unverified account should hit the deactivation
            // message, not the verify-your-email one.
            if (user.getDeactivatedAt() != null) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(new MessageResponse(
                                "This account has been deactivated. Contact your administrator.",
                                ErrorCode.ACCOUNT_DEACTIVATED));
            }

            // Sprint 50 Tier 0.5 PR D — block login until email verified.
            // Legacy rows backfilled to emailVerified=true (V4) so existing
            // operators aren't locked out on deploy. Invite-accepted users
            // also land verified. Only public-signup users start false.
            if (Boolean.FALSE.equals(user.getEmailVerified())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(new MessageResponse(
                                "Email not verified. Check your inbox for the verification link.",
                                ErrorCode.EMAIL_NOT_VERIFIED));
            }

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