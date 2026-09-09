package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.model.CarrierAccountRef;
import com.multiship.backend.repository.CarrierAccountRefRepository;
import com.multiship.backend.service.carriers.StampsSeraOAuthService;
import com.multiship.backend.service.carriers.StampsSeraOAuthService.TokenExchangeResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
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
 * <p>Two endpoints:
 * <ul>
 *   <li>{@code GET /authorize/{accountId}} — ADMIN-only. Builds the signed-state
 *       authorize URL and 302s the operator's browser to
 *       {@code signin.stampsendicia.com/authorize?...}. The account
 *       row's clientId + environment drive the request.</li>
 *   <li>{@code GET /callback} — public (no Bearer token; the browser 302 from
 *       Auctane doesn't carry our JWT). Verifies the signed state, exchanges
 *       the code for tokens, persists the refresh_token (encrypted at rest)
 *       on the account, marks verified, then 302s back to a FE-side landing
 *       page.</li>
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
        description = "3-legged OAuth for Stamps.com SERA accounts (authorize + callback)")
@RestController
@RequestMapping("/api/v1/carrier-accounts/stamps-sera")
@RequiredArgsConstructor
public class StampsSeraOAuthController {

    /** FE-side landing page the browser lands on after the callback stores
     *  the refresh token. Query string carries {@code ok=1|0} and
     *  {@code detail=...} so the FE can render success/error inline. */
    private static final String FE_RETURN_PATH = "/settings/carriers?seraCallback=";

    private final CarrierAccountRefRepository repository;
    private final StampsSeraOAuthService oauthService;

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
     *   <li>State signature — HMAC-SHA256 over {@code accountId:ts:nonce}
     *       ties the callback to a specific account row and prevents
     *       CSRF replay.</li>
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
        Optional<Long> accountIdOpt = oauthService.verifyState(state);
        if (accountIdOpt.isEmpty()) {
            return redirect(false, "state signature invalid or expired");
        }
        if (!StringUtils.hasText(code)) {
            return redirect(false, "no authorization code returned");
        }
        Long accountId = accountIdOpt.get();
        CarrierAccountRef account = repository.findById(accountId).orElse(null);
        if (account == null) {
            log.warn("SERA OAuth callback: state valid but account #{} no longer exists.", accountId);
            return redirect(false, "carrier account no longer exists");
        }

        TokenExchangeResult result = oauthService.exchangeCode(code, account.getClientId(),
                account.getClientSecret(), account.getEnvironment());
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

    private ResponseEntity<Void> redirect(boolean ok, String detail) {
        String url = FE_RETURN_PATH + (ok ? "ok" : "error");
        if (!ok && StringUtils.hasText(detail)) {
            url += "&detail=" + URLEncoder.encode(detail, StandardCharsets.UTF_8);
        }
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(url)).build();
    }

    /** Small DTO for the status endpoint. */
    public record AuthStatus(boolean authorized, LocalDateTime lastVerifiedAt) {}
}
