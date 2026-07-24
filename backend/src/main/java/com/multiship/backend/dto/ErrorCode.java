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

    // ===== Fallback =====
    INTERNAL_ERROR
}
