package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Optional body of POST /orders/{orderNo}/label. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GenerateLabelRequest {

    /**
     * Explicitly chosen carrier account (from the account book). When set it
     * overrides the resolution cascade — the shipper picked manually.
     */
    private Long accountId;

    /**
     * Sprint 50 Tier 1 (finding #3) — the shipper's explicit opt-in to
     * bill the platform (house) account when the client's own carrier
     * credentials fail authentication. The old behaviour silently
     * retried on the platform account and billed it, so the shipper
     * never learned their credentials were broken. Default false:
     * without this flag, an auth failure surfaces
     * {@link ErrorCode#CLIENT_CARRIER_AUTH_FAILED} so the operator can
     * fix the client's credentials before retrying (or resend with
     * {@code useHouseAccount=true} to explicitly bill the platform).
     *
     * <p>Wrapper type, not primitive: Jackson 3 binds through the all-args
     * constructor and passes {@code null} for ABSENT properties — a primitive
     * here made every {@code {"accountId":N}} body (the account-picker's
     * re-ship request) fail with "Cannot map null into type boolean".
     */
    private Boolean useHouseAccount;

    /** Null-safe read — absent/null means the default: do NOT bill the house account. */
    public boolean isUseHouseAccount() {
        return Boolean.TRUE.equals(useHouseAccount);
    }
}
