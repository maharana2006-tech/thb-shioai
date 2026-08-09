package com.multiship.backend.service.carriers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.dto.ShipmentRequestDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden-value tests for the Stamps.com SWSIM {@code GetRates} parser +
 * envelope builder. Canned SOAP fixtures so no live SWSIM sandbox is
 * required. Mirrors the FedEx / UPS / DHL rate-shop test scaffolds.
 */
class StampsRateShopTest {

    private StampsConnector connector;

    @BeforeEach
    void setUp() {
        connector = new StampsConnector(new CarrierProperties(), new ObjectMapper());
    }

    private ShipmentRequestDTO domesticRequest() {
        return ShipmentRequestDTO.builder()
                .carrierCode("USPS").accountNumber("swsim-user")
                .serviceType("USPS GA").packageType("Package")
                .weight(new BigDecimal("2")).weightUnit("LB")
                .shipperName("Acme").shipperPhone("5551234567")
                .shipperAddressLine1("1 Way").shipperCity("Louisville")
                .shipperState("KY").shipperPostalCode("40209").shipperCountryCode("US")
                .recipientName("Jane").recipientPhone("5559876543")
                .recipientAddressLine1("42 Broadway").recipientCity("New York")
                .recipientState("NY").recipientPostalCode("10001").recipientCountryCode("US")
                .build();
    }

    /* -------------------------- Auth guardrails -------------------------- */

    @Test
    void localFallbackTokenReturnsEmptyList() {
        assertTrue(connector.getRates(domesticRequest(), "stamps-local-abc", null).isEmpty());
    }

    @Test
    void blankTokenReturnsEmptyList() {
        assertTrue(connector.getRates(domesticRequest(), "", null).isEmpty());
    }

    @Test
    void nullTokenReturnsEmptyList() {
        assertTrue(connector.getRates(domesticRequest(), null, null).isEmpty());
    }

    /* -------------------------- Envelope -------------------------- */

    @Test
    void envelopeIncludesAuthenticatorAndLaneFields() {
        String xml = connector.buildGetRatesEnvelope(domesticRequest(), "AUTH-123");
        assertTrue(xml.contains("<Authenticator>AUTH-123</Authenticator>"));
        assertTrue(xml.contains("<GetRates xmlns=\""),
                "Should namespace the GetRates element with SWSIM v135");
        assertTrue(xml.contains("<From><ZIPCode>40209</ZIPCode></From>"));
        assertTrue(xml.contains("<ZIPCode>10001</ZIPCode>"));
        assertTrue(xml.contains("<PackageType>Package</PackageType>"));
        // Weight goes on the wire in ounces; 2 LB → 32 OZ (SWSIM tolerates
        // scale, so "32.00" is fine — just verify the ounce conversion ran).
        assertTrue(xml.contains("<WeightOz>32"), xml);
    }

    @Test
    void envelopeOmitsServiceTypeSoSwsimReturnsFullLadder() {
        // GetRates must NOT constrain to a single ServiceType — the whole
        // point of the call is to see every service the lane supports.
        String xml = connector.buildGetRatesEnvelope(domesticRequest(), "AUTH-123");
        assertTrue(!xml.contains("<ServiceType>"),
                "GetRates envelope should omit ServiceType to fetch the full ladder");
    }

    @Test
    void envelopeOmitsCountryForDomesticButAddsForInternational() {
        String domestic = connector.buildGetRatesEnvelope(domesticRequest(), "AUTH-123");
        assertTrue(!domestic.contains("<Country>"),
                "Domestic US destinations must NOT emit a <Country> element");

        ShipmentRequestDTO intl = ShipmentRequestDTO.builder()
                .carrierCode("USPS").accountNumber("swsim-user")
                .serviceType("USPS PMI").packageType("Package")
                .weight(new BigDecimal("2")).weightUnit("LB")
                .shipperPostalCode("40209").shipperCountryCode("US")
                .recipientPostalCode("M5H 2N2").recipientCountryCode("CA")
                .build();
        String intlXml = connector.buildGetRatesEnvelope(intl, "AUTH-123");
        assertTrue(intlXml.contains("<Country>CA</Country>"),
                "International destinations must emit <Country>");
    }

    /* -------------------------- Response parsing -------------------------- */

    @Test
    void parseGetRatesResponseFlattensRatesArray() {
        String canned = """
                <?xml version="1.0" encoding="utf-8"?>
                <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
                  <soap:Body>
                    <GetRatesResponse>
                      <Authenticator>NEW-AUTH-456</Authenticator>
                      <Rates>
                        <Rate>
                          <ServiceType>USPS PM</ServiceType>
                          <ServiceDescription>USPS Priority Mail</ServiceDescription>
                          <Amount>10.20</Amount>
                          <DeliverDays>2</DeliverDays>
                          <DeliveryDate>2024-01-18T00:00:00</DeliveryDate>
                        </Rate>
                        <Rate>
                          <ServiceType>USPS GA</ServiceType>
                          <ServiceDescription>USPS Ground Advantage</ServiceDescription>
                          <Amount>7.85</Amount>
                          <DeliverDays>5</DeliverDays>
                        </Rate>
                      </Rates>
                    </GetRatesResponse>
                  </soap:Body>
                </soap:Envelope>""";
        List<CarrierConnector.RateOption> options = connector.parseGetRatesResponse(canned);
        assertEquals(2, options.size());

        CarrierConnector.RateOption priority = options.get(0);
        assertEquals("USPS", priority.carrierCode());
        assertEquals("USPS PM", priority.serviceCode());
        assertEquals("USPS Priority Mail", priority.serviceName());
        assertEquals(0, new BigDecimal("10.20").compareTo(priority.totalAmount()));
        assertEquals("USD", priority.currency());
        assertEquals(Integer.valueOf(2), priority.transitDays());
        assertNotNull(priority.estimatedDelivery());

        CarrierConnector.RateOption ground = options.get(1);
        assertEquals("USPS GA", ground.serviceCode());
        assertEquals(0, new BigDecimal("7.85").compareTo(ground.totalAmount()));
        assertEquals(Integer.valueOf(5), ground.transitDays());
    }

    @Test
    void parseGetRatesResponseHandlesDeliverDaysRange() {
        // SWSIM sometimes emits a range ("1-3") for regional variability;
        // we return the LOWER bound to match "as fast as N days" copy.
        String canned = """
                <GetRatesResponse>
                  <Rates>
                    <Rate>
                      <ServiceType>USPS GA</ServiceType>
                      <ServiceDescription>USPS Ground Advantage</ServiceDescription>
                      <Amount>7.85</Amount>
                      <DeliverDays>1-3</DeliverDays>
                    </Rate>
                  </Rates>
                </GetRatesResponse>""";
        assertEquals(Integer.valueOf(1),
                connector.parseGetRatesResponse(canned).get(0).transitDays());
    }

    @Test
    void parseSwsimDeliverDaysCoversRangeAndScalarAndBlank() {
        assertEquals(Integer.valueOf(2), StampsConnector.parseSwsimDeliverDays("2"));
        assertEquals(Integer.valueOf(1), StampsConnector.parseSwsimDeliverDays("1-3"));
        assertEquals(Integer.valueOf(5), StampsConnector.parseSwsimDeliverDays(" 5 "));
        assertNull(StampsConnector.parseSwsimDeliverDays(null));
        assertNull(StampsConnector.parseSwsimDeliverDays(""));
        assertNull(StampsConnector.parseSwsimDeliverDays("N/A"));
    }

    @Test
    void parseGetRatesResponseFallsBackToBuiltInServiceName() {
        // SWSIM sometimes omits ServiceDescription; parser should fill from
        // the built-in code → name matrix.
        String canned = """
                <GetRatesResponse>
                  <Rates>
                    <Rate>
                      <ServiceType>USPS PM</ServiceType>
                      <Amount>10.20</Amount>
                      <DeliverDays>2</DeliverDays>
                    </Rate>
                  </Rates>
                </GetRatesResponse>""";
        assertEquals("USPS Priority Mail",
                connector.parseGetRatesResponse(canned).get(0).serviceName());
    }

    @Test
    void parseGetRatesResponseSkipsEntriesWithoutServiceType() {
        String canned = """
                <GetRatesResponse>
                  <Rates>
                    <Rate>
                      <ServiceDescription>Mystery service</ServiceDescription>
                      <Amount>10.20</Amount>
                    </Rate>
                    <Rate>
                      <ServiceType>USPS GA</ServiceType>
                      <ServiceDescription>USPS Ground Advantage</ServiceDescription>
                      <Amount>7.85</Amount>
                    </Rate>
                  </Rates>
                </GetRatesResponse>""";
        List<CarrierConnector.RateOption> options = connector.parseGetRatesResponse(canned);
        assertEquals(1, options.size(),
                "Entry without ServiceType should be silently dropped");
        assertEquals("USPS GA", options.get(0).serviceCode());
    }

    @Test
    void parseGetRatesResponseSkipsEntriesWithoutAmount() {
        String canned = """
                <GetRatesResponse>
                  <Rates>
                    <Rate>
                      <ServiceType>USPS PM</ServiceType>
                      <ServiceDescription>USPS Priority Mail</ServiceDescription>
                    </Rate>
                  </Rates>
                </GetRatesResponse>""";
        assertTrue(connector.parseGetRatesResponse(canned).isEmpty());
    }

    @Test
    void parseGetRatesResponseTolerantOfMoneyFormattedAmounts() {
        // SWSIM occasionally emits "$10.20" (currency-formatted) on error
        // paths — parseSwsimAmount strips $ and , so those don't crash.
        String canned = """
                <GetRatesResponse>
                  <Rates>
                    <Rate>
                      <ServiceType>USPS PM</ServiceType>
                      <ServiceDescription>USPS Priority Mail</ServiceDescription>
                      <Amount>$10.20</Amount>
                    </Rate>
                  </Rates>
                </GetRatesResponse>""";
        assertEquals(0, new BigDecimal("10.20").compareTo(
                connector.parseGetRatesResponse(canned).get(0).totalAmount()));
    }

    @Test
    void parseGetRatesResponseHandlesEmptyAndMalformedResponses() {
        assertTrue(connector.parseGetRatesResponse("").isEmpty());
        assertTrue(connector.parseGetRatesResponse(null).isEmpty());
        assertTrue(connector.parseGetRatesResponse("<GetRatesResponse></GetRatesResponse>").isEmpty());
    }

    @Test
    void parseGetRatesResponseAlwaysReturnsUsdCurrency() {
        // USPS bills in USD only — parser should hard-code USD regardless
        // of SWSIM emitting a currency element or not.
        String canned = """
                <GetRatesResponse>
                  <Rates>
                    <Rate>
                      <ServiceType>USPS PMI</ServiceType>
                      <ServiceDescription>USPS Priority Mail International</ServiceDescription>
                      <Amount>42.75</Amount>
                    </Rate>
                  </Rates>
                </GetRatesResponse>""";
        assertEquals("USD",
                connector.parseGetRatesResponse(canned).get(0).currency());
    }
}
