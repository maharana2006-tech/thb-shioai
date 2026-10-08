package com.multiship.backend.service.carriers.usps.queue;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/** PR-T2 — pins the deterministic Idempotency-Key contract for Stamps
 *  SERA label + manifest POSTs: same inputs always produce the same key,
 *  different inputs produce different keys, and the result is a
 *  canonical UUID string. */
class IdempotencyKeysStampsSeraTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 8);

    // ========== forStampsLabel ==========

    @Test
    void labelKeyIsDeterministic() {
        String a = IdempotencyKeys.forStampsLabel("ORD-123", 1, DAY);
        String b = IdempotencyKeys.forStampsLabel("ORD-123", 1, DAY);
        assertEquals(a, b, "same inputs must produce the same key — this is the whole point");
    }

    @Test
    void labelKeyIsCanonicalUuidShape() {
        String key = IdempotencyKeys.forStampsLabel("ORD-123", 1, DAY);
        assertTrue(key.matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"),
                "SERA documents Idempotency-Key as UUID shape; got: " + key);
    }

    @Test
    void labelKeyVariesByPieceIndex() {
        assertNotEquals(
                IdempotencyKeys.forStampsLabel("ORD-123", 1, DAY),
                IdempotencyKeys.forStampsLabel("ORD-123", 2, DAY));
    }

    @Test
    void labelKeyVariesByReference() {
        assertNotEquals(
                IdempotencyKeys.forStampsLabel("ORD-123", 1, DAY),
                IdempotencyKeys.forStampsLabel("ORD-124", 1, DAY));
    }

    @Test
    void labelKeyVariesByShipDate() {
        assertNotEquals(
                IdempotencyKeys.forStampsLabel("ORD-123", 1, DAY),
                IdempotencyKeys.forStampsLabel("ORD-123", 1, DAY.plusDays(1)));
    }

    @Test
    void labelKeyTrimsReferenceWhitespace() {
        assertEquals(
                IdempotencyKeys.forStampsLabel("ORD-123", 1, DAY),
                IdempotencyKeys.forStampsLabel("  ORD-123  ", 1, DAY));
    }

    @Test
    void labelKeyRejectsBlankReference() {
        assertThrows(IllegalArgumentException.class,
                () -> IdempotencyKeys.forStampsLabel(null, 1, DAY));
        assertThrows(IllegalArgumentException.class,
                () -> IdempotencyKeys.forStampsLabel("", 1, DAY));
        assertThrows(IllegalArgumentException.class,
                () -> IdempotencyKeys.forStampsLabel("   ", 1, DAY));
    }

    @Test
    void labelKeyRejectsZeroOrNegativePiece() {
        assertThrows(IllegalArgumentException.class,
                () -> IdempotencyKeys.forStampsLabel("ORD-123", 0, DAY));
        assertThrows(IllegalArgumentException.class,
                () -> IdempotencyKeys.forStampsLabel("ORD-123", -1, DAY));
    }

    @Test
    void labelKeyRejectsNullShipDate() {
        assertThrows(IllegalArgumentException.class,
                () -> IdempotencyKeys.forStampsLabel("ORD-123", 1, null));
    }

    // ========== forStampsManifest ==========

    @Test
    void manifestKeyIsDeterministic() {
        String a = IdempotencyKeys.forStampsManifest("ACC-99", DAY);
        String b = IdempotencyKeys.forStampsManifest("ACC-99", DAY);
        assertEquals(a, b);
    }

    @Test
    void manifestKeyIsCanonicalUuidShape() {
        String key = IdempotencyKeys.forStampsManifest("ACC-99", DAY);
        assertTrue(key.matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"));
    }

    @Test
    void manifestKeyVariesByAccount() {
        assertNotEquals(
                IdempotencyKeys.forStampsManifest("ACC-99", DAY),
                IdempotencyKeys.forStampsManifest("ACC-100", DAY));
    }

    @Test
    void manifestKeyVariesByDate() {
        assertNotEquals(
                IdempotencyKeys.forStampsManifest("ACC-99", DAY),
                IdempotencyKeys.forStampsManifest("ACC-99", DAY.plusDays(1)));
    }

    @Test
    void manifestKeyRejectsBlankAccount() {
        assertThrows(IllegalArgumentException.class,
                () -> IdempotencyKeys.forStampsManifest(null, DAY));
        assertThrows(IllegalArgumentException.class,
                () -> IdempotencyKeys.forStampsManifest("  ", DAY));
    }

    @Test
    void manifestKeyRejectsNullDate() {
        assertThrows(IllegalArgumentException.class,
                () -> IdempotencyKeys.forStampsManifest("ACC-99", null));
    }

    // ========== cross-namespace safety ==========

    @Test
    void labelAndManifestNamespacesDoNotCollide() {
        // Same reference + date across both key families — the string prefixes
        // under the hash ("stamps-label|..." vs "stamps-manifest|...") must
        // produce different UUIDs so a label-POST retry can't accidentally
        // dedup against a manifest-POST and vice versa.
        String labelKey = IdempotencyKeys.forStampsLabel("ACC-99", 1, DAY);
        String manifestKey = IdempotencyKeys.forStampsManifest("ACC-99", DAY);
        assertNotEquals(labelKey, manifestKey);
    }
}
