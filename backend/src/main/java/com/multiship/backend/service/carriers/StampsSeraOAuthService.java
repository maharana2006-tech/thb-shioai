package com.multiship.backend.service.carriers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.config.CarrierProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.security.SecureRandom;
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
 * {@code accountId:timestamp:nonce:verifier} using {@link #stateSigningSecret}
 * prevents an attacker from forging a callback that would attach their
 * consent-code to somebody else's carrier account. The state carries
 * enough context for the callback to skip a DB lookup on the client_id
 * and instead resolve the account by id, and ALSO carries the PKCE
 * {@code code_verifier} so the exchangeCode call can present it without
 * a server-side session store.
 *
 * <p>PKCE (RFC 7636): {@link #buildAuthorizeUrl} generates a 32-byte
 * SecureRandom verifier (base64url, no padding), computes the SHA-256
 * challenge, and sends {@code code_challenge} + {@code code_challenge_method=S256}
 * on the authorize URL. {@link #exchangeCode} presents the matching
 * verifier back when redeeming the code. An intercepted auth code can't
 * be redeemed without the verifier.
 */
@Slf4j
@Service
public class StampsSeraOAuthService {

    /** State TTL — 10 minutes. Long enough for the operator to complete
     *  consent (usually seconds), short enough that a leaked state can't
     *  be replayed forever. */
    private static final long STATE_TTL_SECONDS = 600;

    /** SecureRandom instance used to seed the state nonce + the PKCE
     *  code_verifier. Both of these are rolled on every authorize call —
     *  a predictable nonce lets an attacker brute-force a replay, a
     *  predictable verifier breaks the PKCE guarantee. */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /** PKCE verifier length (bytes before base64url). RFC 7636 §4.1 allows
     *  43–128 chars for the base64 output; 32 bytes → ~43 chars which hits
     *  the lower bound and keeps the authorize URL short. */
    private static final int PKCE_VERIFIER_BYTE_LENGTH = 32;

    private final CarrierProperties carrierProperties;
    private final ObjectMapper objectMapper;
    /** ObjectProvider so unit tests without a MeterRegistry still boot. */
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;

    /** Base64-encoded HMAC-SHA256 key used to sign the OAuth state.
     *  Falls back to the SECRETS_ENCRYPTION_KEY (already required for
     *  refresh-token persistence) so a fresh dev environment doesn't
     *  need an extra config knob; production should override with a
     *  dedicated key to keep signing + encryption domains separate. */
    @Value("${carrier.stamps.sera-state-signing-key:${secrets.encryption-key:}}")
    private String stateSigningSecret;

    /** Active Spring profile(s). Checked at startup for the localhost
     *  redirect-URI guard — see {@link #logStartupGuards}. */
    @Value("${spring.profiles.active:}")
    private String activeProfiles;

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

    /** Prometheus counter for cache hits. Populated iff MeterRegistry is on
     *  the classpath and injected; null-safe-called elsewhere. */
    private Counter cacheHitCounter;

    /** Refresh a cached access-token this many seconds before its declared
     *  expiry so an in-flight worker doesn't submit against a token that
     *  expires mid-request. */
    private static final long TOKEN_REFRESH_MARGIN_SECONDS = 60;

    @Autowired
    public StampsSeraOAuthService(CarrierProperties carrierProperties,
                                  ObjectMapper objectMapper,
                                  ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.carrierProperties = carrierProperties;
        this.objectMapper = objectMapper;
        this.meterRegistryProvider = meterRegistryProvider;
    }

    /**
     * Overload for unit tests that don't wire a MeterRegistry provider.
     * Delegates to the real constructor with a no-op provider so metrics
     * are simply disabled rather than NPE'ing on first call.
     */
    public StampsSeraOAuthService(CarrierProperties carrierProperties, ObjectMapper objectMapper) {
        this(carrierProperties, objectMapper, new ObjectProvider<MeterRegistry>() {
            @Override public MeterRegistry getObject(Object... args) { return null; }
            @Override public MeterRegistry getObject() { return null; }
            @Override public MeterRegistry getIfAvailable() { return null; }
            @Override public MeterRegistry getIfUnique() { return null; }
        });
    }

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
     * Result of {@link #verifyStateWithVerifier} — the embedded accountId
     * from the signed state plus the PKCE {@code code_verifier} the
     * authorize call tucked in. Callers pass the verifier back to
     * {@link #exchangeCode}. Nullable verifier means the state came from
     * an older authorize call that didn't include PKCE — exchangeCode
     * degrades gracefully to the non-PKCE path.
     */
    public record VerifiedState(Long accountId, String codeVerifier) {}

    /**
     * Startup guards. Logs a WARN when the configured redirect-URI points
     * at localhost in a prod profile — Stamps.com will reject the
     * authorize callback because loopback addresses can't be registered
     * on a production client_id. Non-fatal; the system still starts.
     * Also wires the Prometheus gauges + counter when a MeterRegistry
     * is available.
     */
    @PostConstruct
    void logStartupGuards() {
        String redirectUri = carrierProperties.getStamps().getSeraRedirectUri();
        boolean prod = isProdProfile(activeProfiles);
        if (prod && StringUtils.hasText(redirectUri)
                && (redirectUri.contains("localhost") || redirectUri.contains("127.0.0.1"))) {
            log.warn("SERA redirect-URI points at localhost in a prod profile — "
                    + "Stamps.com will reject the authorize callback. "
                    + "Set carrier.stamps.sera-redirect-uri to a routable https URL. (configured: {})",
                    redirectUri);
        }
        MeterRegistry registry = meterRegistryProvider.getIfAvailable();
        if (registry != null) {
            Gauge.builder("stamps_sera_token_cache_size", tokenCache, Map::size)
                    .description("Size of the Stamps.com SERA access-token cache (keys = SHA-256(refresh_token)|env)")
                    .register(registry);
            this.cacheHitCounter = Counter.builder("stamps_sera_token_cache_hits_total")
                    .description("Count of Stamps.com SERA access-token cache HITs (serviced without a token POST to Auctane)")
                    .register(registry);
        } else {
            log.debug("MeterRegistry not available — SERA token-cache metrics disabled.");
        }
    }

    private static boolean isProdProfile(String profiles) {
        if (!StringUtils.hasText(profiles)) return false;
        for (String p : profiles.split(",")) {
            if ("prod".equalsIgnoreCase(p.trim()) || "production".equalsIgnoreCase(p.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Build the fully-formed authorize URL the frontend should redirect
     * the operator to. The {@code state} field carries a signed
     * account-id + timestamp + nonce + PKCE verifier; the callback
     * verifies it before accepting the {@code code}.
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
        String codeVerifier = generatePkceVerifier();
        String codeChallenge = computePkceChallenge(codeVerifier);
        String state = signState(accountId, codeVerifier);
        return UriComponentsBuilder.fromUriString(authorizeUrl)
                .queryParam("response_type", "code")
                .queryParam("client_id", clientId)
                .queryParam("redirect_uri", redirectUri)
                .queryParam("scope", scope)
                .queryParam("state", state)
                .queryParam("code_challenge", codeChallenge)
                .queryParam("code_challenge_method", "S256")
                .build(true)
                .toUriString();
    }

    /**
     * Verify a state value received on the OAuth callback. Returns the
     * embedded accountId, or empty when the signature doesn't verify
     * OR the state is older than {@link #STATE_TTL_SECONDS}.
     *
     * <p>Legacy signature — kept for callers that only need the accountId.
     * The PKCE-aware callback should use {@link #verifyStateWithVerifier}.
     */
    public Optional<Long> verifyState(String state) {
        return verifyStateWithVerifier(state).map(VerifiedState::accountId);
    }

    /**
     * Verify a state value and extract both the accountId AND the PKCE
     * code_verifier the authorize call embedded. Returns empty when the
     * signature doesn't verify, the state is older than
     * {@link #STATE_TTL_SECONDS}, or the payload is malformed.
     *
     * <p>Payload format: {@code accountId:ts:nonce[:verifier]:hmac} —
     * verifier is the 4th segment, which keeps the legacy 3-segment
     * payloads (no PKCE) decoding cleanly as a {@link VerifiedState} with
     * null codeVerifier.
     */
    public Optional<VerifiedState> verifyStateWithVerifier(String state) {
        if (!StringUtils.hasText(state)) return Optional.empty();
        try {
            byte[] raw = Base64.getUrlDecoder().decode(state);
            String decoded = new String(raw, StandardCharsets.UTF_8);
            // Payload format: <accountId>:<epochSecond>:<nonce>[:<verifier>]:<hexHmac>
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
            Long accountId = Long.parseLong(parts[0]);
            String verifier = parts.length >= 4 ? parts[3] : null;
            return Optional.of(new VerifiedState(accountId, verifier));
        } catch (Exception ex) {
            log.warn("SERA OAuth callback: state parse failed — rejecting. Reason: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Exchange an authorization {@code code} for an access + refresh
     * token pair. Called from the callback controller after state
     * verification passes. PKCE-aware overload: pass the
     * {@code code_verifier} extracted from the signed state.
     */
    public TokenExchangeResult exchangeCode(String code, String clientId, String clientSecret,
                                            String environment, String codeVerifier) {
        return postToken(env(environment), body -> {
            body.put("grant_type", "authorization_code");
            body.put("code", code);
            body.put("redirect_uri", requireRedirectUri());
            body.put("client_id", clientId);
            if (StringUtils.hasText(clientSecret)) {
                body.put("client_secret", clientSecret);
            }
            if (StringUtils.hasText(codeVerifier)) {
                body.put("code_verifier", codeVerifier);
            }
        });
    }

    /**
     * Legacy signature — PKCE verifier omitted. Kept for existing tests +
     * any caller that bypassed verifyStateWithVerifier.
     */
    public TokenExchangeResult exchangeCode(String code, String clientId, String clientSecret,
                                            String environment) {
        return exchangeCode(code, clientId, clientSecret, environment, null);
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
            if (cacheHitCounter != null) cacheHitCounter.increment();
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
                if (cacheHitCounter != null) cacheHitCounter.increment();
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

    /** Public for the disconnect endpoint + tests + operational hygiene. */
    public void clearTokenCache() {
        tokenCache.clear();
    }

    /**
     * Evict a single refresh_token's cached access_tokens across all envs.
     * The map is keyed by {@code SHA-256(refresh_token)|env} so we remove
     * both sandbox + prod entries. Called from disconnect / rotation.
     */
    public void clearCachedTokenFor(String refreshToken) {
        if (!StringUtils.hasText(refreshToken)) return;
        String hash = hashForKey(refreshToken);
        tokenCache.remove(hash + "|PRODUCTION");
        tokenCache.remove(hash + "|SANDBOX");
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

    /** Transient-retry backoffs for {@link #postToken}: three total
     *  attempts, 300ms then 1s between them. Covers the 'Remote host
     *  terminated the handshake' / connect-reset blips Stamps.com's
     *  Auth0-fronted sandbox occasionally throws without warning. */
    private static final long[] TOKEN_RETRY_BACKOFF_MS = {300L, 1000L};

    private TokenExchangeResult postToken(String tokenUrl,
                                          java.util.function.Consumer<Map<String, String>> fillBody) {
        if (!StringUtils.hasText(tokenUrl)) {
            return TokenExchangeResult.failure("SERA token URL is not configured");
        }
        TokenExchangeResult last = null;
        for (int attempt = 0; attempt <= TOKEN_RETRY_BACKOFF_MS.length; attempt++) {
            last = postTokenOnce(tokenUrl, fillBody);
            // Succeeded OR failed permanently — short-circuit. "Permanent"
            // = HTTP 4xx (invalid_grant / unsupported_grant_type / wrong
            // credentials); the detector below targets ONLY the transient
            // network family (IOException, connection reset, TLS handshake
            // failures) that benefit from retry.
            if (last.success() || !isTransientNetworkFailure(last.errorMessage())) {
                return last;
            }
            if (attempt < TOKEN_RETRY_BACKOFF_MS.length) {
                long sleep = TOKEN_RETRY_BACKOFF_MS[attempt];
                log.warn("SERA token exchange transient failure (attempt {}/{}): {} — retrying in {}ms",
                        attempt + 1, TOKEN_RETRY_BACKOFF_MS.length + 1,
                        last.errorMessage(), sleep);
                try { Thread.sleep(sleep); }
                catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return TokenExchangeResult.failure(
                            "token exchange interrupted during retry backoff");
                }
            }
        }
        // All attempts exhausted on transient failure.
        log.warn("SERA token exchange failed after {} attempts: {}",
                TOKEN_RETRY_BACKOFF_MS.length + 1,
                last == null ? "unknown" : last.errorMessage());
        return TokenExchangeResult.failure(
                "SERA token endpoint unreachable after " + (TOKEN_RETRY_BACKOFF_MS.length + 1)
                        + " attempts (" + (last == null ? "unknown" : last.errorMessage())
                        + "). Likely a transient Stamps.com/Auctane blip; please retry.");
    }

    /** Single attempt — kept as the inner retry unit. */
    private TokenExchangeResult postTokenOnce(String tokenUrl,
                                               java.util.function.Consumer<Map<String, String>> fillBody) {
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

    /** Signatures of transient network-side failures that benefit from
     *  retry. HTTP 4xx (OAuth-rejected credentials) + explicit "no
     *  access_token" responses are NOT transient; they'd fail the same
     *  way next attempt and we must not loop on them. */
    static boolean isTransientNetworkFailure(String errorMessage) {
        if (errorMessage == null) return false;
        String m = errorMessage.toLowerCase(Locale.ROOT);
        return m.contains("i/o error")
                || m.contains("remote host terminated")
                || m.contains("connection reset")
                || m.contains("connection refused")
                || m.contains("timed out")
                || m.contains("timeout")
                || m.contains("sslhandshake")
                || m.contains("unknownhost")
                || m.contains("call failed:"); // generic wrapper from the catch above
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

    /** Legacy 3-segment signState (no PKCE verifier) — kept for tests that
     *  predate the PKCE wire-up. New flow uses {@link #signState(Long, String)}. */
    String signState(Long accountId) {
        return signState(accountId, null);
    }

    /**
     * Build a signed state value:
     * {@code base64url(accountId:timestamp:nonce[:verifier]:hmac)}.
     * Nonce is drawn from {@link SecureRandom} — a predictable nonce lets
     * an attacker brute-force a replay of a captured state.
     */
    String signState(Long accountId, String codeVerifier) {
        long ts = Instant.now().getEpochSecond();
        String nonce = Long.toHexString(SECURE_RANDOM.nextLong());
        String payload = accountId + ":" + ts + ":" + nonce;
        if (StringUtils.hasText(codeVerifier)) {
            payload = payload + ":" + codeVerifier;
        }
        String hmac = hmacHex(payload);
        String combined = payload + ":" + hmac;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(combined.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Generate a PKCE {@code code_verifier} per RFC 7636 §4.1 — 32
     * SecureRandom bytes, base64url-encoded with no padding.
     */
    static String generatePkceVerifier() {
        byte[] bytes = new byte[PKCE_VERIFIER_BYTE_LENGTH];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Compute the PKCE {@code code_challenge} per RFC 7636 §4.2 —
     * {@code base64url(SHA-256(code_verifier))} with no padding.
     */
    static String computePkceChallenge(String codeVerifier) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception ex) {
            // SHA-256 is a JDK-required algorithm; this can't realistically fail.
            throw new IllegalStateException("Failed to compute PKCE code_challenge", ex);
        }
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
