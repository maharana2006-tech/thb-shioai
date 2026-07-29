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
 * Golden-value tests for UPS tracking response parsing. Mirrors the FedEx
 * suite from Sprint 12 — reflection into the private helpers + canned
 * response JSON so no live UPS sandbox is required.
 */
class UpsTrackingTest {

    private UpsConnector connector;

    @BeforeEach
    void setUp() {
        connector = new UpsConnector(new CarrierProperties(), new ObjectMapper());
    }

    @Test
    void oneArgStubReturnsUrlOnly() {
        var result = connector.trackShipment("1Z999AA10123456784");
        assertEquals("1Z999AA10123456784", result.trackingNumber());
        assertEquals("UNKNOWN", result.status());
        assertTrue(result.trackingUrl().contains("ups.com/track"));
        assertFalse(result.delivered());
        assertTrue(result.events().isEmpty());
    }

    @Test
    void localFallbackTokenShortCircuitsToStub() {
        var result = connector.trackShipment("1Z999", "ups-local-abcd1234");
        assertEquals("UNKNOWN", result.status());
        assertTrue(result.events().isEmpty());
    }

    @Test
    void blankTokenShortCircuitsToStub() {
        assertEquals("UNKNOWN", connector.trackShipment("1Z", "").status());
    }

    @Test
    void nullTokenShortCircuitsToStub() {
        assertEquals("UNKNOWN", connector.trackShipment("1Z", null).status());
    }

    @Test
    void joinUpsDateTimeMergesDateAndTime() {
        assertEquals(LocalDateTime.of(2024, 1, 15, 14, 30, 45),
                UpsConnector.joinUpsDateTime("20240115", "143045"));
    }

    @Test
    void joinUpsDateTimeDefaultsMissingTimeToMidnight() {
        assertEquals(LocalDateTime.of(2024, 1, 15, 0, 0, 0),
                UpsConnector.joinUpsDateTime("20240115", null));
        assertEquals(LocalDateTime.of(2024, 1, 15, 0, 0, 0),
                UpsConnector.joinUpsDateTime("20240115", ""));
    }

    @Test
    void joinUpsDateTimeRejectsMalformedDate() {
        // A missing or malformed DATE means we can't produce a timestamp at
        // all — UPS never emits an event without a date, so null is the
        // right signal here.
        assertNull(UpsConnector.joinUpsDateTime(null, "143045"));
        assertNull(UpsConnector.joinUpsDateTime("2024", "143045"));
        assertNull(UpsConnector.joinUpsDateTime("BADDATE1", "143045"));
    }

    @Test
    void joinUpsDateTimeToleratesMalformedTime() {
        // Date OK but time garbled — treat as "date at midnight" rather than
        // dropping the event entirely. UPS occasionally has bare-date entries.
        assertEquals(LocalDateTime.of(2024, 1, 15, 0, 0, 0),
                UpsConnector.joinUpsDateTime("20240115", "BADTIME"));
        assertEquals(LocalDateTime.of(2024, 1, 15, 0, 0, 0),
                UpsConnector.joinUpsDateTime("20240115", "1234"));
    }

    @Test
    void buildUpsLocationHandlesFullNode() throws Exception {
        var node = new ObjectMapper().readTree("""
                {"city": "Louisville", "stateProvince": "KY", "country": "US"}""");
        assertEquals("Louisville, KY US", UpsConnector.buildUpsLocation(node));
    }

    @Test
    void buildUpsLocationHandlesPartialNode() throws Exception {
        assertEquals("London GB",
                UpsConnector.buildUpsLocation(new ObjectMapper().readTree("""
                        {"city": "London", "country": "GB"}""")));
    }

    @Test
    void buildUpsLocationHandlesEmptyOrNull() throws Exception {
        assertNull(UpsConnector.buildUpsLocation(new ObjectMapper().readTree("{}")));
        assertNull(UpsConnector.buildUpsLocation(null));
    }

    @Test
    void parseUpsActivityReversesToOldestFirst() throws Exception {
        // UPS returns newest first; parser flips to oldest first.
        var activity = new ObjectMapper().readTree("""
                [
                  {
                    "location": {"address": {"city": "New York", "stateProvince": "NY", "country": "US"}},
                    "status": {"type": "D", "description": "Delivered", "code": "D"},
                    "date": "20240116",
                    "time": "141500"
                  },
                  {
                    "location": {"address": {"city": "New York", "stateProvince": "NY", "country": "US"}},
                    "status": {"type": "I", "description": "Out for Delivery", "code": "OFD"},
                    "date": "20240116",
                    "time": "083000"
                  },
                  {
                    "location": {"address": {"city": "Louisville", "stateProvince": "KY", "country": "US"}},
                    "status": {"type": "I", "description": "Departed from Facility", "code": "DP"},
                    "date": "20240115",
                    "time": "220000"
                  }
                ]""");
        List<com.multiship.backend.service.carriers.CarrierConnector.TrackingEvent> events =
                connector.parseUpsActivity(activity);

        assertEquals(3, events.size());
        // Oldest first
        assertEquals("Departed from Facility", events.get(0).description());
        assertEquals("DP", events.get(0).status());
        assertEquals("Louisville, KY US", events.get(0).location());
        assertNotNull(events.get(0).timestamp());
        // Newest last
        assertEquals("Delivered", events.get(2).description());
        assertEquals("D", events.get(2).status());
    }

    @Test
    void parseUpsActivityHandlesEmptyAndMissing() throws Exception {
        assertTrue(connector.parseUpsActivity(new ObjectMapper().readTree("[]")).isEmpty());
        assertTrue(connector.parseUpsActivity(new ObjectMapper().readTree("null")).isEmpty());
    }

    @Test
    void parseUpsActivityFallsBackToTypeWhenCodeMissing() throws Exception {
        // Older UPS responses omit status.code — parser falls back to type.
        var activity = new ObjectMapper().readTree("""
                [
                  {
                    "location": {"address": {"city": "Louisville", "stateProvince": "KY", "country": "US"}},
                    "status": {"type": "M", "description": "Order Processed"},
                    "date": "20240115",
                    "time": "080000"
                  }
                ]""");
        List<com.multiship.backend.service.carriers.CarrierConnector.TrackingEvent> events =
                connector.parseUpsActivity(activity);
        assertEquals("M", events.get(0).status());
    }
}
