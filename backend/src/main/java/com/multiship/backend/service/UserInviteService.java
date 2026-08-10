package com.multiship.backend.service;

import com.multiship.backend.model.UserInvite;
import com.multiship.backend.repository.UserInviteRepository;
import com.multiship.backend.service.mail.MailSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Sprint 50 Tier 0.5 PR D — mint / list / consume invites.
 *
 * <p>Admin path (via {@code AdminUserInviteController}): mint an invite
 * with a target clientCode + role; the mailer sends the accept link.
 * The admin UI also shows the token inline as a copy-fallback for
 * dev / no-SMTP environments.
 *
 * <p>Public path (via {@code AcceptInviteController}): validate token
 * unexpired + unconsumed → return the invite so the caller can
 * complete signup with the pre-assigned clientCode + role. Consumed
 * rows stay for audit ({@code consumed_at} timestamp).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserInviteService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserInviteRepository repository;
    private final MailSender mailSender;

    @Value("${invite.token-ttl-days:7}")
    private int tokenTtlDays;

    /** Two branches of a token check — used to shape the specific 400/409. */
    public enum InviteStatus { VALID, EXPIRED, ALREADY_USED, NOT_FOUND }

    public record InviteCheckResult(InviteStatus status, UserInvite invite) {}

    /**
     * Admin action: mint an invite for a specific email pre-scoped to
     * a clientCode + role. Returns the persisted row so the caller can
     * echo the token inline for copy-fallback when the mailer is
     * log-only (dev / staging without SMTP).
     */
    @Transactional
    public UserInvite mint(String email, String clientCode, String role, String invitedBy,
                            String acceptLinkBaseUrl) {
        UserInvite invite = new UserInvite();
        invite.setToken(randomToken());
        invite.setEmail(email.trim().toLowerCase());
        invite.setClientCode(clientCode.trim().toUpperCase());
        invite.setRole(role.trim().toUpperCase());
        invite.setInvitedBy(invitedBy);
        invite.setCreatedAt(LocalDateTime.now());
        invite.setExpiresAt(LocalDateTime.now().plusDays(tokenTtlDays));
        repository.save(invite);

        String acceptLink = acceptLinkBaseUrl + "/invite/" + invite.getToken();
        mailSender.send(invite.getEmail(),
                "You've been invited to Multiship",
                "You've been invited to join Multiship as " + invite.getRole()
                        + " for client " + invite.getClientCode() + ".\n\n"
                        + "Accept the invite (expires in " + tokenTtlDays + " days):\n"
                        + acceptLink + "\n\n"
                        + "Invited by: " + invitedBy);
        return invite;
    }

    /**
     * Public token check. Distinguishes NOT_FOUND / EXPIRED / ALREADY_USED
     * so the caller can emit the right errorCode; VALID means the
     * accept path can safely create the User + consume in one tx.
     */
    @Transactional(readOnly = true)
    public InviteCheckResult check(String token) {
        Optional<UserInvite> found = repository.findByToken(token);
        if (found.isEmpty()) {
            return new InviteCheckResult(InviteStatus.NOT_FOUND, null);
        }
        UserInvite invite = found.get();
        if (invite.getConsumedAt() != null) {
            return new InviteCheckResult(InviteStatus.ALREADY_USED, invite);
        }
        if (LocalDateTime.now().isAfter(invite.getExpiresAt())) {
            return new InviteCheckResult(InviteStatus.EXPIRED, invite);
        }
        return new InviteCheckResult(InviteStatus.VALID, invite);
    }

    /** Marks the invite consumed. Callers do this AFTER creating the User atomically. */
    @Transactional
    public void consume(UserInvite invite) {
        invite.setConsumedAt(LocalDateTime.now());
        repository.save(invite);
    }

    public List<UserInvite> listAll() {
        return repository.findAll();
    }

    private static String randomToken() {
        byte[] buf = new byte[24];  // 48-char hex
        RANDOM.nextBytes(buf);
        return HexFormat.of().formatHex(buf);
    }
}
