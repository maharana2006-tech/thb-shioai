package com.multiship.backend.service.carriers.usps;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.service.carriers.HttpClients;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Per-tenant USPS v3 payment-authorization token cache.
 *
 * <p>USPS's label endpoint requires TWO tokens on every request: the
 * platform Bearer OAuth token (see {@link UspsOAuthTokenCache}) and a
 * per-tenant {@code X-Payment-Authorization-Token} header that ties the
 * label to a specific EPS account with named PAYER / LABEL_OWNER /
 * RATE_HOLDER / PLATFORM roles.
 *
 * <p>Cache is keyed by {@code (CRID, MID, accountNumber, environment)}
 * so multi-tenant deployments don't cross-authorize labels. Each entry
 * stores the token USPS returned plus an expiry the caller can use to
 * refresh. Only real tokens are cached; a failed mint returns
 * {@link Optional#empty()} so the connector can surface an actionable
 * error to the operator rather than caching a broken value.
 *
 * <p>Called on-demand by the label + close-out paths; rates + tracking
 * don't need payment authorization (rates never touch money; tracking is
 * pure read).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UspsPaymentAuthCache {

    /** Refresh margin — treat a token as expired {@value} seconds early. */
    static final long TOKEN_REFRESH_MARGIN_SECONDS = 60L;

    /** Default TTL when USPS's response omits an explicit expiry. USPS's
     *  documented default is 8 hours matching the OAuth token; refreshing
     *  at T-1min from that is safe. */
    static final long TOKEN_DEFAULT_TTL_SECONDS = 28_800L;

    /** Base back-off for 429 responses. Sequence: 2s, 4s, 8s. */
    static final long RETRY_BASE_MILLIS = 2_000L;
    /** Max retry attempts for 429 (in addition to the initial request). */
    static final int MAX_RATE_LIMIT_RETRIES = 3;

    private final ObjectMapper objectMapper;

    private final ConcurrentHashMap<CacheKey, CachedToken> cache = new ConcurrentHashMap<>();

    /**
     * Retrieve (mint on miss) a payment-authorization token for the given
     * tenant tuple. Callers pass the OAuth Bearer token separately (from
     * {@link UspsOAuthTokenCache}) — the payment endpoint requires Bearer
     * auth just like every other v3 endpoint.
     */
    public Optional<String> getToken(String crid, String mid, String accountNumber,
                                     String oauthAccessToken, String environment) {
        if (!StringUtils.hasText(crid) || !StringUtils.hasText(mid)
                || !StringUtils.hasText(accountNumber)
                || !StringUtils.hasText(oauthAccessToken)) {
            return Optional.empty();
        }
        CacheKey key = new CacheKey(crid, mid, accountNumber, envKey(environment));
        CachedToken existing = cache.get(key);
        if (existing != null && existing.isValid()) {
            return Optional.of(existing.token);
        }
        CachedToken refreshed = cache.compute(key, (k, cur) -> {
            if (cur != null && cur.isValid()) return cur;
            return mintTokenWithRetry(crid, mid, accountNumber, oauthAccessToken, environment);
        });
        return refreshed == null ? Optional.empty() : Optional.of(refreshed.token);
    }

    /** Package-private for tests + ops hygiene. */
    void clear() {
        cache.clear();
    }

    int size() {
        return cache.size();
    }

    private static String envKey(String environment) {
        return "SANDBOX".equalsIgnoreCase(environment == null ? "" : environment.trim())
                ? "SANDBOX" : "PRODUCTION";
    }

    private CachedToken mintTokenWithRetry(String crid, String mid, String accountNumber,
                                            String oauthAccessToken, String environment) {
        int attempt = 0;
        while (true) {
            try {
                return mintTokenOnce(crid, mid, accountNumber, oauthAccessToken, environment);
            } catch (RestClientResponseException ex) {
                int status = ex.getStatusCode().value();
                if (status == 429 && attempt < MAX_RATE_LIMIT_RETRIES) {
                    long delay = backoffDelayMillis(attempt);
                    log.warn("USPS payment-auth 429 (attempt {}/{}); backing off {}ms.",
                            attempt + 1, MAX_RATE_LIMIT_RETRIES, delay);
                    sleepQuietly(delay);
                    attempt++;
                    continue;
                }
                log.warn("USPS payment-auth rejected (HTTP {}): {}",
                        status, safeBody(ex.getResponseBodyAsString()));
                return null;
            } catch (Exception ex) {
                log.warn("USPS payment-auth request failed: {}", ex.getMessage());
                return null;
            }
        }
    }

    static long backoffDelayMillis(int attempt) {
        long base = RETRY_BASE_MILLIS * (1L << attempt);   // 2s, 4s, 8s
        long jitter = ThreadLocalRandom.current().nextLong(0, 500);
        return base + jitter;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private CachedToken mintTokenOnce(String crid, String mid, String accountNumber,
                                       String oauthAccessToken, String environment) {
        String url = UspsOAuthTokenCache.baseUrl(environment)
                + "/payments/v3/payment-authorization";

        Map<String, Object> body = buildPaymentAuthPayload(crid, mid, accountNumber);

        RestClient client = HttpClients.newBuilder().baseUrl(url).build();
        String response = client.post()
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + oauthAccessToken)
                .body(body)
                .retrieve()
                .body(String.class);

        try {
            JsonNode node = objectMapper.readTree(response == null ? "{}" : response);
            // USPS returns the token in `paymentAuthorizationToken` on the
            // 200 payload; some sandbox responses put it under
            // `X-Payment-Authorization-Token` mirroring the header. Accept
            // both shapes so the connector doesn't break when USPS moves
            // it between docs revisions.
            String token = node.path("paymentAuthorizationToken").asText(null);
            if (!StringUtils.hasText(token)) {
                token = node.path("X-Payment-Authorization-Token").asText(null);
            }
            if (!StringUtils.hasText(token)) {
                log.warn("USPS payment-auth returned no token; body: {}", safeBody(response));
                return null;
            }
            long ttl = node.path("expiresIn").asLong(TOKEN_DEFAULT_TTL_SECONDS);
            return new CachedToken(token, Instant.now().plusSeconds(ttl));
        } catch (Exception ex) {
            log.warn("USPS payment-auth response unparseable: {}", ex.getMessage());
            return null;
        }
    }

    /**
     * Build the USPS v3 payment-authorization request body. The four
     * required roles (PAYER / LABEL_OWNER / RATE_HOLDER / PLATFORM) all
     * carry the tenant's CRID + MID + account so USPS can attribute
     * postage against the correct EPS account.
     *
     * <p>Package-visible for the payload-shape test — the exact JSON
     * shape is a contract with USPS and worth pinning down in a unit
     * test rather than a live-fire integration run.
     */
    static Map<String, Object> buildPaymentAuthPayload(String crid, String mid, String accountNumber) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("roles", List.of(
                roleEntry("PAYER", crid, mid, accountNumber),
                roleEntry("LABEL_OWNER", crid, mid, accountNumber),
                roleEntry("RATE_HOLDER", crid, mid, accountNumber),
                roleEntry("PLATFORM", crid, mid, accountNumber)
        ));
        return body;
    }

    private static Map<String, Object> roleEntry(String roleName, String crid, String mid,
                                                  String accountNumber) {
        Map<String, Object> role = new LinkedHashMap<>();
        role.put("roleName", roleName);
        role.put("CRID", crid);
        role.put("MID", mid);
        role.put("accountNumber", accountNumber);
        role.put("accountType", "EPS");
        return role;
    }

    private static String safeBody(String body) {
        if (body == null) return "null";
        return body.length() > 800 ? body.substring(0, 800) + "…(truncated)" : body;
    }

    /** Composite cache key. Case-sensitive on the identifiers (they're
     *  system-generated by USPS) but env is normalised. */
    static final class CacheKey {
        final String crid;
        final String mid;
        final String accountNumber;
        final String env;

        CacheKey(String crid, String mid, String accountNumber, String env) {
            this.crid = crid;
            this.mid = mid;
            this.accountNumber = accountNumber;
            this.env = env;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof CacheKey k)) return false;
            return crid.equals(k.crid) && mid.equals(k.mid)
                    && accountNumber.equals(k.accountNumber) && env.equals(k.env);
        }

        @Override
        public int hashCode() {
            return Objects.hash(crid, mid, accountNumber, env);
        }
    }

    static final class CachedToken {
        final String token;
        final Instant expiresAt;

        CachedToken(String token, Instant expiresAt) {
            this.token = token;
            this.expiresAt = expiresAt;
        }

        boolean isValid() {
            return Instant.now().isBefore(expiresAt.minusSeconds(TOKEN_REFRESH_MARGIN_SECONDS));
        }
    }
}
