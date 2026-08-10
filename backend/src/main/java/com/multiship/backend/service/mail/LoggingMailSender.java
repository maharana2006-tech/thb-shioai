package com.multiship.backend.service.mail;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * Sprint 50 Tier 0.5 PR D — default {@link MailSender} that INFO-logs
 * every email instead of sending. Sufficient for local dev + the admin
 * UI's copy-link fallback (the plan explicitly calls this out as
 * shippable while SMTP config lands).
 *
 * <p>{@code @ConditionalOnMissingBean} so a prod-shipped SmtpMailSender
 * silently wins without touching this file.
 */
@Slf4j
@Component
@ConditionalOnMissingBean(value = MailSender.class, ignored = LoggingMailSender.class)
public class LoggingMailSender implements MailSender {

    @Override
    public void send(String to, String subject, String body) {
        log.info("[mail:LOG-ONLY] to={} subject={}\n{}", to, subject, body);
    }
}
