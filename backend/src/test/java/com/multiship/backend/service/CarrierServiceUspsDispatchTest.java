package com.multiship.backend.service;

import com.multiship.backend.dto.ShipmentRequestDTO;
import com.multiship.backend.exception.CarrierConnectionException;
import com.multiship.backend.service.carriers.CarrierConnector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * USPS Direct dispatch — pure-Mockito coverage for the
 * {@code USPS_PROVIDER}-driven branch inside
 * {@link CarrierServiceImpl#getCarrierConnector(String)}.
 *
 * <p>Two connectors share {@code carrierCode="USPS"}: the legacy
 * {@code StampsConnector} and the new {@code UspsDirectConnector}. The
 * platform toggle {@code system_setting.USPS_PROVIDER} decides which one
 * wins at dispatch time. This test doubles the two implementations with
 * nested static classes whose {@code getSimpleName()} matches the two
 * real class names ({@code StampsConnector} / {@code UspsDirectConnector})
 * so the {@code getClass().getSimpleName().equals(...)} filter in
 * {@code getCarrierConnector} finds them without dragging in the real
 * connector deps.
 *
 * <p>See {@code docs/usps-direct-integration.md} sections 3 (state machine),
 * 6.5 (dispatch), 6.6 (per-call resolution) for the design.
 *
 * <p>Constructor is invoked reflectively with null collaborators because
 * the branch we exercise only touches {@code carrierConnectors} +
 * {@code systemSettingService}, which we then override via
 * {@link ReflectionTestUtils#setField}. Mirrors the harness used by
 * {@link CarrierServiceImplConnectHelpersTest}.
 */
class CarrierServiceUspsDispatchTest {

    private SystemSettingService systemSettingService;
    private StampsConnector stampsConnector;
    private UspsDirectConnector uspsDirectConnector;
    private FedexConnector fedexConnector;

    private CarrierServiceImpl service;

    /**
     * Minimal {@link CarrierConnector} that returns a fixed carrier code
     * and a stable class simple name. All non-default abstract methods
     * return {@code null} — the dispatch branch under test never calls
     * them, so we don't need to model the carrier envelope.
     */
    abstract static class StubConnector implements CarrierConnector {
        private final String code;
        StubConnector(String code) { this.code = code; }
        @Override public String getCarrierCode() { return code; }
        @Override public String getCarrierName() { return code; }
        @Override public ServiceAvailability listServices(String origin, String tok, String env) { return null; }
        @Override public PackageAvailability listPackages(String origin, String tok, String env) { return null; }
        @Override public CarrierConnectionResult connect(String cid, String cs, String an) { return null; }
        @Override public String getAccessToken(String cid, String cs) { return null; }
        @Override public ShipmentResult createShipment(ShipmentRequestDTO r, String tok, String env) { return null; }
        @Override public boolean validateCredentials(String cid, String cs) { return true; }
        @Override public TrackingResult trackShipment(String tn) { return null; }
        @Override public CarrierConfiguration getConfiguration() { return null; }
    }

    /** Deliberately-named nested class: {@code getSimpleName()} = "StampsConnector".
     *  The dispatch branch filters on this exact simple name — a rename here
     *  must be coordinated with {@link CarrierServiceImpl#getCarrierConnector}. */
    static class StampsConnector extends StubConnector {
        StampsConnector() { super("USPS"); }
    }

    /** Deliberately-named nested class: {@code getSimpleName()} = "UspsDirectConnector". */
    static class UspsDirectConnector extends StubConnector {
        UspsDirectConnector() { super("USPS"); }
    }

    /** Non-USPS baseline; simple name deliberately different — the dispatch
     *  branch must NOT confuse it with the USPS pair. */
    static class FedexConnector extends StubConnector {
        FedexConnector() { super("FEDEX"); }
    }

    @BeforeEach
    void setUp() {
        systemSettingService = mock(SystemSettingService.class);
        stampsConnector = new StampsConnector();
        uspsDirectConnector = new UspsDirectConnector();
        fedexConnector = new FedexConnector();

        service = allocate();
        // The dispatch branch only touches carrierConnectors + (optional)
        // systemSettingService. Overwrite both via reflection so we don't
        // have to spin up the 30-collaborator constructor.
        List<CarrierConnector> connectors =
                List.of(stampsConnector, uspsDirectConnector, fedexConnector);
        ReflectionTestUtils.setField(service, "carrierConnectors", connectors);
        ReflectionTestUtils.setField(service, "systemSettingService", systemSettingService);
    }

    private static CarrierServiceImpl allocate() {
        try {
            Constructor<?>[] ctors = CarrierServiceImpl.class.getDeclaredConstructors();
            Constructor<?> ctor = ctors[0];
            ctor.setAccessible(true);
            Object[] args = new Object[ctor.getParameterCount()];
            return (CarrierServiceImpl) ctor.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to allocate CarrierServiceImpl for dispatch tests", e);
        }
    }

    // ==================================================================
    // USPS branch — provider toggle controls which connector wins
    // ==================================================================

    @Test
    void getCarrierConnector_uspsWithStampsComProvider_returnsStampsConnector() {
        when(systemSettingService.getDecrypted("USPS_PROVIDER"))
                .thenReturn(Optional.of("STAMPS_COM"));

        CarrierConnector resolved = service.getCarrierConnector("USPS");

        assertSame(stampsConnector, resolved,
                "STAMPS_COM must dispatch to the legacy Stamps connector.");
    }

    @Test
    void getCarrierConnector_uspsWithProvisioningProvider_stillReturnsStamps() {
        // Transitional state: platform toggle flipped but operator is still
        // provisioning USPS Direct payment accounts. New labels must keep
        // routing to Stamps until every account carries USPS Direct
        // identifiers — otherwise the FE loses its ability to generate
        // labels on the accounts that haven't been provisioned yet.
        when(systemSettingService.getDecrypted("USPS_PROVIDER"))
                .thenReturn(Optional.of("PROVISIONING_USPS_DIRECT"));

        CarrierConnector resolved = service.getCarrierConnector("USPS");

        assertSame(stampsConnector, resolved,
                "PROVISIONING_USPS_DIRECT is transitional — still dispatch to Stamps.");
    }

    @Test
    void getCarrierConnector_uspsWithUspsDirectProvider_returnsUspsDirectConnector() {
        when(systemSettingService.getDecrypted("USPS_PROVIDER"))
                .thenReturn(Optional.of("USPS_DIRECT"));

        CarrierConnector resolved = service.getCarrierConnector("USPS");

        assertSame(uspsDirectConnector, resolved,
                "USPS_DIRECT must dispatch to the new UspsDirectConnector.");
    }

    @Test
    void getCarrierConnector_uspsSettingAbsent_defaultsToStamps() {
        // Fresh install (row absent) OR service returns empty — must NOT
        // fail closed. Default to Stamps so the existing production
        // behaviour is preserved when the platform toggle hasn't been
        // set yet.
        when(systemSettingService.getDecrypted("USPS_PROVIDER"))
                .thenReturn(Optional.empty());

        CarrierConnector resolved = service.getCarrierConnector("USPS");

        assertSame(stampsConnector, resolved,
                "Missing USPS_PROVIDER must default to Stamps.");
    }

    @Test
    void getCarrierConnector_uspsSettingServiceThrows_defaultsToStamps() {
        // Encryption / DB hiccup must not brick USPS shipping. We log
        // WARN and fall through to the default (Stamps) — same
        // fail-open pattern SystemSettingService itself uses when its
        // key is unset.
        when(systemSettingService.getDecrypted("USPS_PROVIDER"))
                .thenThrow(new RuntimeException("crypto down"));

        CarrierConnector resolved = service.getCarrierConnector("USPS");

        assertSame(stampsConnector, resolved,
                "SystemSettingService failure must fall through to Stamps default.");
    }

    @Test
    void getCarrierConnector_uspsSettingServiceNull_defaultsToStamps() {
        // Pure-Mockito unit tests that don't drive the USPS branch may
        // never wire the SystemSettingService — service must remain
        // usable via the safe default.
        ReflectionTestUtils.setField(service, "systemSettingService", null);

        CarrierConnector resolved = service.getCarrierConnector("USPS");

        assertSame(stampsConnector, resolved,
                "Null SystemSettingService must default to Stamps.");
    }

    // ==================================================================
    // Legacy code + case-insensitive canonical resolution
    // ==================================================================

    @Test
    void getCarrierConnector_legacyL01Code_canonicalisesToUsps_thenBranches() {
        // L01 is the internal ship-via code for USPS; the canonicaliser
        // rewrites it to "USPS" before the branch fires. Sanity-check
        // that the branch still triggers via the legacy code path.
        when(systemSettingService.getDecrypted("USPS_PROVIDER"))
                .thenReturn(Optional.of("USPS_DIRECT"));

        CarrierConnector resolved = service.getCarrierConnector("L01");

        assertSame(uspsDirectConnector, resolved);
    }

    @Test
    void getCarrierConnector_uspsLowercase_stillBranches() {
        // The branch uses equalsIgnoreCase; sanity-check that a
        // lowercase code doesn't slip past it into the generic filter.
        when(systemSettingService.getDecrypted("USPS_PROVIDER"))
                .thenReturn(Optional.of("USPS_DIRECT"));

        CarrierConnector resolved = service.getCarrierConnector("usps");

        assertSame(uspsDirectConnector, resolved);
    }

    // ==================================================================
    // Non-USPS branch — never touches SystemSettingService
    // ==================================================================

    @Test
    void getCarrierConnector_fedex_bypassesUspsBranch_returnsFedexConnector() {
        // FEDEX must not hit the SystemSettingService — the USPS branch
        // is a hard predicate on canonical="USPS". Guard against a
        // regression that would make every carrier lookup consult the
        // toggle unnecessarily.
        CarrierConnector resolved = service.getCarrierConnector("FEDEX");

        assertSame(fedexConnector, resolved);
        // No interaction with the setting service on non-USPS carriers.
        org.mockito.Mockito.verifyNoInteractions(systemSettingService);
    }

    @Test
    void getCarrierConnector_blankCode_throwsCarrierConnectionException() {
        // Guard the "required" precondition — dispatch is meaningless
        // without a carrier code and must fail fast before reaching
        // any branch.
        assertThrows(CarrierConnectionException.class,
                () -> service.getCarrierConnector(""));
        assertThrows(CarrierConnectionException.class,
                () -> service.getCarrierConnector(null));
    }

    @Test
    void getCarrierConnector_uspsButOnlyStampsRegistered_directProviderThrowsMissing() {
        // USPS Direct is enabled but the UspsDirectConnector isn't in
        // the ApplicationContext (misconfiguration). Must surface a
        // clean CarrierConnectionException naming the provider, not
        // fall back to Stamps (which would silently mis-route labels).
        List<CarrierConnector> only = List.of(stampsConnector, fedexConnector);
        ReflectionTestUtils.setField(service, "carrierConnectors", only);
        when(systemSettingService.getDecrypted("USPS_PROVIDER"))
                .thenReturn(Optional.of("USPS_DIRECT"));

        CarrierConnectionException ex = assertThrows(CarrierConnectionException.class,
                () -> service.getCarrierConnector("USPS"));
        assertNotNull(ex.getMessage());
        assertEquals(true, ex.getMessage().contains("USPS_DIRECT"),
                "Error must name the provider so ops can flip the toggle back.");
    }
}
