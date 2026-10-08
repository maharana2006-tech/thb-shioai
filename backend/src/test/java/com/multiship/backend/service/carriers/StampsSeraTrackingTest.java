package com.multiship.backend.service.carriers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.config.CarrierProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** PR-T4 — pins the SERA /tracking parse contract: status mapping,
 *  event timestamps + signed_by folding, delivery flag. HTTP-transport
 *  branches (404, 5xx, unreachable) are covered by inline fallbacks that
 *  already test green via existing suite (StampsTrackingTest patterns).
 *  This test exercises {@code parseSeraTrackingResponse} directly so no
 *  HTTP mocking is needed. */
class StampsSeraTrackingTest {

    private StampsConnector connector;

    @BeforeEach
    void setUp() {
        CarrierProperties props = new CarrierProperties();
        connector = new StampsConnector(props, new ObjectMapper());
    }

    @Test
    void deliveredResponseFlagsDelivered() {
        String body = "{"
                + "\"tracking_number\":\"9400111899223197428437\","
                + "\"status_code\":\"delivered\","
                + "\"estimated_delivery_date\":\"2026-10-08T14:30:00-07:00\","
                + "\"tracking_events\":["
                + "  {\"occurred_at\":\"2026-10-08T14:30:00-07:00\","
                + "   \"event_description\":\"Delivered, Front Door/Porch\","
                + "   \"location\":\"ANN ARBOR, MI 48103\"}"
                + "]"
                + "}";
        var result = connector.parseSeraTrackingResponse(
                "9400111899223197428437", "https://tools.usps.com/go/...", body);
        assertEquals("DELIVERED", result.status());
        assertTrue(result.delivered());
        assertEquals(1, result.events().size());
        assertEquals("ANN ARBOR, MI 48103", result.currentLocation());
        assertNotNull(result.estimatedDelivery());
    }

    @Test
    void inTransitResponseMapsStatus() {
        String body = "{"
                + "\"status_code\":\"in_transit\","
                + "\"tracking_events\":["
                + "  {\"occurred_at\":\"2026-10-07T10:00:00-07:00\","
                + "   \"event_description\":\"In Transit\","
                + "   \"location\":\"DENVER, CO 80202\"}"
                + "]"
                + "}";
        var result = connector.parseSeraTrackingResponse("TRK1", "url", body);
        assertEquals("IN_TRANSIT", result.status());
        assertFalse(result.delivered());
        assertEquals(1, result.events().size());
    }

    @Test
    void exceptionStatusDoesNotFlagDelivered() {
        String body = "{"
                + "\"status_code\":\"exception\","
                + "\"tracking_events\":["
                + "  {\"occurred_at\":\"2026-10-07T09:00:00-07:00\","
                + "   \"event_description\":\"Delivery Attempted — No Access\","
                + "   \"location\":\"SEATTLE, WA 98101\"}"
                + "]"
                + "}";
        var result = connector.parseSeraTrackingResponse("TRK2", "url", body);
        assertEquals("EXCEPTION", result.status());
        assertFalse(result.delivered());
    }

    @Test
    void signedByFoldedIntoEventDescription() {
        String body = "{"
                + "\"status_code\":\"delivered\","
                + "\"tracking_events\":["
                + "  {\"occurred_at\":\"2026-10-08T14:30:00-07:00\","
                + "   \"event_description\":\"Delivered\","
                + "   \"location\":\"ANN ARBOR, MI\","
                + "   \"signed_by\":\"J DOE\"}"
                + "]"
                + "}";
        var result = connector.parseSeraTrackingResponse("TRK3", "url", body);
        String desc = result.events().get(0).description();
        assertTrue(desc.contains("J DOE"), "signed_by should fold into description: " + desc);
    }

    @Test
    void emptyEventsReturnsStatusOnly() {
        String body = "{\"status_code\":\"pre_transit\",\"tracking_events\":[]}";
        var result = connector.parseSeraTrackingResponse("TRK4", "url", body);
        assertEquals("PRE_TRANSIT", result.status());
        assertTrue(result.events().isEmpty());
        assertFalse(result.delivered());
        assertNull(result.currentLocation());
    }

    @Test
    void malformedBodyReturnsUnknown() {
        var result = connector.parseSeraTrackingResponse("TRK5", "url", "not json");
        assertEquals("UNKNOWN", result.status());
        assertFalse(result.delivered());
    }

    @Test
    void emptyBodyReturnsUnknown() {
        var result = connector.parseSeraTrackingResponse("TRK6", "url", "");
        assertEquals("UNKNOWN", result.status());
        assertFalse(result.delivered());
    }

    @Test
    void timestampWithOffsetParsesCleanly() {
        String body = "{\"status_code\":\"in_transit\","
                + "\"tracking_events\":[{"
                + "\"occurred_at\":\"2026-10-07T14:30:00-07:00\","
                + "\"event_description\":\"X\",\"location\":\"NYC\"}]}";
        var result = connector.parseSeraTrackingResponse("TRK7", "url", body);
        assertNotNull(result.events().get(0).timestamp(),
                "SERA offset-timestamp must parse via parseSeraTimestamp");
    }
}
