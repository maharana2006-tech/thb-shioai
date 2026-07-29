package com.multiship.backend.service.carriers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.dto.ShipmentRequestDTO;
import com.multiship.backend.service.fx.FxRateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden-value tests for FedEx rate parsing. Reflection into the private
 * helpers + canned response JSON so no live FedEx sandbox is required.
 * Mirrors the FedExTrackingTest scaffold from Sprint 12.
 */
class FedExRateShopTest {

    private FedExConnector connector;

    @BeforeEach
    void setUp() {
        CarrierProperties props = new CarrierProperties();
        props.getFedEx().setLabelResponseOption("URL_ONLY");
        FxRateService noFx = new FxRateService() {
            @Override public Optional<BigDecimal> rate(String f, String t) { return Optional.empty(); }
            @Override public Optional<BigDecimal> convert(BigDecimal a, String f, String t) { return Optional.empty(); }
            @Override public boolean supports(String c) { return false; }
        };
        connector = new FedExConnector(props, new ObjectMapper(), noFx);
    }

    private ShipmentRequestDTO minimalRequest() {
        return ShipmentRequestDTO.builder()
                .carrierCode("FEDEX").accountNumber("A99999")
                .serviceType("FEDEX_GROUND").packageType("YOUR_PACKAGING")
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
        assertTrue(connector.getRates(minimalRequest(), "fedex-local-abc").isEmpty());
    }

    @Test
    void blankTokenReturnsEmptyList() {
        assertTrue(connector.getRates(minimalRequest(), "").isEmpty());
    }

    @Test
    void nullTokenReturnsEmptyList() {
        assertTrue(connector.getRates(minimalRequest(), null).isEmpty());
    }

    /* -------------------------- Response parsing -------------------------- */

    @Test
    void parseFedExRateResponseFlattensRateReplyDetails() {
        String canned = """
                {
                  "output": {
                    "rateReplyDetails": [
                      {
                        "serviceType": "FEDEX_GROUND",
                        "serviceName": "FedEx Ground",
                        "packagingType": "YOUR_PACKAGING",
                        "operationalDetail": {
                          "transitTime": "TWO_DAYS",
                          "deliveryDate": "2024-01-18T00:00:00"
                        },
                        "ratedShipmentDetails": [
                          {
                            "rateType": "LIST",
                            "shipmentRateDetail": {
                              "totalNetCharge": {"amount": 15.00, "currency": "USD"}
                            }
                          },
                          {
                            "rateType": "ACCOUNT",
                            "shipmentRateDetail": {
                              "totalNetCharge": {"amount": 12.50, "currency": "USD"}
                            }
                          }
                        ]
                      },
                      {
                        "serviceType": "PRIORITY_OVERNIGHT",
                        "serviceName": "FedEx Priority Overnight",
                        "operationalDetail": {
                          "transitTime": "ONE_DAY",
                          "deliveryDate": "2024-01-17T10:30:00"
                        },
                        "ratedShipmentDetails": [
                          {
                            "rateType": "ACCOUNT",
                            "shipmentRateDetail": {
                              "totalNetCharge": {"amount": 42.75, "currency": "USD"}
                            }
                          }
                        ]
                      }
                    ]
                  }
                }""";
        List<CarrierConnector.RateOption> options = connector.parseFedExRateResponse(canned);
        assertEquals(2, options.size());

        CarrierConnector.RateOption ground = options.get(0);
        assertEquals("FEDEX", ground.carrierCode());
        assertEquals("FEDEX_GROUND", ground.serviceCode());
        assertEquals("FedEx Ground", ground.serviceName());
        assertEquals(0, new BigDecimal("12.50").compareTo(ground.totalAmount()),
                "ACCOUNT rate should win over LIST when both present");
        assertEquals("USD", ground.currency());
        assertEquals(Integer.valueOf(2), ground.transitDays());
        assertNotNull(ground.estimatedDelivery());

        CarrierConnector.RateOption overnight = options.get(1);
        assertEquals("PRIORITY_OVERNIGHT", overnight.serviceCode());
        assertEquals(0, new BigDecimal("42.75").compareTo(overnight.totalAmount()));
        assertEquals(Integer.valueOf(1), overnight.transitDays());
    }

    @Test
    void parseFedExRateResponseFallsBackToListWhenNoAccount() {
        String canned = """
                {
                  "output": {
                    "rateReplyDetails": [
                      {
                        "serviceType": "FEDEX_GROUND",
                        "ratedShipmentDetails": [
                          {
                            "rateType": "LIST",
                            "shipmentRateDetail": {
                              "totalNetCharge": {"amount": 15.00, "currency": "USD"}
                            }
                          }
                        ]
                      }
                    ]
                  }
                }""";
        List<CarrierConnector.RateOption> options = connector.parseFedExRateResponse(canned);
        assertEquals(1, options.size());
        assertEquals(0, new BigDecimal("15.00").compareTo(options.get(0).totalAmount()));
    }

    @Test
    void parseFedExRateResponseUsesFirstEntryWhenNoTypedRates() {
        // No rateType at all — pick the first entry as-is.
        String canned = """
                {
                  "output": {
                    "rateReplyDetails": [
                      {
                        "serviceType": "FEDEX_GROUND",
                        "ratedShipmentDetails": [
                          {
                            "shipmentRateDetail": {
                              "totalNetCharge": {"amount": 20.00, "currency": "USD"}
                            }
                          }
                        ]
                      }
                    ]
                  }
                }""";
        List<CarrierConnector.RateOption> options = connector.parseFedExRateResponse(canned);
        assertEquals(0, new BigDecimal("20.00").compareTo(options.get(0).totalAmount()));
    }

    @Test
    void parseFedExRateResponseHandlesTopLevelTotalNetChargeShape() {
        // Older FedEx sandboxes emit totalNetCharge directly on the entry
        // rather than nested under shipmentRateDetail — both forms should
        // land the same amount.
        String canned = """
                {
                  "output": {
                    "rateReplyDetails": [
                      {
                        "serviceType": "FEDEX_GROUND",
                        "ratedShipmentDetails": [
                          {
                            "rateType": "ACCOUNT",
                            "totalNetCharge": 18.75,
                            "currency": "USD"
                          }
                        ]
                      }
                    ]
                  }
                }""";
        List<CarrierConnector.RateOption> options = connector.parseFedExRateResponse(canned);
        assertEquals(1, options.size());
        assertEquals(0, new BigDecimal("18.75").compareTo(options.get(0).totalAmount()));
        assertEquals("USD", options.get(0).currency());
    }

    @Test
    void parseFedExRateResponseHandlesEmptyResponse() {
        assertTrue(connector.parseFedExRateResponse("{}").isEmpty());
        assertTrue(connector.parseFedExRateResponse("").isEmpty());
        assertTrue(connector.parseFedExRateResponse(null).isEmpty());
    }

    @Test
    void parseFedExRateResponseSkipsEntriesWithoutServiceType() {
        String canned = """
                {
                  "output": {
                    "rateReplyDetails": [
                      {
                        "serviceName": "Mystery service",
                        "ratedShipmentDetails": [
                          {"rateType": "ACCOUNT", "shipmentRateDetail":
                            {"totalNetCharge": {"amount": 10, "currency": "USD"}}}
                        ]
                      },
                      {
                        "serviceType": "FEDEX_GROUND",
                        "ratedShipmentDetails": [
                          {"rateType": "ACCOUNT", "shipmentRateDetail":
                            {"totalNetCharge": {"amount": 12.50, "currency": "USD"}}}
                        ]
                      }
                    ]
                  }
                }""";
        List<CarrierConnector.RateOption> options = connector.parseFedExRateResponse(canned);
        assertEquals(1, options.size(),
                "Entry without serviceType should be silently dropped");
        assertEquals("FEDEX_GROUND", options.get(0).serviceCode());
    }

    @Test
    void parseFedExRateResponseSkipsEntriesWithoutAmount() {
        String canned = """
                {
                  "output": {
                    "rateReplyDetails": [
                      {
                        "serviceType": "FEDEX_GROUND",
                        "ratedShipmentDetails": []
                      }
                    ]
                  }
                }""";
        assertTrue(connector.parseFedExRateResponse(canned).isEmpty());
    }

    @Test
    void parseFedExRateResponseDefaultsCurrencyToUsd() {
        // Currency omitted → default USD (matches the Sprint 3 FedEx
        // declaredValue currency fallback).
        String canned = """
                {
                  "output": {
                    "rateReplyDetails": [
                      {
                        "serviceType": "FEDEX_GROUND",
                        "ratedShipmentDetails": [
                          {
                            "rateType": "ACCOUNT",
                            "shipmentRateDetail": {
                              "totalNetCharge": {"amount": 12.50}
                            }
                          }
                        ]
                      }
                    ]
                  }
                }""";
        assertEquals("USD", connector.parseFedExRateResponse(canned).get(0).currency());
    }

    /* -------------------------- Transit time enum matrix -------------------------- */

    @Test
    void parseFedExTransitTimeCoversTheWordEnum() {
        assertEquals(Integer.valueOf(1), FedExConnector.parseFedExTransitTime("ONE_DAY"));
        assertEquals(Integer.valueOf(2), FedExConnector.parseFedExTransitTime("TWO_DAYS"));
        assertEquals(Integer.valueOf(5), FedExConnector.parseFedExTransitTime("FIVE_DAYS"));
        assertEquals(Integer.valueOf(10), FedExConnector.parseFedExTransitTime("TEN_DAYS"));
        assertEquals(Integer.valueOf(20), FedExConnector.parseFedExTransitTime("TWENTY_DAYS"));
    }

    @Test
    void parseFedExTransitTimeReturnsNullForUnknownOrBlank() {
        assertNull(FedExConnector.parseFedExTransitTime(null));
        assertNull(FedExConnector.parseFedExTransitTime(""));
        assertNull(FedExConnector.parseFedExTransitTime("SOME_DAY"));
    }

    @Test
    void parseFedExTransitTimeIsCaseInsensitive() {
        assertEquals(Integer.valueOf(2), FedExConnector.parseFedExTransitTime("two_days"));
    }

    /* -------------------------- Default interface behaviour -------------------------- */

    @Test
    void rateOptionRecordHasExpectedShape() {
        // Sanity check that RateOption's fields wire up cleanly — the tuple
        // is what every future connector will emit.
        CarrierConnector.RateOption opt = new CarrierConnector.RateOption(
                "FEDEX", "FEDEX_GROUND", "FedEx Ground",
                new BigDecimal("12.50"), "USD", null, 2);
        assertEquals("FEDEX", opt.carrierCode());
        assertEquals("FEDEX_GROUND", opt.serviceCode());
        assertEquals(Integer.valueOf(2), opt.transitDays());
    }
}
