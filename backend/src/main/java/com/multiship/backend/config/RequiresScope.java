package com.multiship.backend.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Sprint 50 Tier 0.5 PR B — declares the ApiKey scope required to invoke
 * a controller method.
 *
 * <p>Enforced by {@link ApiKeyScopeInterceptor}. Requests authenticated
 * via an ApiKey (ROLE_API) whose granted scopes don't include the
 * required one get a **403 with a machine-readable body** naming the
 * required + granted scopes so the integrator can diagnose without
 * a support ticket:
 *
 * <pre>
 * { "status":"error", "code":403, "errorCode":"INSUFFICIENT_SCOPE",
 *   "message":"API key is missing the 'shipments' scope.",
 *   "requiredScope":"shipments", "grantedScopes":["rates","tracking"] }
 * </pre>
 *
 * <p>Non-ApiKey callers (JWT with ROLE_ADMIN / ROLE_USER / ROLE_TENANT)
 * bypass this check — scope enforcement is an API-key concept, not an
 * operator-role concept. Role-based auth via {@code @PreAuthorize} still
 * runs first.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresScope {

    ApiKeyScope value();
}
