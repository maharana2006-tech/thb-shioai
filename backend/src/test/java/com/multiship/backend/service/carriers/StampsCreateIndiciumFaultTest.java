package com.multiship.backend.service.carriers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.dto.ShipmentRequestDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for {@link StampsConnector#parseCreateIndiciumResponse}
 * — the SWSIM CreateIndicium response parser.
 *
 * <p>Origin: SWSIM can return HTTP 200 with a {@code <faultstring>} in the
 * SOAP envelope (e.g. "insufficient postage") — those don't trip the
 * transport-level 4xx/5xx handler in {@code createShipment}, they hit this
 * parser. Pre-fix, a fault response missing a TrackingNumber silently
 * returned a {@link CarrierConnector.ShipmentResult} with null tracking /
 * URL / PDF, which the MPS aggregator treated as a successful piece — the
 * operator saw a "created" shipment with no label and no error. Sibling
 * parsers ({@code parseCancelIndiciumResponse},
 * {@code parseSchedulePickupResponse}, {@code parseCreateScanFormResponse})
 * all check for {@code <faultstring>}; createIndicium was the outlier.
 *
 * <p>Post-fix: a fault response throws {@link IllegalStateException}
 * carrying the fault text, which the {@code createShipment} catch block
 * routes through {@code CarrierExceptionMapper} and triggers MPS rollback
 * for any pieces already created earlier in the batch.
 */
class StampsCreateIndiciumFaultTest {

    private StampsConnector connector;
    private Method parseMethod;

    @BeforeEach
    void setUp() throws Exception {
        CarrierProperties props = new CarrierProperties();
        props.setDefaultEnvironment("SANDBOX");
        connector = new StampsConnector(props, new ObjectMapper());
        parseMethod = StampsConnector.class.getDeclaredMethod(
                "parseCreateIndiciumResponse", String.class, ShipmentRequestDTO.class);
        parseMethod.setAccessible(true);
    }

    /** Call the private parseCreateIndiciumResponse via reflection, unwrapping
     *  any InvocationTargetException so tests see the real exception. */
    private CarrierConnector.ShipmentResult parse(String responseXml, ShipmentRequestDTO request) throws Throwable {
        try {
            return (CarrierConnector.ShipmentResult) parseMethod.invoke(connector, responseXml, request);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static ShipmentRequestDTO request() {
        return ShipmentRequestDTO.builder().referenceNumber("PO-1234").build();
    }

    // ===== happy path — real label response parses cleanly =====

    @Test
    void validResponse_withTrackingNumber_parsesIntoShipmentResult() throws Throwable {
        String xml = "<CreateIndiciumResponse>"
                + "<TrackingNumber>9400111899223197428490</TrackingNumber>"
                + "<URL>https://labels.stamps.com/label/abc123.pdf</URL>"
                + "<Rate><Amount>7.85</Amount></Rate>"
                + "</CreateIndiciumResponse>";

        CarrierConnector.ShipmentResult result = parse(xml, request());

        assertNotNull(result, "must return a ShipmentResult for a valid response");
        assertEquals("9400111899223197428490", result.trackingNumber());
        assertNotNull(result.labelUrl());
        assertEquals(0, new java.math.BigDecimal("7.85").compareTo(result.shippingCost()));
    }

    // ===== fault-response cases — must THROW, not silently succeed =====

    @Test
    void faultResponse_withNoTrackingNumber_throwsIllegalStateException() {
        // SWSIM's shape when postage is short — HTTP 200 with a SOAP fault
        // inside. Pre-fix this returned a null-tracking "success"; post-fix
        // it throws so the MPS rollback + operator error surface fire.
        String xml = "<soap:Envelope><soap:Body>"
                + "<soap:Fault><faultstring>Insufficient postage on the account.</faultstring></soap:Fault>"
                + "</soap:Body></soap:Envelope>";

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> parse(xml, request()));
        assertTrue(ex.getMessage().contains("Insufficient postage"),
                "exception must carry the fault text so operators see the reason; got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("PO-1234"),
                "exception must name the order for log-correlation; got: " + ex.getMessage());
    }

    @Test
    void emptyResponse_throws() throws Throwable {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> parse("", request()));
        assertTrue(ex.getMessage().contains("no TrackingNumber"),
                "empty response is treated as fault-shaped; got: " + ex.getMessage());
    }

    @Test
    void nullResponse_throws() throws Throwable {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> parse(null, request()));
        assertTrue(ex.getMessage().contains("no TrackingNumber"),
                "null response is treated as fault-shaped; got: " + ex.getMessage());
    }

    @Test
    void faultResponse_withNullRequest_stillReportsOrderId_asQuestionMark() {
        // Defensive: some code paths pass a null request (edge case in the
        // rollback flow). Exception message uses "?" for the order id
        // instead of NPE-ing.
        String xml = "<soap:Fault><faultstring>service unavailable</faultstring></soap:Fault>";

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> parse(xml, null));
        assertTrue(ex.getMessage().contains("?"),
                "null request should surface '?' as the order id, not NPE; got: " + ex.getMessage());
    }
}
