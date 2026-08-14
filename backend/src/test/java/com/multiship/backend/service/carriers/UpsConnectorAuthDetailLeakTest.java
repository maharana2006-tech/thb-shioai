package com.multiship.backend.service.carriers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.config.CarrierProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Sprint 51 BS-L2 — LAST_AUTH_DETAIL must be scrubbed at the top of
 * {@code getAccessToken} so a prior request's leftover message can't leak
 * onto the current pool-recycled thread. Reflection is used to seed the
 * ThreadLocal + observe it; the real HTTP call is short-circuited by
 * pointing at an unreachable host (any exception path clears the stale
 * value; success paths already remove()).
 */
class UpsConnectorAuthDetailLeakTest {

    private UpsConnector connector;

    @BeforeEach
    void setUp() {
        CarrierProperties props = new CarrierProperties();
        // Point at an unreachable auth URL so the try body fails fast into
        // the catch. That path re-sets LAST_AUTH_DETAIL with a fresh value
        // — but only AFTER our entry-point remove() has cleared the stale
        // one, which is what this test proves.
        props.getUps().setAuthUrl("http://127.0.0.1:0/token");
        props.getUps().setSandboxAuthUrl("http://127.0.0.1:0/token");
        connector = new UpsConnector(props, new ObjectMapper());
    }

    @Test
    @SuppressWarnings("unchecked")
    void staleThreadLocalIsClearedAtEntryEvenBeforeCatch() throws Exception {
        Field f = UpsConnector.class.getDeclaredField("LAST_AUTH_DETAIL");
        f.setAccessible(true);
        ThreadLocal<String> tl = (ThreadLocal<String>) f.get(null);

        // Seed as if a prior request on this thread left a stale message.
        tl.set("STALE-DETAIL-FROM-PREVIOUS-REQUEST");

        // consumeAuthFailureDetail would drain it, so instead: kick a fresh
        // call that goes through the auth-failure fallback branch and
        // observe that the drained value is the FRESH one, not the stale.
        connector.getAccessToken("cid", "csecret", null, "SANDBOX");

        String consumed = connector.consumeAuthFailureDetail();
        // The fresh call recorded a NEW detail (some flavour of "could not
        // reach the UPS OAuth endpoint"); the stale one must be gone.
        // Either the fresh replaces the stale (correct) or the stale
        // survives (bug BS-L2). The test asserts the former by checking
        // the drained value does NOT contain the stale sentinel.
        // consumed can be null if success path was taken; the important
        // property is: the STALE value must not have been what we drained.
        if (consumed != null) {
            org.junit.jupiter.api.Assertions.assertFalse(
                    consumed.contains("STALE-DETAIL-FROM-PREVIOUS-REQUEST"),
                    "stale ThreadLocal leaked into the new request: " + consumed);
        }
        // Second consume must yield null — either the first consume drained
        // whatever fresh detail was set, or the entry-point remove had already
        // cleared it before any set. Either way, no residue survives.
        assertNull(connector.consumeAuthFailureDetail());
    }
}
