package com.multiship.backend.service.mail;

import com.postmarkapp.postmark.Postmark;
import com.postmarkapp.postmark.client.ApiClient;
import com.postmarkapp.postmark.client.data.model.message.Message;
import com.postmarkapp.postmark.client.exception.PostmarkException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

/**
 * A4.3 — Postmark REST provider. Uses postmark 1.11.x. Any exception
 * from the SDK is wrapped in MailSendException so callers don't need to
 * import Postmark-specific classes.
 *
 * <p>Required config keys: {@code from_address}. Secret keys: {@code serverToken}.
 */
@Slf4j
@Component
public class PostmarkMailProvider implements MailProvider {

    public static final String KIND = "POSTMARK";

    public static final String KEY_SERVER_TOKEN = "serverToken";
    public static final String KEY_FROM = "from_address";

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
        return Set.of(KEY_SERVER_TOKEN);
    }

    @Override
    public void send(String to, String subject, String body, Map<String, String> config) {
        String token = require(config, KEY_SERVER_TOKEN);
        String from = require(config, KEY_FROM);

        ApiClient client = Postmark.getApiClient(token);
        Message message = new Message(from, to, subject, body);
        try {
            client.deliverMessage(message);
            log.info("[mail:POSTMARK] delivered to={} subject={}", to, subject);
        } catch (PostmarkException | IOException ex) {
            throw new MailSendException("Postmark send failed: " + ex.getMessage(), ex);
        }
    }

    private static String require(Map<String, String> config, String key) {
        String v = config.get(key);
        if (v == null || v.isBlank()) {
            throw new MailSendException("Postmark config missing required key: " + key);
        }
        return v;
    }
}
