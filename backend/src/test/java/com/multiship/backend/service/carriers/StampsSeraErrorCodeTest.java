package com.multiship.backend.service.carriers;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** PR-T1 — pins the SERA error-code parser contract (code families,
 *  fallback shapes, classification helpers). */
class StampsSeraErrorCodeTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesKnownErrorCodeAndMessage() {
        String body = "{\"error_code\":\"800010\",\"error_message\":\"Invalid label_id xyz\"}";
        StampsSeraErrorCode.SeraError e = StampsSeraErrorCode.parse(body, mapper);
        assertEquals(StampsSeraErrorCode.LABEL_INVALID, e.code());
        assertEquals("800010", e.rawCode());
        assertEquals("Invalid label_id xyz", e.message());
        assertTrue(e.code().isLabelInvalid());
        assertFalse(e.code().isPickup());
    }

    @Test
    void classifiesPickupFamily() {
        for (String code : new String[]{"800100","800101","800102","800103","800104","800105","800106"}) {
            StampsSeraErrorCode c = StampsSeraErrorCode.fromCode(code);
            assertTrue(c.isPickup(), "code " + code + " should classify as pickup");
            assertFalse(c.isIdempotency());
            assertFalse(c.isValidation());
            assertFalse(c.isLabelInvalid());
        }
    }

    @Test
    void classifiesIdempotencyFamily() {
        assertTrue(StampsSeraErrorCode.fromCode("800001").isIdempotency());
        assertTrue(StampsSeraErrorCode.fromCode("800002").isIdempotency());
        assertFalse(StampsSeraErrorCode.fromCode("800010").isIdempotency());
    }

    @Test
    void unknownCodeReturnsUnknownEnumButKeepsRawCodeAndMessage() {
        String body = "{\"error_code\":\"800999\",\"error_message\":\"Something new\"}";
        StampsSeraErrorCode.SeraError e = StampsSeraErrorCode.parse(body, mapper);
        assertEquals(StampsSeraErrorCode.UNKNOWN, e.code());
        assertEquals("800999", e.rawCode());
        assertEquals("Something new", e.message());
        assertTrue(e.code().isUnknown());
    }

    @Test
    void fallbackMessageFieldsWhenErrorMessageAbsent() {
        // detail
        assertEquals("detail wins",
                StampsSeraErrorCode.parse("{\"detail\":\"detail wins\"}", mapper).message());
        // message
        assertEquals("message wins",
                StampsSeraErrorCode.parse("{\"message\":\"message wins\"}", mapper).message());
        // error
        assertEquals("error wins",
                StampsSeraErrorCode.parse("{\"error\":\"error wins\"}", mapper).message());
        // errors[0].message
        assertEquals("array first",
                StampsSeraErrorCode.parse("{\"errors\":[{\"message\":\"array first\"}]}", mapper).message());
    }

    @Test
    void errorMessageWinsOverFallbackFields() {
        String body = "{\"error_code\":\"800000\","
                + "\"error_message\":\"primary\",\"detail\":\"should lose\"}";
        assertEquals("primary", StampsSeraErrorCode.parse(body, mapper).message());
        assertEquals(StampsSeraErrorCode.VALIDATION,
                StampsSeraErrorCode.parse(body, mapper).code());
    }

    @Test
    void emptyAndMalformedBodiesReturnUnknown() {
        StampsSeraErrorCode.SeraError empty = StampsSeraErrorCode.parse("", mapper);
        assertEquals(StampsSeraErrorCode.UNKNOWN, empty.code());
        assertEquals("empty response", empty.message());

        StampsSeraErrorCode.SeraError nullBody = StampsSeraErrorCode.parse(null, mapper);
        assertEquals(StampsSeraErrorCode.UNKNOWN, nullBody.code());

        StampsSeraErrorCode.SeraError bogus = StampsSeraErrorCode.parse("not json", mapper);
        assertEquals(StampsSeraErrorCode.UNKNOWN, bogus.code());
        assertEquals("not json", bogus.message());
    }

    @Test
    void parseMessageOverloadReturnsFreeFormOnly() {
        String body = "{\"error_code\":\"800010\",\"error_message\":\"nope\"}";
        assertEquals("nope", StampsSeraErrorCode.parseMessage(body, mapper));
    }

    @Test
    void truncatesLongBodyToSafeHead() {
        String huge = "x".repeat(500);
        StampsSeraErrorCode.SeraError e = StampsSeraErrorCode.parse(huge, mapper);
        assertEquals(StampsSeraErrorCode.UNKNOWN, e.code());
        assertEquals(201, e.message().length()); // 200 chars + ellipsis
        assertTrue(e.message().endsWith("…"));
    }

    @Test
    void fromCodeIsTrimAndNullSafe() {
        assertEquals(StampsSeraErrorCode.LABEL_INVALID, StampsSeraErrorCode.fromCode("  800010  "));
        assertEquals(StampsSeraErrorCode.UNKNOWN, StampsSeraErrorCode.fromCode(null));
        assertEquals(StampsSeraErrorCode.UNKNOWN, StampsSeraErrorCode.fromCode(""));
        assertEquals(StampsSeraErrorCode.UNKNOWN, StampsSeraErrorCode.fromCode("   "));
    }

    @Test
    void describeIncludesClassifiedNameAndRawCode() {
        StampsSeraErrorCode.SeraError e = StampsSeraErrorCode.parse(
                "{\"error_code\":\"800002\",\"error_message\":\"missing header\"}", mapper);
        String d = e.describe();
        assertTrue(d.contains("IDEMPOTENCY_MISSING"));
        assertTrue(d.contains("800002"));
        assertTrue(d.contains("missing header"));
    }

    @Test
    void genericCodeMapsCorrectly() {
        assertEquals(StampsSeraErrorCode.GENERIC, StampsSeraErrorCode.fromCode("899999"));
        assertEquals(StampsSeraErrorCode.MANIFEST_NOT_SUPPORTED, StampsSeraErrorCode.fromCode("800200"));
    }

    @Test
    void tryCodeReturnsEmptyOnUnknownBody() {
        assertTrue(StampsSeraErrorCode.tryCode("{\"foo\":\"bar\"}", mapper).isEmpty());
        assertTrue(StampsSeraErrorCode.tryCode(
                "{\"error_code\":\"800010\"}", mapper).isPresent());
    }
}
