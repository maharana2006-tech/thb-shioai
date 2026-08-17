package com.multiship.backend.dto;

/**
 * Stable, machine-readable error codes carried in ApiResponse.errorCode.
 * Clients branch on these (never on message text), which keeps messages free
 * to change or be translated. Codes are part of the API contract: renaming
 * one is a breaking change, adding one is not.
 */
public enum ErrorCode {

    // ===== Auth =====
    UNAUTHORIZED,
    FORBIDDEN,
    INVALID_CREDENTIALS,
    USERNAME_TAKEN,
    EMAIL_TAKEN,
    ADMIN_SIGNUP_FORBIDDEN,

    // ===== Sprint 50 Tier 0.5 PR C — API key lifecycle =====
    API_KEY_EXPIRED,
    API_KEY_ROTATED,

    // ===== Sprint 50 Tier 0.5 PR D — invite-only signup + rate limit =====
    /** Public signup is disabled — the operator must use an invite. */
    SIGNUP_DISABLED,
    /** Signup blocked by rate limit — retry after the window. */
    SIGNUP_RATE_LIMITED,
    /** Login refused for an account that has not verified its email. */
    EMAIL_NOT_VERIFIED,
    INVITE_NOT_FOUND,
    INVITE_EXPIRED,
    INVITE_ALREADY_USED,
    /** Signup / invite payload missing the required clientCode. */
    CLIENT_CODE_REQUIRED,

    // ===== Sprint 50 Tier 0.5 PR E — tenant scope + admin user mgmt =====
    /** Login refused: an admin revoked this account (deactivated_at set). */
    ACCOUNT_DEACTIVATED,
    /** Caller tried to read/write a row that belongs to a different client. */
    CROSS_TENANT_ACCESS_DENIED,
    /** Admin tried to move a user to a client that does not exist. */
    ADMIN_TARGET_CLIENT_NOT_FOUND,
    /** Admin tried to modify or deactivate a user that does not exist. */
    ADMIN_TARGET_USER_NOT_FOUND,

    // ===== Sprint 50 finding #15 — per-tenant rate limit + fair scheduler =====
    /** Tenant exceeded the per-minute request budget on a write endpoint. */
    TENANT_RATE_LIMITED,

    // ===== Sprint 51 T2 finding #6 — auth brute-force lockout =====
    /**
     * Too many failed login / OAuth token attempts for this
     * (ip, username) pair. Response carries {@code Retry-After} seconds.
     */
    AUTH_FAILURE_LOCKOUT,

    // ===== Orders =====
    ORDER_NOT_FOUND,
    VALIDATION_ERROR,

    // ===== Label generation =====
    LABEL_ALREADY_GENERATED,
    NEEDS_CARRIER_DETAILS,
    NO_DEFAULT_ACCOUNT,
    CARRIER_FAILURE,
    CUSTOMS_REQUIRED,

    // ===== Carrier accounts =====
    ACCOUNT_NOT_FOUND,
    ACCOUNT_INCOMPLETE,
    /** Delete refused — the account has already generated labels; deactivate instead. */
    ACCOUNT_HAS_LABELS,
    TENANT_REQUIRED,
    TENANT_ACCOUNT_NOT_FOUND,
    CARRIER_CONNECTION_FAILED,

    ACCOUNT_SELECTION_REQUIRED,

    // ===== Clients =====
    CLIENT_NOT_FOUND,
    CLIENT_INACTIVE,
    CLIENT_CODE_TAKEN,
    CLIENT_HAS_ORDERS,

    // ===== Warehouses =====
    WAREHOUSE_NOT_FOUND,
    WAREHOUSE_CODE_TAKEN,
    /** ownerType=CLIENT requires ownerClientCode; PLATFORM forbids it. */
    WAREHOUSE_OWNER_INVALID,
    /** A CLIENT-owned warehouse can only be attached to its owner. */
    WAREHOUSE_ATTACH_FORBIDDEN,
    WAREHOUSE_ALREADY_ATTACHED,
    CLIENT_WAREHOUSE_NOT_FOUND,
    /** Client has no attached warehouses and the request didn't name one. */
    NO_DEFAULT_WAREHOUSE,

    // ===== Client allowlists (services + packages) =====
    /** Shipment named a service the client isn't allowed to use. */
    SERVICE_NOT_ALLOWED,
    /** Shipment named a package the client isn't allowed to use. */
    PACKAGE_NOT_ALLOWED,
    ALLOWLIST_ALREADY_EXISTS,
    ALLOWLIST_ENTRY_NOT_FOUND,

    // ===== Client destination rules =====
    /** A client's rules must all share one mode (ALLOW or DENY). */
    DESTINATION_MODE_MISMATCH,
    /** Shipment destination is not permitted for this client. */
    SHIP_TO_DENIED,

    // ===== Client policy + markup =====
    /** rate_strategy=FIXED requires fixed_service_id pointing to an allowed service. */
    POLICY_FIXED_SERVICE_REQUIRED,
    /** Markup value must be non-negative; currency must be 3 letters. */
    MARKUP_INVALID,
    /**
     * Sprint 50 Tier 1 — the client has no ClientBillingMarkup row and the
     * caller reached the label write path. Fail loud instead of silently
     * shipping at 0% margin. An admin sets a per-client markup on the
     * Clients page.
     */
    MARKUP_REQUIRED_FOR_CLIENT,

    // ===== Sprint 50 Tier 1 — no silent fallbacks =====
    /**
     * The client's carrier credentials failed at the carrier and the
     * caller did not opt into the platform (house) account. The old
     * behaviour silently billed the platform; the new one refuses so the
     * shipper knows their credentials are broken.
     */
    CLIENT_CARRIER_AUTH_FAILED,
    /**
     * A shipment/rate request omitted the weight unit. The old behaviour
     * silently defaulted to LB — a KG shipment would then post as LB
     * (label under-declares by ~2.2×). Callers must send the unit
     * explicitly (or, when Sprint 50 Tier 2 lands, Client.defaultWeightUnit
     * fills it in from the tenant record).
     */
    UNIT_REQUIRED,
    /**
     * A shipment/rate request omitted the currency for declared value.
     * The old behaviour silently defaulted to USD — an EUR shipment
     * would then post as USD (customs risk). Callers must send the
     * currency explicitly (or Client.defaultCurrency fills it in from
     * the tenant record once Sprint 50 Tier 2 lands).
     */
    CURRENCY_REQUIRED,
    /**
     * The carrier returned HTTP 429 (rate-limited). The mapper on
     * CarrierExceptionMapper populates {@code retryAfterSeconds} on
     * {@link com.multiship.backend.service.carriers.exceptions.CarrierRateLimitException};
     * consumers surface this errorcode + the seconds in the response body
     * so ops sees the pressure. Sprint 49 wired the exception; Sprint 50
     * Tier 1 finding #5 wires the consumers.
     */
    CARRIER_RATE_LIMITED,
    /**
     * We rate-limited the caller (per-API-key on the public v2 API).
     * Response carries {@code Retry-After} seconds header + the same
     * value in the message. Sprint 50 Tier 1 finding #15.
     */
    API_KEY_RATE_LIMITED,

    // ===== Order intake code translation (Phase 5) =====
    /** ERP shipvia code has no alias for this client. */
    UNKNOWN_SHIPVIA_CODE,
    /** ERP service-level code has no alias for this client. */
    UNKNOWN_SERVICE_CODE,
    /** ERP destination-country code has no alias for this client. */
    UNKNOWN_DEST_CODE,
    /** ERP package SKU has no alias for this client. */
    UNKNOWN_PACKAGE_CODE,

    // ===== Client allowlist × destination gate =====
    /** Service is allowed for the client, but not for this destination country. */
    SERVICE_NOT_ALLOWED_FOR_DEST,

    // ===== Client allowlist × warehouse gate =====
    /** Service is allowed for the client, but not from this warehouse. */
    SERVICE_NOT_ALLOWED_FOR_WAREHOUSE,

    // ===== Split-shipment groups (Sprint 47) =====
    SHIPMENT_GROUP_NOT_FOUND,

    // ===== Sprint 50 Tier 1-C — idempotency store =====
    /** Another request with this Idempotency-Key is still processing; retry after a few seconds. */
    IDEMPOTENCY_IN_PROGRESS,
    /** The idempotency store (Redis) is unavailable; retry after Retry-After header. Only returned on money-touching endpoints where fail-open would risk duplicates. */
    IDEMPOTENCY_UNAVAILABLE,

    // ===== Sprint 51 AC-M3 / AC-L4 — external webhook subscription CRUD =====
    /** The webhook subscription id in the path or body does not resolve for the caller. */
    WEBHOOK_SUBSCRIPTION_NOT_FOUND,

    // ===== Sprint 52 line-item caps =====
    /**
     * Shipment carries more commodity lines than the resolved carrier
     * cap accepts. Response is HTTP 422 — commodities cannot be split
     * across sub-shipments (would break shipper invoice intent), so the
     * operator must remodel the order.
     */
    COMMODITIES_LIMIT_EXCEEDED,
    /**
     * Bulk-label / bulk-import batch is larger than the platform allows
     * in one call. Response is HTTP 422 — the operator splits their
     * batch into multiple submissions.
     */
    BULK_LIMIT_EXCEEDED,

    // ===== Sprint 52 output routing + network printing =====
    /**
     * The referenced {@code client_output_destination} row does not exist
     * (admin API path variable didn't resolve). Response is HTTP 404.
     */
    OUTPUT_DESTINATION_NOT_FOUND,
    /**
     * A driver raised a delivery failure (network timeout, printer
     * offline, SFTP auth). Response is HTTP 502 or 500 depending on
     * whether the caller invoked the test endpoint or hit an
     * unexpected internal failure.
     */
    OUTPUT_DELIVERY_FAILED,

    // ===== Fallback =====
    INTERNAL_ERROR
}
