package com.multiship.backend.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;

/**
 * A {@link CookieCsrfTokenRepository} that stops re-issuing the token to a
 * browser that already has one.
 *
 * <p><b>Why this exists.</b> Spring rotates the CSRF token every time a
 * request authenticates: {@code CsrfAuthenticationStrategy} deletes the old
 * cookie and issues a replacement. That's correct for form-login, where a
 * user authenticates once per session. This API is STATELESS and the JWT
 * filter authenticates on <em>every</em> request, so the token was being
 * regenerated on each call. The SPA fires several requests in parallel, and
 * any request built from a token that a concurrent response had already
 * rotated came back 403 — writes failed intermittently and unpredictably.
 *
 * <p>Configuring a different {@code sessionAuthenticationStrategy} does not
 * help: {@code CsrfConfigurer} <em>appends</em> its rotation strategy to
 * whatever is configured, so it always runs. Making the repository itself
 * idempotent is the reliable place to break the cycle.
 *
 * <p>Behaviour: the first request from a browser with no {@code XSRF-TOKEN}
 * cookie gets one issued; after that the value is left alone — the deletes
 * and re-issues from rotation become no-ops. Security is unchanged, because
 * double-submit only requires that the cookie and the {@code X-XSRF-TOKEN}
 * header match and that the value is unguessable by another origin.
 */
public class StableCsrfTokenRepository implements CsrfTokenRepository {

    private final CookieCsrfTokenRepository delegate;

    public StableCsrfTokenRepository(CookieCsrfTokenRepository delegate) {
        this.delegate = delegate;
    }

    @Override
    public CsrfToken generateToken(HttpServletRequest request) {
        // Reuse the browser's current token when it has one, so a rotation
        // "generates" the value the client is already holding.
        CsrfToken existing = delegate.loadToken(request);
        return existing != null ? existing : delegate.generateToken(request);
    }

    @Override
    public void saveToken(CsrfToken token, HttpServletRequest request, HttpServletResponse response) {
        if (delegate.loadToken(request) != null) {
            // The request already carried a token: swallow both the
            // rotation's delete (token == null) and any re-issue so the
            // cookie the client holds stays valid.
            return;
        }
        delegate.saveToken(token, request, response);
    }

    @Override
    public CsrfToken loadToken(HttpServletRequest request) {
        return delegate.loadToken(request);
    }
}
