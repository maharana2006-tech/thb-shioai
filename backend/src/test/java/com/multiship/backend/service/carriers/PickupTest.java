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
                "Ring the loading bay bell",
                "740561111",    // FDX-C — real FedEx shipper account for the pickup
                null);           // FDX-F — pickupServiceType null → Ground (pre-FDX-F default)
    }

    /* -------------------------- Auth guardrails -------------------------- */

    @Test
    void everyCarrierReturnsNotSupportedForLocalFallbackToken() {
        PickupRequest r = baseRequest();
        assertEquals("NOT_SUPPORTED",
                new UpsConnector(new CarrierProperties(), new ObjectMapper())
                        .schedulePickup(r, "ups-local-abc", null).status());
        assertEquals("NOT_SUPPORTED",
                new FedExConnector(new CarrierProperties(), new ObjectMapper(), noFx())
                        .schedulePickup(r, "fedex-local-abc", null).status());
        assertEquals("NOT_SUPPORTED",
                new DhlConnector(new CarrierProperties(), new ObjectMapper())
                        .schedulePickup(r, "dhl-local-abc", null).status());
        assertEquals("NOT_SUPPORTED",
                new StampsConnector(new CarrierProperties(), new ObjectMapper())
                        .schedulePickup(r, "stamps-local-abc", null).status());
    }

    @Test
    void nullTokenTreatedAsFallbackEverywhere() {
        PickupRequest r = baseRequest();
        assertEquals("NOT_SUPPORTED",
                new UpsConnector(new CarrierProperties(), new ObjectMapper())
                        .schedulePickup(r, null, null).status());
        assertEquals("NOT_SUPPORTED",
                new FedExConnector(new CarrierProperties(), new ObjectMapper(), noFx())
                        .schedulePickup(r, null, null).status());
        assertEquals("NOT_SUPPORTED",
                new DhlConnector(new CarrierProperties(), new ObjectMapper())
                        .schedulePickup(r, null, null).status());
        assertEquals("NOT_SUPPORTED",
                new StampsConnector(new CarrierProperties(), new ObjectMapper())
                        .schedulePickup(r, null, null).status());
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

    @Test
    @SuppressWarnings("unchecked")
    void ups_pickup_body_carries_real_account_number_not_contact_name() {
        // FDX-C2 — pre-fix, UPS AccountNumber was populated with
        // req.address().name() (the shipper's CONTACT NAME) which UPS
        // rejects. Now sourced from PickupRequest.accountNumber().
        UpsConnector c = new UpsConnector(new CarrierProperties(), new ObjectMapper());
        java.util.Map<String, Object> body = c.buildUpsPickupRequest(baseRequest());
        java.util.Map<String, Object> pcr = (java.util.Map<String, Object>) body.get("PickupCreationRequest");
        java.util.Map<String, Object> shipper = (java.util.Map<String, Object>) pcr.get("Shipper");
        java.util.Map<String, Object> account = (java.util.Map<String, Object>) shipper.get("Account");
        assertEquals("740561111", account.get("AccountNumber"),
                "UPS body must carry the real shipper account from PickupRequest, "
                        + "not the pre-FDX-C2 shipper-contact-name misfiling");
    }

    @Test
    void ups_schedulePickup_blank_account_shortCircuits() {
        UpsConnector c = new UpsConnector(new CarrierProperties(), new ObjectMapper());
        PickupRequest noAccount = new PickupRequest(
                LocalDate.of(2026, 8, 1), LocalTime.of(13, 0), LocalTime.of(17, 0),
                new AddressToValidate(null, null, "1 A St", null, null,
                        "Denver", "CO", "80202", "US"),
                "Contact", "5551234567", 1, new BigDecimal("5"), "LB", null, "", null);
        PickupResult r = c.schedulePickup(noAccount, "real-oauth-bearer-token", "SANDBOX");
        assertEquals("ERROR", r.status());
        assertTrue(r.message().contains("shipper account number"),
                "got: " + r.message());
    }

    @Test
    void ups_schedulePickup_blank_addressCountry_shortCircuits() {
        // UPS-12 — pickup address country is required so UPS's
        // DestinationCountryCode reflects the shipper's country instead of
        // silently defaulting to "US" (misroutes European shippers). Guard
        // fires at entry with an actionable ERROR message.
        UpsConnector c = new UpsConnector(new CarrierProperties(), new ObjectMapper());
        PickupRequest noCountry = new PickupRequest(
                LocalDate.of(2026, 8, 1), LocalTime.of(13, 0), LocalTime.of(17, 0),
                new AddressToValidate(null, null, "1 A St", null, null,
                        "Denver", "CO", "80202", ""),   // blank country
                "Contact", "5551234567", 1, new BigDecimal("5"), "LB", null,
                "REAL-ACCOUNT",   // account is fine
                null);
        PickupResult r = c.schedulePickup(noCountry, "real-oauth-bearer-token", "SANDBOX");
        assertEquals("ERROR", r.status());
        assertTrue(r.message().contains("pickup address country"),
                "message must name the missing field; got: " + r.message());
    }

    @Test
    void ups_schedulePickup_null_address_shortCircuits() {
        // Sanity — a null address is also caught (rare but possible for
        // direct-constructor callers without going through PickupRequestDTO).
        UpsConnector c = new UpsConnector(new CarrierProperties(), new ObjectMapper());
        PickupRequest noAddress = new PickupRequest(
                LocalDate.of(2026, 8, 1), LocalTime.of(13, 0), LocalTime.of(17, 0),
                null,
                "Contact", "5551234567", 1, new BigDecimal("5"), "LB", null,
                "REAL-ACCOUNT", null);
        PickupResult r = c.schedulePickup(noAddress, "real-oauth-bearer-token", "SANDBOX");
        assertEquals("ERROR", r.status());
        assertTrue(r.message().contains("pickup address country"), "got: " + r.message());
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

    @Test
    @SuppressWarnings("unchecked")
    void fedex_pickup_body_carries_real_account_number_not_placeholder() {
        // FDX-C — pre-fix, associatedAccountNumber was the literal string
        // "ACCOUNT" (hardcoded on line 1151 pre-refactor). FedEx pickup
        // rejects that with a validation error every time. Now sourced
        // from PickupRequest.accountNumber().
        FedExConnector c = new FedExConnector(new CarrierProperties(), new ObjectMapper(), noFx());
        java.util.Map<String, Object> body = c.buildFedExPickupRequest(baseRequest());
        java.util.Map<String, Object> account =
                (java.util.Map<String, Object>) body.get("associatedAccountNumber");
        assertEquals("740561111", account.get("value"),
                "pickup body must carry the real shipper account from PickupRequest, "
                        + "not the pre-FDX-C \"ACCOUNT\" placeholder");
    }

    @Test
    void fedex_schedulePickup_blank_account_shortCircuits_with_operator_message() {
        // FDX-C — pre-fix, schedulePickup would call FedEx with the literal
        // "ACCOUNT" placeholder and get a cryptic FedEx validation error.
        // Now short-circuits at the entry point with an actionable message.
        FedExConnector c = new FedExConnector(new CarrierProperties(), new ObjectMapper(), noFx());
        PickupRequest noAccount = new PickupRequest(
                LocalDate.of(2026, 8, 1), LocalTime.of(13, 0), LocalTime.of(17, 0),
                new AddressToValidate(null, null, "1 A St", null, null,
                        "Denver", "CO", "80202", "US"),
                "Contact", "5551234567", 1, new BigDecimal("5"), "LB",
                null,
                "",     // blank account
                null);   // FDX-F pickupServiceType
        PickupResult r = c.schedulePickup(noAccount, "real-oauth-bearer-token", "SANDBOX");
        assertEquals("ERROR", r.status());
        assertTrue(r.message().contains("shipper account number"),
                "message must name the missing field for the operator; got: " + r.message());
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

    @Test
    @SuppressWarnings("unchecked")
    void dhl_pickup_body_carries_real_account_number_not_empty_string() {
        // FDX-C2 — pre-fix, DHL accounts[0].number was hardcoded to "".
        // DHL rejected that with a validation error every time. Now
        // sourced from PickupRequest.accountNumber().
        DhlConnector c = new DhlConnector(new CarrierProperties(), new ObjectMapper());
        java.util.Map<String, Object> body = c.buildDhlPickupRequest(baseRequest());
        java.util.List<java.util.Map<String, Object>> accounts =
                (java.util.List<java.util.Map<String, Object>>) body.get("accounts");
        assertEquals(1, accounts.size());
        assertEquals("shipper", accounts.get(0).get("typeCode"));
        assertEquals("740561111", accounts.get(0).get("number"),
                "DHL body must carry the real shipper account from PickupRequest, "
                        + "not the pre-FDX-C2 empty-string placeholder");
    }

    // ===== FDX-F — pickup body reflects operator intent =====

    @Test
    @SuppressWarnings("unchecked")
    void fedex_pickup_body_carrierCode_defaultsToGround_whenServiceTypeNull() {
        // Pre-FDX-F: hardcoded to FDXG. Post-FDX-F: still FDXG when
        // pickupServiceType is null (backward-compat guarantee for
        // callers that predate the new field).
        FedExConnector c = new FedExConnector(new CarrierProperties(), new ObjectMapper(), noFx());
        java.util.Map<String, Object> body = c.buildFedExPickupRequest(baseRequest());
        assertEquals("FDXG", body.get("carrierCode"),
                "null pickupServiceType must preserve the pre-FDX-F Ground default");
    }

    @Test
    @SuppressWarnings("unchecked")
    void fedex_pickup_body_carrierCode_switchesToExpress_forExpressServiceType() {
        // FDX-F — EXPRESS routes to FDXE (Express driver fleet); pre-fix
        // Express-only shippers couldn't schedule pickups at all.
        FedExConnector c = new FedExConnector(new CarrierProperties(), new ObjectMapper(), noFx());
        PickupRequest req = new PickupRequest(
                LocalDate.of(2026, 8, 1), LocalTime.of(13, 0), LocalTime.of(17, 0),
                new AddressToValidate(null, null, "1 A St", null, null,
                        "Denver", "CO", "80202", "US"),
                "Contact", "5551234567", 1, new BigDecimal("5"), "LB", null,
                "740561111", "EXPRESS");
        java.util.Map<String, Object> body = c.buildFedExPickupRequest(req);
        assertEquals("FDXE", body.get("carrierCode"));
    }

    @Test
    void fedex_pickup_mapCarrierCode_matrix() {
        // Ground / null / anything unknown → FDXG (safe backward-compat).
        // EXPRESS / INTERNATIONAL → FDXE (both flow through the Express
        // driver fleet; INTL is still Express under the hood).
        assertEquals("FDXG", FedExConnector.mapFedExPickupCarrierCode(null));
        assertEquals("FDXG", FedExConnector.mapFedExPickupCarrierCode(""));
        assertEquals("FDXG", FedExConnector.mapFedExPickupCarrierCode("GROUND"));
        assertEquals("FDXG", FedExConnector.mapFedExPickupCarrierCode("SOMETHING_UNKNOWN"));
        assertEquals("FDXE", FedExConnector.mapFedExPickupCarrierCode("EXPRESS"));
        assertEquals("FDXE", FedExConnector.mapFedExPickupCarrierCode("express"));  // case-insens
        assertEquals("FDXE", FedExConnector.mapFedExPickupCarrierCode("INTERNATIONAL"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void fedex_pickup_body_customerCloseTime_comesFromWindowEnd() {
        // FDX-F — pre-fix customerCloseTime was hardcoded "17:00:00".
        // Now sourced from req.pickupWindowEnd() so an early-close
        // warehouse (14:00) or late-close (20:00) get the correct
        // arrival window on the wire.
        FedExConnector c = new FedExConnector(new CarrierProperties(), new ObjectMapper(), noFx());
        PickupRequest req = new PickupRequest(
                LocalDate.of(2026, 8, 1), LocalTime.of(9, 0), LocalTime.of(14, 30),
                new AddressToValidate(null, null, "1 A St", null, null,
                        "Denver", "CO", "80202", "US"),
                "Contact", "5551234567", 1, new BigDecimal("5"), "LB", null,
                "740561111", null);
        java.util.Map<String, Object> body = c.buildFedExPickupRequest(req);
        java.util.Map<String, Object> originDetail =
                (java.util.Map<String, Object>) body.get("originDetail");
        assertEquals("14:30:00", originDetail.get("customerCloseTime"),
                "customerCloseTime must reflect req.pickupWindowEnd(), not the pre-FDX-F 17:00 hardcode");
    }

    @Test
    @SuppressWarnings("unchecked")
    void fedex_pickup_body_pickupDateType_isFutureDayForNonTodayPickup() {
        // FDX-F — pre-fix always sent SAME_DAY; FedEx rejects a
        // future-dated pickup submitted as SAME_DAY. baseRequest uses
        // 2026-08-01 which is far in the past — safe to assert FUTURE_DAY
        // (the isSameDayPickup helper compares against LabelDates.today()).
        FedExConnector c = new FedExConnector(new CarrierProperties(), new ObjectMapper(), noFx());
        java.util.Map<String, Object> body = c.buildFedExPickupRequest(baseRequest());
        java.util.Map<String, Object> originDetail =
                (java.util.Map<String, Object>) body.get("originDetail");
        assertEquals("FUTURE_DAY", originDetail.get("pickupDateType"),
                "a non-today pickupDate must produce FUTURE_DAY, not the pre-FDX-F SAME_DAY hardcode");
    }

    @Test
    @SuppressWarnings("unchecked")
    void ups_pickup_body_serviceCode_defaultsToGround_whenServiceTypeNull() {
        // Pre-FDX-F: hardcoded to "003". Post-FDX-F: still "003" when
        // pickupServiceType is null (backward-compat).
        UpsConnector c = new UpsConnector(new CarrierProperties(), new ObjectMapper());
        java.util.Map<String, Object> body = c.buildUpsPickupRequest(baseRequest());
        java.util.Map<String, Object> pcr = (java.util.Map<String, Object>) body.get("PickupCreationRequest");
        java.util.List<java.util.Map<String, Object>> pieces =
                (java.util.List<java.util.Map<String, Object>>) pcr.get("PickupPiece");
        assertEquals("003", pieces.get(0).get("ServiceCode"),
                "null pickupServiceType must preserve the pre-FDX-F Ground default (003)");
    }

    @Test
    @SuppressWarnings("unchecked")
    void ups_pickup_body_serviceCode_switchesToExpress_forExpressServiceType() {
        // FDX-F — EXPRESS routes to "007" (UPS Worldwide Express fleet).
        UpsConnector c = new UpsConnector(new CarrierProperties(), new ObjectMapper());
        PickupRequest req = new PickupRequest(
                LocalDate.of(2026, 8, 1), LocalTime.of(13, 0), LocalTime.of(17, 0),
                new AddressToValidate(null, null, "1 A St", null, null,
                        "Denver", "CO", "80202", "US"),
                "Contact", "5551234567", 1, new BigDecimal("5"), "LB", null,
                "V4-UPS-42", "EXPRESS");
        java.util.Map<String, Object> body = c.buildUpsPickupRequest(req);
        java.util.Map<String, Object> pcr = (java.util.Map<String, Object>) body.get("PickupCreationRequest");
        java.util.List<java.util.Map<String, Object>> pieces =
                (java.util.List<java.util.Map<String, Object>>) pcr.get("PickupPiece");
        assertEquals("007", pieces.get(0).get("ServiceCode"));
    }

    @Test
    void ups_pickup_mapServiceCode_matrix() {
        assertEquals("003", UpsConnector.mapUpsPickupServiceCode(null));
        assertEquals("003", UpsConnector.mapUpsPickupServiceCode(""));
        assertEquals("003", UpsConnector.mapUpsPickupServiceCode("GROUND"));
        assertEquals("003", UpsConnector.mapUpsPickupServiceCode("SOMETHING_UNKNOWN"));
        assertEquals("007", UpsConnector.mapUpsPickupServiceCode("EXPRESS"));
        assertEquals("007", UpsConnector.mapUpsPickupServiceCode("express"));   // case-insens
        assertEquals("007", UpsConnector.mapUpsPickupServiceCode("INTERNATIONAL"));
    }

    @Test
    void dhl_schedulePickup_blank_account_shortCircuits() {
        DhlConnector c = new DhlConnector(new CarrierProperties(), new ObjectMapper());
        PickupRequest noAccount = new PickupRequest(
                LocalDate.of(2026, 8, 1), LocalTime.of(13, 0), LocalTime.of(17, 0),
                new AddressToValidate(null, null, "1 A St", null, null,
                        "London", null, "SW1A 1AA", "GB"),
                "Contact", "5551234567", 1, new BigDecimal("5"), "KG", null, "", null);
        PickupResult r = c.schedulePickup(noAccount, "real-basic-auth-token", "SANDBOX");
        assertEquals("ERROR", r.status());
        assertTrue(r.message().contains("shipper account number"),
                "got: " + r.message());
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
