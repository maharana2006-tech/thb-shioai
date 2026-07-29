package com.multiship.backend.service.carriers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.service.carriers.CarrierConnector.VoidResult;
import com.multiship.backend.service.fx.FxRateService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 30 — void/cancel response parsing across all four carriers.
 * One test file per the ReturnLabelTest / FedExDhlUspsHazMatTest
 * precedent — future reviewers get the full void matrix in one place.
 */
class VoidLabelTest {

    private static FxRateService noFx() {
        return new FxRateService() {
            @Override public Optional<BigDecimal> rate(String f, String t) { return Optional.empty(); }
            @Override public Optional<BigDecimal> convert(BigDecimal a, String f, String t) { return Optional.empty(); }
            @Override public boolean supports(String c) { return false; }
        };
    }

    /* -------------------------- Auth guardrails -------------------------- */

    @Test
    void upsLocalFallbackTokenReturnsNotSupported() {
        UpsConnector c = new UpsConnector(new CarrierProperties(), new ObjectMapper());
        VoidResult r = c.voidShipment("1Z999", "ups-local-abc");
        assertEquals("NOT_SUPPORTED", r.status());
        assertFalse(r.voided());
    }

    @Test
    void fedexLocalFallbackTokenReturnsNotSupported() {
        FedExConnector c = new FedExConnector(new CarrierProperties(), new ObjectMapper(), noFx());
        VoidResult r = c.voidShipment("794699999999", "fedex-local-abc");
        assertEquals("NOT_SUPPORTED", r.status());
        assertFalse(r.voided());
    }

    @Test
    void dhlLocalFallbackTokenReturnsNotSupported() {
        DhlConnector c = new DhlConnector(new CarrierProperties(), new ObjectMapper());
        VoidResult r = c.voidShipment("JD99999", "dhl-local-abc");
        assertEquals("NOT_SUPPORTED", r.status());
    }

    @Test
    void stampsLocalFallbackTokenReturnsNotSupported() {
        StampsConnector c = new StampsConnector(new CarrierProperties(), new ObjectMapper());
        VoidResult r = c.voidShipment("9400111899223811234567", "stamps-local-abc");
        assertEquals("NOT_SUPPORTED", r.status());
    }

    @Test
    void nullTokenTreatedAsFallback() {
        assertEquals("NOT_SUPPORTED",
                new UpsConnector(new CarrierProperties(), new ObjectMapper())
                        .voidShipment("1Z999", null).status());
        assertEquals("NOT_SUPPORTED",
                new FedExConnector(new CarrierProperties(), new ObjectMapper(), noFx())
                        .voidShipment("794699999999", null).status());
        assertEquals("NOT_SUPPORTED",
                new DhlConnector(new CarrierProperties(), new ObjectMapper())
                        .voidShipment("JD99999", null).status());
        assertEquals("NOT_SUPPORTED",
                new StampsConnector(new CarrierProperties(), new ObjectMapper())
                        .voidShipment("9400111899223811234567", null).status());
    }

    /* -------------------------- UPS response parsing -------------------------- */

    @Test
    void upsVoidSuccessOnStatusCode1() {
        UpsConnector c = new UpsConnector(new CarrierProperties(), new ObjectMapper());
        String canned = """
                {
                  "VoidShipmentResponse": {
                    "SummaryResult": {
                      "Status": {"Code": "1", "Description": "Success"}
                    }
                  }
                }""";
        VoidResult r = c.parseUpsVoidResponse("1Z999", canned);
        assertTrue(r.voided());
        assertEquals("VOIDED", r.status());
    }

    @Test
    void upsVoidRejectedOnAnyOtherStatusCode() {
        UpsConnector c = new UpsConnector(new CarrierProperties(), new ObjectMapper());
        String canned = """
                {
                  "VoidShipmentResponse": {
                    "SummaryResult": {
                      "Status": {"Code": "2", "Description": "Not eligible for void"}
                    }
                  }
                }""";
        VoidResult r = c.parseUpsVoidResponse("1Z999", canned);
        assertFalse(r.voided());
        assertEquals("ERROR", r.status());
        assertTrue(r.message().contains("Not eligible"), r.message());
    }

    @Test
    void upsVoidMalformedResponseIsError() {
        UpsConnector c = new UpsConnector(new CarrierProperties(), new ObjectMapper());
        VoidResult r = c.parseUpsVoidResponse("1Z999", "not-json");
        assertEquals("ERROR", r.status());
    }

    /* -------------------------- FedEx response parsing -------------------------- */

    @Test
    void fedexVoidSuccessOnCancelledShipmentTrue() {
        FedExConnector c = new FedExConnector(new CarrierProperties(), new ObjectMapper(), noFx());
        String canned = """
                {"output": {"cancelledShipment": true, "message": "Cancellation successful"}}""";
        VoidResult r = c.parseFedExVoidResponse("794699999999", canned);
        assertTrue(r.voided());
        assertEquals("VOIDED", r.status());
    }

    @Test
    void fedexVoidRejectedOnCancelledShipmentFalse() {
        FedExConnector c = new FedExConnector(new CarrierProperties(), new ObjectMapper(), noFx());
        String canned = """
                {"output": {"cancelledShipment": false, "message": "Shipment not found"}}""";
        VoidResult r = c.parseFedExVoidResponse("794699999999", canned);
        assertFalse(r.voided());
        assertEquals("ERROR", r.status());
        assertTrue(r.message().contains("Shipment not found"));
    }

    @Test
    void fedexVoidEmptyResponseTreatedAsError() {
        FedExConnector c = new FedExConnector(new CarrierProperties(), new ObjectMapper(), noFx());
        VoidResult r = c.parseFedExVoidResponse("794699999999", "{}");
        assertFalse(r.voided());
    }

    /* -------------------------- Stamps SWSIM parsing -------------------------- */

    @Test
    void stampsVoidBuildEnvelopeIncludesAuthenticatorAndTxId() {
        StampsConnector c = new StampsConnector(new CarrierProperties(), new ObjectMapper());
        String xml = c.buildCancelIndiciumEnvelope("9400111899", "AUTH-XYZ");
        assertTrue(xml.contains("<Authenticator>AUTH-XYZ</Authenticator>"), xml);
        assertTrue(xml.contains("<StampsTxID>9400111899</StampsTxID>"), xml);
        assertTrue(xml.contains("<CancelIndicium xmlns="),
                "Should namespace the CancelIndicium element with SWSIM v135");
    }

    @Test
    void stampsVoidResponseWithoutFaultIsSuccess() {
        StampsConnector c = new StampsConnector(new CarrierProperties(), new ObjectMapper());
        String canned = """
                <?xml version="1.0"?>
                <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
                  <soap:Body>
                    <CancelIndiciumResponse>
                      <Authenticator>NEW-AUTH-456</Authenticator>
                    </CancelIndiciumResponse>
                  </soap:Body>
                </soap:Envelope>""";
        VoidResult r = c.parseCancelIndiciumResponse("9400111899", canned);
        assertTrue(r.voided());
        assertEquals("VOIDED", r.status());
    }

    @Test
    void stampsVoidResponseWithFaultstringIsRejected() {
        StampsConnector c = new StampsConnector(new CarrierProperties(), new ObjectMapper());
        String canned = """
                <?xml version="1.0"?>
                <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
                  <soap:Body>
                    <soap:Fault>
                      <faultstring>Indicium already voided</faultstring>
                    </soap:Fault>
                  </soap:Body>
                </soap:Envelope>""";
        VoidResult r = c.parseCancelIndiciumResponse("9400111899", canned);
        assertFalse(r.voided());
        assertEquals("ERROR", r.status());
        assertTrue(r.message().contains("Indicium already voided"), r.message());
    }

    @Test
    void stampsEmptyResponseTreatedAsError() {
        StampsConnector c = new StampsConnector(new CarrierProperties(), new ObjectMapper());
        VoidResult r = c.parseCancelIndiciumResponse("9400111899", "");
        assertFalse(r.voided());
        assertEquals("ERROR", r.status());
    }

    /* -------------------------- Result record integrity -------------------------- */

    @Test
    void voidResultCarriesTrackingNumberFromInputEverywhere() {
        String tracking = "1Z999TEST";
        assertEquals(tracking,
                new UpsConnector(new CarrierProperties(), new ObjectMapper())
                        .voidShipment(tracking, "ups-local-").trackingNumber());
        assertEquals(tracking,
                new FedExConnector(new CarrierProperties(), new ObjectMapper(), noFx())
                        .voidShipment(tracking, "fedex-local-").trackingNumber());
        assertEquals(tracking,
                new DhlConnector(new CarrierProperties(), new ObjectMapper())
                        .voidShipment(tracking, "dhl-local-").trackingNumber());
        assertEquals(tracking,
                new StampsConnector(new CarrierProperties(), new ObjectMapper())
                        .voidShipment(tracking, "stamps-local-").trackingNumber());
    }
}
