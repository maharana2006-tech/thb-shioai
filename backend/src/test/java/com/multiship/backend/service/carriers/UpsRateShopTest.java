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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden-value tests for UPS Rate Shop parsing — canned response JSON so
 * no live UPS sandbox is required. Mirrors FedExRateShopTest.
 */
class UpsRateShopTest {

    private UpsConnector connector;

    @BeforeEach
    void setUp() {
        CarrierProperties props = new CarrierProperties();
        props.getUps().setApiVersion("v2205");
        connector = new UpsConnector(props, new ObjectMapper());
    }

    private ShipmentRequestDTO minimalRequest() {
        return ShipmentRequestDTO.builder()
                .carrierCode("UPS").accountNumber("A99999")
                .serviceType("03").packageType("02")
                .weight(new BigDecimal("2.5")).weightUnit("LB")
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
        assertTrue(connector.getRates(minimalRequest(), "ups-local-abc", null).isEmpty());
    }

    @Test
    void blankTokenReturnsEmptyList() {
        assertTrue(connector.getRates(minimalRequest(), "", null).isEmpty());
    }

    @Test
    void nullTokenReturnsEmptyList() {
        assertTrue(connector.getRates(minimalRequest(), null, null).isEmpty());
    }

    /* -------------------------- Response parsing -------------------------- */

    @Test
    void parseUpsRateResponseFlattensRatedShipmentArray() {
        String canned = """
                {
                  "RateResponse": {
                    "RatedShipment": [
                      {
                        "Service": {"Code": "03", "Description": "UPS Ground"},
                        "TotalCharges": {"MonetaryValue": "15.00", "CurrencyCode": "USD"},
                        "NegotiatedRateCharges": {
                          "TotalCharge": {"MonetaryValue": "12.50", "CurrencyCode": "USD"}
                        },
                        "GuaranteedDelivery": {
                          "BusinessDaysInTransit": "2",
                          "DeliveryByTime": "2024-01-18T00:00:00"
                        }
                      },
                      {
                        "Service": {"Code": "01", "Description": "UPS Next Day Air"},
                        "TotalCharges": {"MonetaryValue": "42.75", "CurrencyCode": "USD"},
                        "GuaranteedDelivery": {"BusinessDaysInTransit": "1"}
                      }
                    ]
                  }
                }""";
        List<CarrierConnector.RateOption> options = connector.parseUpsRateResponse(canned);
        assertEquals(2, options.size());

        CarrierConnector.RateOption ground = options.get(0);
        assertEquals("UPS", ground.carrierCode());
        assertEquals("03", ground.serviceCode());
        assertEquals("UPS Ground", ground.serviceName());
        assertEquals(0, new BigDecimal("12.50").compareTo(ground.totalAmount()),
                "NegotiatedRateCharges should win over TotalCharges when both present");
        assertEquals("USD", ground.currency());
        assertEquals(Integer.valueOf(2), ground.transitDays());
        assertNotNull(ground.estimatedDelivery());

        CarrierConnector.RateOption overnight = options.get(1);
        assertEquals("01", overnight.serviceCode());
        assertEquals(0, new BigDecimal("42.75").compareTo(overnight.totalAmount()));
        assertEquals(Integer.valueOf(1), overnight.transitDays());
    }

    @Test
    void parseUpsRateResponseFallsBackToTotalChargesWhenNoNegotiated() {
        String canned = """
                {
                  "RateResponse": {
                    "RatedShipment": [
                      {
                        "Service": {"Code": "03", "Description": "UPS Ground"},
                        "TotalCharges": {"MonetaryValue": "15.00", "CurrencyCode": "USD"}
                      }
                    ]
                  }
                }""";
        List<CarrierConnector.RateOption> options = connector.parseUpsRateResponse(canned);
        assertEquals(1, options.size());
        assertEquals(0, new BigDecimal("15.00").compareTo(options.get(0).totalAmount()));
    }

    @Test
    void parseUpsRateResponseTreatsSingleObjectAsList() {
        // UPS returns a single object (not an array) when only one service
        // matches the lane; the parser must handle both shapes.
        String canned = """
                {
                  "RateResponse": {
                    "RatedShipment": {
                      "Service": {"Code": "11", "Description": "UPS Standard"},
                      "TotalCharges": {"MonetaryValue": "10.00", "CurrencyCode": "EUR"}
                    }
                  }
                }""";
        List<CarrierConnector.RateOption> options = connector.parseUpsRateResponse(canned);
        assertEquals(1, options.size());
        assertEquals("11", options.get(0).serviceCode());
        assertEquals("EUR", options.get(0).currency());
    }

    @Test
    void parseUpsRateResponseFallsBackToBuiltInServiceName() {
        // UPS often omits Service.Description; the parser should fill from
        // the built-in matrix so downstream sees "UPS Ground" not empty.
        String canned = """
                {
                  "RateResponse": {
                    "RatedShipment": [
                      {
                        "Service": {"Code": "03"},
                        "TotalCharges": {"MonetaryValue": "15.00", "CurrencyCode": "USD"}
                      }
                    ]
                  }
                }""";
        List<CarrierConnector.RateOption> options = connector.parseUpsRateResponse(canned);
        assertEquals("UPS Ground", options.get(0).serviceName());
    }

    @Test
    void parseUpsRateResponseHandlesNumericMonetaryValue() {
        // UPS occasionally returns MonetaryValue as a JSON number instead of
        // a string — the parser handles both.
        String canned = """
                {
                  "RateResponse": {
                    "RatedShipment": [
                      {
                        "Service": {"Code": "03"},
                        "TotalCharges": {"MonetaryValue": 18.75, "CurrencyCode": "USD"}
                      }
                    ]
                  }
                }""";
        List<CarrierConnector.RateOption> options = connector.parseUpsRateResponse(canned);
        assertEquals(0, new BigDecimal("18.75").compareTo(options.get(0).totalAmount()));
    }

    @Test
    void parseUpsRateResponseHandlesEmptyAndMalformedResponses() {
        assertTrue(connector.parseUpsRateResponse("{}").isEmpty());
        assertTrue(connector.parseUpsRateResponse("").isEmpty());
        assertTrue(connector.parseUpsRateResponse(null).isEmpty());
    }

    @Test
    void parseUpsRateResponseSkipsEntriesWithoutServiceCode() {
        String canned = """
                {
                  "RateResponse": {
                    "RatedShipment": [
                      {
                        "Service": {"Description": "Mystery service"},
                        "TotalCharges": {"MonetaryValue": "10.00", "CurrencyCode": "USD"}
                      },
                      {
                        "Service": {"Code": "03", "Description": "UPS Ground"},
                        "TotalCharges": {"MonetaryValue": "12.50", "CurrencyCode": "USD"}
                      }
                    ]
                  }
                }""";
        List<CarrierConnector.RateOption> options = connector.parseUpsRateResponse(canned);
        assertEquals(1, options.size(),
                "Entry without Service.Code should be silently dropped");
        assertEquals("03", options.get(0).serviceCode());
    }

    @Test
    void parseUpsRateResponseSkipsEntriesWithoutAmount() {
        String canned = """
                {
                  "RateResponse": {
                    "RatedShipment": [
                      {
                        "Service": {"Code": "03", "Description": "UPS Ground"}
                      }
                    ]
                  }
                }""";
        assertTrue(connector.parseUpsRateResponse(canned).isEmpty());
    }

    @Test
    void parseUpsRateResponseDefaultsCurrencyToUsd() {
        String canned = """
                {
                  "RateResponse": {
                    "RatedShipment": [
                      {
                        "Service": {"Code": "03"},
                        "TotalCharges": {"MonetaryValue": "15.00"}
                      }
                    ]
                  }
                }""";
        assertEquals("USD", connector.parseUpsRateResponse(canned).get(0).currency());
    }

    @Test
    void parseUpsRateResponseIgnoresMalformedTransitDays() {
        // A non-numeric BusinessDaysInTransit should leave transitDays null
        // rather than throwing — matches the FedEx pattern.
        String canned = """
                {
                  "RateResponse": {
                    "RatedShipment": [
                      {
                        "Service": {"Code": "03"},
                        "TotalCharges": {"MonetaryValue": "15.00", "CurrencyCode": "USD"},
                        "GuaranteedDelivery": {"BusinessDaysInTransit": "N/A"}
                      }
                    ]
                  }
                }""";
        assertEquals(null, connector.parseUpsRateResponse(canned).get(0).transitDays());
    }
}
