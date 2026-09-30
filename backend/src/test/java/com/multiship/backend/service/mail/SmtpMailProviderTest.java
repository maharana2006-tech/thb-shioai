package com.multiship.backend.service.mail;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A4.1 — SmtpMailProvider input validation. We don't stand up a real SMTP
 * server here (that's ITs / manual test-send); the unit test verifies the
 * SPI contract + the "missing required key" fail-fast.
 */
class SmtpMailProviderTest {

    private final SmtpMailProvider provider = new SmtpMailProvider();

    @Test
    void declaresKindAndKeys() {
        assertEquals("SMTP", provider.kind());
        assertEquals(Set.of("host", "port", "username", "from_address"), provider.requiredConfigKeys());
        assertEquals(Set.of("password"), provider.secretConfigKeys());
    }

    @Test
    void missingHostThrowsClearError() {
        Map<String, String> cfg = new HashMap<>();
        cfg.put("port", "587");
        cfg.put("username", "u");
        cfg.put("from_address", "f@example.com");
        MailSendException ex = assertThrows(MailSendException.class,
                () -> provider.send("to@example.com", "s", "b", cfg));
        assertTrue(ex.getMessage().contains("host"),
                "error must name the missing key so the admin knows what to fill in");
    }

    @Test
    void unreachableHostSurfacesAsMailSendException() {
        // 127.0.0.2:1 will fail to connect fast — proves the provider wraps
        // JavaMail exceptions in MailSendException rather than leaking them.
        Map<String, String> cfg = new HashMap<>();
        cfg.put("host", "127.0.0.2");
        cfg.put("port", "1");
        cfg.put("username", "u");
        cfg.put("password", "p");
        cfg.put("from_address", "f@example.com");
        cfg.put("use_tls", "false");
        assertThrows(MailSendException.class,
                () -> provider.send("to@example.com", "s", "b", cfg));
    }
}
