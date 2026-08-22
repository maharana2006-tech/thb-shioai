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
}
