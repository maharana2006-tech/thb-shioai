package com.multiship.backend.service.carriers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.service.carriers.CarrierConnector.AddressToValidate;
import com.multiship.backend.service.carriers.CarrierConnector.AddressValidationResult;
import com.multiship.backend.service.fx.FxRateService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 31 — address validation response parsing across all four carriers.
 * Mirrors the VoidLabelTest / ReturnLabelTest pattern.
 */
class AddressValidationTest {

    private static FxRateService noFx() {
        return new FxRateService() {
            @Override public Optional<BigDecimal> rate(String f, String t) { return Optional.empty(); }
            @Override public Optional<BigDecimal> convert(BigDecimal a, String f, String t) { return Optional.empty(); }
            @Override public boolean supports(String c) { return false; }
        };
    }

    private static AddressToValidate address() {
        return new AddressToValidate(
                "Jane Doe", null,
                "42 Broadway", null, null,
                "New York", "NY", "10001", "US");
    }

    /* -------------------------- Auth guardrails -------------------------- */

    @Test
    void everyCarrierReturnsNotSupportedForLocalFallbackToken() {
        assertEquals("NOT_SUPPORTED",
                new UpsConnector(new CarrierProperties(), new ObjectMapper())
                        .validateAddress(address(), "ups-local-abc").matchLevel());
        assertEquals("NOT_SUPPORTED",
                new FedExConnector(new CarrierProperties(), new ObjectMapper(), noFx())
                        .validateAddress(address(), "fedex-local-abc").matchLevel());
        assertEquals("NOT_SUPPORTED",
                new DhlConnector(new CarrierProperties(), new ObjectMapper())
                        .validateAddress(address(), "dhl-local-abc").matchLevel());
        assertEquals("NOT_SUPPORTED",
                new StampsConnector(new CarrierProperties(), new ObjectMapper())
                        .validateAddress(address(), "stamps-local-abc").matchLevel());
    }

    @Test
    void nullTokenTreatedAsFallbackEverywhere() {
        assertEquals("NOT_SUPPORTED",
                new UpsConnector(new CarrierProperties(), new ObjectMapper())
                        .validateAddress(address(), null).matchLevel());
        assertEquals("NOT_SUPPORTED",
                new FedExConnector(new CarrierProperties(), new ObjectMapper(), noFx())
                        .validateAddress(address(), null).matchLevel());
        assertEquals("NOT_SUPPORTED",
                new DhlConnector(new CarrierProperties(), new ObjectMapper())
                        .validateAddress(address(), null).matchLevel());
        assertEquals("NOT_SUPPORTED",
                new StampsConnector(new CarrierProperties(), new ObjectMapper())
                        .validateAddress(address(), null).matchLevel());
    }

    /* -------------------------- UPS AVS response parsing -------------------------- */

    @Test
    void upsExactMatchWithCommercialClassification() {
        UpsConnector c = new UpsConnector(new CarrierProperties(), new ObjectMapper());
        String canned = """
                {
                  "XAVResponse": {
                    "ValidAddressIndicator": "",
                    "Candidate": {
                      "AddressClassification": {"Code": "1", "Description": "Commercial"},
                      "AddressKeyFormat": {
                        "AddressLine": ["42 BROADWAY"],
                        "PoliticalDivision2": "NEW YORK",
                        "PoliticalDivision1": "NY",
                        "PostcodePrimaryLow": "10001",
                        "CountryCode": "US"
                      }
                    }
                  }
                }""";
        AddressValidationResult r = c.parseUpsAvsResponse(address(), canned);
        assertTrue(r.valid());
        assertEquals("EXACT", r.matchLevel());
        assertEquals("COMMERCIAL", r.classification());
    }

    @Test
    void upsCorrectedAddressWithResidentialClassification() {
        UpsConnector c = new UpsConnector(new CarrierProperties(), new ObjectMapper());
        // No ValidAddressIndicator + one candidate → CORRECTED.
        String canned = """
                {
                  "XAVResponse": {
                    "Candidate": {
                      "AddressClassification": {"Code": "2", "Description": "Residential"},
                      "AddressKeyFormat": {
                        "AddressLine": ["42 W BROADWAY APT 5A"],
                        "PoliticalDivision2": "NEW YORK",
                        "PoliticalDivision1": "NY",
                        "PostcodePrimaryLow": "10001-1234",
                        "CountryCode": "US"
                      }
                    }
                  }
                }""";
        AddressValidationResult r = c.parseUpsAvsResponse(address(), canned);
        assertTrue(r.valid());
        assertEquals("CORRECTED", r.matchLevel());
        assertEquals("RESIDENTIAL", r.classification());
        assertNotNull(r.suggested());
        assertEquals("42 W BROADWAY APT 5A", r.suggested().addressLine1());
        assertEquals("10001-1234", r.suggested().postalCode());
    }

    @Test
    void upsAmbiguousReturnsMultipleCandidates() {
        UpsConnector c = new UpsConnector(new CarrierProperties(), new ObjectMapper());
        String canned = """
                {
                  "XAVResponse": {
                    "AmbiguousAddressIndicator": "",
                    "Candidate": [
                      {"AddressKeyFormat": {"AddressLine": ["42 E BROADWAY"], "PoliticalDivision2": "NEW YORK"}},
                      {"AddressKeyFormat": {"AddressLine": ["42 W BROADWAY"], "PoliticalDivision2": "NEW YORK"}}
                    ]
                  }
                }""";
        AddressValidationResult r = c.parseUpsAvsResponse(address(), canned);
        assertFalse(r.valid());
        assertEquals("AMBIGUOUS", r.matchLevel());
        assertNotNull(r.suggested(), "First candidate should surface as the suggestion");
    }

    @Test
    void upsNoCandidatesReturnsNotFound() {
        UpsConnector c = new UpsConnector(new CarrierProperties(), new ObjectMapper());
        String canned = """
                {"XAVResponse": {"NoCandidatesIndicator": ""}}""";
        AddressValidationResult r = c.parseUpsAvsResponse(address(), canned);
        assertFalse(r.valid());
        assertEquals("NOT_FOUND", r.matchLevel());
    }

    @Test
    void upsMalformedResponseIsError() {
        UpsConnector c = new UpsConnector(new CarrierProperties(), new ObjectMapper());
        assertEquals("ERROR", c.parseUpsAvsResponse(address(), "not-json").matchLevel());
    }

    /* -------------------------- FedEx AV response parsing -------------------------- */

    @Test
    void fedexStandardizedIsExact() {
        FedExConnector c = new FedExConnector(new CarrierProperties(), new ObjectMapper(), noFx());
        String canned = """
                {
                  "output": {
                    "resolvedAddresses": [{
                      "classification": "BUSINESS",
                      "state": "STANDARDIZED",
                      "streetLinesToken": ["42 BROADWAY"],
                      "city": "NEW YORK",
                      "stateOrProvinceCode": "NY",
                      "postalCode": "10001",
                      "countryCode": "US"
                    }]
                  }
                }""";
        AddressValidationResult r = c.parseFedExAvResponse(canned);
        assertTrue(r.valid());
        assertEquals("EXACT", r.matchLevel());
        assertEquals("COMMERCIAL", r.classification());
    }

    @Test
    void fedexNormalizedIsCorrectedWithSuggestion() {
        FedExConnector c = new FedExConnector(new CarrierProperties(), new ObjectMapper(), noFx());
        String canned = """
                {
                  "output": {
                    "resolvedAddresses": [{
                      "classification": "RESIDENTIAL",
                      "state": "NORMALIZED",
                      "streetLinesToken": ["42 W BROADWAY APT 5A"],
                      "city": "NEW YORK",
                      "stateOrProvinceCode": "NY",
                      "postalCode": "10001-1234",
                      "countryCode": "US"
                    }]
                  }
                }""";
        AddressValidationResult r = c.parseFedExAvResponse(canned);
        assertTrue(r.valid());
        assertEquals("CORRECTED", r.matchLevel());
        assertEquals("RESIDENTIAL", r.classification());
        assertNotNull(r.suggested());
        assertEquals("42 W BROADWAY APT 5A", r.suggested().addressLine1());
    }

    @Test
    void fedexInvalidIsNotFound() {
        FedExConnector c = new FedExConnector(new CarrierProperties(), new ObjectMapper(), noFx());
        String canned = """
                {"output": {"resolvedAddresses": [{"state": "INVALID", "classification": "UNKNOWN"}]}}""";
        AddressValidationResult r = c.parseFedExAvResponse(canned);
        assertFalse(r.valid());
        assertEquals("NOT_FOUND", r.matchLevel());
    }

    @Test
    void fedexEmptyResolvedAddressesIsNotFound() {
        FedExConnector c = new FedExConnector(new CarrierProperties(), new ObjectMapper(), noFx());
        assertEquals("NOT_FOUND",
                c.parseFedExAvResponse("{\"output\": {}}").matchLevel());
    }

    /* -------------------------- DHL response parsing -------------------------- */

    @Test
    void dhlValidCombinationIsExact() {
        DhlConnector c = new DhlConnector(new CarrierProperties(), new ObjectMapper());
        String canned = """
                {
                  "address": [
                    {"countryCode": "US", "postalCode": "10001", "cityName": "NEW YORK",
                     "serviceArea": {"code": "JFK"}}
                  ]
                }""";
        AddressToValidate input = new AddressToValidate(null, null, "42 Broadway", null, null,
                "NEW YORK", null, "10001", "US");
        AddressValidationResult r = c.parseDhlAvResponse(input, canned);
        assertTrue(r.valid());
        assertEquals("EXACT", r.matchLevel());
    }

    @Test
    void dhlCityDiffersReturnsCorrected() {
        DhlConnector c = new DhlConnector(new CarrierProperties(), new ObjectMapper());
        String canned = """
                {
                  "address": [
                    {"countryCode": "US", "postalCode": "10001", "cityName": "NEW YORK",
                     "serviceArea": {"code": "JFK"}}
                  ]
                }""";
        // Input city differs from matched city → CORRECTED.
        AddressToValidate input = new AddressToValidate(null, null, "42 Broadway", null, null,
                "Manhattan", null, "10001", "US");
        AddressValidationResult r = c.parseDhlAvResponse(input, canned);
        assertTrue(r.valid());
        assertEquals("CORRECTED", r.matchLevel());
        assertNotNull(r.suggested());
        assertEquals("NEW YORK", r.suggested().city());
    }

    @Test
    void dhlEmptyAddressArrayIsNotFound() {
        DhlConnector c = new DhlConnector(new CarrierProperties(), new ObjectMapper());
        AddressValidationResult r = c.parseDhlAvResponse(address(), "{\"address\": []}");
        assertFalse(r.valid());
        assertEquals("NOT_FOUND", r.matchLevel());
    }

    @Test
    void dhlMissingAddressFieldIsNotFound() {
        DhlConnector c = new DhlConnector(new CarrierProperties(), new ObjectMapper());
        assertEquals("NOT_FOUND", c.parseDhlAvResponse(address(), "{}").matchLevel());
    }

    /* -------------------------- SWSIM CleanseAddress parsing -------------------------- */

    @Test
    void stampsBuildDomesticEnvelopeOmitsCountry() {
        StampsConnector c = new StampsConnector(new CarrierProperties(), new ObjectMapper());
        String xml = c.buildCleanseAddressEnvelope(address(), "AUTH-XYZ", true);
        assertTrue(xml.contains("<CleanseAddress xmlns=\""), xml);
        assertTrue(xml.contains("<Authenticator>AUTH-XYZ</Authenticator>"));
        assertTrue(xml.contains("<ZIPCode>10001</ZIPCode>"));
        assertFalse(xml.contains("<Country>"),
                "Domestic CleanseAddress should NOT emit Country");
    }

    @Test
    void stampsBuildForeignEnvelopeUsesValidateForeignAddress() {
        StampsConnector c = new StampsConnector(new CarrierProperties(), new ObjectMapper());
        AddressToValidate uk = new AddressToValidate(
                "Alice", null, "10 Downing St", null, null,
                "London", null, "SW1A 2AA", "GB");
        String xml = c.buildCleanseAddressEnvelope(uk, "AUTH-XYZ", false);
        assertTrue(xml.contains("<ValidateForeignAddress xmlns=\""), xml);
        assertTrue(xml.contains("<Country>GB</Country>"),
                "Foreign path must emit Country");
    }

    @Test
    void stampsExactMatchWhenAddressMatchTrue() {
        StampsConnector c = new StampsConnector(new CarrierProperties(), new ObjectMapper());
        String canned = """
                <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
                  <soap:Body>
                    <CleanseAddressResponse>
                      <AddressMatch>true</AddressMatch>
                      <CityStateZipOK>true</CityStateZipOK>
                    </CleanseAddressResponse>
                  </soap:Body>
                </soap:Envelope>""";
        AddressValidationResult r = c.parseCleanseAddressResponse(address(), canned, true);
        assertTrue(r.valid());
        assertEquals("EXACT", r.matchLevel());
    }

    @Test
    void stampsCorrectedWhenCityStateZipOkButNotAddressMatch() {
        StampsConnector c = new StampsConnector(new CarrierProperties(), new ObjectMapper());
        String canned = """
                <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
                  <soap:Body>
                    <CleanseAddressResponse>
                      <AddressMatch>false</AddressMatch>
                      <CityStateZipOK>true</CityStateZipOK>
                      <Address1>42 BROADWAY</Address1>
                      <City>NEW YORK</City>
                      <State>NY</State>
                      <ZIPCode>10001-1234</ZIPCode>
                    </CleanseAddressResponse>
                  </soap:Body>
                </soap:Envelope>""";
        AddressValidationResult r = c.parseCleanseAddressResponse(address(), canned, true);
        assertTrue(r.valid());
        assertEquals("CORRECTED", r.matchLevel());
        assertNotNull(r.suggested());
        assertEquals("10001-1234", r.suggested().postalCode());
    }

    @Test
    void stampsNotFoundWhenNeitherAddressMatchNorCityStateZipOk() {
        StampsConnector c = new StampsConnector(new CarrierProperties(), new ObjectMapper());
        String canned = """
                <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
                  <soap:Body>
                    <CleanseAddressResponse>
                      <AddressMatch>false</AddressMatch>
                      <CityStateZipOK>false</CityStateZipOK>
                    </CleanseAddressResponse>
                  </soap:Body>
                </soap:Envelope>""";
        AddressValidationResult r = c.parseCleanseAddressResponse(address(), canned, true);
        assertFalse(r.valid());
        assertEquals("NOT_FOUND", r.matchLevel());
    }

    @Test
    void stampsFaultstringSurfacesAsError() {
        StampsConnector c = new StampsConnector(new CarrierProperties(), new ObjectMapper());
        String canned = """
                <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
                  <soap:Body>
                    <soap:Fault><faultstring>Invalid Authenticator</faultstring></soap:Fault>
                  </soap:Body>
                </soap:Envelope>""";
        AddressValidationResult r = c.parseCleanseAddressResponse(address(), canned, true);
        assertEquals("ERROR", r.matchLevel());
        assertTrue(r.message().contains("Invalid Authenticator"));
    }
}
