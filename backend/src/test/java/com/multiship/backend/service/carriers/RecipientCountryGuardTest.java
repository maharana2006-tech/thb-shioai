package com.multiship.backend.service.carriers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.dto.ShipmentRequestDTO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F7 regression tests — every {@code createShipment} entry point now throws
 * on blank recipient country instead of silently defaulting to {@code "US"}.
 *
 * <p>Pre-fix each connector's envelope-build code independently substituted
 * {@code "US"} via {@code nonBlank / firstNonBlank(country, "US")}:
 * <ul>
 *   <li>StampsConnector.java:1685 — appendServiceRate</li>
 *   <li>FedExConnector.java:359, 955 — payload builders</li>
 *   <li>DhlConnector.java:1015 — shared buildParty helper</li>
 * </ul>
 * A blank {@code Order.shiptoCountryCd} on a bulk shipment therefore
 * shipped an international parcel as US domestic (wrong service, wrong
 * price, wrong customs). Post-fix each connector guards at the boundary
 * so the wrong-country label is never printed.
 */
class RecipientCountryGuardTest {

    private static ShipmentRequestDTO minimal(String recipientCountry) {
        return ShipmentRequestDTO.builder()
                .carrierCode("USPS")
                .serviceType("USPS GA")
                .packageType("Package")
                .weight(new BigDecimal("1"))
                .weightUnit("LB")
                .shipperName("Sender")
                .shipperAddressLine1("1 A St")
                .shipperCity("Denver").shipperState("CO").shipperPostalCode("80202").shipperCountryCode("US")
                .recipientName("Recipient")
                .recipientAddressLine1("2 B St")
                .recipientCity("NYC").recipientState("NY").recipientPostalCode("10001")
                .recipientCountryCode(recipientCountry)     // ← under test
                .referenceNumber("PO-9999")
                .build();
    }

    private static CarrierProperties propsWithLocalSwsim() {
        CarrierProperties props = new CarrierProperties();
        CarrierProperties.Stamps s = props.getStamps();
        s.setAuthUrl("http://localhost:1/x"); s.setSandboxAuthUrl("http://localhost:1/x");
        s.setApiBaseUrl("http://localhost:1/x"); s.setSandboxUrl("http://localhost:1/x");
        s.setApiVersion("v135");
        props.setDefaultEnvironment("SANDBOX");
        return props;
    }

    // ===== StampsConnector =====

    @Test
    void stamps_createShipment_blankRecipientCountry_throws() {
        StampsConnector connector = new StampsConnector(propsWithLocalSwsim(), new ObjectMapper());
        // Use a real-shaped token so the -local- guard (F4) doesn't fire first.
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> connector.createShipment(minimal(""),
                        "real-swsim-authenticator-guid-here", "SANDBOX"));
        assertTrue(ex.getMessage().contains("recipient country"),
                "message must name the missing field; got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("PO-9999"),
                "message must name the order for log-correlation; got: " + ex.getMessage());
    }

    @Test
    void stamps_createShipment_nullRecipientCountry_throws() {
        StampsConnector connector = new StampsConnector(propsWithLocalSwsim(), new ObjectMapper());
        assertThrows(IllegalArgumentException.class,
                () -> connector.createShipment(minimal(null),
                        "real-swsim-authenticator-guid-here", "SANDBOX"));
    }

    // ===== FedExConnector =====

    @Test
    void fedex_createShipment_blankRecipientCountry_throws() {
        CarrierProperties props = new CarrierProperties();
        props.setDefaultEnvironment("SANDBOX");
        // FedExConnector takes CarrierProperties + ObjectMapper + FxRateService.
        // Pass null FxRateService — the guard fires BEFORE any FX lookup runs.
        FedExConnector connector = new FedExConnector(props, new ObjectMapper(), null);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> connector.createShipment(minimal(""), "fake-token", "SANDBOX"));
        assertTrue(ex.getMessage().contains("recipient country"),
                "got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("PO-9999"),
                "got: " + ex.getMessage());
    }

    // ===== UpsConnector =====

    @Test
    void ups_createShipment_blankRecipientCountry_throws() {
        CarrierProperties props = new CarrierProperties();
        props.setDefaultEnvironment("SANDBOX");
        UpsConnector connector = new UpsConnector(props, new ObjectMapper());
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> connector.createShipment(minimal(""), "fake-token", "SANDBOX"));
        assertTrue(ex.getMessage().contains("recipient country"),
                "got: " + ex.getMessage());
    }

    // ===== DhlConnector =====

    @Test
    void dhl_createShipment_blankRecipientCountry_throws() {
        CarrierProperties props = new CarrierProperties();
        props.setDefaultEnvironment("SANDBOX");
        DhlConnector connector = new DhlConnector(props, new ObjectMapper());
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> connector.createShipment(minimal(""), "fake-token", "SANDBOX"));
        assertTrue(ex.getMessage().contains("recipient country"),
                "got: " + ex.getMessage());
    }

    // ===== FDX-B1 — FedEx rate-shop + EDT paths =====
    //
    // Pre-fix, FedExConnector.buildRateRequestBody + estimateLandedCost both
    // defaulted blank countries to "US" and returned believable-but-wrong
    // domestic quotes. F7 fixed createShipment; these tests lock the rate
    // paths against the same regression class.

    @Test
    void fedex_getRates_blankRecipientCountry_throws() {
        CarrierProperties props = new CarrierProperties();
        props.setDefaultEnvironment("SANDBOX");
        FedExConnector connector = new FedExConnector(props, new ObjectMapper(), null);
        // Use a real-looking token so the -local- guard doesn't short-circuit
        // to an empty list before our country guard runs.
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> connector.getRates(minimal(""), "real-oauth-bearer-token", "SANDBOX"));
        assertTrue(ex.getMessage().contains("recipient country"),
                "got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("US-domestic"),
                "message should explain the silent-fallback risk; got: " + ex.getMessage());
    }

    @Test
    void fedex_getRates_nullRecipientCountry_throws() {
        CarrierProperties props = new CarrierProperties();
        props.setDefaultEnvironment("SANDBOX");
        FedExConnector connector = new FedExConnector(props, new ObjectMapper(), null);
        assertThrows(IllegalArgumentException.class,
                () -> connector.getRates(minimal(null), "real-oauth-bearer-token", "SANDBOX"));
    }

    @Test
    void fedex_estimateLandedCost_blankRecipientCountry_throws() {
        CarrierProperties props = new CarrierProperties();
        props.setDefaultEnvironment("SANDBOX");
        FedExConnector connector = new FedExConnector(props, new ObjectMapper(), null);
        // EDT's isInternational check would silently short-circuit to
        // NOT_SUPPORTED when both countries are blank/equal — the guard
        // must fire FIRST so a config bug becomes a loud error, not a
        // suppressed duty estimate.
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> connector.estimateLandedCost(minimal(""), "real-oauth-bearer-token", "SANDBOX"));
        assertTrue(ex.getMessage().contains("recipient country"),
                "got: " + ex.getMessage());
    }

    // Sanity guard — the -local-* short-circuit MUST still fire before the
    // country guard, matching every other connector method's pattern.
    // Otherwise a rate-shop from a caller with only fallback credentials
    // (rate-comparison background job) would throw instead of returning
    // an empty list.
    @Test
    void fedex_getRates_localToken_shortCircuits_evenWithoutCountry() {
        CarrierProperties props = new CarrierProperties();
        props.setDefaultEnvironment("SANDBOX");
        FedExConnector connector = new FedExConnector(props, new ObjectMapper(), null);
        java.util.List<CarrierConnector.RateOption> rates = connector.getRates(
                minimal(""), "fedex-local-abc123", "SANDBOX");
        assertTrue(rates.isEmpty(),
                "-local- tokens must short-circuit to empty regardless of country presence");
    }

    // ===== FDX-B2 — UPS rate-shop path =====

    @Test
    void ups_getRates_blankRecipientCountry_throws() {
        CarrierProperties props = new CarrierProperties();
        props.setDefaultEnvironment("SANDBOX");
        UpsConnector connector = new UpsConnector(props, new ObjectMapper());
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> connector.getRates(minimal(""), "real-oauth-bearer-token", "SANDBOX"));
        assertTrue(ex.getMessage().contains("recipient country"),
                "got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("US-domestic"),
                "message should explain the silent-fallback risk; got: " + ex.getMessage());
    }

    @Test
    void ups_getRates_localToken_shortCircuits_evenWithoutCountry() {
        // -local- guard must fire before the country guard (same invariant
        // as FedEx above; rate-comparison background jobs on fallback
        // credentials return [] instead of throwing).
        CarrierProperties props = new CarrierProperties();
        props.setDefaultEnvironment("SANDBOX");
        UpsConnector connector = new UpsConnector(props, new ObjectMapper());
        java.util.List<CarrierConnector.RateOption> rates = connector.getRates(
                minimal(""), "ups-local-xyz", "SANDBOX");
        assertTrue(rates.isEmpty(),
                "-local- tokens must short-circuit to empty regardless of country presence");
    }

    // ===== FDX-B3 — DHL rate-shop path =====

    @Test
    void dhl_getRates_blankRecipientCountry_throws() {
        CarrierProperties props = new CarrierProperties();
        props.setDefaultEnvironment("SANDBOX");
        DhlConnector connector = new DhlConnector(props, new ObjectMapper());
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> connector.getRates(minimal(""), "real-basic-auth-token", "SANDBOX"));
        assertTrue(ex.getMessage().contains("recipient country"),
                "got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("US-domestic"),
                "message should explain the silent-fallback risk; got: " + ex.getMessage());
    }

    @Test
    void dhl_getRates_localToken_shortCircuits_evenWithoutCountry() {
        CarrierProperties props = new CarrierProperties();
        props.setDefaultEnvironment("SANDBOX");
        DhlConnector connector = new DhlConnector(props, new ObjectMapper());
        java.util.List<CarrierConnector.RateOption> rates = connector.getRates(
                minimal(""), "dhl-local-xyz", "SANDBOX");
        assertTrue(rates.isEmpty(),
                "-local- tokens must short-circuit to empty regardless of country presence");
    }
}
