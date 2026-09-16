package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Readiness projection for the USPS_PROVIDER 3-value state machine.
 *
 * <p>The {@code USPS_PROVIDER} system setting cycles through
 * {@code STAMPS_COM → PROVISIONING_USPS_DIRECT → USPS_DIRECT}. The
 * transition from {@code PROVISIONING_USPS_DIRECT} to {@code USPS_DIRECT}
 * is gated: platform OAuth consumer credentials must be seeded AND every
 * USPS carrier_account_ref row must carry an EPS account number, a CRID,
 * and an MID. This DTO exposes what's missing so the FE can guide the
 * operator through the last mile.
 *
 * <p>Returned unwrapped by the readiness endpoint and again as the 409
 * body when a transition to USPS_DIRECT is rejected for lack of readiness.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UspsProviderReadinessDTO {

    /** Current stored value of the USPS_PROVIDER setting (or default). */
    private String currentProvider;

    /** The target of the transition being checked — always {@code USPS_DIRECT}. */
    private String targetProvider;

    /** Platform OAuth 2.0 credential presence (developers.usps.com app). */
    private PlatformCreds platformCreds;

    /** Total count of active USPS carrier_account_ref rows on the platform. */
    private int totalUspsAccounts;

    /** Count of USPS accounts with all three USPS_DIRECT identifiers populated. */
    private int readyAccounts;

    /**
     * Accounts still missing one or more USPS_DIRECT identifiers. Rows with
     * a fully-populated triple are omitted (count included in
     * {@link #readyAccounts}).
     */
    private List<PendingAccount> pendingAccounts;

    /**
     * {@code true} iff both platform-cred booleans are set AND
     * {@link #pendingAccounts} is empty. The transition-guard requires
     * this to be true to allow PROVISIONING_USPS_DIRECT → USPS_DIRECT.
     */
    private boolean overallReady;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PlatformCreds {
        /** True if USPS_PLATFORM_CLIENT_ID system setting is present + non-blank. */
        private boolean clientIdSet;

        /** True if USPS_PLATFORM_CLIENT_SECRET system setting is present + non-blank. */
        private boolean clientSecretSet;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PendingAccount {
        /** Tenant scope of the account. {@code null} on a platform-level row. */
        private String tenantCode;

        /** carrier_account_ref.account_number — the platform's row key. */
        private String accountNumber;

        /**
         * Column names that are null or blank on this row. Ordered:
         * {@code usps_direct_account_number}, {@code usps_direct_crid},
         * {@code usps_direct_mid}.
         */
        private List<String> missing;
    }
}
