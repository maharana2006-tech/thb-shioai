package com.multiship.backend.service.carriers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Sprint 51 BS-L1 — LogRedaction util unit tests.
 */
class LogRedactionTest {

    @Test
    void masksClientIdAndSecret() {
        String body = "{\"error\":\"invalid_client\",\"client_id\":\"MYSUPERKEY123\",\"secret\":\"SHHHH-SECRET\"}";
        String out = LogRedaction.redactSecrets(body, "MYSUPERKEY123", "SHHHH-SECRET");
        assertFalse(out.contains("MYSUPERKEY123"), "clientId must be scrubbed: " + out);
        assertFalse(out.contains("SHHHH-SECRET"), "clientSecret must be scrubbed: " + out);
        assertEquals("{\"error\":\"invalid_client\",\"client_id\":\"***\",\"secret\":\"***\"}", out);
    }

    @Test
    void nullBody_returnsNull() {
        assertNull(LogRedaction.redactSecrets(null, "x", "y"));
    }

    @Test
    void blankSecrets_areSkipped_notAppliedAsEmptyStringReplace() {
        // Empty-string replace would insert MASK between every char; assert
        // that path is guarded so a missing secret can't corrupt the log.
        String body = "hello";
        assertEquals("hello", LogRedaction.redactSecrets(body, "", null));
        assertEquals("hello", LogRedaction.redactSecrets(body, null, "  "));
    }
}
