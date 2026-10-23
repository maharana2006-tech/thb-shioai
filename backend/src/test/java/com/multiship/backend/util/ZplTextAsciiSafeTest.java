package com.multiship.backend.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Stamps' binary ^GFB graphics must leave the API as ASCII ^GFA, bit-for-bit. */
class ZplTextAsciiSafeTest {

    @Test
    void gfb_binary_block_becomes_gfa_hex_and_the_rest_is_untouched() {
        byte[] raw = {
                '^', 'X', 'A', '^', 'F', 'O', '1', ',', '2', '^', 'G', 'F', 'B', ',', '3', ',', '3', ',', '1', ',',
                (byte) 0xFF, 0x00, (byte) 0xA5,
                '^', 'F', 'S', '^', 'X', 'Z'};
        String zpl = new String(raw, StandardCharsets.ISO_8859_1);

        String out = ZplText.asciiSafe(zpl);

        assertEquals("^XA^FO1,2^GFA,3,3,1,FF00A5^FS^XZ", out);
        assertTrue(out.chars().allMatch(c -> c < 0x80), "pure ASCII");
    }

    @Test
    void two_blocks_including_a_comma_inside_binary_data_are_both_recoded() {
        byte[] raw = {'^', 'G', 'F', 'B', ',', '2', ',', '2', ',', '1', ',', ',', '^',
                '^', 'G', 'F', 'B', ',', '1', ',', '1', ',', '1', ',', 0x7F, '^', 'X', 'Z'};
        String out = ZplText.asciiSafe(new String(raw, StandardCharsets.ISO_8859_1));
        assertEquals("^GFA,2,2,1,2C5E^GFA,1,1,1,7F^XZ", out);
    }

    @Test
    void ascii_only_zpl_passes_through_unchanged() {
        String zpl = "^XA^GFA,1,1,1,F0^FS^XZ";
        assertEquals(zpl, ZplText.asciiSafe(zpl));
        assertEquals(zpl, ZplText.stripUnsupportedCommands(zpl));
    }
}
