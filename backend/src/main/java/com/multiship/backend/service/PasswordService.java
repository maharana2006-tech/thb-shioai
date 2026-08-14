package com.multiship.backend.service;

import com.multiship.backend.dto.PasswordChangeRequest;
import com.multiship.backend.dto.PasswordForgotRequest;
import com.multiship.backend.dto.PasswordResetRequest;
import com.multiship.backend.model.PasswordResetToken;
import com.multiship.backend.model.User;
import com.multiship.backend.repository.PasswordResetTokenRepository;
import com.multiship.backend.repository.UserRepository;
import com.multiship.backend.service.mail.MailSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Sprint 51 BS-M4 — password change + forgot + reset flow.
 *
 * <p>Three entry points, all called from {@link com.multiship.backend.controller.PasswordController}:
 * <ul>
 *   <li><b>change</b> — authenticated. Verifies old password via bcrypt,
 *       updates the new one, bumps {@code token_version} so every
 *       outstanding JWT for the user is invalidated.</li>
 *   <li><b>forgot</b> — unauthenticated. Mints a 32-byte one-shot token,
 *       stores its SHA-256 hash + 30-min expiry in
 *       {@code password_reset_tokens}, dispatches the plaintext via
 *       {@link MailSender}. Returns unconditionally so an attacker cannot
 *       enumerate registered emails.</li>
 *   <li><b>reset</b> — unauthenticated. Consumes the token (single-use:
 *       row deleted on success), updates the password, bumps
 *       {@code token_version}. Rejects expired + already-used tokens.</li>
 * </ul>
 *
 * <p>The token is a 32-byte SecureRandom hex string (64 chars). Only the
 * hash lives in the DB — the plaintext is emailed to the user and never
 * persisted server-side.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PasswordService {

    private static final SecureRandom RANDOM = new SecureRandom();
    /** 30 min matches typical industry SLA for a password-reset link. */
    private static final int RESET_TOKEN_TTL_MINUTES = 30;

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository resetRepo;
    private final PasswordEncoder passwordEncoder;
    private final TokenRevocationService tokenRevocation;
    private final MailSender mailSender;

    @Value("${app.password-reset.link-base-url:http://localhost:5173/reset-password}")
    private String resetLinkBaseUrl;

    /** Outcome of a change/reset — the controller maps this to a status code. */
    public enum ChangeOutcome { OK, WRONG_OLD_PASSWORD, USER_NOT_FOUND, TOKEN_INVALID, TOKEN_EXPIRED }

    /**
     * Authenticated password change. Verifies old via bcrypt, writes the
     * new hash + bumps {@code token_version} (revokes every outstanding
     * JWT for this user).
     */
    @Transactional
    public ChangeOutcome change(String authenticatedUsername, PasswordChangeRequest req) {
        Optional<User> maybe = userRepository.findByUsername(authenticatedUsername);
        if (maybe.isEmpty()) {
            // Auth said this user exists — if the DB row is gone the caller's
            // token is stale; treat as USER_NOT_FOUND so the controller returns 404.
            return ChangeOutcome.USER_NOT_FOUND;
        }
        User user = maybe.get();
        if (!passwordEncoder.matches(req.getOldPassword(), user.getPassword())) {
            return ChangeOutcome.WRONG_OLD_PASSWORD;
        }
        user.setPassword(passwordEncoder.encode(req.getNewPassword()));
        tokenRevocation.bumpTokenVersion(user);
        userRepository.save(user);
        return ChangeOutcome.OK;
    }

    /**
     * Unauthenticated forgot flow. Mints + persists a hashed one-shot
     * token when the email is known; silently no-ops otherwise so an
     * attacker cannot enumerate. Caller ALWAYS returns 202.
     */
    @Transactional
    public void forgot(PasswordForgotRequest req) {
        String email = req.getEmail() == null ? "" : req.getEmail().trim();
        Optional<User> maybe = userRepository.findByEmailIgnoreCase(email);
        if (maybe.isEmpty()) {
            log.info("Password forgot: email {} not registered — silent no-op (anti-enumeration)", email);
            return;
        }
        User user = maybe.get();
        String plaintext = randomToken();
        String hash = sha256Hex(plaintext);

        PasswordResetToken row = PasswordResetToken.builder()
                .userId(user.getId())
                .tokenHash(hash)
                .expiresAt(LocalDateTime.now().plusMinutes(RESET_TOKEN_TTL_MINUTES))
                .build();
        resetRepo.save(row);

        // TODO wire to SMTP before enabling in prod — the default MailSender
        // impl is LoggingMailSender which INFO-logs the token. That's fine
        // for dev but must be swapped for a real SmtpMailSender in prod
        // (see MailSender javadoc). The invite flow lives with the same
        // caveat; this endpoint inherits it.
        String link = resetLinkBaseUrl + "?token=" + plaintext;
        mailSender.send(user.getEmail(),
                "Password reset request",
                "A password reset was requested for your Multiship account.\n\n"
                        + "Reset your password (link expires in "
                        + RESET_TOKEN_TTL_MINUTES + " minutes):\n"
                        + link + "\n\n"
                        + "If you didn't request this, ignore this email — your password will not change.");
    }

    /**
     * Consume the reset token. Single-use — deletes the row on success.
     * Bumps {@code token_version} so any outstanding JWT for the user is
     * invalidated.
     */
    @Transactional
    public ChangeOutcome reset(PasswordResetRequest req) {
        String plaintext = req.getToken() == null ? "" : req.getToken().trim();
        if (plaintext.isBlank()) {
            return ChangeOutcome.TOKEN_INVALID;
        }
        String hash = sha256Hex(plaintext);
        Optional<PasswordResetToken> maybe = resetRepo.findByTokenHash(hash);
        if (maybe.isEmpty()) {
            return ChangeOutcome.TOKEN_INVALID;
        }
        PasswordResetToken row = maybe.get();
        if (row.getExpiresAt().isBefore(LocalDateTime.now())) {
            // Delete the expired row so a leak of the plaintext later can't
            // be used against a fresh row (unique constraint would catch
            // the reuse anyway; this keeps the table tidy).
            resetRepo.delete(row);
            return ChangeOutcome.TOKEN_EXPIRED;
        }
        Optional<User> ownerOpt = userRepository.findById(row.getUserId());
        if (ownerOpt.isEmpty()) {
            // Owner was deleted between mint + reset — nothing to update.
            resetRepo.delete(row);
            return ChangeOutcome.USER_NOT_FOUND;
        }
        User owner = ownerOpt.get();
        owner.setPassword(passwordEncoder.encode(req.getNewPassword()));
        tokenRevocation.bumpTokenVersion(owner);
        userRepository.save(owner);
        // Single-use: consume by deleting.
        resetRepo.delete(row);
        return ChangeOutcome.OK;
    }

    private static String randomToken() {
        byte[] buf = new byte[32];
        RANDOM.nextBytes(buf);
        return HexFormat.of().formatHex(buf);
    }

    private static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] out = md.digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(out);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required by every JVM — unreachable in practice.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
