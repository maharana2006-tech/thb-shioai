package com.multiship.backend.service.carriers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.config.CarrierProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden-value tests for DHL tracking response parsing. Mirrors the FedEx +
 * UPS suites (Sprints 12 and 13) — reflection into the private helpers +
 * canned response JSON so no live DHL sandbox is required.
 */
class DhlTrackingTest {

    private DhlConnector connector;

    @BeforeEach
    void setUp() {
        connector = new DhlConnector(new CarrierProperties(), new ObjectMapper());
    }

    @Test
    void oneArgStubReturnsUrlOnly() {
        var result = connector.trackShipment("1234567890");
        assertEquals("1234567890", result.trackingNumber());
        assertEquals("UNKNOWN", result.status());
        assertTrue(result.trackingUrl().contains("dhl.com"));
        assertFalse(result.delivered());
        assertTrue(result.events().isEmpty());
    }

    @Test
    void localFallbackTokenShortCircuitsToStub() {
        var result = connector.trackShipment("1234", "dhl-local-abc123", null);
        assertEquals("UNKNOWN", result.status());
        assertTrue(result.events().isEmpty());
    }

    @Test
    void blankTokenShortCircuitsToStub() {
        assertEquals("UNKNOWN", connector.trackShipment("1234", "", null).status());
    }

    @Test
    void nullTokenShortCircuitsToStub() {
        assertEquals("UNKNOWN", connector.trackShipment("1234", null, null).status());
    }

    @Test
    void joinDhlDateTimeMergesIsoDateAndTime() {
        assertEquals(LocalDateTime.of(2024, 1, 15, 14, 30, 45),
                DhlConnector.joinDhlDateTime("2024-01-15", "14:30:45"));
    }

    @Test
    void joinDhlDateTimeDefaultsMissingTimeToMidnight() {
        assertEquals(LocalDateTime.of(2024, 1, 15, 0, 0, 0),
                DhlConnector.joinDhlDateTime("2024-01-15", null));
        assertEquals(LocalDateTime.of(2024, 1, 15, 0, 0, 0),
                DhlConnector.joinDhlDateTime("2024-01-15", ""));
    }

    @Test
    void joinDhlDateTimeToleratesMalformedTime() {
        assertEquals(LocalDateTime.of(2024, 1, 15, 0, 0, 0),
                DhlConnector.joinDhlDateTime("2024-01-15", "BADTIME"),
                "Malformed time should fall back to midnight — matches UPS behaviour");
    }

    @Test
    void joinDhlDateTimeRejectsMissingOrMalformedDate() {
        assertNull(DhlConnector.joinDhlDateTime(null, "14:30:00"));
        assertNull(DhlConnector.joinDhlDateTime("", "14:30:00"));
        assertNull(DhlConnector.joinDhlDateTime("BADDATE", "14:30:00"));
    }

    @Test
    void parseIsoDateTimeHandlesPlainAndOffset() {
        assertEquals(LocalDateTime.of(2024, 1, 15, 14, 0, 0),
                DhlConnector.parseIsoDateTime("2024-01-15T14:00:00"));
        assertEquals(LocalDateTime.of(2024, 1, 15, 14, 0, 0),
                DhlConnector.parseIsoDateTime("2024-01-15T14:00:00Z"));
    }

    @Test
    void parseIsoDateTimeReturnsNullOnGarbage() {
        assertNull(DhlConnector.parseIsoDateTime(null));
        assertNull(DhlConnector.parseIsoDateTime(""));
        assertNull(DhlConnector.parseIsoDateTime("not-a-date"));
    }

    @Test
    void parseDhlEventsReversesToOldestFirst() throws Exception {
        // DHL returns newest first; parser reverses to oldest first.
        var events = new ObjectMapper().readTree("""
                [
                  {
                    "date": "2024-01-16",
                    "time": "14:15:00",
                    "typeCode": "OK",
                    "description": "Delivered",
                    "serviceArea": [{"code": "LHR", "description": "London Heathrow"}]
                  },
                  {
                    "date": "2024-01-16",
                    "time": "08:30:00",
                    "typeCode": "WC",
                    "description": "With courier for delivery",
                    "serviceArea": [{"code": "LHR", "description": "London Heathrow"}]
                  },
                  {
                    "date": "2024-01-15",
                    "time": "22:00:00",
                    "typeCode": "PU",
                    "description": "Shipment picked up",
                    "serviceArea": [{"code": "CVG", "description": "Cincinnati OH"}]
                  }
                ]""");
        List<CarrierConnector.TrackingEvent> parsed = connector.parseDhlEvents(events);

        assertEquals(3, parsed.size());
        // Oldest first
        assertEquals("Shipment picked up", parsed.get(0).description());
        assertEquals("PU", parsed.get(0).status());
        assertEquals("Cincinnati OH", parsed.get(0).location());
        assertNotNull(parsed.get(0).timestamp());
        // Newest last
        assertEquals("Delivered", parsed.get(2).description());
        assertEquals("OK", parsed.get(2).status());
        assertEquals("London Heathrow", parsed.get(2).location());
    }

    @Test
    void parseDhlEventsFallsBackToServiceAreaCodeWhenDescriptionMissing() throws Exception {
        var events = new ObjectMapper().readTree("""
                [
                  {
                    "date": "2024-01-15",
                    "time": "10:00:00",
                    "typeCode": "PU",
                    "description": "Picked up",
                    "serviceArea": [{"code": "CVG"}]
                  }
                ]""");
        List<CarrierConnector.TrackingEvent> parsed = connector.parseDhlEvents(events);
        assertEquals("CVG", parsed.get(0).location(),
                "When DHL omits serviceArea.description we fall back to the code");
    }

    @Test
    void parseDhlEventsHandlesEmptyAndMissing() throws Exception {
        assertTrue(connector.parseDhlEvents(new ObjectMapper().readTree("[]")).isEmpty());
        assertTrue(connector.parseDhlEvents(new ObjectMapper().readTree("null")).isEmpty());
    }

    @Test
    void parseDhlEventsHandlesEventsWithoutServiceArea() throws Exception {
        var events = new ObjectMapper().readTree("""
                [
                  {
                    "date": "2024-01-15",
                    "time": "10:00:00",
                    "typeCode": "MC",
                    "description": "Manifest created"
                  }
                ]""");
        List<CarrierConnector.TrackingEvent> parsed = connector.parseDhlEvents(events);
        assertEquals(1, parsed.size());
        assertNull(parsed.get(0).location());
    }
}
