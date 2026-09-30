package com.multiship.backend.service.mail;

import com.multiship.backend.model.MailProviderEntity;
import com.multiship.backend.model.NotificationTemplateEntity;
import com.multiship.backend.model.User;
import com.multiship.backend.repository.NotificationTemplateRepository;
import com.multiship.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;

/**
 * A4.2 — the single call every producer of an email uses. Resolves the
 * {@code notification_template} row by key, renders both subject + body
 * via {@link TemplateRenderer}, and delegates to {@link MailSender} for
 * actual delivery.
 *
 * <p>A4.4 — every send + fail is journaled to
 * {@code notification_delivery_log} so ops can inspect the outcome and
 * retry from /settings/notification-delivery-log without re-running the
 * originating flow.
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
    private final MailConfigService mailConfig;
    private final NotificationDeliveryLogService deliveryLog;
    private final NotificationSubscriptionService subscriptions;
    private final UserRepository userRepository;

    /**
     * @throws TemplateNotFoundException if the key has no row.
     * @throws MailSendException on render or provider failure.
     *
     * <p>A4.5 — when the template has {@code opt_out_allowed=true} AND the
     * recipient email resolves to a user AND that user has explicitly
     * opted out, the send is skipped (still logged? No — no log row on
     * skip, matches "user didn't want this in the first place"). All
     * other cases send unconditionally, so transactional emails (invite,
     * verify, password reset) always fire.
     */
    public void send(String templateKey, String to, Map<String, ?> vars) {
        NotificationTemplateEntity tpl = repo.findById(templateKey)
                .orElseThrow(() -> new TemplateNotFoundException(templateKey));

        if (tpl.isOptOutAllowed() && !isSubscribed(to, templateKey)) {
            log.info("[mail:OPT-OUT] skipping template={} to={} — user opted out", templateKey, to);
            return;
        }

        String subject = renderer.render(tpl.getSubjectTemplate(), vars);
        String body = renderer.render(tpl.getBodyTemplate(), vars);
        dispatch(templateKey, to, subject, body, null);
    }

    private boolean isSubscribed(String toEmail, String templateKey) {
        if (toEmail == null || toEmail.isBlank()) return true;
        Optional<User> user = userRepository.findByEmailIgnoreCase(toEmail);
        return user.map(u -> subscriptions.isSubscribed(u.getId(), templateKey)).orElse(true);
    }

    /**
     * A4.4 — re-send an already-rendered message. Used by the retry endpoint;
     * the log row supplied via {@code retryOfId} is linked from the fresh
     * entry so ops can trace chains.
     */
    public void resend(String templateKey, String to, String subject, String body, Long retryOfId) {
        dispatch(templateKey, to, subject, body, retryOfId);
    }

    private void dispatch(String templateKey, String to, String subject, String body, Long retryOfId) {
        Optional<MailProviderEntity> active = mailConfig.activeProvider();
        String providerKind = active.map(MailProviderEntity::getKind).orElse(null);
        Long providerId = active.map(MailProviderEntity::getId).orElse(null);

        long start = System.currentTimeMillis();
        try {
            mailSender.send(to, subject, body);
            int latency = (int) Math.min(Integer.MAX_VALUE, System.currentTimeMillis() - start);
            deliveryLog.recordSent(templateKey, to, subject, body,
                    providerKind, providerId, latency, retryOfId);
        } catch (RuntimeException ex) {
            int latency = (int) Math.min(Integer.MAX_VALUE, System.currentTimeMillis() - start);
            deliveryLog.recordFailed(templateKey, to, subject, body,
                    providerKind, providerId, latency, ex.getMessage(), retryOfId);
            throw ex;
        }
    }
}
