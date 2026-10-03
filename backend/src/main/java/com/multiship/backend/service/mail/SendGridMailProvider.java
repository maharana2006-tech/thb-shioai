package com.multiship.backend.service.mail;

import com.sendgrid.Method;
import com.sendgrid.Request;
import com.sendgrid.Response;
import com.sendgrid.SendGrid;
import com.sendgrid.helpers.mail.Mail;
import com.sendgrid.helpers.mail.objects.Content;
import com.sendgrid.helpers.mail.objects.Email;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

/**
 * A4.3 — SendGrid REST provider. Uses the classic v3 mail/send endpoint
 * via sendgrid-java 4.x. Response codes 2xx are success; anything else
 * surfaces as MailSendException with the body attached.
 *
 * <p>Required config keys: {@code apiKey, from_address}. Optional:
 * {@code from_name} (defaults to empty).
 */
@Slf4j
@Component
public class SendGridMailProvider implements MailProvider {

    public static final String KIND = "SENDGRID";

    public static final String KEY_API_KEY = "apiKey";
    public static final String KEY_FROM = "from_address";
    public static final String KEY_FROM_NAME = "from_name";

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public Set<String> requiredConfigKeys() {
        return Set.of(KEY_FROM);
    }

    @Override
    public Set<String> secretConfigKeys() {
        return Set.of(KEY_API_KEY);
    }

    @Override
    public void send(String to, String subject, String body, Map<String, String> config) {
        String apiKey = requireSecret(config, KEY_API_KEY);
        String from = requireNonBlank(config, KEY_FROM);
        String fromName = config.getOrDefault(KEY_FROM_NAME, "");

        Email fromEmail = new Email(from, fromName);
        Email toEmail = new Email(to);
        Content content = new Content("text/plain", body);
        Mail mail = new Mail(fromEmail, subject, toEmail, content);

        SendGrid sg = new SendGrid(apiKey);
        try {
            Request request = new Request();
            request.setMethod(Method.POST);
            request.setEndpoint("mail/send");
            request.setBody(mail.build());
            Response response = sg.api(request);
            int status = response.getStatusCode();
            if (status < 200 || status >= 300) {
                throw new MailSendException("SendGrid send failed status=" + status
                        + " body=" + response.getBody());
            }
            log.info("[mail:SENDGRID] delivered to={} subject={} status={}", to, subject, status);
        } catch (IOException ex) {
            throw new MailSendException("SendGrid send failed: " + ex.getMessage(), ex);
        }
    }

    private static String requireNonBlank(Map<String, String> config, String key) {
        String v = config.get(key);
        if (v == null || v.isBlank()) {
            throw new MailSendException("SendGrid config missing required key: " + key);
        }
        return v;
    }

    private static String requireSecret(Map<String, String> config, String key) {
        String v = config.get(key);
        if (v == null || v.isBlank()) {
            throw new MailSendException("SendGrid secret missing: " + key);
        }
        return v;
    }
}
