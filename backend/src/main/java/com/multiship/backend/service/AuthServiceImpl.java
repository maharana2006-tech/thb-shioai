package com.multiship.backend.service;

import com.multiship.backend.dto.AcceptInviteRequest;
import com.multiship.backend.dto.AuthResponse;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.InvitePreviewResponse;
import com.multiship.backend.dto.LoginRequest;
import com.multiship.backend.dto.MessageResponse;
import com.multiship.backend.dto.SignupRequest;
import com.multiship.backend.config.JwtService;
import com.multiship.backend.model.User;
import com.multiship.backend.model.UserInvite;
import com.multiship.backend.config.JwtAuthenticationFilter;
import com.multiship.backend.dto.SessionResponse;
import com.multiship.backend.repository.ClientRepository;
import com.multiship.backend.repository.UserRepository;
import com.multiship.backend.service.ratelimit.AuthFailureLimiter;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource; // Added for enterprise string lookups
import org.springframework.context.i18n.LocaleContextHolder; // Automatically tracks runtime system defaults
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
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

    /** Sprint 51 T2 finding #5 — blacklists the token's jti on logout so the
     *  same JWT can't be replayed after the user hits sign out. */
    @Autowired
    private TokenRevocationService tokenRevocationService;

    /** Sprint 50 Tier 0.5 PR D — signup gating + invite acceptance. */
    @Autowired
    private SignupRateLimiter rateLimiter;

    @Autowired
    private UserInviteService inviteService;

    @Autowired
    private ClientRepository clientRepository;

    @Autowired
    private com.multiship.backend.service.mail.MailSender mailSender;

    /** Sprint 51 T2 finding #6 — brute-force lockout on login. */
    @Autowired
    private AuthFailureLimiter authFailureLimiter;

    /**
     * Sprint 51 T2 finding #6 (secondary) — timing side channel fix.
     * When the username doesn't exist we still run a bcrypt compare
     * against this precomputed hash so a scripted attacker can't
     * distinguish "no such user" from "wrong password" by measuring
     * response latency. Precomputed once at class load; the exact
     * plaintext is irrelevant — only the CPU-cost matters.
     */
    private static final String DUMMY_BCRYPT_HASH =
            new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
                    .encode("dummy-hash-for-timing-parity");

    @Value("${signup.public-enabled:false}")
    private boolean publicSignupEnabled;

    @Value("${signup.email-verify-ttl-hours:24}")
    private int emailVerifyTtlHours;

    /**
     * Sprint 51 User↔Client linkage re-audit item #2 — SPA base URL for
     * the verify-email link the mailer sends. Empty default keeps the
     * pre-audit behaviour (backend-relative link, only usable when the
     * SPA is served from the same origin as the API). Set
     * {@code app.spa-base-url} (or the {@code SPA_BASE_URL} env var) on
     * deploys where the SPA is on a different host so the invitee can
     * click the link straight from Gmail.
     */
    @Value("${app.spa-base-url:}")
    private String spaBaseUrl;

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

        // Sprint 51 User↔Client linkage re-audit item #2 — build the link
        // against the SPA route (not the backend endpoint). The SPA's
        // VerifyEmailPage reads the token from ?token= and POSTs it to
        // /api/v1/auth/verify-email so the invitee sees a friendly
        // "Verified — you can now log in" page instead of a raw JSON
        // response. Base URL is empty by default; deploys with a
        // cross-origin SPA must set app.spa-base-url.
        String verifyBase = (spaBaseUrl == null ? "" : spaBaseUrl.trim());
        if (!verifyBase.isEmpty() && verifyBase.endsWith("/")) {
            verifyBase = verifyBase.substring(0, verifyBase.length() - 1);
        }
        mailSender.send(user.getEmail(),
                "Verify your Multiship account",
                "Click to verify (expires in " + emailVerifyTtlHours + " hours):\n"
                        + verifyBase + "/verify-email?token=" + verifyToken);

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

    @Override
    @Transactional(readOnly = true)
    public ResponseEntity<?> previewInvite(String token) {
        // Sprint 51 User↔Client linkage re-audit item #1 — read-only
        // preview. Delegates to UserInviteService#check (same VALID /
        // EXPIRED / ALREADY_USED / NOT_FOUND branching as
        // acceptInvite) but never calls consume, so a preview can be
        // fetched multiple times as the invitee refreshes the SPA.
        //
        // Status codes match the SPA's expectations:
        //   404 INVITE_NOT_FOUND    — unknown token, bad URL
        //   410 INVITE_EXPIRED      — past expires_at (Gone semantic)
        //   409 INVITE_ALREADY_USED — consumed_at set, duplicate accept
        //
        // acceptInvite returns 400 for EXPIRED; here we prefer 410
        // (Gone) because the resource legitimately existed and won't
        // ever come back for this token — the SPA can then show a
        // "Ask your admin for a new invite" CTA. The Message body
        // format stays identical to acceptInvite so the SPA's friendly
        // error map fires the same way.
        if (token == null || token.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new MessageResponse("Invite token is required.",
                            ErrorCode.VALIDATION_ERROR));
        }
        UserInviteService.InviteCheckResult check = inviteService.check(token);
        switch (check.status()) {
            case NOT_FOUND:
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(new MessageResponse("Invite not found.",
                                ErrorCode.INVITE_NOT_FOUND));
            case EXPIRED:
                return ResponseEntity.status(HttpStatus.GONE)
                        .body(new MessageResponse("Invite expired. Ask the admin for a new one.",
                                ErrorCode.INVITE_EXPIRED));
            case ALREADY_USED:
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(new MessageResponse("Invite already used.",
                                ErrorCode.INVITE_ALREADY_USED));
            default:
                return ResponseEntity.ok(InvitePreviewResponse.of(check.invite()));
        }
    }

    private static String randomToken() {
        byte[] buf = new byte[24];
        RANDOM.nextBytes(buf);
        return HexFormat.of().formatHex(buf);
    }

    /** Sprint 50 PR Q1 — httpOnly cookie name. Public constant so the
     *  filter can reference it symmetrically. */
    public static final String AUTH_COOKIE = "multiship_token";

    @Value("${cookie.secure:false}")
    private boolean cookieSecure;

    @Value("${cookie.samesite:Strict}")
    private String cookieSameSite;

    /** Sprint 50 PR Q1 — cookie Max-Age must match JWT expiry so the
     *  browser stops sending it at the same moment the token would
     *  have failed validation. Same property JwtService reads. */
    @Value("${jwt.expiration:86400000}")
    private long jwtExpirationMillis;

    private ResponseCookie authCookie(String token, long maxAgeSeconds) {
        return ResponseCookie.from(AUTH_COOKIE, token == null ? "" : token)
                .httpOnly(true)
                .secure(cookieSecure)
                .path("/")
                .maxAge(maxAgeSeconds)
                .sameSite(cookieSameSite)
                .build();
    }

    @Override
    public ResponseEntity<?> loginUser(LoginRequest loginRequest, HttpServletResponse response) {
        // Sprint 51 T2 finding #6 — delegate to the IP-aware overload.
        // "unknown" disables per-IP lockout for callers without a servlet
        // context (integration tests, internal callers). Per-username
        // half of the composite still bounds automated abuse.
        return loginUser(loginRequest, response, "unknown");
    }

    @Override
    public ResponseEntity<?> loginUser(LoginRequest loginRequest, HttpServletResponse response, String remoteIp) {
        String ip = remoteIp == null || remoteIp.isBlank() ? "unknown" : remoteIp;
        String username = loginRequest.getUsername();

        // Sprint 51 T2 finding #6 — brute-force lockout check BEFORE
        // bcrypt so a locked-out caller doesn't cost 150ms of CPU per
        // hit (that would amplify a DDoS). 429 + Retry-After.
        if (authFailureLimiter.isLocked(ip, username)) {
            int retry = authFailureLimiter.retryAfterSeconds(ip, username);
            log.warn("Login lockout hit for ip={} username={} retry={}s", ip, username, retry);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", String.valueOf(retry))
                    .body(new MessageResponse(
                            "Too many failed login attempts. Try again in "
                                    + Math.max(1, retry / 60) + " minute(s).",
                            ErrorCode.AUTH_FAILURE_LOCKOUT));
        }

        Optional<User> userOptional = userRepository.findByUsername(username);

        // Sprint 51 T2 finding #6 (secondary) — timing side channel fix.
        // Run a dummy bcrypt compare when the user doesn't exist so
        // response time doesn't reveal username validity. Result is
        // discarded — the authOK check below uses userOptional.isPresent().
        boolean passwordOk = false;
        if (userOptional.isPresent()) {
            passwordOk = passwordEncoder.matches(loginRequest.getPassword(),
                    userOptional.get().getPassword());
        } else {
            // Same-cost bcrypt against a throwaway hash.
            passwordEncoder.matches(loginRequest.getPassword(), DUMMY_BCRYPT_HASH);
        }

        if (userOptional.isPresent() && passwordOk) {
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
            //
            // Sprint 51 T2 finding #5 — also carry the user's current
            // token_version so revocation via bumpTokenVersion() invalidates
            // this JWT on the next request.
            long tv = user.getTokenVersion() == null ? 0L : user.getTokenVersion();
            String token = jwtService.generateToken(user.getUsername(), user.getRole(),
                    user.getClientCode(), tv);

            // Sprint 50 PR Q1 — write the JWT as an httpOnly cookie so the SPA
            // can't be XSS-exfiltrated. PR Q3 dropped the transitional body
            // `token` field; the SPA now reads it exclusively from the cookie.
            response.addHeader(HttpHeaders.SET_COOKIE,
                    authCookie(token, Duration.ofMillis(jwtExpirationMillis).toSeconds()).toString());
            // Sprint 51 T2 finding #6 — clear the failure counter so
            // legitimate typos earlier in the session don't leave the
            // account half-locked at next login.
            authFailureLimiter.recordSuccess(ip, username);
            return ResponseEntity.ok(new AuthResponse(user.getUsername(), user.getRole()));
        }

        // Sprint 51 T2 finding #6 — bad credentials → count toward
        // lockout. Deliberately covers both "no such user" and "wrong
        // password" (paired with the DUMMY_BCRYPT_HASH above so timing
        // matches). Deactivated + unverified paths above do NOT count
        // — those aren't brute-force signals.
        authFailureLimiter.recordFailure(ip, username);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new MessageResponse("Error: Invalid username or password!", ErrorCode.INVALID_CREDENTIALS));
    }

    @Override
    public ResponseEntity<MessageResponse> logoutUser(String tokenHeader, HttpServletResponse response) {
        // Sprint 51 T2 finding #5 — blacklist the presented token's jti so
        // the JWT can't be replayed after sign out. Per-device: leaves
        // other sessions for the same user intact (bumpTokenVersion() is
        // the global variant used by admin deactivate / role change).
        //
        // Header parsing is best-effort — if the caller signed out with
        // only the cookie set (SPA flow), the interceptor already cleared
        // the SecurityContext by the time we get here so JWTs from other
        // devices remain the only ones needing revocation, which is
        // exactly what per-device logout intends.
        if (tokenHeader != null && tokenHeader.startsWith("Bearer ")) {
            String jwt = tokenHeader.substring(7);
            try {
                var claims = jwtService.parseClaims(jwt);
                String jti = claims.getId();
                java.util.Date exp = claims.getExpiration();
                if (jti != null && exp != null) {
                    tokenRevocationService.blacklistJti(jti, exp.toInstant());
                }
            } catch (Exception ex) {
                // Malformed / expired token — nothing to blacklist. Cookie
                // clear + context clear below still run.
                log.debug("Logout with unparseable token — skipping jti blacklist: {}", ex.getMessage());
            }
        }

        SecurityContextHolder.clearContext();

        // Sprint 50 PR Q1 — clear the auth cookie. Must set the SAME
        // path + secure + sameSite attributes as the login cookie —
        // browsers only DELETE a cookie when every attribute matches.
        response.addHeader(HttpHeaders.SET_COOKIE, authCookie("", 0).toString());

        String logoutSuccessMsg = messageSource.getMessage("success.user.loggedout", null, LocaleContextHolder.getLocale());

        return ResponseEntity.ok(new MessageResponse(logoutSuccessMsg));
    }

    @Override
    public ResponseEntity<?> currentSession() {
        // Sprint 50 PR Q1 — SPA bootstrap after page refresh. The
        // JwtAuthenticationFilter has already populated the SecurityContext
        // from the cookie (or header, for transitional callers). If nothing
        // was set, return 401 so the SPA redirects to /login.
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()
                || "anonymousUser".equals(String.valueOf(auth.getPrincipal()))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new MessageResponse(
                            "Not authenticated.", ErrorCode.UNAUTHORIZED));
        }
        String role = auth.getAuthorities().stream().findFirst()
                .map(g -> g.getAuthority().replaceFirst("^ROLE_", ""))
                .orElse(null);
        String clientCode = null;
        if (auth.getDetails() instanceof JwtAuthenticationFilter.AuthDetails d) {
            clientCode = d.clientCode();
        }
        return ResponseEntity.ok(new SessionResponse(auth.getName(), role, clientCode));
    }
}