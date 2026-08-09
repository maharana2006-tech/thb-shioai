package com.multiship.backend.service.carriers;

import com.multiship.backend.service.carriers.exceptions.CarrierAuthException;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Sprint 49 Tier 2 — OAuth 401→refresh→retry helper.
 *
 * <p>Cached carrier tokens can go stale between checks (clock drift,
 * carrier-side revocation, provider-side rotation). Before this fix
 * a stale token 401 propagated straight to the fake-label fallback;
 * now (with the fallback removed) it would propagate to the caller
 * as a hard 502 — undesirable, since a fresh token would usually
 * work.
 *
 * <p>Semantics: try once → on {@link CarrierAuthException} refresh
 * the token via {@code tokenAcquirer.get()} → retry exactly once →
 * propagate whatever the second try throws. Never loops.
 */
@Slf4j
public final class AuthRetry {

    private AuthRetry() {}

    /**
     * @param initialToken   The token to try first (typically the cached one).
     * @param tokenRefresher Called only if the first attempt throws
     *                       {@link CarrierAuthException}. Should force-fetch
     *                       a new token, bypassing any cache.
     * @param call           Given a token, executes the carrier call.
     * @param <T>            Return type of the carrier call.
     * @return The result of the first successful attempt.
     */
    public static <T> T withAuthRetry(
            String initialToken,
            Supplier<String> tokenRefresher,
            Function<String, T> call) {
        try {
            return call.apply(initialToken);
        } catch (CarrierAuthException authEx) {
            log.info("Carrier auth failure — force-refreshing token and retrying once. Reason: {}",
                    authEx.getMessage());
            String freshToken = tokenRefresher.get();
            return call.apply(freshToken);  // second failure propagates
        }
    }
}
