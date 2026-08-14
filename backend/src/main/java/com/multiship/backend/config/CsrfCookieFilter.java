package com.multiship.backend.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Forces the deferred CSRF token to materialize so the double-submit cookie
 * actually reaches the browser.
 *
 * <p>Spring Security 6 made {@code CsrfToken} lookup lazy: {@code CsrfFilter}
 * stores a {@code DeferredCsrfToken} on the request, and the repository only
 * writes its {@code XSRF-TOKEN} cookie when something calls
 * {@link CsrfToken#getToken()}. A server-rendered app resolves it while
 * rendering a hidden form field — but this backend only serves JSON, so
 * nothing ever touched the token. The cookie was never issued, the SPA had
 * nothing to echo in {@code X-XSRF-TOKEN}, and every state-changing request
 * (POST / PUT / PATCH / DELETE) was rejected 403 even for a fully
 * authenticated ADMIN.
 *
 * <p>Reading the token here is the fix documented by Spring Security for
 * exactly this SPA case. It runs on every request, including GETs, so the
 * browser picks the cookie up on the first page load and holds a valid token
 * before the operator's first write.
 */
public class CsrfCookieFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Object attribute = request.getAttribute(CsrfToken.class.getName());
        if (attribute instanceof CsrfToken token) {
            // Touching the value renders the deferred token, which triggers
            // CookieCsrfTokenRepository.saveToken(...) → Set-Cookie XSRF-TOKEN.
            token.getToken();
        }
        filterChain.doFilter(request, response);
    }
}
