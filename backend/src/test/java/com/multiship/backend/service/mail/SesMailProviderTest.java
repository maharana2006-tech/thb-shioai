package com.multiship.backend.service.mail;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A4.3 — SES provider contract. Real AWS call goes through /test-send. */
class SesMailProviderTest {

    private final SesMailProvider provider = new SesMailProvider();

    @Test
    void declaresKindAndKeys() {
        assertEquals("SES", provider.kind());
        assertEquals(Set.of("region", "from_address"), provider.requiredConfigKeys());
        assertEquals(Set.of("accessKeyId", "secretAccessKey"), provider.secretConfigKeys());
    }

    @Test
    void missingRegionThrowsClearError() {
        Map<String, String> cfg = new HashMap<>();
        cfg.put("from_address", "noreply@example.com");
        cfg.put("accessKeyId", "AK");
        cfg.put("secretAccessKey", "SK");
        MailSendException ex = assertThrows(MailSendException.class,
                () -> provider.send("to@example.com", "s", "b", cfg));
        assertTrue(ex.getMessage().contains("region"));
    }

    @Test
    void missingCredentialsThrowsClearError() {
        Map<String, String> cfg = new HashMap<>();
        cfg.put("region", "us-east-1");
        cfg.put("from_address", "noreply@example.com");
        MailSendException ex = assertThrows(MailSendException.class,
                () -> provider.send("to@example.com", "s", "b", cfg));
        assertTrue(ex.getMessage().contains("accessKeyId")
                || ex.getMessage().contains("secretAccessKey"));
    }
}
