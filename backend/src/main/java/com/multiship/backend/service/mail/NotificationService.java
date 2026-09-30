package com.multiship.backend.service.mail;

import com.multiship.backend.model.NotificationTemplateEntity;
import com.multiship.backend.repository.NotificationTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * A4.2 — the single call every producer of an email uses. Resolves the
 * {@code notification_template} row by key, renders both subject + body
 * via {@link TemplateRenderer}, and delegates to {@link MailSender} for
 * actual delivery.
 *
 * <p>Callers pass a variables map keyed by the identifiers the template
 * mentions; unknown vars are silently dropped by Handlebars (renders as
 * empty), so a template can safely add optional slots without breaking
 * old call sites.
 *
 * <p>Keys follow {@code DOMAIN.EVENT} convention (e.g. {@code AUTH.VERIFY_EMAIL}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationTemplateRepository repo;
    private final TemplateRenderer renderer;
    private final MailSender mailSender;

    /**
     * @throws TemplateNotFoundException if the key has no row.
     * @throws MailSendException on render or provider failure.
     */
    public void send(String templateKey, String to, Map<String, ?> vars) {
        NotificationTemplateEntity tpl = repo.findById(templateKey)
                .orElseThrow(() -> new TemplateNotFoundException(templateKey));
        String subject = renderer.render(tpl.getSubjectTemplate(), vars);
        String body = renderer.render(tpl.getBodyTemplate(), vars);
        mailSender.send(to, subject, body);
    }
}
