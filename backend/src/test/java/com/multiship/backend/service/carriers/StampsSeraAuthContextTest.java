package com.multiship.backend.service.carriers;

import com.multiship.backend.model.CarrierAccountRef;
import com.multiship.backend.repository.CarrierAccountRefRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * PR-S1 (audit finding S-B1) — pins the push/pop mechanics of
 * {@link StampsSeraAuthContext}. Regression here silently unwires the
 * background-worker SERA auth path, so every branch that could return
 * NOOP has an explicit test.
 */
class StampsSeraAuthContextTest {

    private static Field SERA_TOKEN_FIELD;

    @BeforeEach
    void reset() throws Exception {
        // Belt-and-braces — a leaking test elsewhere could poison this
        // ThreadLocal for our thread. Grab reflective access once + clear.
        SERA_TOKEN_FIELD = StampsConnector.class.getDeclaredField("SERA_REFRESH_TOKEN");
        SERA_TOKEN_FIELD.setAccessible(true);
        threadLocal().remove();
    }

    @AfterEach
    void tearDown() {
        threadLocal().remove();
    }

    @SuppressWarnings("unchecked")
    private static ThreadLocal<String> threadLocal() {
        try {
            return (ThreadLocal<String>) SERA_TOKEN_FIELD.get(null);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    // ================================================================
    // openFor(account)
    // ================================================================

    @Test
    void openFor_stampsAccountWithToken_pushesTokenAndClearsOnClose() throws Exception {
        CarrierAccountRef account = stampsAccount("STAMPS", "acc-1", "refresh-xyz");

        try (AutoCloseable ctx = StampsSeraAuthContext.openFor(account)) {
            assertEquals("refresh-xyz", threadLocal().get(),
                    "Refresh token must be visible on the ThreadLocal while the ctx is open.");
        }
        assertNull(threadLocal().get(),
                "close() must clear the ThreadLocal so the next order on this thread starts fresh.");
    }

    @Test
    void openFor_uspsCarrierCode_alsoPushes() throws Exception {
        // The canonicalisation lives elsewhere — StampsConnector callers
        // reach it under either "STAMPS" or "USPS". Accept both.
        CarrierAccountRef account = stampsAccount("USPS", "acc-2", "refresh-usps");

        try (AutoCloseable ctx = StampsSeraAuthContext.openFor(account)) {
            assertEquals("refresh-usps", threadLocal().get());
        }
    }

    @Test
    void openFor_nonStampsCarrier_isNoop() throws Exception {
        CarrierAccountRef account = stampsAccount("FEDEX", "acc-fedex", "should-be-ignored");

        try (AutoCloseable ctx = StampsSeraAuthContext.openFor(account)) {
            assertNull(threadLocal().get(),
                    "FedEx accounts must not touch the Stamps SERA ThreadLocal.");
        }
    }

    @Test
    void openFor_blankRefreshToken_isNoop() throws Exception {
        // Legacy SWSIM account — no OAuth refresh_token. Must not push
        // an empty string, would trigger `hasText` failure downstream.
        CarrierAccountRef account = stampsAccount("STAMPS", "acc-3", "");

        try (AutoCloseable ctx = StampsSeraAuthContext.openFor(account)) {
            assertNull(threadLocal().get());
        }
    }

    @Test
    void openFor_nullAccount_isNoop() throws Exception {
        try (AutoCloseable ctx = StampsSeraAuthContext.openFor((CarrierAccountRef) null)) {
            assertNull(threadLocal().get());
        }
    }

    // ================================================================
    // openFor(repo, carrier, accountNumber)
    // ================================================================

    @Test
    void openFor_repoLookup_stampsAccount_pushesToken() throws Exception {
        CarrierAccountRefRepository repo = mock(CarrierAccountRefRepository.class);
        CarrierAccountRef account = stampsAccount("STAMPS", "acc-4", "refresh-abc");
        when(repo.findFirstByAccountNumberIgnoreCaseAndCarrierCodeIgnoreCase("acc-4", "STAMPS"))
                .thenReturn(Optional.of(account));

        try (AutoCloseable ctx = StampsSeraAuthContext.openFor(repo, "STAMPS", "acc-4")) {
            assertEquals("refresh-abc", threadLocal().get());
        }
        verify(repo).findFirstByAccountNumberIgnoreCaseAndCarrierCodeIgnoreCase("acc-4", "STAMPS");
    }

    @Test
    void openFor_repoLookup_nonStampsCarrier_skipsDbLookup() throws Exception {
        // A carrier-generic caller may pass FEDEX / UPS through; must not
        // hit the DB in that case (waste of a lookup per label call).
        CarrierAccountRefRepository repo = mock(CarrierAccountRefRepository.class);

        try (AutoCloseable ctx = StampsSeraAuthContext.openFor(repo, "FEDEX", "acc-fedex")) {
            assertNull(threadLocal().get());
        }
        verifyNoInteractions(repo);
    }

    @Test
    void openFor_repoLookup_accountNotFound_isNoop() throws Exception {
        CarrierAccountRefRepository repo = mock(CarrierAccountRefRepository.class);
        when(repo.findFirstByAccountNumberIgnoreCaseAndCarrierCodeIgnoreCase(eq("acc-missing"), eq("STAMPS")))
                .thenReturn(Optional.empty());

        try (AutoCloseable ctx = StampsSeraAuthContext.openFor(repo, "STAMPS", "acc-missing")) {
            assertNull(threadLocal().get());
        }
        // Lookup fires but returns nothing — that's fine, don't NPE.
        verify(repo).findFirstByAccountNumberIgnoreCaseAndCarrierCodeIgnoreCase("acc-missing", "STAMPS");
    }

    @Test
    void openFor_repoLookup_blankAccountNumber_skipsDbLookup() throws Exception {
        CarrierAccountRefRepository repo = mock(CarrierAccountRefRepository.class);

        try (AutoCloseable ctx = StampsSeraAuthContext.openFor(repo, "STAMPS", "  ")) {
            assertNull(threadLocal().get());
        }
        verifyNoInteractions(repo);
    }

    @Test
    void openFor_repoLookup_nullRepo_isNoop() throws Exception {
        try (AutoCloseable ctx = StampsSeraAuthContext.openFor(null, "STAMPS", "acc-4")) {
            assertNull(threadLocal().get());
        }
    }

    // ================================================================
    // helpers
    // ================================================================

    private static CarrierAccountRef stampsAccount(String carrierCode, String accountNumber, String refresh) {
        CarrierAccountRef ref = new CarrierAccountRef();
        ref.setCarrierCode(carrierCode);
        ref.setAccountNumber(accountNumber);
        ref.setStampsRefreshToken(refresh);
        assertNotNull(ref.getStampsRefreshToken());
        return ref;
    }
}
