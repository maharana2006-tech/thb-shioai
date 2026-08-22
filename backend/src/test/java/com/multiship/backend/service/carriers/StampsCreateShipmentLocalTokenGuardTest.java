package com.multiship.backend.service.carriers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.dto.ShipmentRequestDTO;
import com.multiship.backend.exception.CarrierConnectionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the F4 fix — {@link StampsConnector#createShipment}
 * now short-circuits on `-local-*` fallback access tokens, matching what
 * every other SWSIM entry point (trackShipment, getRates, voidShipment,
 * validateAddress, schedulePickup, closeOutDay) already does.
 *
 * <p>Pre-fix, createShipment fired the CreateIndicium SOAP with the fallback
 * token and let SWSIM 500 with a generic auth fault — operators saw a
 * BAD_GATEWAY error instead of the clear "re-verify the account" message the
 * siblings surface. Sibling parity was broken.
 */
class StampsCreateShipmentLocalTokenGuardTest {

    private StampsConnector connector;

    @BeforeEach
    void setUp() {
        CarrierProperties props = new CarrierProperties();
        CarrierProperties.Stamps s = props.getStamps();
        s.setAuthUrl("http://localhost:1/swsim");
        s.setSandboxAuthUrl("http://localhost:1/swsim");
        s.setApiBaseUrl("http://localhost:1/swsim");
        s.setSandboxUrl("http://localhost:1/swsim");
        s.setApiVersion("v135");
        props.setDefaultEnvironment("SANDBOX");
        connector = new StampsConnector(props, new ObjectMapper());
    }

    private ShipmentRequestDTO minimalRequest() {
        return ShipmentRequestDTO.builder()
                .carrierCode("USPS")
                .serviceType("USPS GA")
                .packageType("Package")
                .weight(new BigDecimal("1.5"))
                .weightUnit("LB")
                .shipperName("Sender")
                .shipperAddressLine1("1 A St")
                .shipperCity("Denver").shipperState("CO").shipperPostalCode("80202").shipperCountryCode("US")
                .recipientName("Recipient")
                .recipientAddressLine1("2 B St")
                .recipientCity("NYC").recipientState("NY").recipientPostalCode("10001").recipientCountryCode("US")
                .build();
    }

    @Test
    void createShipment_withLocalFallbackToken_throwsBeforeSwsim() {
        // Pre-fix, this would have fired the SWSIM POST against localhost:1
        // and thrown a wrapped connection-refused. Post-fix, we throw the
        // sibling-parity CarrierConnectionException BEFORE the HTTP call.
        CarrierConnectionException ex = assertThrows(CarrierConnectionException.class,
                () -> connector.createShipment(minimalRequest(), "stamps-local-abc123", "SANDBOX"));
        assertTrue(ex.getMessage().toLowerCase().contains("re-verify"),
                "message must tell operator to re-verify the account; got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("Stamps.com"),
                "message must name the carrier; got: " + ex.getMessage());
    }

    @Test
    void createShipment_withNullToken_throwsBeforeSwsim() {
        // A null token means auth was never called or was cleared — same
        // failure mode as `-local-` — should be handled the same way.
        CarrierConnectionException ex = assertThrows(CarrierConnectionException.class,
                () -> connector.createShipment(minimalRequest(), null, "SANDBOX"));
        assertTrue(ex.getMessage().toLowerCase().contains("re-verify"),
                "message must tell operator to re-verify; got: " + ex.getMessage());
    }

    @Test
    void createShipment_withBlankToken_throwsBeforeSwsim() {
        CarrierConnectionException ex = assertThrows(CarrierConnectionException.class,
                () -> connector.createShipment(minimalRequest(), "   ", "SANDBOX"));
        assertTrue(ex.getMessage().toLowerCase().contains("re-verify"),
                "blank token treated the same as null; got: " + ex.getMessage());
    }

    @Test
    void createShipment_withRealToken_movesPastTheGuard() {
        // A non-`-local-` token means auth succeeded — we WANT the call to
        // proceed to SWSIM (which will fail with connection-refused because
        // localhost:1 is unreachable in tests, but that's a different
        // failure surface than the pre-guard "re-verify" message).
        try {
            connector.createShipment(minimalRequest(), "real-swsim-authenticator-guid-goes-here", "SANDBOX");
        } catch (CarrierConnectionException guardEx) {
            // Whatever thrown, it MUST NOT be the guard's "re-verify" message
            // — that would mean the guard is wrongly firing for a real token.
            if (guardEx.getMessage().toLowerCase().contains("re-verify")) {
                org.junit.jupiter.api.Assertions.fail(
                        "Regression: guard fired for a real token. Message: " + guardEx.getMessage());
            }
        } catch (Exception downstreamEx) {
            // Connection-refused, MPS-loop mapper exception, etc — anything
            // downstream is fine. The guard didn't fire → correct behaviour.
        }
    }
}
