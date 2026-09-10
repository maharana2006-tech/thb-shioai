package com.multiship.backend.service.carriers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.config.CarrierProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stamps.com SERA 3-legged OAuth support — builds the browser-authorize
 * URL, signs + verifies the {@code state} value that ties the callback
 * back to a specific carrier_account_ref row, and exchanges an
 * authorization {@code code} for an access + refresh token pair.
 *
 * <p>Why 3-legged: Stamps.com developer accounts are provisioned for
 * {@code authorization_code} + {@code refresh_token} grants, NOT
 * {@code client_credentials}. The prior "Verify credentials" flow posted
 * {@code grant_type=client_credentials} and Auctane returned
 * {@code unsupported_grant_type}. This service replaces that with a
 * proper browser-redirect flow whose refresh token is persisted on the
 * account (encrypted at rest) and used to mint short-lived access
 * tokens going forward.
 *
 * <p>State signing: an HMAC-SHA256 tag over
 * {@code accountId:timestamp:nonce} using {@link #stateSigningSecret}
 * prevents an attacker from forging a callback that would attach their
 * consent-code to somebody else's carrier account. The state carries
 * enough context for the callback to skip a DB lookup on the client_id
 * and instead resolve the account by id.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StampsSeraOAuthService {

    /** State TTL — 10 minutes. Long enough for the operator to complete
     *  consent (usually seconds), short enough that a leaked state can't
     *  be replayed forever. */
    private static final long STATE_TTL_SECONDS = 600;

    private final CarrierProperties carrierProperties;
    private final ObjectMapper objectMapper;

    /** Base64-encoded HMAC-SHA256 key used to sign the OAuth state.
     *  Falls back to the SECRETS_ENCRYPTION_KEY (already required for
     *  refresh-token persistence) so a fresh dev environment doesn't
     *  need an extra config knob; production should override with a
     *  dedicated key to keep signing + encryption domains separate. */
    @Value("${carrier.stamps.sera-state-signing-key:${secrets.encryption-key:}}")
    private String stateSigningSecret;

    /**
     * SERA access-token cache keyed by {@code SHA-256(refresh_token) + "|" + env}.
     * The value we cache is the short-lived {@code access_token} SERA returns;
     * the point of the cache is to skip the {@code POST /oauth/token} round-trip
     * on every carrier call. Without it, a 500-order bulk batch with the default
     * 24-worker pool = up to 500 refresh_token exchanges against Auctane per
     * batch — Auctane rate-limits token requests separately from ship requests,
     * so this trips before the shipment API does.
     *
     * <p><b>Refresh_token itself is NEVER used as the map key</b> — even as an
     * in-memory hashmap key, refresh_tokens are secrets we treat like passwords.
     * SHA-256 collapses to a 32-byte fingerprint that's safe to hold in a map;
     * a caller with a different refresh_token deterministically hits a
     * different slot.
     *
     * <p><b>refresh_tokens may rotate.</b> SERA can hand back a new
     * {@code refresh_token} in the exchange response; the caller is responsible
     * for persisting it and passing the new value on subsequent calls. When
     * that happens, the new refresh_token hashes to a different key and we
     * miss the cache once — that's the correct behavior. The old entry
     * eventually expires and is evicted lazily on the next lookup for that
     * hash (which won't happen again).
     *
     * <p><b>Access_token failures are NOT cached.</b> {@link #refreshToken}
     * returns a failure {@link TokenExchangeResult} on rejection; the cache
     * only ever stores successful results with a positive TTL from the
     * response's {@code expires_in}.
     */
    private final ConcurrentHashMap<String, CachedAccessToken> tokenCache = new ConcurrentHashMap<>();

    /** Refresh a cached access-token this many seconds before its declared
     *  expiry so an in-flight worker doesn't submit against a token that
     *  expires mid-request. */
    private static final long TOKEN_REFRESH_MARGIN_SECONDS = 60;

    /**
     * Cached access-token entry. Does NOT store the refresh_token, only
     * the access_token — the caller owns refresh_token persistence.
     */
    private record CachedAccessToken(String accessToken, String rotatedRefreshToken,
                                      Instant expiresAt) {
        boolean isValid() {
            return Instant.now().isBefore(expiresAt.minusSeconds(TOKEN_REFRESH_MARGIN_SECONDS));
        }
    }

    /**
     * Build the fully-formed authorize URL the frontend should redirect
     * the operator to. The {@code state} field carries a signed
     * account-id + timestamp + nonce; the callback verifies it before
     * accepting the {@code code}.
     *
     * @param accountId  carrier_account_ref.id — the row whose refresh_token
     *                   will be populated on successful callback
     * @param clientId   SERA client_id from the developer portal
     * @param environment SANDBOX | PRODUCTION — routes SANDBOX to
     *                    signin.testing.stampsendicia.com
     */
    public String buildAuthorizeUrl(Long accountId, String clientId, String environment) {
        CarrierProperties.Stamps s = carrierProperties.getStamps();
        boolean sandbox = isSandbox(environment);
        String authorizeUrl = sandbox ? s.getSeraSandboxAuthorizeUrl() : s.getSeraAuthorizeUrl();
        if (!StringUtils.hasText(authorizeUrl)) {
            throw new IllegalStateException("carrier.stamps.sera-"
                    + (sandbox ? "sandbox-" : "") + "authorize-url is not configured");
        }
        String redirectUri = requireRedirectUri();
        String scope = StringUtils.hasText(s.getSeraScope()) ? s.getSeraScope() : "offline_access";
        String state = signState(accountId);
        return UriComponentsBuilder.fromUriString(authorizeUrl)
                .queryParam("response_type", "code")
                .queryParam("client_id", clientId)
                .queryParam("redirect_uri", redirectUri)
                .queryParam("scope", scope)
                .queryParam("state", state)
                .build(true)
                .toUriString();
    }

    /**
     * Verify a state value received on the OAuth callback. Returns the
     * embedded accountId, or empty when the signature doesn't verify
     * OR the state is older than {@link #STATE_TTL_SECONDS}.
     */
    public Optional<Long> verifyState(String state) {
        if (!StringUtils.hasText(state)) return Optional.empty();
        try {
            byte[] raw = Base64.getUrlDecoder().decode(state);
            String decoded = new String(raw, StandardCharsets.UTF_8);
            // Payload format: <accountId>:<epochSecond>:<nonce>:<hexHmac>
            int lastColon = decoded.lastIndexOf(':');
            if (lastColon < 0) return Optional.empty();
            String payload = decoded.substring(0, lastColon);
            String hmac = decoded.substring(lastColon + 1);
            String expected = hmacHex(payload);
            if (!constantTimeEquals(hmac, expected)) {
                log.warn("SERA OAuth callback: state signature mismatch — rejecting.");
                return Optional.empty();
            }
            String[] parts = payload.split(":");
            if (parts.length < 3) return Optional.empty();
            long ts = Long.parseLong(parts[1]);
            if (Instant.now().getEpochSecond() - ts > STATE_TTL_SECONDS) {
                log.warn("SERA OAuth callback: state expired (age {}s > {}s TTL) — rejecting.",
                        Instant.now().getEpochSecond() - ts, STATE_TTL_SECONDS);
                return Optional.empty();
            }
            return Optional.of(Long.parseLong(parts[0]));
        } catch (Exception ex) {
            log.warn("SERA OAuth callback: state parse failed — rejecting. Reason: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Exchange an authorization {@code code} for an access + refresh
     * token pair. Called from the callback controller after state
     * verification passes.
     */
    public TokenExchangeResult exchangeCode(String code, String clientId, String clientSecret,
                                            String environment) {
        return postToken(env(environment), body -> {
            body.put("grant_type", "authorization_code");
            body.put("code", code);
            body.put("redirect_uri", requireRedirectUri());
            body.put("client_id", clientId);
            if (StringUtils.hasText(clientSecret)) {
                body.put("client_secret", clientSecret);
            }
        });
    }

    /**
     * Mint a fresh access token from a persisted refresh_token. Returns
     * the exchange result including any newly-issued refresh_token (SERA
     * MAY rotate the refresh token on each refresh; callers should
     * persist a non-null value back).
     *
     * <p>Cached by {@code SHA-256(refresh_token) + "|" + env}. Successive
     * calls with the same refresh_token within the cached access_token's
     * TTL (minus the {@link #TOKEN_REFRESH_MARGIN_SECONDS} margin) return
     * the cached value without a network round-trip. On rotation the new
     * refresh_token hashes to a different key and we miss the cache once,
     * as intended.
     */
    public TokenExchangeResult refreshToken(String refreshToken, String clientId, String clientSecret,
                                            String environment) {
        if (!StringUtils.hasText(refreshToken)) {
            return TokenExchangeResult.failure("refresh_token is required");
        }
        String cacheKey = hashForKey(refreshToken) + "|" + envKey(environment);
        CachedAccessToken existing = tokenCache.get(cacheKey);
        if (existing != null && existing.isValid()) {
            // Cache HIT — return a TokenExchangeResult built from the
            // cached access_token. Preserve the rotatedRefreshToken from
            // the original exchange so callers still see it (they may
            // have persisted it already, so re-emitting is a no-op).
            long remainingSeconds = Math.max(0,
                    existing.expiresAt().getEpochSecond() - Instant.now().getEpochSecond());
            return TokenExchangeResult.success(
                    existing.accessToken(), existing.rotatedRefreshToken(), remainingSeconds);
        }
        // Miss — fetch a fresh access_token. Single-flighted via compute()
        // so 24 concurrent bulk workers sharing the same refresh_token
        // serialise on the same map bin rather than fire parallel token
        // POSTs against Auctane. Returning null from the lambda removes
        // any stale entry (compute contract) — used on failure so a
        // transient Auctane outage doesn't leave a bad entry behind.
        //
        // We split into a boolean holder because compute() can only return
        // the cached value; the failure/success result the caller needs
        // is on the exchange response, not the cache entry.
        java.util.concurrent.atomic.AtomicReference<TokenExchangeResult> outcome =
                new java.util.concurrent.atomic.AtomicReference<>();
        tokenCache.compute(cacheKey, (k, cur) -> {
            if (cur != null && cur.isValid()) {
                long rem = Math.max(0,
                        cur.expiresAt().getEpochSecond() - Instant.now().getEpochSecond());
                outcome.set(TokenExchangeResult.success(
                        cur.accessToken(), cur.rotatedRefreshToken(), rem));
                return cur;
            }
            TokenExchangeResult fresh = postToken(env(environment), body -> {
                body.put("grant_type", "refresh_token");
                body.put("refresh_token", refreshToken);
                body.put("client_id", clientId);
                if (StringUtils.hasText(clientSecret)) {
                    body.put("client_secret", clientSecret);
                }
            });
            outcome.set(fresh);
            if (!fresh.success() || fresh.expiresInSeconds() <= 0) {
                // Don't cache failures, and don't cache a zero-TTL success
                // (safety — SERA should always provide expires_in, but if
                // it doesn't, caching indefinitely would be worse than
                // re-fetching).
                return null;
            }
            return new CachedAccessToken(
                    fresh.accessToken(),
                    fresh.refreshToken(),
                    Instant.now().plusSeconds(fresh.expiresInSeconds()));
        });
        return outcome.get();
    }

    /** Package-private for tests + operational hygiene. */
    void clearTokenCache() {
        tokenCache.clear();
    }

    private static String envKey(String environment) {
        return "SANDBOX".equalsIgnoreCase(environment) ? "SANDBOX" : "PRODUCTION";
    }

    /**
     * SHA-256 fingerprint of the refresh_token, hex-encoded. Used as
     * part of the cache key so we don't hold refresh_tokens (secrets) as
     * plaintext map keys. Deterministic — same input always hashes to
     * the same key.
     */
    private static String hashForKey(String refreshToken) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(refreshToken.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format(Locale.ROOT, "%02x", b));
            return sb.toString();
        } catch (Exception ex) {
            // SHA-256 is a JDK-required algorithm; this can't realistically
            // fail. Fall back to identityHashCode so the cache still works,
            // just with a wider key surface (an attacker who can enumerate
            // identityHashCodes to guess refresh_tokens has already lost).
            return "id-" + System.identityHashCode(refreshToken);
        }
    }

    // ===== implementation helpers =====

    private TokenExchangeResult postToken(String tokenUrl,
                                          java.util.function.Consumer<Map<String, String>> fillBody) {
        if (!StringUtils.hasText(tokenUrl)) {
            return TokenExchangeResult.failure("SERA token URL is not configured");
        }
        // SERA v1 token endpoint requires an application/json body per
        // developer.stamps.com/rest-api/reference/serav1.html#tag/qs_connect
        // (unusual — most OAuth2 servers take form-urlencoded). LinkedHashMap
        // preserves insertion order so grant_type shows up first in
        // troubleshooting captures.
        Map<String, String> body = new LinkedHashMap<>();
        fillBody.accept(body);
        try {
            String response = HttpClients.newBuilder().baseUrl(tokenUrl).build()
                    .post()
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            JsonNode json = objectMapper.readTree(Optional.ofNullable(response).orElse("{}"));
            String access = json.path("access_token").asText(null);
            String refresh = json.path("refresh_token").asText(null);
            long expiresIn = json.path("expires_in").asLong(0);
            if (!StringUtils.hasText(access)) {
                String err = json.path("error_description").asText(json.path("error").asText("no access_token"));
                return TokenExchangeResult.failure(err);
            }
            return TokenExchangeResult.success(access, refresh, expiresIn);
        } catch (RestClientResponseException ex) {
            String errorBody = ex.getResponseBodyAsString();
            String err = extractOAuthError(errorBody);
            log.warn("SERA token exchange rejected (HTTP {}): {}", ex.getStatusCode().value(), err);
            return TokenExchangeResult.failure("HTTP " + ex.getStatusCode().value()
                    + (StringUtils.hasText(err) ? ": " + err : ""));
        } catch (Exception ex) {
            log.warn("SERA token exchange call failed: {}", ex.getMessage());
            return TokenExchangeResult.failure("token exchange call failed: " + ex.getMessage());
        }
    }

    private String extractOAuthError(String body) {
        if (!StringUtils.hasText(body)) return null;
        try {
            JsonNode j = objectMapper.readTree(body);
            String desc = j.path("error_description").asText(null);
            if (StringUtils.hasText(desc)) return desc;
            return j.path("error").asText(null);
        } catch (Exception ignored) {
            return body.length() > 200 ? body.substring(0, 200) + "…" : body;
        }
    }

    private String env(String environment) {
        CarrierProperties.Stamps s = carrierProperties.getStamps();
        return isSandbox(environment) ? s.getSeraSandboxAuthUrl() : s.getSeraAuthUrl();
    }

    private boolean isSandbox(String environment) {
        return environment != null && environment.trim().equalsIgnoreCase("SANDBOX");
    }

    private String requireRedirectUri() {
        String uri = carrierProperties.getStamps().getSeraRedirectUri();
        if (!StringUtils.hasText(uri)) {
            throw new IllegalStateException("carrier.stamps.sera-redirect-uri is not configured");
        }
        return uri;
    }

    /** Build a signed state value: {@code base64url(accountId:timestamp:nonce:hmac)}. */
    String signState(Long accountId) {
        long ts = Instant.now().getEpochSecond();
        String nonce = Long.toHexString(new java.util.Random().nextLong());
        String payload = accountId + ":" + ts + ":" + nonce;
        String hmac = hmacHex(payload);
        String combined = payload + ":" + hmac;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(combined.getBytes(StandardCharsets.UTF_8));
    }

    private String hmacHex(String payload) {
        if (!StringUtils.hasText(stateSigningSecret)) {
            throw new IllegalStateException(
                    "SERA state signing key is unset (carrier.stamps.sera-state-signing-key OR SECRETS_ENCRYPTION_KEY)");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            byte[] keyBytes = decodeKey(stateSigningSecret);
            mac.init(new SecretKeySpec(keyBytes, "HmacSHA256"));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format(Locale.ROOT, "%02x", b));
            return sb.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to compute SERA state HMAC", ex);
        }
    }

    private static byte[] decodeKey(String maybeBase64) {
        try {
            return Base64.getDecoder().decode(maybeBase64);
        } catch (IllegalArgumentException notBase64) {
            return maybeBase64.getBytes(StandardCharsets.UTF_8);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(
                a.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8),
                b.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
    }

    // ===== result value =====

    /**
     * Result of an OAuth token exchange (either code-for-token or
     * refresh). {@link #success} distinguishes success from failure;
     * on failure {@link #errorMessage} carries the human-readable
     * cause. On success at least {@link #accessToken} is populated;
     * {@link #refreshToken} may be null when Auctane doesn't rotate
     * the refresh token.
     */
    public record TokenExchangeResult(boolean success, String accessToken,
                                       String refreshToken, long expiresInSeconds,
                                       String errorMessage) {
        public static TokenExchangeResult success(String access, String refresh, long expiresIn) {
            return new TokenExchangeResult(true, access, refresh, expiresIn, null);
        }

        public static TokenExchangeResult failure(String message) {
            return new TokenExchangeResult(false, null, null, 0, message);
        }
    }

    // silence unused-import checkers when refactoring
    static {
        Objects.requireNonNull(URLEncoder.class);
    }
}
