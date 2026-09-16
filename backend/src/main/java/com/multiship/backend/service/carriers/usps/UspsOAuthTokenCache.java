package com.multiship.backend.service.carriers.usps;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.service.carriers.HttpClients;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Platform-wide USPS v3 OAuth 2.0 token cache.
 *
 * <p>USPS's Direct v3 APIs authenticate with a single platform-wide OAuth
 * client (Client ID + Client Secret) that the ops team registers at
 * developer.usps.com and pastes into {@code /settings/system} under the
 * keys {@code USPS_PLATFORM_CLIENT_ID} / {@code USPS_PLATFORM_CLIENT_SECRET}.
 * All tenants share the same platform client — per-tenant identity is
 * carried on the payment-authorization envelope (CRID / MID / account)
 * instead of on the OAuth exchange.
 *
 * <p>Because the credentials are platform-wide, the cache is effectively
 * single-entry per environment (SANDBOX vs PRODUCTION). Keying by clientId
 * as well lets ops rotate the platform key without a JVM restart —
 * different clientIds get their own cache slots.
 *
 * <p>USPS tokens have an 8-hour TTL by default. To avoid burning a token
 * that expires mid-request the cache refreshes at
 * {@link #TOKEN_REFRESH_MARGIN_SECONDS} before the declared expiry
 * (i.e. at T-30min for an 8h token).
 *
 * <p>Only real tokens are cached; every failure path returns
 * {@link Optional#empty()} so the caller can decide whether to fall back
 * to a {@code usps-direct-local-*} placeholder. Caching a placeholder
 * would poison the whole TTL window on a transient outage.
 *
 * <p>Concurrent refreshes are single-flighted via
 * {@link ConcurrentHashMap#compute} so N workers hitting the same key
 * only fire ONE OAuth POST to USPS. 429 responses are retried with
 * exponential-backoff-with-jitter (2s / 4s / 8s, 3 attempts).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UspsOAuthTokenCache {

    /** USPS v3 OAuth scope covering all Direct APIs (rates + labels +
     *  payments + tracking + addresses + carrier-pickup). Pasted into
     *  the token request body as a space-separated string. */
    public static final String USPS_V3_SCOPE = "addresses service-delivery-standards locations "
            + "prices international-prices tracking subscriptions-tracking "
            + "labels international-labels payments carrier-pickup";

    /** Production OAuth host — apis.usps.com. */
    public static final String PROD_HOST = "https://apis.usps.com";
    /** CAT (Customer Acceptance Testing) sandbox host — apis-tem.usps.com. */
    public static final String SANDBOX_HOST = "https://apis-tem.usps.com";

    /** Refresh margin — treat a token as expired {@value} seconds early so
     *  in-flight workers don't submit against a token that expires
     *  mid-request. USPS tokens are 8h; refreshing at T-30min is safe. */
    static final long TOKEN_REFRESH_MARGIN_SECONDS = 1_800L;

    /** Fallback when the USPS OAuth response doesn't include {@code expires_in}.
     *  USPS documents 8-hour tokens; the refresh margin then re-mints at
     *  T-30min from that. */
    static final long TOKEN_DEFAULT_TTL_SECONDS = 28_800L;

    /** Base back-off for 429 responses. Sequence: 2s, 4s, 8s. */
    static final long RETRY_BASE_MILLIS = 2_000L;
    /** Max retry attempts for 429 (in addition to the initial request). */
    static final int MAX_RATE_LIMIT_RETRIES = 3;

    private final ObjectMapper objectMapper;

    /**
     * Cache keyed by {@code clientId + "|" + environment}. Environment is
     * normalised to {@code SANDBOX} / {@code PRODUCTION} so a null / typo
     * env label doesn't create a phantom third slot.
     */
    private final ConcurrentHashMap<String, CachedToken> tokenCache = new ConcurrentHashMap<>();

    /**
     * Retrieve (mint on miss) a USPS v3 OAuth access token for the given
     * platform credentials and environment.
     *
     * @return the raw access_token string on success; {@link Optional#empty()}
     *         when the OAuth endpoint rejected the credentials or was
     *         unreachable. Callers who need a placeholder should construct
     *         their own {@code usps-direct-local-...} token — this cache
     *         never returns one.
     */
    public Optional<String> getToken(String clientId, String clientSecret, String environment) {
        if (!StringUtils.hasText(clientId) || !StringUtils.hasText(clientSecret)) {
            return Optional.empty();
        }
        String key = cacheKey(clientId, environment);
        CachedToken existing = tokenCache.get(key);
        if (existing != null && existing.isValid()) {
            return Optional.of(existing.token);
        }
        // Single-flight the refresh under compute() so N concurrent callers
        // on the same key only mint ONCE. Returning null from the lambda
        // REMOVES the entry (compute() contract) — exactly what we want on
        // failure so a transient outage doesn't poison the cache.
        CachedToken refreshed = tokenCache.compute(key, (k, cur) -> {
            if (cur != null && cur.isValid()) return cur;
            return fetchTokenWithRetry(clientId, clientSecret, environment);
        });
        return refreshed == null ? Optional.empty() : Optional.of(refreshed.token);
    }

    /** Package-private for tests + ops hygiene. */
    void clear() {
        tokenCache.clear();
    }

    /** Package-private — cache size for observability tests. */
    int size() {
        return tokenCache.size();
    }

    static String cacheKey(String clientId, String environment) {
        return clientId + "|" + envKey(environment);
    }

    private static String envKey(String environment) {
        return "SANDBOX".equalsIgnoreCase(environment == null ? "" : environment.trim())
                ? "SANDBOX" : "PRODUCTION";
    }

    /** Base URL selector for the OAuth host. Package-private so the
     *  connector can reuse the same routing logic for API calls. */
    public static String baseUrl(String environment) {
        return "SANDBOX".equalsIgnoreCase(environment == null ? "" : environment.trim())
                ? SANDBOX_HOST : PROD_HOST;
    }

    /**
     * POST {@code /oauth2/v3/token} with a client_credentials grant. On 429
     * we back off (2s / 4s / 8s + jitter) and retry up to
     * {@link #MAX_RATE_LIMIT_RETRIES} times. Any other error returns null so
     * the caller records a miss.
     */
    private CachedToken fetchTokenWithRetry(String clientId, String clientSecret, String environment) {
        int attempt = 0;
        while (true) {
            try {
                return fetchTokenOnce(clientId, clientSecret, environment);
            } catch (RestClientResponseException ex) {
                int status = ex.getStatusCode().value();
                if (status == 429 && attempt < MAX_RATE_LIMIT_RETRIES) {
                    long delay = backoffDelayMillis(attempt);
                    log.warn("USPS OAuth 429 (attempt {}/{}); backing off {}ms.",
                            attempt + 1, MAX_RATE_LIMIT_RETRIES, delay);
                    sleepQuietly(delay);
                    attempt++;
                    continue;
                }
                log.warn("USPS OAuth request rejected (HTTP {}): {}",
                        status, safeBody(ex.getResponseBodyAsString()));
                return null;
            } catch (Exception ex) {
                log.warn("USPS OAuth request failed; using local fallback. Reason: {}", ex.getMessage());
                return null;
            }
        }
    }

    /** Exponential base-2 back-off plus 0-500ms jitter to avoid a thundering
     *  herd hitting USPS in lock-step after a shared rate-limit trip. */
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

    private CachedToken fetchTokenOnce(String clientId, String clientSecret, String environment) {
        String url = baseUrl(environment) + "/oauth2/v3/token";
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("scope", USPS_V3_SCOPE);

        RestClient client = HttpClients.newBuilder().baseUrl(url).build();
        String response = client.post()
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .accept(MediaType.APPLICATION_JSON)
                .body(form)
                .retrieve()
                .body(String.class);

        try {
            JsonNode node = objectMapper.readTree(response == null ? "{}" : response);
            String accessToken = node.path("access_token").asText(null);
            if (!StringUtils.hasText(accessToken)) {
                log.warn("USPS OAuth returned no access_token; body: {}", safeBody(response));
                return null;
            }
            long ttl = node.path("expires_in").asLong(TOKEN_DEFAULT_TTL_SECONDS);
            return new CachedToken(accessToken, Instant.now().plusSeconds(ttl));
        } catch (Exception parseEx) {
            log.warn("USPS OAuth response unparseable: {}", parseEx.getMessage());
            return null;
        }
    }

    /** Truncate a body for logs so a verbose USPS error doesn't blow past
     *  the log-line budget. Never echoes clientSecret because we don't put
     *  it in the response — USPS just returns error codes. */
    private static String safeBody(String body) {
        if (body == null) return "null";
        String trimmed = body.length() > 800 ? body.substring(0, 800) + "…(truncated)" : body;
        return trimmed;
    }

    /**
     * Cache entry. isValid() applies the refresh margin so callers never
     * pick up a token that's about to expire.
     */
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
