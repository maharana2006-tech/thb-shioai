package com.multiship.backend.service.mail;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A4.1 — {@link MailProviderRegistry} indexes providers by kind + rejects
 * duplicates. Ensures a future SendGrid impl can't accidentally claim the
 * same kind as SmtpMailProvider.
 */
class MailProviderRegistryTest {

    @Test
    void indexesByKindAndFinds() {
        MailProviderRegistry reg = new MailProviderRegistry(List.of(new StubProvider("SMTP")));
        assertEquals(List.of("SMTP"), reg.kinds());
        assertTrue(reg.find("smtp").isPresent(), "case-insensitive lookup");
        assertTrue(reg.find("").isEmpty());
        assertTrue(reg.find(null).isEmpty());
    }

    @Test
    void requireThrowsOnUnknownKind() {
        MailProviderRegistry reg = new MailProviderRegistry(List.of(new StubProvider("SMTP")));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> reg.require("SENDGRID"));
        assertTrue(ex.getMessage().contains("SENDGRID"));
    }

    @Test
    void duplicateKindsRejected() {
        assertThrows(IllegalStateException.class,
                () -> new MailProviderRegistry(List.of(new StubProvider("SMTP"), new StubProvider("smtp"))));
    }

    private static class StubProvider implements MailProvider {
        private final String kind;
        StubProvider(String kind) { this.kind = kind; }
        @Override public String kind() { return kind; }
        @Override public Set<String> requiredConfigKeys() { return Set.of(); }
        @Override public Set<String> secretConfigKeys() { return Set.of(); }
        @Override public void send(String to, String subject, String body, Map<String, String> config) { }
    }
}
