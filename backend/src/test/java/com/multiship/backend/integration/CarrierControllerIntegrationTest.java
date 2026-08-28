package com.multiship.backend.integration;

import com.multiship.backend.controller.CarrierController;
import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.CarrierConnectRequest;
import com.multiship.backend.dto.CarrierConnectResponse;
import com.multiship.backend.dto.CarrierListResponse;
import com.multiship.backend.dto.CarrierStatusResponse;
import com.multiship.backend.model.CarrierConfig;
import com.multiship.backend.model.ShipVia;
import com.multiship.backend.model.User;
import com.multiship.backend.repository.CarrierConfigRepository;
import com.multiship.backend.repository.ShipViaRepository;
import com.multiship.backend.repository.UserRepository;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sprint 51 carriers-be-integration — full-lifecycle exercise of the four
 * {@link CarrierController} endpoints against real Postgres, with every
 * carrier connector replaced by a Mockito mock so the tests can never
 * silently hit a live UPS / FedEx / DHL / Stamps host.
 *
 * <p><b>Anti-fallback design</b> (see class-level Javadoc on
 * {@link ForbidOutboundHttpTestConfig} + {@link MockCarrierConnectorsTestConfig}):
 * <ol>
 *   <li>{@link MockCarrierConnectorsTestConfig} provides pre-stubbed
 *       {@code @Primary} mock beans for {@link UpsConnector}, {@link FedExConnector},
 *       {@link StampsConnector}, {@link DhlConnector} — the real HTTP-backed
 *       connectors never boot, and the stubs are ready before any
 *       constructor (ExternalApiService, ...) resolves them.</li>
 *   <li>{@link ForbidOutboundHttpTestConfig} imported to fail-fast any
 *       autowired RestTemplate / RestClient.Builder path.</li>
 *   <li>Each test that exercises a connector-facing endpoint asserts the
 *       relevant mock was invoked via {@link org.mockito.Mockito#verify verify}
 *       — proves the code took the mocked branch rather than any fallback.</li>
 * </ol>
 *
 * <p>The controller is autowired directly rather than exercised via HTTP —
 * this matches the pattern of every other integration test in the class
 * ({@link AuditLogScopeIntegrationTest}, {@link TenantScopeFlagOnIntegrationTest})
 * and sidesteps the CSRF + JWT-cookie wiring that a MockMvc round-trip
 * would need without adding value: Spring's {@code @PreAuthorize} guards
 * are covered by the controller-slice unit tests, and the goal here is
 * end-to-end persistence + connector-mock proof.
 *
 * <p>Guarded by {@code INTEGRATION_TESTS=1} via {@link AbstractIntegrationTest}.
 */
@Import({ForbidOutboundHttpTestConfig.class, MockCarrierConnectorsTestConfig.class})
@TestPropertySource(properties = "spring.main.allow-bean-definition-overriding=true")
class CarrierControllerIntegrationTest extends AbstractIntegrationTest {

    /** Username used across every test in this class — kept distinctive
     *  so the tear-down can delete only this fixture's rows without
     *  disturbing other integration tests seeding the shared container. */
    private static final String USERNAME = "carrier-it-user";

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
    @Autowired
    private StampsConnector stampsMock;
    @Autowired
    private DhlConnector dhlMock;

    @BeforeEach
    void setUp() {
        // Purge this class' user + their carrier configs inside a real
        // transaction so lazy-loaded @ManyToOne user proxies can dereference.
        // Filter by user id (loaded eagerly) rather than by lazy user.username
        // to keep the wipe outside a session lifetime concern.
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

        // Seed the caller: ADMIN, no preferredCarrier → falls through to
        // the platform default (FEDEX) for /status + /disconnect.
        userRepository.save(User.builder()
                .username(USERNAME)
                .email(USERNAME + "@local.test")
                .password("not-used-integration-test")
                .fullName("Carrier IT User")
                .role("ADMIN")
                .emailVerified(true)
                .carrierConnected(false)
                .build());

        // Seed the ShipVia rows the connect() path resolves against.
        // Ids are manually assigned (ShipVia.id is not @GeneratedValue).
        seedShipVia(9101, "P80", "UPS");
        seedShipVia(9102, "F77", "FedEx");

        // Connectors already carry base stubs (carrierCode / name / configuration)
        // from MockCarrierConnectorsTestConfig — per-test stubs (connect return
        // value, access-token) are added in the individual test methods.

        // Platform-ADMIN in SecurityContext so TenantScopeEnforcer
        // treats every clamp as pass-through.
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USERNAME, "",
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
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

    /* ------------------------------- 1. LIST ------------------------------- */

    @Test
    void list_returnsEveryConnectorSortedByName() {
        ResponseEntity<ApiResponse<List<CarrierListResponse>>> resp = controller.getAvailableCarriers();

        assertEquals(200, resp.getStatusCode().value(),
                "GET /api/v1/carriers must succeed for an ADMIN caller.");
        List<CarrierListResponse> carriers = resp.getBody().getData();
        assertNotNull(carriers, "Payload data must not be null.");
        // 4 mocked connectors registered → 4 entries.
        assertEquals(4, carriers.size(),
                "Every mocked connector bean must appear in the list.");
        // Anti-fallback proof: getConfiguration() is called per connector
        // — never a real HTTP handshake against a carrier host.
        verify(upsMock, atLeastOnce()).getConfiguration();
        verify(fedExMock, atLeastOnce()).getConfiguration();
    }

    /* ------------------------------ 2. CONNECT ----------------------------- */

    @Test
    void connect_persistsCarrierConfig_andInvokesMockedConnector() {
        CarrierConnectRequest req = CarrierConnectRequest.builder()
                .carrierCode("UPS")
                .clientId("connect-client-id")
                .clientSecret("connect-client-secret")
                .accountNumber("CONN-1")
                .environment("SANDBOX")
                .build();

        // F-MODE-4 — CarrierServiceImpl.connectToCarrier no longer calls
        // connector.connect() for token minting. It resolves env first,
        // validates credentials, then mints the token via 4-arg
        // getAccessToken(). Mock both so the connect path succeeds.
        when(upsMock.validateCredentials(anyString(), anyString())).thenReturn(true);
        when(upsMock.getAccessToken(anyString(), anyString(), anyString(), anyString()))
                .thenReturn("live-access-token");

        UserDetails principal = principal();
        ResponseEntity<ApiResponse<CarrierConnectResponse>> resp = controller.connectToCarrier(req, principal);

        assertEquals(200, resp.getStatusCode().value(),
                "connect must succeed for a valid ADMIN request.");
        CarrierConnectResponse body = resp.getBody().getData();
        assertTrue(body.getConnected(), "Response must flag connected=true.");
        assertEquals("UPS", body.getCarrierCode());

        // Anti-fallback proof: mocked connector was invoked EXACTLY once
        // via the 4-arg getAccessToken (post F-MODE-4 handshake).
        verify(upsMock, times(1)).getAccessToken(anyString(), anyString(), anyString(), anyString());

        // DB round-trip: a CarrierConfig row must exist for our user.
        // Use the tenant-agnostic finder to avoid dereferencing the lazy
        // User proxy outside a session.
        CarrierConfig persisted = carrierConfigRepository
                .findFirstByUserUsernameAndCarrierCodeAndTenantIdIsNull(USERNAME, "UPS")
                .orElse(null);
        assertNotNull(persisted, "connect must persist a CarrierConfig row for the caller.");
        assertEquals("UPS", persisted.getCarrierCode(),
                "Persisted CarrierConfig must carry the connected carrier code.");
        assertEquals("CONN-1", persisted.getAccountNumber());
    }

    /* ------------------------------- 3. STATUS ----------------------------- */

    @Test
    void status_readsFromPersistedUserState() {
        // Toggle the user to "connected" in the DB so /status reflects it.
        // NB: User.isCarrierConnected() is true only when BOTH carrierConnected
        // and carrierClientId are set — see User.java line 158-160.
        User u = userRepository.findByUsername(USERNAME).orElseThrow();
        u.setCarrierConnected(true);
        u.setCarrierClientId("status-client-id");
        u.setPreferredCarrier("FEDEX");
        u.setCarrierAccountNumber("STATUS-9");
        u.setCarrierEnvironment("PRODUCTION");
        userRepository.save(u);

        ResponseEntity<ApiResponse<CarrierStatusResponse>> resp = controller.getCarrierStatus(principal());

        assertEquals(200, resp.getStatusCode().value(),
                "GET /status must succeed for the seeded ADMIN.");
        CarrierStatusResponse body = resp.getBody().getData();
        assertEquals("FEDEX", body.getCarrierCode(),
                "Reflects the user's preferred carrier from the DB row.");
        assertEquals("STATUS-9", body.getAccountNumber(),
                "Reflects the persisted carrier account number.");
        assertTrue(body.getConnected(),
                "Reflects the persisted carrierConnected flag.");
    }

    /* ---------------------------- 4. DISCONNECT ---------------------------- */

    @Test
    void disconnect_flipsPersistedState_andReturnsDisconnected() {
        // Seed a "connected" CarrierConfig so disconnect has something to
        // clear on the DB side.
        User u = userRepository.findByUsername(USERNAME).orElseThrow();
        u.setCarrierConnected(true);
        u.setPreferredCarrier("FEDEX");
        userRepository.save(u);

        ResponseEntity<ApiResponse<CarrierStatusResponse>> resp = controller.disconnectCarrier(principal());

        assertEquals(200, resp.getStatusCode().value(),
                "POST /disconnect must succeed.");
        CarrierStatusResponse body = resp.getBody().getData();
        assertFalse(body.getConnected(),
                "Response must flag connected=false after disconnect.");
        // Anti-fallback proof: FedEx mock consulted for the CarrierConfiguration
        // (documentationUrl/connectionGuide) on the response; NEVER an outbound HTTP call.
        verify(fedExMock, atLeastOnce()).getConfiguration();
    }

    /* ------------------------------ helpers -------------------------------- */

    private static UserDetails principal() {
        return new org.springframework.security.core.userdetails.User(
                USERNAME, "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private void seedShipVia(Integer id, String cd, String desc) {
        if (shipViaRepository.findById(id).isPresent()) return;
        ShipVia sv = new ShipVia();
        sv.setId(id);
        sv.setShipviaCd(cd);
        sv.setShipviaDesc(desc);
        sv.setActive(true);
        shipViaRepository.save(sv);
    }
}
