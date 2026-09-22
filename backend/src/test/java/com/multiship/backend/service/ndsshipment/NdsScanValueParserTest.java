package com.multiship.backend.service.ndsshipment;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link NdsScanValueParser}. */
class NdsScanValueParserTest {

    @Test
    void parsesDirectContainerScan() {
        NdsScanValue v = NdsScanValueParser.parse(".X0012345");
        assertEquals(NdsScanValue.Scope.DIRECT, v.scope());
        assertEquals("0012345", v.stripped());
        assertEquals(".X0012345", v.scannedRaw());
    }

    @Test
    void parsesBatchScan() {
        NdsScanValue v = NdsScanValueParser.parse(".YB99");
        assertEquals(NdsScanValue.Scope.BATCH, v.scope());
        assertEquals("B99", v.stripped());
    }

    @Test
    void isCaseInsensitiveOnPrefix() {
        assertEquals(NdsScanValue.Scope.DIRECT, NdsScanValueParser.parse(".x777").scope());
        assertEquals(NdsScanValue.Scope.BATCH,  NdsScanValueParser.parse(".y777").scope());
    }

    @Test
    void tolerantOfLeadingAndTrailingWhitespace() {
        NdsScanValue v = NdsScanValueParser.parse("  .X42  \r\n");
        assertEquals("42", v.stripped());
    }

    @Test
    void rejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> NdsScanValueParser.parse(null));
    }

    @Test
    void rejectsBlank() {
        assertThrows(IllegalArgumentException.class, () -> NdsScanValueParser.parse("   "));
    }

    @Test
    void rejectsWrongPrefix() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> NdsScanValueParser.parse(".Z999"));
        assertTrue(ex.getMessage().contains(".X or .Y"),
                "reject-message must name both accepted prefixes; was: " + ex.getMessage());
    }

    @Test
    void rejectsEmptyPayload() {
        assertThrows(IllegalArgumentException.class, () -> NdsScanValueParser.parse(".X"));
        assertThrows(IllegalArgumentException.class, () -> NdsScanValueParser.parse(".Y"));
    }
}
