package com.multiship.backend.service;

import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.exception.CarrierConnectionException;
import com.multiship.backend.model.ShipVia;
import com.multiship.backend.model.User;
import com.multiship.backend.repository.ShipViaRepository;
import com.multiship.backend.repository.UserRepository;
import com.multiship.backend.service.carriers.CarrierConnector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Sprint 53 /settings/carriers page-tests (slice: BE-svc-impl-status resolvers).
 *
 * <p>Covers the resolver + lookup surface {@code CarrierServiceImpl} exposes
 * to the status / disconnect / getAvailableCarriers paths:
 *
 * <ul>
 *   <li>{@code getCarrierConnector(String)} — the public connector-lookup
 *       used by every status flow. Positive: canonical + legacy codes,
 *       case-insensitive. Negative: null / blank / unknown.</li>
 *   <li>{@code resolveUser(UserDetails)} — private, invoked via reflection.
 *       Positive: happy lookup. Negative: null actor, blank username,
 *       user not found.</li>
 *   <li>{@code resolveCarrierCode(User)} — private. Positive: preferred
 *       carrier on the user. Negative: user has none → falls back to
 *       {@code CarrierProperties.defaultCarrierCode}.</li>
 *   <li>{@code resolveShipVia(String)} — private. Positive: happy. Negative:
 *       missing ship-via row → CarrierConnectionException.</li>
 * </ul>
 *
 * <p>Instance built with only the fields these methods touch; the rest
 * stay null. This is by design — the full 30-collaborator harness is
 * documented on the {@link CarrierServiceImplTest} placeholder and
 * remains a future-sprint deliverable.
 */
class CarrierServiceImplStatusResolversTest {

    private CarrierConnector upsConnector;
    private CarrierConnector fedexConnector;
    private CarrierConnector uspsConnector;

    private UserRepository userRepository;
    private ShipViaRepository shipViaRepository;
    private CarrierProperties carrierProperties;

    private CarrierServiceImpl impl;

    @BeforeEach
    void setUp() throws Exception {
        upsConnector = mock(CarrierConnector.class);
        when(upsConnector.getCarrierCode()).thenReturn("UPS");
        when(upsConnector.getCarrierName()).thenReturn("UPS");

        fedexConnector = mock(CarrierConnector.class);
        when(fedexConnector.getCarrierCode()).thenReturn("FEDEX");
        when(fedexConnector.getCarrierName()).thenReturn("FedEx");

        uspsConnector = mock(CarrierConnector.class);
        when(uspsConnector.getCarrierCode()).thenReturn("USPS");
        when(uspsConnector.getCarrierName()).thenReturn("USPS");

        userRepository = mock(UserRepository.class);
        shipViaRepository = mock(ShipViaRepository.class);
        carrierProperties = mock(CarrierProperties.class);

        impl = allocate();
        ReflectionTestUtils.setField(impl, "carrierConnectors",
                List.of(upsConnector, fedexConnector, uspsConnector));
        ReflectionTestUtils.setField(impl, "userRepository", userRepository);
        ReflectionTestUtils.setField(impl, "shipViaRepository", shipViaRepository);
        ReflectionTestUtils.setField(impl, "carrierProperties", carrierProperties);
    }

    /** All-null-args allocation — the fields we care about get set via
     *  ReflectionTestUtils. Every other collaborator stays null; the
     *  code paths under test don't touch them. */
    private static CarrierServiceImpl allocate() throws Exception {
        Constructor<?>[] ctors = CarrierServiceImpl.class.getDeclaredConstructors();
        Constructor<?> ctor = ctors[0];
        ctor.setAccessible(true);
        return (CarrierServiceImpl) ctor.newInstance(new Object[ctor.getParameterCount()]);
    }

    // ==================================================================
    // getCarrierConnector — the ONE lookup every carrier flow depends on
    // ==================================================================

    @Test
    void getCarrierConnector_upsCanonical_returnsUpsConnector() {
        assertSame(upsConnector, impl.getCarrierConnector("UPS"));
    }

    @Test
    void getCarrierConnector_fedexCanonical_returnsFedexConnector() {
        assertSame(fedexConnector, impl.getCarrierConnector("FEDEX"));
    }

    @Test
    void getCarrierConnector_uspsCanonical_returnsUspsConnector() {
        assertSame(uspsConnector, impl.getCarrierConnector("USPS"));
    }

    @Test
    void getCarrierConnector_legacyP80_resolvesToUps() {
        // Legacy ship-via codes go through resolveCanonicalCarrierCode
        // before the connector filter runs.
        assertSame(upsConnector, impl.getCarrierConnector("P80"));
    }

    @Test
    void getCarrierConnector_legacyF77_resolvesToFedex() {
        assertSame(fedexConnector, impl.getCarrierConnector("F77"));
    }

    @Test
    void getCarrierConnector_legacyL01_resolvesToUsps() {
        assertSame(uspsConnector, impl.getCarrierConnector("L01"));
    }

    @Test
    void getCarrierConnector_lowercaseCanonicalIsAccepted() {
        // Callers occasionally pass lowercase — the API contract is
        // upper-cased internally, so this MUST resolve.
        assertSame(upsConnector, impl.getCarrierConnector("ups"));
    }

    @Test
    void getCarrierConnector_nullCode_throwsCarrierConnectionException() {
        CarrierConnectionException ex = assertThrows(CarrierConnectionException.class,
                () -> impl.getCarrierConnector(null));
        assertEquals("Carrier code is required.", ex.getMessage());
    }

    @Test
    void getCarrierConnector_blankCode_throwsCarrierConnectionException() {
        CarrierConnectionException ex = assertThrows(CarrierConnectionException.class,
                () -> impl.getCarrierConnector("   "));
        assertEquals("Carrier code is required.", ex.getMessage());
    }

    @Test
    void getCarrierConnector_unknownCode_throwsCarrierConnectionExceptionWithOriginalCode() {
        // Documented behaviour: the exception message carries the
        // caller-supplied code (not the uppercased canonical), so
        // operators see exactly what they sent.
        CarrierConnectionException ex = assertThrows(CarrierConnectionException.class,
                () -> impl.getCarrierConnector("DHL"));
        assertEquals("Unsupported carrier: DHL", ex.getMessage());
    }

    // ==================================================================
    // resolveUser (private via reflection)
    // ==================================================================

    @Test
    void resolveUser_null_throwsCarrierConnectionException() {
        CarrierConnectionException ex = assertThrows(CarrierConnectionException.class,
                () -> ReflectionTestUtils.invokeMethod(impl, "resolveUser", (Object) null));
        assertEquals("Authenticated user is required.", ex.getMessage());
    }

    @Test
    void resolveUser_blankUsername_throwsCarrierConnectionException() {
        UserDetails principal = mock(UserDetails.class);
        when(principal.getUsername()).thenReturn("   ");
        CarrierConnectionException ex = assertThrows(CarrierConnectionException.class,
                () -> ReflectionTestUtils.invokeMethod(impl, "resolveUser", principal));
        assertEquals("Authenticated user is required.", ex.getMessage());
    }

    @Test
    void resolveUser_notFoundInRepo_throwsCarrierConnectionExceptionWithUsername() {
        UserDetails principal = mock(UserDetails.class);
        when(principal.getUsername()).thenReturn("ghost@acme");
        when(userRepository.findByUsername("ghost@acme")).thenReturn(Optional.empty());
        CarrierConnectionException ex = assertThrows(CarrierConnectionException.class,
                () -> ReflectionTestUtils.invokeMethod(impl, "resolveUser", principal));
        assertEquals("User not found: ghost@acme", ex.getMessage());
    }

    @Test
    void resolveUser_happyPath_returnsUserFromRepo() {
        UserDetails principal = mock(UserDetails.class);
        when(principal.getUsername()).thenReturn("alice@acme");
        User expected = new User();
        expected.setUsername("alice@acme");
        when(userRepository.findByUsername("alice@acme")).thenReturn(Optional.of(expected));
        User actual = ReflectionTestUtils.invokeMethod(impl, "resolveUser", principal);
        assertSame(expected, actual);
    }

    // ==================================================================
    // resolveCarrierCode (private via reflection)
    // ==================================================================

    @Test
    void resolveCarrierCode_userHasPreferred_returnsPreferred() {
        User u = new User();
        u.setPreferredCarrier("UPS");
        String out = ReflectionTestUtils.invokeMethod(impl, "resolveCarrierCode", u);
        assertEquals("UPS", out);
    }

    @Test
    void resolveCarrierCode_userHasBlankPreferred_fallsBackToPropertiesDefault() {
        User u = new User();
        u.setPreferredCarrier("");
        when(carrierProperties.getDefaultCarrierCode()).thenReturn("FEDEX");
        String out = ReflectionTestUtils.invokeMethod(impl, "resolveCarrierCode", u);
        assertEquals("FEDEX", out);
    }

    @Test
    void resolveCarrierCode_userHasNullPreferred_fallsBackToPropertiesDefault() {
        User u = new User();
        u.setPreferredCarrier(null);
        when(carrierProperties.getDefaultCarrierCode()).thenReturn("UPS");
        String out = ReflectionTestUtils.invokeMethod(impl, "resolveCarrierCode", u);
        assertEquals("UPS", out);
    }

    // ==================================================================
    // resolveShipVia (private via reflection)
    // ==================================================================

    @Test
    void resolveShipVia_happyPath_returnsRepoRow() {
        ShipVia sv = new ShipVia();
        when(shipViaRepository.findByShipviaCdIgnoreCase("P80")).thenReturn(Optional.of(sv));
        ShipVia actual = ReflectionTestUtils.invokeMethod(impl, "resolveShipVia", "P80");
        assertSame(sv, actual);
    }

    @Test
    void resolveShipVia_missing_throwsCarrierConnectionExceptionWithCode() {
        when(shipViaRepository.findByShipviaCdIgnoreCase(anyString())).thenReturn(Optional.empty());
        CarrierConnectionException ex = assertThrows(CarrierConnectionException.class,
                () -> ReflectionTestUtils.invokeMethod(impl, "resolveShipVia", "ZZZ"));
        assertEquals("ShipVia row not found for carrier ZZZ", ex.getMessage());
    }

    // ==================================================================
    // firstNonBlank / firstNonNull — pure helpers used across every
    // status-response builder.
    // ==================================================================

    @Test
    void firstNonBlank_returnsFirstNonBlankString() {
        Object out = ReflectionTestUtils.invokeMethod(impl, "firstNonBlank",
                (Object) new String[]{"  ", "", "found", "later"});
        assertEquals("found", out);
    }

    @Test
    void firstNonBlank_allBlankReturnsNull() {
        Object out = ReflectionTestUtils.invokeMethod(impl, "firstNonBlank",
                (Object) new String[]{"  ", "", null});
        assertNull(out);
    }

    @Test
    void firstNonBlank_nonStringValuesShortCircuitOnNonNull() {
        // Documented: for non-String Ts, the first non-null wins.
        Integer expected = 42;
        Object out = ReflectionTestUtils.invokeMethod(impl, "firstNonBlank",
                (Object) new Integer[]{null, expected, 99});
        assertEquals(expected, out);
    }

    @Test
    void firstNonNull_firstIsNull_returnsSecond() {
        Object out = ReflectionTestUtils.invokeMethod(impl, "firstNonNull", null, "b");
        assertEquals("b", out);
    }

    @Test
    void firstNonNull_firstIsNotNull_returnsFirst() {
        Object out = ReflectionTestUtils.invokeMethod(impl, "firstNonNull", "a", "b");
        assertEquals("a", out);
    }

    @Test
    void firstNonNull_bothNull_returnsNull() {
        Object out = ReflectionTestUtils.invokeMethod(impl, "firstNonNull", null, null);
        assertNull(out);
    }

    // ==================================================================
    // resolveConnectorName — thin wrapper that delegates to getCarrierConnector.
    // ==================================================================

    @Test
    void resolveConnectorName_knownCarrier_returnsConnectorName() {
        String out = ReflectionTestUtils.invokeMethod(impl, "resolveConnectorName", "UPS");
        assertEquals("UPS", out);
    }

    @Test
    void resolveConnectorName_unknownCarrier_bubblesConnectorException() {
        assertThrows(CarrierConnectionException.class,
                () -> ReflectionTestUtils.invokeMethod(impl, "resolveConnectorName", "DHL"));
    }
}
