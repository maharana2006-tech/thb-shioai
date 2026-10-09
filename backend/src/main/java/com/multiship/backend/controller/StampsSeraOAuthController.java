package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.model.CarrierAccountRef;
import com.multiship.backend.repository.CarrierAccountRefRepository;
import com.multiship.backend.service.carriers.StampsSeraOAuthService;
import com.multiship.backend.service.carriers.StampsSeraOAuthService.TokenExchangeResult;
import com.multiship.backend.service.carriers.StampsSeraOAuthService.VerifiedState;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Stamps.com SERA 3-legged OAuth (authorization_code + refresh_token).
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET /authorize/{accountId}} — ADMIN-only. Builds the signed-state
 *       authorize URL (with PKCE S256 challenge) and 302s the operator's
 *       browser to {@code signin.stampsendicia.com/authorize?...}. The account
 *       row's clientId + environment drive the request.</li>
 *   <li>{@code GET /callback} — public (no Bearer token; the browser 302 from
 *       Auctane doesn't carry our JWT). Verifies the signed state, extracts
 *       the PKCE verifier from state, exchanges the code + verifier for tokens,
 *       persists the refresh_token (encrypted at rest) on the account, marks
 *       verified, then 302s back to a FE-side landing page.</li>
 *   <li>{@code DELETE /authorize/{accountId}} — ADMIN-only. Disconnect: nulls
 *       the stored refresh_token, marks the account unverified, evicts the
 *       token cache. Returns 204 on success / 404 when the account is unknown.</li>
 * </ul>
 *
 * <p>Why 3-legged: Stamps.com developer accounts are provisioned for
 * {@code authorization_code}, NOT {@code client_credentials}. The previous
 * verify-credentials flow used the wrong grant and Auctane returned
 * {@code unsupported_grant_type}. This controller replaces that with the
 * browser-consent flow.
 */
@Slf4j
@Tag(name = "Stamps SERA OAuth",
        description = "3-legged OAuth for Stamps.com SERA accounts (authorize + callback + disconnect)")
@RestController
@RequestMapping("/api/v1/carrier-accounts/stamps-sera")
@RequiredArgsConstructor
public class StampsSeraOAuthController {

    /** FE-side landing page the browser lands on after the callback stores
     *  the refresh token. Query string carries {@code ok=1|0} and
     *  {@code detail=...} so the FE can render success/error inline.
     *
     *  <p>Relative path works in prod where FE + backend share an origin
     *  (app.company.com + api.company.com behind one gateway, say). In
     *  split-port dev (SPA on :5173, API on :8080), set the absolute
     *  prefix via {@code carrier.stamps.sera-fe-return-url} so the
     *  browser lands on the FE host instead of asking the backend for
     *  {@code /settings/carriers} (which it doesn't serve → 401 / 404). */
    private static final String FE_RETURN_PATH = "/settings/carriers?seraCallback=";

    @org.springframework.beans.factory.annotation.Value("${carrier.stamps.sera-fe-return-url:}")
    private String feReturnUrl;

    private final CarrierAccountRefRepository repository;
    private final StampsSeraOAuthService oauthService;
    private final com.multiship.backend.service.carriers.StampsConnector stampsConnector;

    /**
     * Kick off the SERA authorize flow for the given account. Only
     * ADMINs may trigger — same authority as adding or verifying an
     * account. Returns a 302 to the SERA authorize endpoint.
     */
    @Operation(summary = "Redirect the operator's browser to Stamps.com SERA authorize",
            description = "Requires ADMIN. Builds a signed-state authorize URL for the given account "
                    + "and returns 302 to signin.stampsendicia.com/authorize. On operator consent the "
                    + "SERA server 302s back to /callback which persists the refresh token.")
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/authorize/{accountId}")
    public ResponseEntity<Void> authorize(@PathVariable Long accountId) {
        CarrierAccountRef account = repository.findById(accountId).orElse(null);
        if (account == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        if (!"USPS".equalsIgnoreCase(account.getCarrierCode())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        if (!StringUtils.hasText(account.getClientId())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        String url = oauthService.buildAuthorizeUrl(accountId, account.getClientId(), account.getEnvironment());
        // Log the full authorize URL (host + params) so we can diagnose
        // Stamps.com's "Oops! Something went wrong" response. Common
        // causes: (a) wrong sandbox host — signin.testing.stampsendicia
        // .com may not exist for some tenant classes; (b) redirect_uri
        // not registered on the client_id in the developer portal.
        // client_id is masked in production logs elsewhere but exposed
        // here at INFO so ops can compare against the developer portal.
        log.info("SERA authorize: redirecting operator for account #{} (env={}) → {}",
                accountId, account.getEnvironment(), url);
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(url)).build();
    }

    /**
     * Callback endpoint the Stamps.com server 302s the operator's browser
     * to after consent. Not authenticated — a redirect from an external
     * server can't carry our JWT cookie/header. Security relies on:
     * <ul>
     *   <li>State signature — HMAC-SHA256 over {@code accountId:ts:nonce:verifier}
     *       ties the callback to a specific account row and prevents
     *       CSRF replay. The PKCE verifier is carried in the signed state
     *       (segment 4) so the callback can present it without a DB round-trip.</li>
     *   <li>PKCE S256 — the authorize URL carried {@code code_challenge =
     *       base64url(SHA-256(verifier))}; here we present the matching
     *       {@code code_verifier} alongside the code. An intercepted code
     *       alone can't be redeemed.</li>
     *   <li>Redirect-URI whitelist — Stamps.com only 302s here if the
     *       operator's developer portal has this URL registered on the
     *       client_id.</li>
     *   <li>Code single-use — Auctane invalidates the code after one
     *       exchange (RFC 6749 §4.1.2 requirement).</li>
     * </ul>
     */
    @Operation(summary = "OAuth callback for Stamps.com SERA — persists the refresh token",
            description = "Public endpoint (redirect from stampsendicia.com; no Bearer token). "
                    + "Verifies the signed state, exchanges the code for access + refresh tokens, "
                    + "persists the refresh_token (encrypted) on the account, and 302s back to a "
                    + "FE landing page with ?seraCallback=ok or ?seraCallback=error&detail=...")
    @GetMapping("/callback")
    public ResponseEntity<Void> callback(
            @RequestParam(value = "code", required = false) String code,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "error", required = false) String error,
            @RequestParam(value = "error_description", required = false) String errorDescription) {

        if (StringUtils.hasText(error)) {
            log.warn("SERA OAuth callback: server returned error={}, description={}", error, errorDescription);
            return redirect(false, "carrier returned " + error
                    + (StringUtils.hasText(errorDescription) ? ": " + errorDescription : ""));
        }
        Optional<VerifiedState> verifiedOpt = oauthService.verifyStateWithVerifier(state);
        if (verifiedOpt.isEmpty()) {
            return redirect(false, "state signature invalid or expired");
        }
        if (!StringUtils.hasText(code)) {
            return redirect(false, "no authorization code returned");
        }
        VerifiedState verified = verifiedOpt.get();
        Long accountId = verified.accountId();
        CarrierAccountRef account = repository.findById(accountId).orElse(null);
        if (account == null) {
            log.warn("SERA OAuth callback: state valid but account #{} no longer exists.", accountId);
            return redirect(false, "carrier account no longer exists");
        }

        TokenExchangeResult result = oauthService.exchangeCode(code, account.getClientId(),
                account.getClientSecret(), account.getEnvironment(), verified.codeVerifier());
        if (!result.success()) {
            log.warn("SERA OAuth callback: code exchange failed for account #{}: {}",
                    accountId, result.errorMessage());
            return redirect(false, result.errorMessage());
        }
        if (!StringUtils.hasText(result.refreshToken())) {
            log.warn("SERA OAuth callback: exchange succeeded but no refresh_token returned. "
                    + "Check that scope=offline_access is registered on the client_id.");
            return redirect(false, "SERA returned no refresh_token — scope offline_access missing");
        }

        account.setStampsRefreshToken(result.refreshToken());
        account.setVerified(true);
        account.setLastVerifiedAt(LocalDateTime.now());
        repository.save(account);
        log.info("SERA OAuth callback: refresh_token stored and account #{} marked verified.", accountId);
        return redirect(true, null);
    }

    /**
     * Small helper to admins: is 3-legged OAuth wired for this account
     * yet (i.e. does it have a refresh_token stored)? Returned on the
     * carrier account row for the FE to show "connect / reconnect".
     */
    @Operation(summary = "Has the account completed the SERA authorize flow yet?")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping("/status/{accountId}")
    public ResponseEntity<ApiResponse<AuthStatus>> status(@PathVariable Long accountId) {
        CarrierAccountRef account = repository.findById(accountId).orElse(null);
        if (account == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        boolean has = StringUtils.hasText(account.getStampsRefreshToken());
        return ResponseEntity.ok(ApiResponse.<AuthStatus>builder()
                .code(HttpStatus.OK.value())
                .message(has ? "SERA account is authorized." : "SERA account needs authorization.")
                .data(new AuthStatus(has, account.getLastVerifiedAt()))
                .build());
    }

    /**
     * Disconnect: nulls the stored refresh_token, marks the account
     * unverified, and evicts any cached access_token so a stale copy
     * doesn't keep working until it expires. ADMIN-only — same bar as
     * connecting. 204 on success, 404 when the account is unknown.
     */
    @Operation(summary = "Disconnect: clear refresh_token + unverify + evict cache",
            description = "Requires ADMIN. Clears the SERA refresh_token on the account, "
                    + "flips verified=false, and clears the OAuth access-token cache so a "
                    + "cached token doesn't keep working. Reconnect via GET /authorize/{id}.")
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/authorize/{accountId}")
    public ResponseEntity<Void> disconnect(@PathVariable Long accountId) {
        CarrierAccountRef account = repository.findById(accountId).orElse(null);
        if (account == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        String oldRefresh = account.getStampsRefreshToken();
        account.setStampsRefreshToken(null);
        account.setVerified(false);
        repository.save(account);
        // ponytail: nukes the whole cache rather than one entry — SERA refresh_tokens
        // are per-account so blast radius is tiny (every subsequent call just mints
        // a fresh access_token). Swap to clearCachedTokenFor(oldRefresh) if the cache
        // ever grows cross-account.
        if (StringUtils.hasText(oldRefresh)) {
            oauthService.clearTokenCache();
        }
        log.info("SERA OAuth: disconnected account #{} (refresh_token cleared, verified=false).", accountId);
        return ResponseEntity.noContent().build();
    }

    /**
     * One-off manual top-up of a SERA account's postage balance. Posts
     * {@code POST /sera/v1/balance/add-funds} with the authenticated
     * account's refresh_token → access_token round-trip. ADMIN-only.
     *
     * <p>Sandbox play-money accounts run out fast under test load (one
     * US→AU heavy intl label is $200+); the scheduled
     * {@link com.multiship.backend.service.carriers.StampsTopupService}
     * polls at 30 min and only fires when a {@code stamps_topup_policy}
     * row exists. This endpoint is the human-driven alternative — one
     * click adds the requested amount now.
     *
     * <p>Idempotency key: {@code (accountId, yyyy-MM-dd-HH-mm)} so a
     * fast double-click doesn't double-charge; a second POST in the
     * same minute returns the cached top-up (SERA dedups on key for
     * 24h). Different minute = fresh top-up allowed.
     */
    @Operation(summary = "Manual top-up of a SERA account's postage balance",
            description = "Requires ADMIN. Dials POST /sera/v1/balance/add-funds "
                    + "with the given amount. Idempotent within the same minute. "
                    + "Returns the post-topup balance.")
    @PreAuthorize("hasRole('ADMIN')")
    @org.springframework.web.bind.annotation.PostMapping("/add-funds/{accountId}")
    public ResponseEntity<ApiResponse<com.multiship.backend.service.carriers.CarrierConnector.BalanceResult>>
            addFunds(@PathVariable Long accountId,
                     @org.springframework.web.bind.annotation.RequestBody AddFundsRequest req) {
        CarrierAccountRef account = repository.findById(accountId).orElse(null);
        if (account == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        if (!"USPS".equalsIgnoreCase(account.getCarrierCode())
                && !"STAMPS".equalsIgnoreCase(account.getCarrierCode())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        if (req == null || req.amount == null || req.amount.signum() <= 0) {
            return ResponseEntity.badRequest().build();
        }
        String refresh = account.getStampsRefreshToken();
        if (!StringUtils.hasText(refresh)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiResponse.<com.multiship.backend.service.carriers.CarrierConnector.BalanceResult>builder()
                            .status("error").code(409)
                            .message("Account not authorized with SERA — complete /authorize/{id} first.")
                            .build());
        }
        StampsSeraOAuthService.TokenExchangeResult tok = oauthService.refreshToken(
                refresh, account.getClientId(), account.getClientSecret(), account.getEnvironment());
        if (!tok.success()) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(ApiResponse.<com.multiship.backend.service.carriers.CarrierConnector.BalanceResult>builder()
                            .status("error").code(502)
                            .message("SERA auth failed: " + tok.errorMessage())
                            .build());
        }
        String currency = StringUtils.hasText(req.currency) ? req.currency.trim().toLowerCase() : "usd";
        String idemKey = com.multiship.backend.service.carriers.usps.queue.IdempotencyKeys
                .forStampsTopup(accountId,
                        java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)
                                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm")));
        com.multiship.backend.service.carriers.CarrierConnector.BalanceResult result =
                stampsConnector.addFundsSera(tok.accessToken(), req.amount, currency, idemKey,
                        account.getEnvironment());
        log.info("SERA add-funds: account #{} env={} +{} {} → status={}", accountId,
                account.getEnvironment(), req.amount, currency, result.status());
        HttpStatus status = "OK".equals(result.status()) || "SUCCESS".equals(result.status())
                ? HttpStatus.OK : HttpStatus.BAD_GATEWAY;
        return ResponseEntity.status(status)
                .body(ApiResponse.<com.multiship.backend.service.carriers.CarrierConnector.BalanceResult>builder()
                        .code(status.value())
                        .status("OK".equals(result.status()) ? "ok" : "error")
                        .message(result.message())
                        .data(result)
                        .build());
    }

    /** Admin add-funds request body. */
    public record AddFundsRequest(java.math.BigDecimal amount, String currency) {}

    private ResponseEntity<Void> redirect(boolean ok, String detail) {
        // Prefer the configured absolute FE host (split-port dev); fall back
        // to the relative path (shared-origin prod).
        String base = StringUtils.hasText(feReturnUrl)
                ? feReturnUrl.replaceFirst("/+$", "") + FE_RETURN_PATH
                : FE_RETURN_PATH;
        String url = base + (ok ? "ok" : "error");
        if (!ok && StringUtils.hasText(detail)) {
            url += "&detail=" + URLEncoder.encode(detail, StandardCharsets.UTF_8);
        }
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(url)).build();
    }

    /** Small DTO for the status endpoint. */
    public record AuthStatus(boolean authorized, LocalDateTime lastVerifiedAt) {}
}
