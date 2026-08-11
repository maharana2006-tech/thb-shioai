package com.multiship.backend.service.mail;

/**
 * Sprint 50 Tier 0.5 PR D — outbound email surface.
 *
 * <p>Deliberately minimal so the real SMTP wiring (JavaMail /
 * spring-boot-starter-mail) can slot in later without touching callers.
 * The default bean {@link LoggingMailSender} INFO-logs the recipient +
 * subject + full body — this is enough for local dev and the invite
 * admin UI copy-link fallback the plan calls out.
 *
 * <p>Prod deploys swap the {@code @Primary} bean by shipping a
 * {@code SmtpMailSender} configured with {@code spring.mail.host} /
 * {@code port} / {@code username} / {@code password} env vars. That
 * follow-up doesn't touch a single caller of this interface.
 */
public interface MailSender {

    void send(String to, String subject, String body);
}
