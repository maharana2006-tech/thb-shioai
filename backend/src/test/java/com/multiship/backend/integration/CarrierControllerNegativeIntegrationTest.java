package com.multiship.backend.integration;

import com.multiship.backend.controller.CarrierController;
import com.multiship.backend.controller.CarrierExceptionHandler;
import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.CarrierConnectRequest;
import com.multiship.backend.dto.CarrierConnectResponse;
import com.multiship.backend.dto.CarrierStatusResponse;
import com.multiship.backend.exception.CarrierConnectionException;
import com.multiship.backend.model.CarrierConfig;
import com.multiship.backend.model.ShipVia;
import com.multiship.backend.model.User;
import com.multiship.backend.repository.CarrierConfigRepository;
import com.multiship.backend.repository.ShipViaRepository;
import com.multiship.backend.repository.UserRepository;
import com.multiship.backend.service.carriers.CarrierConnector;
import com.multiship.backend.service.carriers.FedExConnector;
import com.multiship.backend.service.carriers.UpsConnector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sprint 53 /settings/carriers page-tests (slice: BE-integration-connect, v2).
 *
 * <p>Supplements {@link CarrierControllerIntegrationTest} (4 happy-path
 * tests) with connect-flow negative + edge coverage against real
 * Postgres:
 *
 * <ul>
 *   <li>Connect with an UNKNOWN carrier code — surfaces as a
 *       {@link CarrierConnectionException} (raw from the service; the
 *       {@link CarrierExceptionHandler} would translate to 400 in a
 *       real request cycle).</li>
 *   <li>Connect with legacy ship-via {@code P80} — must resolve to
 *       UPS in-flight and consult the UPS connector mock.</li>
 *   <li>Connect with tenantId — must clamp / uppercase and persist as
 *       a tenant-scoped {@link CarrierConfig}.</li>
 *   <li>Connect + connect (same user + same carrier) — must UPDATE the
 *       existing row, not INSERT a duplicate.</li>
 *   <li>Connect where {@code connector.connect(...)} throws — must
 *       propagate for the exception handler to translate.</li>
 *   <li>Status with NO CarrierConfig persisted — must still return 200
 *       with {@code connected=false} (defensive; no crash).</li>
 *   <li>Disconnect when nothing persisted — must succeed idempotently.</li>
 * </ul>
 *
 * <h3>State-isolation notes (previously CI-flaky)</h3>
 *
 * <p>Two things make this class robust in CI's shared testcontainer:
 *
 * <ol>
 *   <li><b>Per-test unique USERNAME</b> derived from {@link TestInfo} —
 *       cross-test state cannot leak between tests within this class.</li>
 *   <li><b>{@code seedShipVia} checks by {@code shipviaCd}</b> (the
 *       semantic key), not by manual id. The primary
 *       {@link CarrierControllerIntegrationTest} in the same shared
 *       testcontainer already seeds P80/F77 at IDs 9101/9102. Checking
 *       by id would insert a duplicate at 9201/9202 → the connect flow's
 *       {@code findByShipviaCdIgnoreCase} then throws
 *       {@code IncorrectResultSizeDataAccessException}. Checking by cd
 *       is a strict upsert semantic that survives shared-container state.</li>
 * </ol>
 *
 * <p>Same anti-fallback design as the primary IT: no real UPS / FedEx
 * / DHL / Stamps host is EVER contacted. Guarded by
 * {@code INTEGRATION_TESTS=1} via {@link AbstractIntegrationTest}.
 */
@Import({ForbidOutboundHttpTestConfig.class, MockCarrierConnectorsTestConfig.class})
@TestPropertySource(properties = "spring.main.allow-bean-definition-overriding=true")
class CarrierControllerNegativeIntegrationTest extends AbstractIntegrationTest {

    /** Distinct username PER TEST so cross-test state can never bleed.
     *  Assigned in @BeforeEach from {@link TestInfo#getTestMethod()}. */
    private String USERNAME;
    /** Distinct tenant id per class — clamped upstream via TenantScopeEnforcer. */
    private static final String TENANT_ID = "NEGIT";

    @Autowired
    private CarrierController controller;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ShipViaRepository shipViaRepository;
    @Autowired
    private CarrierConfigRepository carrierConfigRepository;
    @Autowired
    private PlatformTransactionManager txManager;

    @Autowired
    private UpsConnector upsMock;
    @Autowired
    private FedExConnector fedExMock;

    @BeforeEach
    void setUp(TestInfo info) {
        // Derive a unique username from the test-method name — cross-test
        // state cannot leak, so a failure never depends on order.
        USERNAME = "carrier-neg-it-" + info.getTestMethod()
                .map(m -> m.getName().toLowerCase()
                        .replaceAll("[^a-z0-9]+", "-")
                        .replaceAll("(^-|-$)", ""))
                .orElse("unknown");
        // Trim to fit VARCHAR(50) on the username column.
        if (USERNAME.length() > 45) USERNAME = USERNAME.substring(0, 45);

        cleanup();

        userRepository.save(User.builder()
                .username(USERNAME)
                .email(USERNAME + "@local.test")
                .password("not-used-integration-test")
                .fullName("Carrier Neg IT User")
                .role("ADMIN")
                .emailVerified(true)
                .carrierConnected(false)
                .build());

        // Ensure the ship-via rows the connect() path resolves against exist.
        // Check by shipviaCd (semantic key) rather than by manual id: the
        // primary CarrierControllerIntegrationTest in the same shared
        // testcontainer may have already seeded P80/F77 at DIFFERENT ids;
        // seeding again at a fresh id would create a duplicate cd row and
        // break findByShipviaCdIgnoreCase.
        seedShipViaIfMissing(9201, "P80", "UPS");
        seedShipViaIfMissing(9202, "F77", "FedEx");
        seedShipViaIfMissing(9203, "L01", "USPS");

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USERNAME, "",
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));

        // Connector mocks are Spring singletons — invocation state accumulates
        // across tests. Clear so each test's verify(...) counts start at 0.
        // Stubs from MockCarrierConnectorsTestConfig.prime() survive.
        clearInvocations(upsMock, fedExMock);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        cleanup();
    }

    private void cleanup() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(status -> {
            userRepository.findByUsername(USERNAME).ifPresent(u -> {
                carrierConfigRepository.findAll().stream()
                        .filter(c -> c.getUser() != null && c.getUser().getId() != null
                                && c.getUser().getId().equals(u.getId()))
                        .map(CarrierConfig::getId)
                        .toList()
                        .forEach(carrierConfigRepository::deleteById);
                userRepository.delete(u);
            });
        });
    }

    // ==================================================================
    // Unknown / legacy carrier code
    // ==================================================================

    @Test
    void connect_withUnknownCarrierCode_throwsCarrierConnectionException() {
        CarrierConnectRequest req = CarrierConnectRequest.builder()
                .carrierCode("XYZ_UNKNOWN")
                .clientId("cid").clientSecret("csec").accountNumber("A")
                .environment("SANDBOX")
                .build();

        CarrierConnectionException ex = assertThrows(CarrierConnectionException.class,
                () -> controller.connectToCarrier(req, principal()));
        assertTrue(ex.getMessage().contains("Unsupported carrier"),
                "message must identify the unsupported code path");

        assertNull(carrierConfigForUser("XYZ_UNKNOWN"),
                "unknown-carrier failure must NOT persist a CarrierConfig row");
    }

    @Test
    void connect_withLegacyP80Code_resolvesToUpsAndInvokesUpsConnector() {
        // F-MODE-4 — connect flow now mints token via 4-arg getAccessToken;
        // connector.connect() no longer runs.
        when(upsMock.validateCredentials(anyString(), anyString())).thenReturn(true);
        when(upsMock.getAccessToken(anyString(), anyString(), anyString(), anyString()))
                .thenReturn("tok-legacy-p80");

        CarrierConnectRequest req = CarrierConnectRequest.builder()
                .carrierCode("P80")
                .clientId("cid").clientSecret("csec").accountNumber("P80-1")
                .environment("SANDBOX")
                .build();

        ResponseEntity<ApiResponse<CarrierConnectResponse>> resp = controller.connectToCarrier(req, principal());

        assertEquals(200, resp.getStatusCode().value());
        assertEquals("UPS", resp.getBody().getData().getCarrierCode(),
                "response must emit canonical UPS, never the legacy P80 code");
        assertEquals("P80-1", resp.getBody().getData().getAccountNumber(),
                "response must carry the original account number");

        verify(upsMock, times(1)).getAccessToken(anyString(), anyString(), anyString(), anyString());
    }

    // ==================================================================
    // Tenant-scoped connect
    // ==================================================================

    @Test
    void connect_withTenantId_persistsUppercasedTenantScopedRow() {
        // F-MODE-4 — connect flow mints token via 4-arg getAccessToken;
        // connector.connect() no longer runs.
        when(fedExMock.validateCredentials(anyString(), anyString())).thenReturn(true);
        when(fedExMock.getAccessToken(anyString(), anyString(), anyString(), anyString()))
                .thenReturn("tok-tenant");

        CarrierConnectRequest req = CarrierConnectRequest.builder()
                .carrierCode("FEDEX")
                .clientId("cid").clientSecret("csec").accountNumber("TEN-1")
                .environment("SANDBOX")
                .tenantId(TENANT_ID.toLowerCase())
                .setAsDefault(true)
                .build();

        ResponseEntity<ApiResponse<CarrierConnectResponse>> resp = controller.connectToCarrier(req, principal());
        assertEquals(200, resp.getStatusCode().value());

        Optional<CarrierConfig> tenantScoped = carrierConfigRepository
                .findFirstByUserUsernameAndCarrierCodeAndTenantId(USERNAME, "FEDEX", TENANT_ID);
        assertTrue(tenantScoped.isPresent(),
                "connect with tenantId must persist a tenant-scoped row");
        assertEquals(TENANT_ID, tenantScoped.get().getTenantId(),
                "tenantId must be uppercased in the persisted row");

        Optional<CarrierConfig> platformScoped = carrierConfigRepository
                .findFirstByUserUsernameAndCarrierCodeAndTenantIdIsNull(USERNAME, "FEDEX");
        assertFalse(platformScoped.isPresent(),
                "tenant-scoped row must NOT be returned by the null-tenant finder");
    }

    // ==================================================================
    // Upsert: connect twice → UPDATE, not INSERT
    // ==================================================================

    @Test
    void connect_twiceForSameUserAndCarrier_updatesExistingRow() {
        // F-MODE-4 — connect flow mints token via 4-arg getAccessToken;
        // connector.connect() no longer runs. Return two different tokens
        // to prove the second call minted a fresh one.
        when(upsMock.validateCredentials(anyString(), anyString())).thenReturn(true);
        when(upsMock.getAccessToken(anyString(), anyString(), anyString(), anyString()))
                .thenReturn("tok-1")
                .thenReturn("tok-2");

        CarrierConnectRequest first = CarrierConnectRequest.builder()
                .carrierCode("UPS").clientId("cid1").clientSecret("csec1")
                .accountNumber("UP-1").environment("SANDBOX").build();
        CarrierConnectRequest second = CarrierConnectRequest.builder()
                .carrierCode("UPS").clientId("cid2").clientSecret("csec2")
                .accountNumber("UP-2").environment("PRODUCTION").build();

        controller.connectToCarrier(first, principal());
        ResponseEntity<ApiResponse<CarrierConnectResponse>> secondResp =
                controller.connectToCarrier(second, principal());

        assertEquals(200, secondResp.getStatusCode().value());
        verify(upsMock, times(2)).getAccessToken(anyString(), anyString(), anyString(), anyString());

        // Filter by user id (avoids lazy user.username dereference).
        Long uid = userRepository.findByUsername(USERNAME).orElseThrow().getId();
        long count = carrierConfigRepository.findAll().stream()
                .filter(c -> c.getUser() != null && c.getUser().getId() != null
                        && c.getUser().getId().equals(uid)
                        && "UPS".equals(c.getCarrierCode())
                        && c.getTenantId() == null)
                .count();
        assertEquals(1, count,
                "second connect must UPDATE the existing row, not INSERT a duplicate");
    }

    // ==================================================================
    // Connector-thrown failure surfaces
    // ==================================================================

    @Test
    void connect_whenConnectorThrows_bubblesRuntimeException() {
        // F-MODE-4 — the connect handshake now throws via 4-arg
        // getAccessToken instead of connect(). validateCredentials is
        // called first; stub it to succeed so we reach the token call.
        when(upsMock.validateCredentials(anyString(), anyString())).thenReturn(true);
        when(upsMock.getAccessToken(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new CarrierConnectionException("UPS OAuth rejected"));

        CarrierConnectRequest req = CarrierConnectRequest.builder()
                .carrierCode("UPS").clientId("cid").clientSecret("csec")
                .accountNumber("A").environment("SANDBOX").build();

        CarrierConnectionException ex = assertThrows(CarrierConnectionException.class,
                () -> controller.connectToCarrier(req, principal()));
        assertEquals("UPS OAuth rejected", ex.getMessage());

        assertNull(carrierConfigForUser("UPS"),
                "failed connect must NOT persist a CarrierConfig row");
    }

    // ==================================================================
    // Status + disconnect defensive paths
    // ==================================================================

    @Test
    void status_withNoCarrierConfigPersisted_returns200AndConnectedFalse() {
        ResponseEntity<ApiResponse<CarrierStatusResponse>> resp =
                controller.getCarrierStatus(principal());

        assertEquals(200, resp.getStatusCode().value());
        CarrierStatusResponse body = resp.getBody().getData();
        assertNotNull(body);
        assertFalse(body.getConnected(),
                "status with no persisted CarrierConfig must be connected=false, not a crash");
    }

    @Test
    void disconnect_withNoCarrierConfigPersisted_isIdempotent() {
        ResponseEntity<ApiResponse<CarrierStatusResponse>> resp =
                controller.disconnectCarrier(principal());

        assertEquals(200, resp.getStatusCode().value(),
                "disconnect must succeed idempotently even with nothing to disconnect");
        assertFalse(resp.getBody().getData().getConnected());
    }

    // ==================================================================
    // helpers
    // ==================================================================

    private UserDetails principal() {
        return new org.springframework.security.core.userdetails.User(
                USERNAME, "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private CarrierConfig carrierConfigForUser(String carrierCode) {
        return carrierConfigRepository
                .findFirstByUserUsernameAndCarrierCodeAndTenantIdIsNull(USERNAME, carrierCode)
                .orElse(null);
    }

    /**
     * Seed a ship-via row IF (and only if) no row with the given cd already
     * exists. The primary {@link CarrierControllerIntegrationTest} shares
     * the testcontainer with this class in CI's {@code mvn test} phase; it
     * may have already seeded P80/F77 at a DIFFERENT id. Checking by cd
     * (not id) prevents a duplicate cd row that would break the connect
     * flow's {@code findByShipviaCdIgnoreCase}.
     */
    private void seedShipViaIfMissing(Integer id, String cd, String desc) {
        if (shipViaRepository.findByShipviaCdIgnoreCase(cd).isPresent()) return;
        ShipVia sv = new ShipVia();
        sv.setId(id);
        sv.setShipviaCd(cd);
        sv.setShipviaDesc(desc);
        sv.setActive(true);
        shipViaRepository.save(sv);
    }
}
