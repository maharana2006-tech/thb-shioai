package com.multiship.backend.service;

import com.multiship.backend.dto.UspsProviderReadinessDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PR-A Agent-A — pure-Mockito tests for
 * {@link UspsProviderReadinessService#check()}.
 *
 * <p>{@link SystemSettingService} + {@link JdbcTemplate} both mocked. The
 * account scan is exercised by stubbing the {@link JdbcTemplate#query}
 * call with a per-test list of {@link StubRow} which we run through the
 * captured {@link RowMapper} to simulate a real DB result.
 *
 * <p>Scenarios:
 * <ul>
 *   <li>No accounts, no creds — overallReady=false, empty pending list.</li>
 *   <li>No accounts, creds present — overallReady=true.</li>
 *   <li>All accounts ready + creds present — overallReady=true.</li>
 *   <li>Some accounts pending — overallReady=false, pending list carries
 *       exactly the missing column names in the documented order.</li>
 *   <li>Platform creds missing — overallReady=false even with 0 accounts.</li>
 *   <li>Current provider defaults to STAMPS_COM when unset.</li>
 *   <li>Account with blank customer_no surfaces tenantCode=null.</li>
 *   <li>JdbcTemplate throws — service degrades to empty account list, not 500.</li>
 * </ul>
 */
class UspsProviderReadinessServiceTest {

    private SystemSettingService systemSettingService;
    private JdbcTemplate jdbcTemplate;
    private UspsProviderReadinessService service;

    @BeforeEach
    void setUp() {
        systemSettingService = mock(SystemSettingService.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        service = new UspsProviderReadinessService(systemSettingService, jdbcTemplate);

        // Default: no rows, no creds. Tests override piecewise.
        stubAccounts(List.of());
        stubProvider(null);
        stubCred(UspsProviderReadinessService.USPS_PLATFORM_CLIENT_ID_KEY, null);
        stubCred(UspsProviderReadinessService.USPS_PLATFORM_CLIENT_SECRET_KEY, null);
    }

    // ============================ helpers ============================

    /** Stub the current provider value (null = not stored). */
    private void stubProvider(String value) {
        when(systemSettingService.getDecrypted(UspsProviderReadinessService.USPS_PROVIDER_KEY))
                .thenReturn(Optional.ofNullable(value));
    }

    /** Stub a platform cred (null / blank = not stored). */
    private void stubCred(String key, String value) {
        when(systemSettingService.getDecrypted(key)).thenReturn(Optional.ofNullable(value));
    }

    /**
     * Route {@link JdbcTemplate#query(String, RowMapper)} through the
     * supplied stub rows by driving the captured {@link RowMapper} with
     * a mock ResultSet per row.
     */
    private void stubAccounts(List<StubRow> rows) {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class))).thenAnswer(inv -> {
            RowMapper<?> mapper = inv.getArgument(1);
            List<Object> mapped = new ArrayList<>(rows.size());
            for (int i = 0; i < rows.size(); i++) {
                StubRow r = rows.get(i);
                ResultSet rs = mock(ResultSet.class);
                when(rs.getString("customer_no")).thenReturn(r.customerNo);
                when(rs.getString("account_number")).thenReturn(r.accountNumber);
                when(rs.getString("usps_direct_account_number")).thenReturn(r.eps);
                when(rs.getString("usps_direct_crid")).thenReturn(r.crid);
                when(rs.getString("usps_direct_mid")).thenReturn(r.mid);
                mapped.add(mapper.mapRow(rs, i));
            }
            return mapped;
        });
    }

    private record StubRow(String customerNo, String accountNumber,
                            String eps, String crid, String mid) { }

    // ============================ scenarios ============================

    @Test
    void noAccounts_noCreds_overallReadyFalse_targetIsUspsDirect() {
        UspsProviderReadinessDTO dto = service.check();

        assertEquals("USPS_DIRECT", dto.getTargetProvider(),
                "target is always USPS_DIRECT — the check is for the transition INTO it");
        assertEquals("STAMPS_COM", dto.getCurrentProvider(),
                "no stored value → currentProvider falls back to STAMPS_COM default");
        assertEquals(0, dto.getTotalUspsAccounts());
        assertEquals(0, dto.getReadyAccounts());
        assertTrue(dto.getPendingAccounts().isEmpty());
        assertFalse(dto.getPlatformCreds().isClientIdSet());
        assertFalse(dto.getPlatformCreds().isClientSecretSet());
        assertFalse(dto.isOverallReady(),
                "creds missing → overallReady must be false even with 0 accounts");
    }

    @Test
    void noAccounts_credsPresent_overallReadyTrue() {
        stubCred(UspsProviderReadinessService.USPS_PLATFORM_CLIENT_ID_KEY, "ck-abc");
        stubCred(UspsProviderReadinessService.USPS_PLATFORM_CLIENT_SECRET_KEY, "cs-xyz");

        UspsProviderReadinessDTO dto = service.check();

        assertTrue(dto.getPlatformCreds().isClientIdSet());
        assertTrue(dto.getPlatformCreds().isClientSecretSet());
        assertTrue(dto.isOverallReady(),
                "creds present + no accounts to gate = ready");
    }

    @Test
    void allAccountsReady_credsPresent_overallReadyTrue() {
        stubCred(UspsProviderReadinessService.USPS_PLATFORM_CLIENT_ID_KEY, "ck");
        stubCred(UspsProviderReadinessService.USPS_PLATFORM_CLIENT_SECRET_KEY, "cs");
        stubAccounts(List.of(
                new StubRow("ACME", "A123", "EPS1", "CRID1", "MID1"),
                new StubRow("WIDG", "A456", "EPS2", "CRID2", "MID2")));

        UspsProviderReadinessDTO dto = service.check();

        assertEquals(2, dto.getTotalUspsAccounts());
        assertEquals(2, dto.getReadyAccounts());
        assertTrue(dto.getPendingAccounts().isEmpty());
        assertTrue(dto.isOverallReady());
    }

    @Test
    void somePending_carriesExactMissingColumnNames_inDocumentedOrder() {
        stubCred(UspsProviderReadinessService.USPS_PLATFORM_CLIENT_ID_KEY, "ck");
        stubCred(UspsProviderReadinessService.USPS_PLATFORM_CLIENT_SECRET_KEY, "cs");
        stubAccounts(List.of(
                new StubRow("ACME", "A1", "EPS", "CRID", "MID"),  // ready
                new StubRow("ACME", "A2", null, null, "MID"),      // missing EPS + CRID
                new StubRow("WIDG", "A3", "EPS", "", null),         // missing CRID (blank) + MID
                new StubRow("ACME", "A4", "  ", "CRID", "MID")));  // missing EPS (whitespace)

        UspsProviderReadinessDTO dto = service.check();

        assertEquals(4, dto.getTotalUspsAccounts());
        assertEquals(1, dto.getReadyAccounts());
        assertEquals(3, dto.getPendingAccounts().size());
        assertFalse(dto.isOverallReady(),
                "any pending account blocks readiness even when creds are set");

        UspsProviderReadinessDTO.PendingAccount a2 = pendingByAcct(dto, "A2");
        assertEquals(List.of("usps_direct_account_number", "usps_direct_crid"), a2.getMissing(),
                "missing list must be in documented order: account_number, crid, mid");

        UspsProviderReadinessDTO.PendingAccount a3 = pendingByAcct(dto, "A3");
        assertEquals(List.of("usps_direct_crid", "usps_direct_mid"), a3.getMissing(),
                "blank string counts as missing (StringUtils.hasText contract)");

        UspsProviderReadinessDTO.PendingAccount a4 = pendingByAcct(dto, "A4");
        assertEquals(List.of("usps_direct_account_number"), a4.getMissing(),
                "whitespace-only value counts as missing");
    }

    @Test
    void accountsReady_butOneCredMissing_overallReadyFalse() {
        stubCred(UspsProviderReadinessService.USPS_PLATFORM_CLIENT_ID_KEY, "ck");
        // secret unset
        stubAccounts(List.of(new StubRow("ACME", "A1", "EPS", "CRID", "MID")));

        UspsProviderReadinessDTO dto = service.check();

        assertTrue(dto.getPlatformCreds().isClientIdSet());
        assertFalse(dto.getPlatformCreds().isClientSecretSet());
        assertEquals(1, dto.getReadyAccounts());
        assertTrue(dto.getPendingAccounts().isEmpty());
        assertFalse(dto.isOverallReady(),
                "even with 100% ready accounts, a missing platform cred blocks readiness");
    }

    @Test
    void blankCustomerNo_surfacesAsNullTenantCode() {
        stubCred(UspsProviderReadinessService.USPS_PLATFORM_CLIENT_ID_KEY, "ck");
        stubCred(UspsProviderReadinessService.USPS_PLATFORM_CLIENT_SECRET_KEY, "cs");
        stubAccounts(List.of(
                new StubRow(null, "PLAT1", null, null, null),
                new StubRow("", "PLAT2", null, null, null)));

        UspsProviderReadinessDTO dto = service.check();

        assertEquals(2, dto.getPendingAccounts().size());
        assertNull(pendingByAcct(dto, "PLAT1").getTenantCode(),
                "null customer_no must surface as null tenantCode (platform-level row)");
        assertNull(pendingByAcct(dto, "PLAT2").getTenantCode(),
                "blank customer_no must surface as null tenantCode (StringUtils.hasText)");
    }

    @Test
    void currentProvider_reflectsStoredValue_whenPresent() {
        stubProvider("PROVISIONING_USPS_DIRECT");

        UspsProviderReadinessDTO dto = service.check();

        assertEquals("PROVISIONING_USPS_DIRECT", dto.getCurrentProvider());
    }

    @Test
    void blankStoredCred_treatedAsMissing() {
        stubCred(UspsProviderReadinessService.USPS_PLATFORM_CLIENT_ID_KEY, "   ");
        stubCred(UspsProviderReadinessService.USPS_PLATFORM_CLIENT_SECRET_KEY, "");

        UspsProviderReadinessDTO dto = service.check();

        assertFalse(dto.getPlatformCreds().isClientIdSet(),
                "whitespace-only cred must count as unset");
        assertFalse(dto.getPlatformCreds().isClientSecretSet(),
                "empty-string cred must count as unset");
    }

    @Test
    void jdbcQueryThrows_degradesToEmptyAccountList_notServerError() {
        stubCred(UspsProviderReadinessService.USPS_PLATFORM_CLIENT_ID_KEY, "ck");
        stubCred(UspsProviderReadinessService.USPS_PLATFORM_CLIENT_SECRET_KEY, "cs");
        // V58 hasn't run + Hibernate hasn't created the table yet on a truly
        // fresh DB — the query throws. Readiness endpoint must not 500.
        when(jdbcTemplate.query(anyString(), any(RowMapper.class)))
                .thenThrow(new org.springframework.jdbc.BadSqlGrammarException(
                        "test", "SELECT ...", new java.sql.SQLException("relation does not exist")));

        UspsProviderReadinessDTO dto = service.check();

        assertEquals(0, dto.getTotalUspsAccounts());
        assertEquals(0, dto.getReadyAccounts());
        assertTrue(dto.getPendingAccounts().isEmpty());
        // With 0 accounts + creds set, we degrade to "ready" — that's fine
        // because the guard only fires when a real caller wants to transition,
        // and the migration precondition is a separate ops signal.
        assertTrue(dto.isOverallReady(),
                "0 accounts + both creds set → ready (empty-account edge is documented)");
    }

    // ============================ tiny helper ============================

    private static UspsProviderReadinessDTO.PendingAccount pendingByAcct(
            UspsProviderReadinessDTO dto, String accountNumber) {
        return dto.getPendingAccounts().stream()
                .filter(p -> accountNumber.equals(p.getAccountNumber()))
                .findFirst().orElseThrow(
                        () -> new AssertionError("pending row for " + accountNumber + " missing"));
    }
}
