package com.multiship.backend.service.carriers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.service.fx.FxRateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden-value tests for FedEx tracking response parsing. Reflection into the
 * private {@code parseScanEvents} + helper methods so we don't need a live
 * FedEx sandbox for the tests. The response JSON strings match the shapes
 * FedEx's Track API v1 documentation publishes.
 *
 * <p>The 1-arg URL-only stub and the 2-arg local-fallback short-circuit are
 * tested directly against the public interface.
 */
class FedExTrackingTest {

    private FedExConnector connector;

    @BeforeEach
    void setUp() {
        CarrierProperties props = new CarrierProperties();
        props.getFedEx().setLabelResponseOption("URL_ONLY");
        FxRateService noFx = new FxRateService() {
            @Override public Optional<BigDecimal> rate(String f, String t) { return Optional.empty(); }
            @Override public Optional<BigDecimal> convert(BigDecimal a, String f, String t) { return Optional.empty(); }
            @Override public boolean supports(String c) { return false; }
        };
        connector = new FedExConnector(props, new ObjectMapper(), noFx);
    }

    @Test
    void oneArgStubReturnsUrlOnly() {
        var result = connector.trackShipment("123456789012");
        assertEquals("123456789012", result.trackingNumber());
        assertEquals("UNKNOWN", result.status());
        assertTrue(result.trackingUrl().contains("fedextrack"));
        assertFalse(result.delivered());
        assertTrue(result.events().isEmpty());
    }

    @Test
    void localFallbackTokenShortCircuitsToStub() {
        // -local- token = FedEx auth couldn't get a real token, don't hit the
        // API (it would 401), just return the URL-only stub.
        var result = connector.trackShipment("123", "fedex-local-abcd1234", null);
        assertEquals("UNKNOWN", result.status());
        assertTrue(result.events().isEmpty());
    }

    @Test
    void blankTokenShortCircuitsToStub() {
        var result = connector.trackShipment("123", "", null);
        assertEquals("UNKNOWN", result.status());
    }

    @Test
    void nullTokenShortCircuitsToStub() {
        var result = connector.trackShipment("123", null, null);
        assertEquals("UNKNOWN", result.status());
    }

    @Test
    void buildLocationHandlesFullNode() throws Exception {
        Method m = FedExConnector.class.getDeclaredMethod("buildLocation",
                com.fasterxml.jackson.databind.JsonNode.class);
        m.setAccessible(true);
        var node = new ObjectMapper().readTree("""
                {"city": "New York", "stateOrProvinceCode": "NY", "countryCode": "US"}""");
        assertEquals("New York, NY US", m.invoke(null, node));
    }

    @Test
    void buildLocationHandlesPartialNode() throws Exception {
        Method m = FedExConnector.class.getDeclaredMethod("buildLocation",
                com.fasterxml.jackson.databind.JsonNode.class);
        m.setAccessible(true);
        var node = new ObjectMapper().readTree("""
                {"city": "London", "countryCode": "GB"}""");
        assertEquals("London GB", m.invoke(null, node));
    }

    @Test
    void buildLocationHandlesMissingNode() throws Exception {
        Method m = FedExConnector.class.getDeclaredMethod("buildLocation",
                com.fasterxml.jackson.databind.JsonNode.class);
        m.setAccessible(true);
        assertNull(m.invoke(null, new ObjectMapper().readTree("{}")));
        assertNull(m.invoke(null, (Object) null));
    }

    @Test
    void parseScanEventsReversesToOldestFirst() throws Exception {
        Method m = FedExConnector.class.getDeclaredMethod("parseScanEvents",
                com.fasterxml.jackson.databind.JsonNode.class);
        m.setAccessible(true);
        // FedEx returns newest first — our parser should reverse to oldest.
        var scanEvents = new ObjectMapper().readTree("""
                [
                  {"date": "2024-01-16T14:00:00-05:00", "eventType": "DL",
                   "eventDescription": "Delivered",
                   "scanLocation": {"city": "New York", "stateOrProvinceCode": "NY", "countryCode": "US"}},
                  {"date": "2024-01-16T08:30:00-05:00", "eventType": "OD",
                   "eventDescription": "On FedEx vehicle for delivery",
                   "scanLocation": {"city": "New York", "stateOrProvinceCode": "NY", "countryCode": "US"}},
                  {"date": "2024-01-15T20:15:00-05:00", "eventType": "AR",
                   "eventDescription": "Arrived at FedEx location",
                   "scanLocation": {"city": "Memphis", "stateOrProvinceCode": "TN", "countryCode": "US"}}
                ]""");
        @SuppressWarnings("unchecked")
        List<com.multiship.backend.service.carriers.CarrierConnector.TrackingEvent> events =
                (List<com.multiship.backend.service.carriers.CarrierConnector.TrackingEvent>) m.invoke(connector, scanEvents);

        assertEquals(3, events.size());
        // Oldest first
        assertEquals("Arrived at FedEx location", events.get(0).description());
        assertEquals("AR", events.get(0).status());
        assertEquals("Memphis, TN US", events.get(0).location());
        assertEquals("Delivered", events.get(2).description());
        assertEquals("DL", events.get(2).status());
        assertNotNull(events.get(2).timestamp());
    }

    @Test
    void parseScanEventsHandlesEmpty() throws Exception {
        Method m = FedExConnector.class.getDeclaredMethod("parseScanEvents",
                com.fasterxml.jackson.databind.JsonNode.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<com.multiship.backend.service.carriers.CarrierConnector.TrackingEvent> events =
                (List<com.multiship.backend.service.carriers.CarrierConnector.TrackingEvent>) m.invoke(
                        connector, new ObjectMapper().readTree("[]"));
        assertTrue(events.isEmpty());
    }

    @Test
    void parseScanEventsHandlesMissingNode() throws Exception {
        Method m = FedExConnector.class.getDeclaredMethod("parseScanEvents",
                com.fasterxml.jackson.databind.JsonNode.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<com.multiship.backend.service.carriers.CarrierConnector.TrackingEvent> events =
                (List<com.multiship.backend.service.carriers.CarrierConnector.TrackingEvent>) m.invoke(
                        connector, new ObjectMapper().readTree("null"));
        assertTrue(events.isEmpty());
    }

    @Test
    void findDateAndTimeMatchesRequestedType() throws Exception {
        Method m = FedExConnector.class.getDeclaredMethod("findDateAndTime",
                com.fasterxml.jackson.databind.JsonNode.class, String.class);
        m.setAccessible(true);
        var arr = new ObjectMapper().readTree("""
                [
                  {"type": "ACTUAL_PICKUP", "dateTime": "2024-01-15T10:00:00-05:00"},
                  {"type": "ESTIMATED_DELIVERY", "dateTime": "2024-01-16T17:00:00-05:00"}
                ]""");
        Object result = m.invoke(connector, arr, "ESTIMATED_DELIVERY");
        assertNotNull(result);
        // Case-insensitive matching
        result = m.invoke(connector, arr, "estimated_delivery");
        assertNotNull(result);
    }

    @Test
    void findDateAndTimeReturnsNullForUnmatchedType() throws Exception {
        Method m = FedExConnector.class.getDeclaredMethod("findDateAndTime",
                com.fasterxml.jackson.databind.JsonNode.class, String.class);
        m.setAccessible(true);
        var arr = new ObjectMapper().readTree("""
                [{"type": "ACTUAL_PICKUP", "dateTime": "2024-01-15T10:00:00-05:00"}]""");
        assertNull(m.invoke(connector, arr, "ACTUAL_DELIVERY"));
    }

    @Test
    void trackingResultBackwardsCompatShimStillWorks() {
        // Existing connectors (UPS, DHL, Stamps) call the 7-arg constructor
        // and this needs to keep compiling. Ensure the shim provides an
        // empty events list.
        var result = new com.multiship.backend.service.carriers.CarrierConnector.TrackingResult(
                "TN", "IN_TRANSIT", "https://track.example/TN",
                "Louisville, KY US", null, false, "raw-json");
        assertNotNull(result.events());
        assertTrue(result.events().isEmpty());
    }
}
