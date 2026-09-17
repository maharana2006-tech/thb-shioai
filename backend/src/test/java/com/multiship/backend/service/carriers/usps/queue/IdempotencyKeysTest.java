package com.multiship.backend.service.carriers.usps.queue;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * PR-G5 D1 — pins the canonical order-anchored idempotency-key format
 * across USPS_DIRECT internal surfaces (queue retry + import row
 * generation). Regressing the prefix silently defeats
 * {@code CarrierServiceImpl.generateLabel}'s tracking-row dedup on
 * cross-surface retries, so the exact string matters.
 */
class IdempotencyKeysTest {

    @Test
    void forUspsOrder_producesOrderAnchoredKey() {
        assertEquals("usps-order-42", IdempotencyKeys.forUspsOrder(42L));
        assertEquals("usps-order-1000000", IdempotencyKeys.forUspsOrder(1_000_000L));
    }

    @Test
    void forUspsOrder_rejectsNonPositiveOrderNo() {
        assertThrows(IllegalArgumentException.class, () -> IdempotencyKeys.forUspsOrder(0L));
        assertThrows(IllegalArgumentException.class, () -> IdempotencyKeys.forUspsOrder(-1L));
    }

    @Test
    void legacyForQueueItem_matchesPreG5Format() {
        // Kept only for audit tooling / diagnostics — new writes must
        // never use this; regression here would mean someone reintroduced
        // the per-attempt itemId key that defeated cross-surface dedup.
        assertEquals("usps-queue-99", IdempotencyKeys.legacyForQueueItem(99L));
    }

    @Test
    void orderAndLegacyKeysNeverCollide() {
        // Prefixes must stay disjoint; a collision would let a queue-side
        // legacy row masquerade as a G5 order-anchored row (or vice versa)
        // and trip the tracking-row dedup check on the wrong entity.
        String order = IdempotencyKeys.forUspsOrder(42L);
        String legacy = IdempotencyKeys.legacyForQueueItem(42L);
        assertEquals(false, order.equals(legacy));
        assertEquals(false, order.startsWith(IdempotencyKeys.LEGACY_USPS_QUEUE_PREFIX));
        assertEquals(false, legacy.startsWith(IdempotencyKeys.USPS_ORDER_PREFIX));
    }
}
