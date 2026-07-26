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
 * Golden-value tests for USPS/SWSIM tracking. Mirrors the FedEx (Sprint 12),
 * UPS (Sprint 13), and DHL (Sprint 14) suites — canned response XML, no live
 * SWSIM sandbox.
 *
 * <p>One convention differs from the other three: SWSIM emits events
 * oldest-first natively, so no reversal happens in the parser.
 */
class StampsTrackingTest {

    private StampsConnector connector;

    @BeforeEach
    void setUp() {
        connector = new StampsConnector(new CarrierProperties(), new ObjectMapper());
    }

    @Test
    void oneArgStubReturnsUrlOnly() {
        var result = connector.trackShipment("9400111899223197123456");
        assertEquals("9400111899223197123456", result.trackingNumber());
        assertEquals("UNKNOWN", result.status());
        assertTrue(result.trackingUrl().contains("usps.com"));
        assertFalse(result.delivered());
        assertTrue(result.events().isEmpty());
    }

    @Test
    void localFallbackTokenShortCircuitsToStub() {
        var result = connector.trackShipment("9400", "stamps-local-abc");
        assertEquals("UNKNOWN", result.status());
        assertTrue(result.events().isEmpty());
    }

    @Test
    void blankTokenShortCircuitsToStub() {
        assertEquals("UNKNOWN", connector.trackShipment("9400", "").status());
    }

    @Test
    void nullTokenShortCircuitsToStub() {
        assertEquals("UNKNOWN", connector.trackShipment("9400", null).status());
    }

    @Test
    void trackShipmentEnvelopeContainsCorrectElements() {
        String soap = connector.buildTrackShipmentEnvelope("9400111899223197123456", "AUTH-TOKEN");
        assertTrue(soap.contains("<soap:Envelope"), "Should be a SOAP envelope");
        assertTrue(soap.contains("<TrackShipment xmlns=\"http://stamps.com/xml/namespace/2023/07/swsim/SwsimV135\">"),
                "Namespace must match the WSDL");
        assertTrue(soap.contains("<Authenticator>AUTH-TOKEN</Authenticator>"));
        assertTrue(soap.contains("<TrackingNumber>9400111899223197123456</TrackingNumber>"));
        assertTrue(soap.contains("<Carrier>USPS</Carrier>"));
    }

    @Test
    void trackShipmentEnvelopeXmlEscapesInputs() {
        String soap = connector.buildTrackShipmentEnvelope("94001&99", "AUTH & TOKEN");
        assertTrue(soap.contains("94001&amp;99"), "Ampersand in tracking # must be escaped");
        assertTrue(soap.contains("AUTH &amp; TOKEN"), "Ampersand in authenticator must be escaped");
    }

    @Test
    void parseSwsimTimestampHandlesLocalAndOffset() {
        assertEquals(LocalDateTime.of(2024, 1, 15, 14, 30, 0),
                StampsConnector.parseSwsimTimestamp("2024-01-15T14:30:00"));
        assertEquals(LocalDateTime.of(2024, 1, 15, 14, 30, 0),
                StampsConnector.parseSwsimTimestamp("2024-01-15T14:30:00-05:00"));
    }

    @Test
    void parseSwsimTimestampReturnsNullOnGarbage() {
        assertNull(StampsConnector.parseSwsimTimestamp(null));
        assertNull(StampsConnector.parseSwsimTimestamp(""));
        assertNull(StampsConnector.parseSwsimTimestamp("not-a-timestamp"));
    }

    @Test
    void buildSwsimLocationHandlesFullValues() {
        assertEquals("New York, NY US",
                StampsConnector.buildSwsimLocation("New York", "NY", "US"));
    }

    @Test
    void buildSwsimLocationHandlesPartial() {
        assertEquals("New York",
                StampsConnector.buildSwsimLocation("New York", null, null));
        assertEquals("NY US",
                StampsConnector.buildSwsimLocation("", "NY", "US"));
        assertNull(StampsConnector.buildSwsimLocation(null, null, null),
                "All three blank returns null so the event.location field is honestly empty");
    }

    @Test
    void parseSwsimTrackingEventsExtractsAllEventsInOrder() {
        // SWSIM already sends oldest first — parser preserves that order.
        String response = """
                <?xml version="1.0" encoding="utf-8"?>
                <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
                  <soap:Body>
                    <TrackShipmentResponse xmlns="http://stamps.com/xml/namespace/2023/07/swsim/SwsimV135">
                      <Authenticator>NEW-AUTH</Authenticator>
                      <TrackingEvents>
                        <TrackingEvent>
                          <TrackingEventType>Acceptance</TrackingEventType>
                          <Timestamp>2024-01-15T08:00:00</Timestamp>
                          <Event>USPS in possession of item</Event>
                          <City>Louisville</City>
                          <State>KY</State>
                          <Country>US</Country>
                        </TrackingEvent>
                        <TrackingEvent>
                          <TrackingEventType>OutForDelivery</TrackingEventType>
                          <Timestamp>2024-01-16T08:30:00</Timestamp>
                          <Event>Out for Delivery</Event>
                          <City>New York</City>
                          <State>NY</State>
                          <Country>US</Country>
                        </TrackingEvent>
                        <TrackingEvent>
                          <TrackingEventType>Delivered</TrackingEventType>
                          <Timestamp>2024-01-16T14:15:00</Timestamp>
                          <Event>Delivered, Front Door/Porch</Event>
                          <City>New York</City>
                          <State>NY</State>
                          <Country>US</Country>
                        </TrackingEvent>
                      </TrackingEvents>
                    </TrackShipmentResponse>
                  </soap:Body>
                </soap:Envelope>""";
        List<CarrierConnector.TrackingEvent> events = connector.parseSwsimTrackingEvents(response);
        assertEquals(3, events.size());

        // Oldest first (already SWSIM's order)
        assertEquals("Acceptance", events.get(0).status());
        assertEquals("USPS in possession of item", events.get(0).description());
        assertEquals("Louisville, KY US", events.get(0).location());
        assertNotNull(events.get(0).timestamp());

        assertEquals("OutForDelivery", events.get(1).status());
        assertEquals("New York, NY US", events.get(1).location());

        assertEquals("Delivered", events.get(2).status());
        assertEquals("Delivered, Front Door/Porch", events.get(2).description());
    }

    @Test
    void parseSwsimTrackingEventsHandlesEmptyResponse() {
        assertTrue(connector.parseSwsimTrackingEvents(null).isEmpty());
        assertTrue(connector.parseSwsimTrackingEvents("").isEmpty());
        assertTrue(connector.parseSwsimTrackingEvents(
                "<TrackShipmentResponse><TrackingEvents/></TrackShipmentResponse>").isEmpty());
    }

    @Test
    void parseSwsimTrackingEventsHandlesSingleEvent() {
        String response = """
                <TrackingEvents>
                  <TrackingEvent>
                    <TrackingEventType>Delivered</TrackingEventType>
                    <Timestamp>2024-01-16T14:15:00</Timestamp>
                    <Event>Delivered</Event>
                    <City>Buffalo</City>
                    <State>NY</State>
                  </TrackingEvent>
                </TrackingEvents>""";
        List<CarrierConnector.TrackingEvent> events = connector.parseSwsimTrackingEvents(response);
        assertEquals(1, events.size());
        assertEquals("Buffalo, NY", events.get(0).location(),
                "Country omitted → location built without it, no trailing space");
    }

    @Test
    void parseSwsimTrackingEventsHandlesMalformedTimestamp() {
        String response = """
                <TrackingEvents>
                  <TrackingEvent>
                    <TrackingEventType>Acceptance</TrackingEventType>
                    <Timestamp>not-a-date</Timestamp>
                    <Event>Accepted</Event>
                    <City>Louisville</City>
                    <State>KY</State>
                  </TrackingEvent>
                </TrackingEvents>""";
        List<CarrierConnector.TrackingEvent> events = connector.parseSwsimTrackingEvents(response);
        assertEquals(1, events.size());
        assertNull(events.get(0).timestamp(),
                "Malformed timestamp → null in the event, not a hard failure");
        assertEquals("Acceptance", events.get(0).status(),
                "The rest of the event still lands");
    }
}
