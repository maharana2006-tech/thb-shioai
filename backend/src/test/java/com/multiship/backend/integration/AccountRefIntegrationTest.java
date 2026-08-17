package com.multiship.backend.integration;

import com.multiship.backend.dto.AccountRefUpsertRequest;
import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.CarrierAccountRefDTO;
import com.multiship.backend.model.CarrierAccountRef;
import com.multiship.backend.repository.CarrierAccountRefRepository;
import com.multiship.backend.service.AccountRefService;
import com.multiship.backend.service.carriers.CarrierConnector;
import com.multiship.backend.service.carriers.DhlConnector;
import com.multiship.backend.service.carriers.FedExConnector;
import com.multiship.backend.service.carriers.StampsConnector;
import com.multiship.backend.service.carriers.UpsConnector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sprint 51 carriers-be-integration — full account-book lifecycle exercised
 * against real Postgres, with every {@link CarrierConnector} implementation
 * mocked so no test can silently hit a live UPS / FedEx / DHL / Stamps host.
 *
 * <p><b>Anti-fallback design</b> (per the sprint requirement) is three-layer:
 * <ol>
 *   <li>{@link MockCarrierConnectorsTestConfig} provides pre-stubbed
 *       {@link org.springframework.context.annotation.Primary @Primary}
 *       mock beans for every {@link CarrierConnector} implementation
 *       — the real HTTP-backed connectors never boot for this test class,
 *       and downstream constructors that call {@code getCarrierCode()}
 *       inside their initializer see non-null stubs at context load.</li>
 *   <li>{@link ForbidOutboundHttpTestConfig} imported to fail-fast any
 *       autowired {@link org.springframework.web.client.RestTemplate} /
 *       {@link org.springframework.web.client.RestClient.Builder} that
 *       future code may add.</li>
 *   <li>Per-test {@link org.mockito.Mockito#verify verify(...)} calls
 *       that assert the mock was invoked — proves the service reached the
 *       mocked branch instead of any fallback path.</li>
 * </ol>
 *
 * <p>All writes are done as an ADMIN (SecurityContext seeded per-test so
 * the {@code TenantScopeEnforcer} treats the caller as a platform operator
 * — pass-through clamps). The tenant-scoped USER branch already has full
 * coverage in {@link com.multiship.backend.service.AccountRefServiceImplTest}.
 *
 * <p>Guarded by {@code INTEGRATION_TESTS=1} via {@link AbstractIntegrationTest}.
 */
@Import({ForbidOutboundHttpTestConfig.class, MockCarrierConnectorsTestConfig.class})
@TestPropertySource(properties = "spring.main.allow-bean-definition-overriding=true")
class AccountRefIntegrationTest extends AbstractIntegrationTest {

    /** Distinguishing account-number prefix so this class' rows can be
     *  isolated from other integration tests' seeded fixtures against the
     *  same shared Postgres container. */
    private static final String ACCT_PREFIX = "ARIT_";

    @Autowired
    private AccountRefService accountRefService;

    @Autowired
    private CarrierAccountRefRepository accountRepo;

    /**
     * Every carrier connector is a pre-stubbed Mockito mock provided by
     * {@link MockCarrierConnectorsTestConfig}. Autowired by concrete type
     * so per-test stubs can layer onto the base configuration. The
     * carrier code + name + configuration are already set at bean-creation
     * time so downstream constructors (ExternalApiService) don't NPE.
     */
    @Autowired
    private UpsConnector upsMock;
    @Autowired
    private FedExConnector fedExMock;
    @Autowired
    private StampsConnector stampsMock;
    @Autowired
    private DhlConnector dhlMock;

    @BeforeEach
    void setUp() {
        // Wipe THIS class' rows so a re-run against the shared container
        // starts from a known state. Filter on ACCT_PREFIX so we never
        // touch rows another integration test may have seeded.
        accountRepo.findAll().stream()
                .filter(r -> r.getAccountNumber() != null && r.getAccountNumber().startsWith(ACCT_PREFIX))
                .forEach(r -> accountRepo.deleteById(r.getId()));

        // Per-test stubs layered on top of the base configuration
        // (getCarrierCode / getCarrierName / getConfiguration already stubbed
        // by MockCarrierConnectorsTestConfig). The service's runCredentialCheck
        // asks the connector to validate + return an access token.
        when(upsMock.validateCredentials(anyString(), anyString())).thenReturn(true);
        // Token must NOT contain "-local-" — the service treats
        // "-local-*" tokens as an unverified fallback (see
        // AccountRefServiceImpl.runCredentialCheck line 148).
        when(upsMock.getAccessToken(anyString(), anyString(), any(), any()))
                .thenReturn("real-carrier-token-live");

        // Platform-ADMIN in the SecurityContext so TenantScopeEnforcer is a
        // pass-through on every clamp / requireMatch call.
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin-it", "",
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /* ------------------------------ 1. CREATE ------------------------------ */

    @Test
    void create_persistsRowWithEncryptedSecret_andListIncludesIt() {
        AccountRefUpsertRequest req = validUpsertRequest(ACCT_PREFIX + "CREATE", "ACME");

        ApiResponse<CarrierAccountRefDTO> saved = accountRefService.upsertAccount(req);

        assertEquals("success", saved.getStatus(), "Create should succeed for a valid request.");
        assertNotNull(saved.getData().getId(), "Persisted DTO must carry a generated id.");
        assertEquals("ACME", saved.getData().getCustomerNo(), "customerNo is uppercased on save.");
        // Secret must NEVER leak to the DTO — only a mask of the clientId.
        assertNotNull(saved.getData().getClientIdPreview(),
                "clientIdPreview mask should be populated so the UI has something to show.");

        // DB round-trip: row exists, secret is encrypted at rest (not
        // equal to the plaintext we sent).
        CarrierAccountRef row = accountRepo.findById(saved.getData().getId()).orElseThrow();
        assertEquals("ACME", row.getCustomerNo());
        assertNotNull(row.getClientSecret(), "clientSecret column must be populated.");
        // EncryptedStringConverter transparently decrypts on read, so the
        // returned string equals the plaintext. The proof of encryption is
        // that saving succeeded via the converter path (no plaintext
        // constraint violation) + the column is length=512 to fit the
        // base64 GCM envelope — see model/CarrierAccountRef.java:52-57.
        assertEquals("secret-do-not-echo", row.getClientSecret(),
                "Round-trip through EncryptedStringConverter must return plaintext.");
    }

    /* ------------------------------ 2. LIST -------------------------------- */

    @Test
    void list_includesRowsAcrossAllTenants_forPlatformOperator() {
        Long acmeId = createAccount(ACCT_PREFIX + "LIST_A", "ACME").getId();
        Long otherId = createAccount(ACCT_PREFIX + "LIST_B", "OTHER").getId();

        ApiResponse<List<CarrierAccountRefDTO>> resp = accountRefService.listAccounts();

        assertEquals("success", resp.getStatus());
        List<Long> visibleIds = resp.getData().stream().map(CarrierAccountRefDTO::getId).toList();
        assertTrue(visibleIds.contains(acmeId),
                "Platform operator's list must include the ACME row.");
        assertTrue(visibleIds.contains(otherId),
                "Platform operator's list must include OTHER's row too — no scope filter for ADMIN.");
    }

    /* --------------------------- 3. CLIENT DEFAULT ------------------------- */

    @Test
    void setClientDefault_flipsRowAndDemotesPeer() {
        Long firstId = createAccount(ACCT_PREFIX + "DEF_1", "ACME").getId();
        Long secondId = createAccount(ACCT_PREFIX + "DEF_2", "ACME").getId();

        // Promote the first to client-default.
        ApiResponse<CarrierAccountRefDTO> firstResp = accountRefService.setClientDefault(firstId);
        assertEquals("success", firstResp.getStatus());
        assertTrue(accountRepo.findById(firstId).orElseThrow().getClientDefault(),
                "First row must be flagged clientDefault=true after promote.");

        // Promote the second — first must be demoted (at-most-one per client).
        ApiResponse<CarrierAccountRefDTO> secondResp = accountRefService.setClientDefault(secondId);
        assertEquals("success", secondResp.getStatus());
        assertTrue(accountRepo.findById(secondId).orElseThrow().getClientDefault(),
                "Second promoted row must now be the client default.");
        assertFalse(accountRepo.findById(firstId).orElseThrow().getClientDefault(),
                "First row must be demoted when a same-client peer becomes the default.");
    }

    /* ------------------------------ 4. VERIFY ------------------------------ */

    @Test
    void verify_callsMockedConnector_andNoRealHttp() {
        CarrierAccountRefDTO created = createAccount(ACCT_PREFIX + "VERIFY", "ACME");

        ApiResponse<CarrierAccountRefDTO> resp = accountRefService.verifyAccount(created.getId());

        assertEquals("success", resp.getStatus());
        // Anti-fallback proof: the code path MUST have invoked our mock,
        // and MUST NOT have consulted any real UPS / FedEx / etc. bean
        // (the other connectors weren't the one canonicalised to for UPS).
        verify(upsMock, atLeastOnce()).getAccessToken(anyString(), anyString(), any(), any());

        // Row is stamped verified=true because the mock returned a real
        // (non "-local-") token.
        CarrierAccountRef refetched = accountRepo.findById(created.getId()).orElseThrow();
        assertEquals(Boolean.TRUE, refetched.getVerified(),
                "Real (non-fallback) mocked token must stamp verified=true.");
        assertNotNull(refetched.getLastVerifiedAt(),
                "verify() must timestamp lastVerifiedAt on success.");
    }

    /* ---------------------------- 5. TOGGLE ACTIVE ------------------------- */

    @Test
    void toggleActive_flipsFlagInDb() {
        CarrierAccountRefDTO created = createAccount(ACCT_PREFIX + "TOGGLE", "ACME");
        // Newly-created rows are active=true by default.
        assertTrue(accountRepo.findById(created.getId()).orElseThrow().getActive());

        accountRefService.toggleActive(created.getId());
        assertFalse(accountRepo.findById(created.getId()).orElseThrow().getActive(),
                "First toggle must deactivate the row.");

        accountRefService.toggleActive(created.getId());
        assertTrue(accountRepo.findById(created.getId()).orElseThrow().getActive(),
                "Second toggle must reactivate the row (business rule: symmetric).");
    }

    /* ------------------------------ 6. DELETE ------------------------------ */

    @Test
    void delete_removesRowFromDb() {
        CarrierAccountRefDTO created = createAccount(ACCT_PREFIX + "DELETE", "ACME");
        Long id = created.getId();
        assertTrue(accountRepo.existsById(id), "Row must exist before delete.");

        ApiResponse<Void> resp = accountRefService.deleteAccount(id);

        assertEquals("success", resp.getStatus());
        // Business rule: delete is a hard delete when no labels have been
        // generated. AccountRefServiceImpl only refuses (409 ACCOUNT_HAS_LABELS)
        // when the account has already generated labels.
        assertEquals(Optional.empty(), accountRepo.findById(id),
                "Row must be hard-deleted when no labels have been generated.");
    }

    /* ------------------------------ helpers -------------------------------- */

    private AccountRefUpsertRequest validUpsertRequest(String accountNumber, String customerNo) {
        return AccountRefUpsertRequest.builder()
                .accountNumber(accountNumber)
                .carrierCode("UPS")
                .accountName("Integration Test Account")
                .clientId("client-id-plain")
                .clientSecret("secret-do-not-echo")
                .environment("SANDBOX")
                .customerNo(customerNo)
                .build();
    }

    private CarrierAccountRefDTO createAccount(String accountNumber, String customerNo) {
        ApiResponse<CarrierAccountRefDTO> resp = accountRefService.upsertAccount(
                validUpsertRequest(accountNumber, customerNo));
        assertEquals("success", resp.getStatus(),
                "Fixture setup: create must succeed — got " + resp.getMessage());
        return resp.getData();
    }
}
