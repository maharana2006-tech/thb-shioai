package com.multiship.backend.service.mail;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A4.3 — SendGrid provider contract. Real API call goes through /test-send. */
class SendGridMailProviderTest {

    private final SendGridMailProvider provider = new SendGridMailProvider();

    @Test
    void declaresKindAndKeys() {
        assertEquals("SENDGRID", provider.kind());
        assertEquals(Set.of("from_address"), provider.requiredConfigKeys());
        assertEquals(Set.of("apiKey"), provider.secretConfigKeys());
    }

    @Test
    void missingApiKeyThrowsClearError() {
        Map<String, String> cfg = new HashMap<>();
        cfg.put("from_address", "noreply@example.com");
        MailSendException ex = assertThrows(MailSendException.class,
                () -> provider.send("to@example.com", "s", "b", cfg));
        assertTrue(ex.getMessage().contains("apiKey"));
    }

    @Test
    void missingFromThrowsClearError() {
        Map<String, String> cfg = new HashMap<>();
        cfg.put("apiKey", "SG.dummy");
        MailSendException ex = assertThrows(MailSendException.class,
                () -> provider.send("to@example.com", "s", "b", cfg));
        assertTrue(ex.getMessage().contains("from_address"));
    }
}
