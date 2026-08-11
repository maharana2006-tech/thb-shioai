package com.multiship.backend.config;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Sprint 50 Tier 0.5 PR B — canonical vocabulary for ApiKey scopes.
 *
 * <p>Prior to this PR {@code ApiKeyPrincipal.hasScope(String)} existed
 * ({@code ApiKeyPrincipal.java:37}) but had ZERO call sites — an API key
 * issued with {@code scopes="shipments"} could still hit {@code /rates},
 * {@code /tracking}, {@code /void}. The scope enum + {@link RequiresScope}
 * annotation + {@link ApiKeyScopeInterceptor} close the gap.
 *
 * <p>Token names match the whitespace-separated strings persisted in
 * {@code ApiKey.scopes} (see {@code ApiKeyService.DEFAULT_SCOPES}) so the
 * existing mint flow keeps working — the enforcement layer just adds a
 * check the persisted vocabulary already anticipated.
 */
public enum ApiKeyScope {

    /** POST /shipments, POST /rate-shop (a shipment always incurs a shipment cost). */
    SHIPMENTS("shipments"),

    /** POST /rates — quote only, no shipment. */
    RATES("rates"),

    /** GET /shipments/{id}/tracking. */
    TRACKING("tracking"),

    /** POST /shipments/{id}/void. */
    VOID("void"),

    /** POST /addresses/validate. */
    ADDRESSES("addresses"),

    /** POST /pickups, POST /close-out — post-shipment operations. */
    PICKUPS("pickups"),

    /** POST /landed-cost. */
    LANDED_COST("landed-cost");

    private final String token;

    ApiKeyScope(String token) {
        this.token = token;
    }

    /** The whitespace-separated string persisted in {@code ApiKey.scopes}. */
    public String token() {
        return token;
    }

    /**
     * Parse a persisted scopes string ({@code "shipments rates tracking"})
     * into the enum set. Unknown tokens are silently dropped — the enum is
     * the source of truth for what the app recognises.
     */
    public static Set<String> parseTokens(String scopes) {
        if (scopes == null || scopes.isBlank()) return Set.of();
        return new LinkedHashSet<>(Arrays.asList(scopes.trim().split("\\s+")));
    }
}
