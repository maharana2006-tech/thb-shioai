package com.multiship.backend.service.carriers;

import com.multiship.backend.service.carriers.exceptions.CarrierAuthException;
import com.multiship.backend.service.carriers.exceptions.CarrierServerException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 49 Tier 2 — AuthRetry contract.
 *
 * <p>Exactly once retry, exactly one refresh, second failure propagates.
 * No infinite loop under sustained auth failure. Non-auth exceptions
 * pass through without retry.
 */
class AuthRetryTest {

    @Test
    void succeedsOnFirstTryWithoutRefresh() {
        AtomicInteger callCount = new AtomicInteger();
        AtomicInteger refreshCount = new AtomicInteger();
        String result = AuthRetry.withAuthRetry(
                "token-1",
                () -> { refreshCount.incrementAndGet(); return "token-2"; },
                token -> { callCount.incrementAndGet(); return "ok-with-" + token; });
        assertEquals("ok-with-token-1", result);
        assertEquals(1, callCount.get());
        assertEquals(0, refreshCount.get(), "no refresh needed on happy path");
    }

    @Test
    void refreshesAndSucceedsOnRetry() {
        AtomicInteger callCount = new AtomicInteger();
        AtomicInteger refreshCount = new AtomicInteger();
        String result = AuthRetry.withAuthRetry(
                "stale-token",
                () -> { refreshCount.incrementAndGet(); return "fresh-token"; },
                token -> {
                    int n = callCount.incrementAndGet();
                    if (n == 1) throw new CarrierAuthException("UPS", "401");
                    return "ok-with-" + token;
                });
        assertEquals("ok-with-fresh-token", result);
        assertEquals(2, callCount.get());
        assertEquals(1, refreshCount.get());
    }

    @Test
    void secondFailurePropagates() {
        AtomicInteger callCount = new AtomicInteger();
        AtomicInteger refreshCount = new AtomicInteger();
        CarrierAuthException ex = assertThrows(CarrierAuthException.class, () ->
                AuthRetry.withAuthRetry(
                        "stale-token",
                        () -> { refreshCount.incrementAndGet(); return "fresh-token"; },
                        token -> {
                            callCount.incrementAndGet();
                            throw new CarrierAuthException("UPS", "still 401 after refresh");
                        }));
        assertTrue(ex.getMessage().contains("still 401"));
        assertEquals(2, callCount.get(), "exactly one retry — no infinite loop");
        assertEquals(1, refreshCount.get(), "exactly one refresh");
    }

    @Test
    void nonAuthExceptionPassesThroughWithoutRetry() {
        AtomicInteger callCount = new AtomicInteger();
        AtomicInteger refreshCount = new AtomicInteger();
        assertThrows(CarrierServerException.class, () ->
                AuthRetry.withAuthRetry(
                        "token",
                        () -> { refreshCount.incrementAndGet(); return "fresh"; },
                        token -> {
                            callCount.incrementAndGet();
                            throw new CarrierServerException("UPS", 502, "carrier down");
                        }));
        assertEquals(1, callCount.get(), "5xx must NOT retry (may not be idempotent)");
        assertEquals(0, refreshCount.get());
    }
}
