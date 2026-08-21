package com.multiship.backend.service.carriers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Regression tests for {@link StampsConnector#normaliseIntegrationId(String)}.
 *
 * <p>Origin: operators reporting "USPS via Stamps.com rejected the credentials —
 * the Stamps.com Client ID (IntegrationID) must be a GUID..." after pasting an
 * IntegrationID that WAS a valid GUID — but wrapped in braces (Windows/registry
 * copy-button style), prefixed with {@code urn:uuid:} (IETF tooling), or given
 * as 32-hex-no-hyphens (some SDK samples). The strict pattern used to reject
 * these; the normaliser reshapes them into the canonical {@code 8-4-4-4-12}
 * form before the pattern check runs.
 *
 * <p>Anything that can't be reshaped into a canonical GUID returns null so
 * callers surface an actionable error instead of firing a doomed SWSIM call.
 */
class StampsGuidNormalisationTest {

    private static final String CANONICAL = "01234567-89ab-cdef-0123-456789abcdef";

    // ===== pass-through =====

    @Test
    void canonicalGuid_passesThroughUnchanged() {
        assertEquals(CANONICAL, StampsConnector.normaliseIntegrationId(CANONICAL));
    }

    @Test
    void canonicalGuidUppercase_passesThrough_becausePatternIsCaseInsensitive() {
        String upper = CANONICAL.toUpperCase();
        assertEquals(upper, StampsConnector.normaliseIntegrationId(upper));
    }

    @Test
    void canonicalGuidWithSurroundingWhitespace_isTrimmed() {
        assertEquals(CANONICAL, StampsConnector.normaliseIntegrationId("  " + CANONICAL + "\t\n"));
    }

    // ===== brace-wrapped (Windows / .NET dev portal copy button) =====

    @Test
    void bracedGuid_hasBracesStripped() {
        assertEquals(CANONICAL, StampsConnector.normaliseIntegrationId("{" + CANONICAL + "}"));
    }

    @Test
    void bracedGuidWithWhitespaceInsideBraces_isTrimmed() {
        // .NET's ToString("B") never emits inner whitespace but manual paste can.
        assertEquals(CANONICAL, StampsConnector.normaliseIntegrationId("{ " + CANONICAL + " }"));
    }

    // ===== urn:uuid: prefix (IETF-style) =====

    @Test
    void urnUuidPrefix_isStripped() {
        assertEquals(CANONICAL, StampsConnector.normaliseIntegrationId("urn:uuid:" + CANONICAL));
    }

    @Test
    void urnUuidPrefix_isCaseInsensitive() {
        assertEquals(CANONICAL, StampsConnector.normaliseIntegrationId("URN:UUID:" + CANONICAL));
    }

    // ===== 32-hex-no-hyphens (some SDK samples) =====

    @Test
    void thirtyTwoHexNoHyphens_getsHyphensInserted() {
        String noHyphens = CANONICAL.replace("-", "");
        assertEquals(CANONICAL, StampsConnector.normaliseIntegrationId(noHyphens));
    }

    @Test
    void thirtyTwoHexUppercase_getsHyphensInserted_preservingCase() {
        String noHyphens = CANONICAL.replace("-", "").toUpperCase();
        assertEquals(CANONICAL.toUpperCase(), StampsConnector.normaliseIntegrationId(noHyphens));
    }

    // ===== combined variants =====

    @Test
    void bracedNoHyphens_bothReshaped() {
        String noHyphens = CANONICAL.replace("-", "");
        assertEquals(CANONICAL, StampsConnector.normaliseIntegrationId("{" + noHyphens + "}"));
    }

    @Test
    void urnUuidPlusBraced_bothStripped() {
        assertEquals(CANONICAL, StampsConnector.normaliseIntegrationId("urn:uuid:{" + CANONICAL + "}"));
    }

    // ===== truly-invalid inputs → null =====

    @Test
    void nullInput_returnsNull() {
        assertNull(StampsConnector.normaliseIntegrationId(null));
    }

    @Test
    void blankInput_returnsNull() {
        assertNull(StampsConnector.normaliseIntegrationId("   "));
    }

    @Test
    void nonHexCharacters_returnNull() {
        // 'g' isn't hex — no reshape possible.
        assertNull(StampsConnector.normaliseIntegrationId("g1234567-89ab-cdef-0123-456789abcdef"));
    }

    @Test
    void tooFewHexChars_returnsNull() {
        // 31 chars — can't be reshaped.
        assertNull(StampsConnector.normaliseIntegrationId("0123456789abcdef0123456789abcde"));
    }

    @Test
    void tooManyHexChars_returnsNull() {
        // 33 chars — can't be reshaped.
        assertNull(StampsConnector.normaliseIntegrationId("0123456789abcdef0123456789abcdef0"));
    }

    @Test
    void bracedButWrongLengthInside_returnsNull() {
        assertNull(StampsConnector.normaliseIntegrationId("{not-a-guid}"));
    }

    @Test
    void plainWord_returnsNull() {
        // Sanity: totally-not-a-guid returns null so the caller surfaces
        // the "IntegrationID must be a GUID" error instead of firing a
        // SWSIM call that the schema will bounce with HTTP 500.
        assertNull(StampsConnector.normaliseIntegrationId("integration-id-goes-here"));
    }
}
