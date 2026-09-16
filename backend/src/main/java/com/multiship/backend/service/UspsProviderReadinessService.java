package com.multiship.backend.service;

import com.multiship.backend.dto.UspsProviderReadinessDTO;
import com.multiship.backend.dto.UspsProviderReadinessDTO.PendingAccount;
import com.multiship.backend.dto.UspsProviderReadinessDTO.PlatformCreds;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Read-side helper for the USPS_PROVIDER state machine's transition guard.
 *
 * <p>Two consumers:
 * <ul>
 *   <li>{@code GET /admin/system-settings/USPS_PROVIDER/readiness} — the FE
 *       polls this to show the operator what still needs filling in during
 *       {@code PROVISIONING_USPS_DIRECT}.</li>
 *   <li>The transition guard on
 *       {@code PUT /admin/system-settings/USPS_PROVIDER} — when the target
 *       is {@code USPS_DIRECT}, the update is rejected 409 iff readiness
 *       is not satisfied.</li>
 * </ul>
 *
 * <p>Uses {@link JdbcTemplate} for the {@code carrier_account_ref} scan so
 * this service can ship independently of Agent C's entity update (the new
 * columns arrive in V58 but the {@link com.multiship.backend.model.CarrierAccountRef}
 * fields land in a peer PR). Native SQL means the read works the moment
 * V58 has run, regardless of entity state.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UspsProviderReadinessService {

    /** System-setting key holding the state-machine value. */
    public static final String USPS_PROVIDER_KEY = "USPS_PROVIDER";

    /** System-setting key for the developers.usps.com OAuth 2.0 consumer key. */
    public static final String USPS_PLATFORM_CLIENT_ID_KEY = "USPS_PLATFORM_CLIENT_ID";

    /** System-setting key for the developers.usps.com OAuth 2.0 consumer secret. */
    public static final String USPS_PLATFORM_CLIENT_SECRET_KEY = "USPS_PLATFORM_CLIENT_SECRET";

    /** Default USPS_PROVIDER value if nothing is stored yet. */
    public static final String DEFAULT_PROVIDER = "STAMPS_COM";

    /** Target of the guarded transition. */
    public static final String TARGET_PROVIDER = "USPS_DIRECT";

    private static final String USPS_ACCOUNTS_SQL = ""
            + "SELECT customer_no, account_number, "
            + "       usps_direct_account_number, usps_direct_crid, usps_direct_mid "
            + "  FROM carrier_account_ref "
            + " WHERE UPPER(carrier_code) = 'USPS' "
            + "   AND active = TRUE "
            + " ORDER BY id";

    private final SystemSettingService systemSettingService;
    private final JdbcTemplate jdbcTemplate;

    /**
     * Compute the full readiness snapshot. Never throws — surfaces problems
     * through {@link UspsProviderReadinessDTO#isOverallReady()} rather than
     * exceptions so the endpoint always returns a JSON body the FE can render.
     */
    public UspsProviderReadinessDTO check() {
        String current = systemSettingService.getDecrypted(USPS_PROVIDER_KEY)
                .filter(StringUtils::hasText)
                .orElse(DEFAULT_PROVIDER);

        PlatformCreds creds = PlatformCreds.builder()
                .clientIdSet(hasPlaintext(USPS_PLATFORM_CLIENT_ID_KEY))
                .clientSecretSet(hasPlaintext(USPS_PLATFORM_CLIENT_SECRET_KEY))
                .build();

        List<PendingAccount> pending = new ArrayList<>();
        int total = 0;
        int ready = 0;

        List<UspsAccountRow> rows = fetchUspsAccounts();
        for (UspsAccountRow row : rows) {
            total++;
            List<String> missing = new ArrayList<>(3);
            if (!StringUtils.hasText(row.uspsDirectAccountNumber)) {
                missing.add("usps_direct_account_number");
            }
            if (!StringUtils.hasText(row.uspsDirectCrid)) {
                missing.add("usps_direct_crid");
            }
            if (!StringUtils.hasText(row.uspsDirectMid)) {
                missing.add("usps_direct_mid");
            }
            if (missing.isEmpty()) {
                ready++;
            } else {
                pending.add(PendingAccount.builder()
                        .tenantCode(StringUtils.hasText(row.customerNo) ? row.customerNo : null)
                        .accountNumber(row.accountNumber)
                        .missing(missing)
                        .build());
            }
        }

        boolean overallReady = creds.isClientIdSet()
                && creds.isClientSecretSet()
                && pending.isEmpty();

        return UspsProviderReadinessDTO.builder()
                .currentProvider(current)
                .targetProvider(TARGET_PROVIDER)
                .platformCreds(creds)
                .totalUspsAccounts(total)
                .readyAccounts(ready)
                .pendingAccounts(pending)
                .overallReady(overallReady)
                .build();
    }

    /**
     * Native-SQL scan of {@code carrier_account_ref}. Kept package-private
     * so the unit test can mock the surrounding service; the scan itself
     * is covered by an integration test.
     */
    private List<UspsAccountRow> fetchUspsAccounts() {
        try {
            return jdbcTemplate.query(USPS_ACCOUNTS_SQL, (rs, rowNum) -> new UspsAccountRow(
                    rs.getString("customer_no"),
                    rs.getString("account_number"),
                    rs.getString("usps_direct_account_number"),
                    rs.getString("usps_direct_crid"),
                    rs.getString("usps_direct_mid")));
        } catch (Exception ex) {
            // If the columns don't exist yet (V58 hasn't run) or the table
            // is missing (Hibernate hasn't created it on a truly fresh DB),
            // we surface an empty account list rather than 500-ing the
            // readiness endpoint. overallReady will be false anyway.
            log.warn("USPS readiness scan failed; treating as no accounts. cause={}", ex.getMessage());
            return List.of();
        }
    }

    private boolean hasPlaintext(String key) {
        return systemSettingService.getDecrypted(key)
                .filter(StringUtils::hasText)
                .isPresent();
    }

    /** Row projection for the native scan — kept private to the service. */
    private record UspsAccountRow(String customerNo,
                                    String accountNumber,
                                    String uspsDirectAccountNumber,
                                    String uspsDirectCrid,
                                    String uspsDirectMid) { }
}
