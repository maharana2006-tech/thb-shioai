package com.multiship.backend.service.carriers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.service.carriers.CarrierConnector.AddressToValidate;
import com.multiship.backend.service.carriers.CarrierConnector.PickupRequest;
import com.multiship.backend.service.carriers.CarrierConnector.PickupResult;
import com.multiship.backend.service.fx.FxRateService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 33 — pickup scheduling wire emission + response parsing across
 * all four carriers.
 */
class PickupTest {

    private static FxRateService noFx() {
        return new FxRateService() {
            @Override public Optional<BigDecimal> rate(String f, String t) { return Optional.empty(); }
            @Override public Optional<BigDecimal> convert(BigDecimal a, String f, String t) { return Optional.empty(); }
            @Override public boolean supports(String c) { return false; }
        };
    }

    private static PickupRequest baseRequest() {
        return new PickupRequest(
                LocalDate.of(2026, 8, 1),
                LocalTime.of(13, 0),
                LocalTime.of(17, 0),
                new AddressToValidate(
                        "Acme Warehouse", "Acme Ltd",
                        "1 Warehouse Way", null, null,
                        "Louisville", "KY", "40209", "US"),
                "John Shipper",
                "5551234567",
                3,
                new BigDecimal("15"),
                "LB",
                "Ring the loading bay bell");
    }

    /* -------------------------- Auth guardrails -------------------------- */

    @Test
    void everyCarrierReturnsNotSupportedForLocalFallbackToken() {
        PickupRequest r = baseRequest();
        assertEquals("NOT_SUPPORTED",
                new UpsConnector(new CarrierProperties(), new ObjectMapper())
                        .schedulePickup(r, "ups-local-abc").status());
        assertEquals("NOT_SUPPORTED",
                new FedExConnector(new CarrierProperties(), new ObjectMapper(), noFx())
                        .schedulePickup(r, "fedex-local-abc").status());
        assertEquals("NOT_SUPPORTED",
                new DhlConnector(new CarrierProperties(), new ObjectMapper())
                        .schedulePickup(r, "dhl-local-abc").status());
        assertEquals("NOT_SUPPORTED",
                new StampsConnector(new CarrierProperties(), new ObjectMapper())
                        .schedulePickup(r, "stamps-local-abc").status());
    }

    @Test
    void nullTokenTreatedAsFallbackEverywhere() {
        PickupRequest r = baseRequest();
        assertEquals("NOT_SUPPORTED",
                new UpsConnector(new CarrierProperties(), new ObjectMapper())
                        .schedulePickup(r, null).status());
        assertEquals("NOT_SUPPORTED",
                new FedExConnector(new CarrierProperties(), new ObjectMapper(), noFx())
                        .schedulePickup(r, null).status());
        assertEquals("NOT_SUPPORTED",
                new DhlConnector(new CarrierProperties(), new ObjectMapper())
                        .schedulePickup(r, null).status());
        assertEquals("NOT_SUPPORTED",
                new StampsConnector(new CarrierProperties(), new ObjectMapper())
                        .schedulePickup(r, null).status());
    }

    /* -------------------------- UPS -------------------------- */

    @Test
    @SuppressWarnings("unchecked")
    void upsPickupRequestBodyIncludesPickupDateInfo() {
        UpsConnector c = new UpsConnector(new CarrierProperties(), new ObjectMapper());
        java.util.Map<String, Object> body = c.buildUpsPickupRequest(baseRequest());
        java.util.Map<String, Object> pcr = (java.util.Map<String, Object>) body.get("PickupCreationRequest");
        java.util.Map<String, Object> pdi = (java.util.Map<String, Object>) pcr.get("PickupDateInfo");
        assertEquals("20260801", pdi.get("PickupDate"),
                "UPS wants PickupDate as YYYYMMDD without hyphens");
        assertEquals("1300", pdi.get("ReadyTime"));
        assertEquals("1700", pdi.get("CloseTime"));
    }

    @Test
    void upsSuccessResponseCarriesPrn() {
        UpsConnector c = new UpsConnector(new CarrierProperties(), new ObjectMapper());
        String canned = """
                {
                  "PickupCreationResponse": {
                    "PRN": "ABC12345",
                    "RateStatus": {"Code": "01"}
                  }
                }""";
        PickupResult r = c.parseUpsPickupResponse(baseRequest(), canned);
        assertEquals("SCHEDULED", r.status());
        assertEquals("ABC12345", r.confirmationNumber());
        assertNotNull(r.scheduledDate());
    }

    @Test
    void upsMissingPrnReturnsError() {
        UpsConnector c = new UpsConnector(new CarrierProperties(), new ObjectMapper());
        String canned = "{\"PickupCreationResponse\": {}}";
        PickupResult r = c.parseUpsPickupResponse(baseRequest(), canned);
        assertEquals("ERROR", r.status());
    }

    /* -------------------------- FedEx -------------------------- */

    @Test
    @SuppressWarnings("unchecked")
    void fedexPickupBodyIncludesCarrierCodeAndTimestamp() {
        FedExConnector c = new FedExConnector(new CarrierProperties(), new ObjectMapper(), noFx());
        java.util.Map<String, Object> body = c.buildFedExPickupRequest(baseRequest());
        assertEquals("FDXG", body.get("carrierCode"),
                "Ground is the safe operational default for manual-label flows");
        java.util.Map<String, Object> origin = (java.util.Map<String, Object>) body.get("originDetail");
        String ts = (String) origin.get("readyDateTimestamp");
        assertTrue(ts.startsWith("2026-08-01T13:00"), ts);
        assertEquals(3, body.get("totalPackageCount"));
    }

    @Test
    void fedexSuccessResponseCarriesConfirmationCode() {
        FedExConnector c = new FedExConnector(new CarrierProperties(), new ObjectMapper(), noFx());
        String canned = """
                {"output": {"pickupConfirmationCode": "FDX-987654", "location": "LOU"}}""";
        PickupResult r = c.parseFedExPickupResponse(baseRequest(), canned);
        assertEquals("SCHEDULED", r.status());
        assertEquals("FDX-987654", r.confirmationNumber());
    }

    @Test
    void fedexMissingConfirmationCodeReturnsError() {
        FedExConnector c = new FedExConnector(new CarrierProperties(), new ObjectMapper(), noFx());
        PickupResult r = c.parseFedExPickupResponse(baseRequest(), "{\"output\": {}}");
        assertEquals("ERROR", r.status());
    }

    /* -------------------------- DHL -------------------------- */

    @Test
    @SuppressWarnings("unchecked")
    void dhlPickupBodyIncludesPlannedPickupDateAndTimeAndPackages() {
        DhlConnector c = new DhlConnector(new CarrierProperties(), new ObjectMapper());
        java.util.Map<String, Object> body = c.buildDhlPickupRequest(baseRequest());
        String ts = (String) body.get("plannedPickupDateAndTime");
        assertTrue(ts.startsWith("2026-08-01T13:00"), ts);
        java.util.List<java.util.Map<String, Object>> shipmentDetails =
                (java.util.List<java.util.Map<String, Object>>) body.get("shipmentDetails");
        assertEquals(1, shipmentDetails.size());
        java.util.List<java.util.Map<String, Object>> packages =
                (java.util.List<java.util.Map<String, Object>>) shipmentDetails.get(0).get("packages");
        assertEquals(3, packages.size(), "packageCount=3 should produce 3 packages");
    }

    @Test
    void dhlSuccessResponseCarriesDispatchConfirmationNumber() {
        DhlConnector c = new DhlConnector(new CarrierProperties(), new ObjectMapper());
        String canned = """
                {"dispatchConfirmationNumbers": ["DHL-135790"], "readyByTime": "2026-08-01T13:00"}""";
        PickupResult r = c.parseDhlPickupResponse(baseRequest(), canned);
        assertEquals("SCHEDULED", r.status());
        assertEquals("DHL-135790", r.confirmationNumber());
    }

    @Test
    void dhlEmptyDispatchConfirmationArrayReturnsError() {
        DhlConnector c = new DhlConnector(new CarrierProperties(), new ObjectMapper());
        PickupResult r = c.parseDhlPickupResponse(baseRequest(), "{\"dispatchConfirmationNumbers\": []}");
        assertEquals("ERROR", r.status());
    }

    /* -------------------------- SWSIM -------------------------- */

    @Test
    void stampsPickupEnvelopeEmitsSpecialInstructionsAndPickupAddress() {
        StampsConnector c = new StampsConnector(new CarrierProperties(), new ObjectMapper());
        String xml = c.buildSchedulePickupEnvelope(baseRequest(), "AUTH-XYZ");
        assertTrue(xml.contains("<SchedulePickup xmlns=\""), xml);
        assertTrue(xml.contains("<Authenticator>AUTH-XYZ</Authenticator>"));
        assertTrue(xml.contains("<PickupDate>2026-08-01</PickupDate>"));
        assertTrue(xml.contains("<PackageCount>3</PackageCount>"));
        assertTrue(xml.contains("<SpecialInstructions>Ring the loading bay bell</SpecialInstructions>"));
        assertTrue(xml.contains("<Address1>1 Warehouse Way</Address1>"));
        assertTrue(xml.contains("<City>Louisville</City>"));
        // 15 LB → 240 OZ
        assertTrue(xml.contains("<EstimatedWeight>240"), xml);
    }

    @Test
    void stampsSuccessResponseCarriesConfirmationNumber() {
        StampsConnector c = new StampsConnector(new CarrierProperties(), new ObjectMapper());
        String canned = """
                <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
                  <soap:Body>
                    <SchedulePickupResponse>
                      <Authenticator>NEW-AUTH-456</Authenticator>
                      <ConfirmationNumber>USPS-24680</ConfirmationNumber>
                    </SchedulePickupResponse>
                  </soap:Body>
                </soap:Envelope>""";
        PickupResult r = c.parseSchedulePickupResponse(baseRequest(), canned);
        assertEquals("SCHEDULED", r.status());
        assertEquals("USPS-24680", r.confirmationNumber());
    }

    @Test
    void stampsFaultstringRejectsAsError() {
        StampsConnector c = new StampsConnector(new CarrierProperties(), new ObjectMapper());
        String canned = """
                <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
                  <soap:Body>
                    <soap:Fault><faultstring>Pickup date must be in the future</faultstring></soap:Fault>
                  </soap:Body>
                </soap:Envelope>""";
        PickupResult r = c.parseSchedulePickupResponse(baseRequest(), canned);
        assertEquals("ERROR", r.status());
        assertTrue(r.message().contains("Pickup date must be in the future"));
    }

    @Test
    void stampsMissingConfirmationNumberIsError() {
        StampsConnector c = new StampsConnector(new CarrierProperties(), new ObjectMapper());
        String canned = """
                <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
                  <soap:Body>
                    <SchedulePickupResponse>
                      <Authenticator>NEW-AUTH-456</Authenticator>
                    </SchedulePickupResponse>
                  </soap:Body>
                </soap:Envelope>""";
        PickupResult r = c.parseSchedulePickupResponse(baseRequest(), canned);
        assertEquals("ERROR", r.status());
    }
}
