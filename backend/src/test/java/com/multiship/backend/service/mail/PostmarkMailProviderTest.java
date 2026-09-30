package com.multiship.backend.service.mail;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A4.3 — Postmark provider contract. Real API call goes through /test-send. */
class PostmarkMailProviderTest {

    private final PostmarkMailProvider provider = new PostmarkMailProvider();

    @Test
    void declaresKindAndKeys() {
        assertEquals("POSTMARK", provider.kind());
        assertEquals(Set.of("from_address"), provider.requiredConfigKeys());
        assertEquals(Set.of("serverToken"), provider.secretConfigKeys());
    }

    @Test
    void missingServerTokenThrowsClearError() {
        Map<String, String> cfg = new HashMap<>();
        cfg.put("from_address", "noreply@example.com");
        MailSendException ex = assertThrows(MailSendException.class,
                () -> provider.send("to@example.com", "s", "b", cfg));
        assertTrue(ex.getMessage().contains("serverToken"));
    }

    @Test
    void missingFromThrowsClearError() {
        Map<String, String> cfg = new HashMap<>();
        cfg.put("serverToken", "dummy");
        MailSendException ex = assertThrows(MailSendException.class,
                () -> provider.send("to@example.com", "s", "b", cfg));
        assertTrue(ex.getMessage().contains("from_address"));
    }
}
