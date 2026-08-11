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

    // ===== Fallback =====
    INTERNAL_ERROR
}
