package com.multiship.backend.service.carriers.usps.queue;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * PR-S3 — pins {@link IdempotencyKeys#forStampsOrder(long)} as a
 * carrier-agnostic semantic alias for {@link IdempotencyKeys#forUspsOrder(long)}.
 *
 * <p>The two methods produce the exact same string so a provider-flip
 * mid-batch (STAMPS_COM ↔ USPS_DIRECT) doesn't break the tracking-row
 * dedup — same key = same replay behaviour. Regression here silently
 * splits the namespace and reintroduces the D1 audit issue.
 */
class IdempotencyKeysStampsAliasTest {

    @Test
    void forStampsOrder_returnsIdenticalStringToForUspsOrder() {
        assertEquals(IdempotencyKeys.forUspsOrder(42L), IdempotencyKeys.forStampsOrder(42L));
        assertEquals(IdempotencyKeys.forUspsOrder(1_000_000L), IdempotencyKeys.forStampsOrder(1_000_000L));
    }

    @Test
    void forStampsOrder_producesUspsOrderPrefixedString() {
        // Belt-and-braces: pin the actual string so a future refactor that
        // diverges the two methods can't do so silently.
        assertEquals("usps-order-42", IdempotencyKeys.forStampsOrder(42L));
    }

    @Test
    void forStampsOrder_rejectsNonPositiveOrderNo() {
        assertThrows(IllegalArgumentException.class, () -> IdempotencyKeys.forStampsOrder(0L));
        assertThrows(IllegalArgumentException.class, () -> IdempotencyKeys.forStampsOrder(-1L));
    }
}
