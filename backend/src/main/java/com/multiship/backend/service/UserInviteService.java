package com.multiship.backend.service;

import com.multiship.backend.model.UserInvite;
import com.multiship.backend.repository.UserInviteRepository;
import com.multiship.backend.service.mail.NotificationService;
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
    private final NotificationService notifications;
    /**
     * Sprint 50 Tier 0.5 PR E - clamp clientCode on invite mint so a
     * scoped USER (in the unlikely case they're allowed to invite) cannot
     * mint an invite for a foreign tenant. Platform ADMIN pass-through.
     */
    private final TenantScopeEnforcer tenantScope;

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
        // Sprint 50 Tier 0.5 PR E - Pattern A clamp: scoped USER can only
        // invite to their own tenant. ADMIN pass-through.
        String scopedClientCode = tenantScope.clampClientCode(clientCode);
        // BUG-084 — refuse a mint when a live pending invite for this email
        // already exists. Previously the second POST would issue a fresh
        // token so the recipient saw two live accept links in two emails,
        // which is confusing on the admin side (the Pending-invites panel
        // listed the same address twice) and a token-sprawl risk. Operator
        // can Revoke the existing invite first, or Resend it (which already
        // refreshes the expiry) if they want to send again.
        String normalisedEmail = email.trim().toLowerCase();
        java.util.List<UserInvite> live = repository.findLivePendingByEmail(normalisedEmail);
        if (!live.isEmpty()) {
            // BUG-145 — pre-fix we printed LocalDateTime.toString ('2026-10-21T14:06:40.879515')
            // which looks like a timestamp from a stacktrace, not an expiry date
            // an operator can read. Format as 'Oct 21, 2026, 2:06 PM UTC' instead.
            java.time.LocalDateTime exp = live.get(0).getExpiresAt();
            String expiryText = exp.format(
                java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy, h:mm a"))
                + " UTC";
            throw new IllegalStateException(
                "An invite for " + normalisedEmail + " is still pending (expires "
                    + expiryText + "). Revoke it from Pending invites, "
                    + "or use Resend to extend its expiry.");
        }
        UserInvite invite = new UserInvite();
        invite.setToken(randomToken());
        invite.setEmail(normalisedEmail);
        invite.setClientCode(scopedClientCode.trim().toUpperCase());
        invite.setRole(role.trim().toUpperCase());
        invite.setInvitedBy(invitedBy);
        invite.setCreatedAt(LocalDateTime.now());
        invite.setExpiresAt(LocalDateTime.now().plusDays(tokenTtlDays));
        repository.save(invite);

        String acceptLink = acceptLinkBaseUrl + "/invite/" + invite.getToken();
        notifications.send("AUTH.USER_INVITE", invite.getEmail(), java.util.Map.of(
                "acceptLink", acceptLink,
                "role", invite.getRole(),
                "clientCode", invite.getClientCode(),
                "ttlDays", tokenTtlDays,
                "invitedBy", invitedBy == null ? "" : invitedBy));
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

    /** BUG-050 — resend an existing invite. Refreshes the expiry so the
     *  recipient has another {@code tokenTtlDays} window and re-fires
     *  the AUTH.USER_INVITE email. Token is kept, so any link the
     *  recipient still has in their mailbox keeps working. Rejects if
     *  the invite was already consumed. */
    @Transactional
    public UserInvite resend(Long id, String acceptLinkBaseUrl) {
        UserInvite invite = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Invite " + id + " not found"));
        if (invite.getConsumedAt() != null) {
            throw new IllegalStateException("Invite " + id + " was already consumed; cannot resend.");
        }
        invite.setExpiresAt(LocalDateTime.now().plusDays(tokenTtlDays));
        repository.save(invite);
        String acceptLink = acceptLinkBaseUrl + "/invite/" + invite.getToken();
        notifications.send("AUTH.USER_INVITE", invite.getEmail(), java.util.Map.of(
                "acceptLink", acceptLink,
                "role", invite.getRole(),
                "clientCode", invite.getClientCode(),
                "ttlDays", tokenTtlDays,
                "invitedBy", invite.getInvitedBy() == null ? "" : invite.getInvitedBy()));
        return invite;
    }

    /** BUG-050 — revoke an invite by deleting the row. The token is
     *  purged so the accept URL stops working immediately. Admin can
     *  mint a new invite afterwards if they want to retry. */
    @Transactional
    public void revoke(Long id) {
        repository.deleteById(id);
    }

    /** BUG-085 — read-only lookup so the controller can audit resend /
     *  revoke with the email + client code before the row is gone. */
    @Transactional(readOnly = true)
    public java.util.Optional<UserInvite> findById(Long id) {
        return repository.findById(id);
    }

    private static String randomToken() {
        byte[] buf = new byte[24];  // 48-char hex
        RANDOM.nextBytes(buf);
        return HexFormat.of().formatHex(buf);
    }
}
