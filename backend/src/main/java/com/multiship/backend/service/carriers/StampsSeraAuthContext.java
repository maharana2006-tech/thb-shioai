package com.multiship.backend.service.carriers;

import com.multiship.backend.model.CarrierAccountRef;
import com.multiship.backend.repository.CarrierAccountRefRepository;
import org.springframework.util.StringUtils;

import java.util.Optional;

/**
 * PR-S1 (audit finding S-B1) — auto-push the account's SERA
 * {@code refresh_token} onto {@link StampsConnector#SERA_REFRESH_TOKEN}
 * before a Stamps carrier call, then clear the ThreadLocal in the
 * try-with-resources close.
 *
 * <p>Before S1, only the account-verify path
 * ({@code AccountRefServiceImpl#verify}) called
 * {@link StampsConnector#pushSeraRefreshToken(String)}. Label-generation
 * paths (interactive {@code generateManualLabel}, background import /
 * bulk worker) skipped the push entirely — a SERA-only tenant's label
 * calls all fell through to {@code buildFallbackToken()} + the
 * {@code SERA_NEEDS_AUTHORIZATION} flag, silently failing at the carrier
 * with an actionable but unsurfaced auth error.
 *
 * <p>The context is an {@link AutoCloseable} so callers wrap the carrier
 * dispatch in a try-with-resources:
 * <pre>{@code
 * try (var ignored = StampsSeraAuthContext.openFor(account)) {
 *     String token = connector.getAccessToken(...);
 *     var result = connector.createShipment(...);
 * }
 * }</pre>
 * The close-lambda calls {@code pushSeraRefreshToken(null)} so the
 * ThreadLocal is cleared before the worker thread services another
 * order (avoids the S-I4 pollution scenario where token from group A
 * leaks to group B's first row).
 *
 * <p>No-ops when the carrier isn't Stamps/USPS or when the account is
 * missing — safe to call unconditionally from carrier-generic code.
 */
public final class StampsSeraAuthContext {

    /** Test hook: the no-op closer used by all "not a Stamps call" paths. */
    private static final AutoCloseable NOOP = () -> {};

    private StampsSeraAuthContext() {}

    /**
     * Open a push-then-clear context for the given account. Non-Stamps
     * carriers and null/blank refresh tokens return the NOOP closer; the
     * push only fires when there's actually a token to push (so a not-yet-
     * authorized SERA account keeps hitting the {@code needsAuthorization}
     * flag exactly as before, no behavioural regression).
     */
    public static AutoCloseable openFor(CarrierAccountRef account) {
        if (account == null) return NOOP;
        String carrier = account.getCarrierCode();
        if (!isStampsCarrier(carrier)) return NOOP;
        String refresh = account.getStampsRefreshToken();
        if (!StringUtils.hasText(refresh)) return NOOP;
        StampsConnector.pushSeraRefreshToken(refresh);
        return () -> StampsConnector.pushSeraRefreshToken(null);
    }

    /**
     * Overload for paths that don't already hold a {@link CarrierAccountRef}
     * (import + bulk-worker paths — they carry only carrierCode +
     * accountNumber from resolution). Loads the account via
     * {@link CarrierAccountRefRepository#findFirstByAccountNumberIgnoreCaseAndCarrierCodeIgnoreCase}
     * then delegates to {@link #openFor(CarrierAccountRef)}.
     *
     * <p>NOOP when any input is missing or the account isn't found —
     * the caller keeps the pre-S1 behaviour on those edges.
     */
    public static AutoCloseable openFor(CarrierAccountRefRepository repo,
                                        String carrierCode,
                                        String accountNumber) {
        if (repo == null
                || !StringUtils.hasText(carrierCode)
                || !StringUtils.hasText(accountNumber)) {
            return NOOP;
        }
        if (!isStampsCarrier(carrierCode)) return NOOP;
        Optional<CarrierAccountRef> account =
                repo.findFirstByAccountNumberIgnoreCaseAndCarrierCodeIgnoreCase(
                        accountNumber, carrierCode);
        return account.map(StampsSeraAuthContext::openFor).orElse(NOOP);
    }

    /**
     * PR-S1 — accept either "USPS" or "STAMPS" carrier code since the
     * canonicalisation lives in {@code ShippingConfigService.canonicalCarrierFor}
     * and both surface at different call sites. Case-insensitive per the
     * repository's naming convention.
     */
    private static boolean isStampsCarrier(String carrier) {
        return StringUtils.hasText(carrier)
                && ("USPS".equalsIgnoreCase(carrier) || "STAMPS".equalsIgnoreCase(carrier));
    }
}
