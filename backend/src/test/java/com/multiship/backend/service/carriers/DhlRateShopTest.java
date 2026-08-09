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
 * Golden-value tests for DHL rate response parsing. Uses canned JSON so no
 * live DHL sandbox is required.
 */
class DhlRateShopTest {

    private DhlConnector connector;

    @BeforeEach
    void setUp() {
        connector = new DhlConnector(new CarrierProperties(), new ObjectMapper());
    }

    private ShipmentRequestDTO minimalRequest() {
        return ShipmentRequestDTO.builder()
                .carrierCode("DHL").accountNumber("A99999")
                .serviceType("P").packageType("3BX")
                .weight(new BigDecimal("2.5")).weightUnit("KG")
                .length(new BigDecimal("30")).width(new BigDecimal("20")).height(new BigDecimal("10"))
                .dimUnit("CM")
                .shipperName("Acme").shipperPhone("5551234567")
                .shipperAddressLine1("1 Way").shipperCity("Bonn")
                .shipperPostalCode("53113").shipperCountryCode("DE")
                .recipientName("Jane").recipientPhone("5559876543")
                .recipientAddressLine1("42 Broadway").recipientCity("New York")
                .recipientState("NY").recipientPostalCode("10001").recipientCountryCode("US")
                .build();
    }

    /* -------------------------- Auth guardrails -------------------------- */

    @Test
    void localFallbackTokenReturnsEmptyList() {
        assertTrue(connector.getRates(minimalRequest(), "dhl-local-abc", null).isEmpty());
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
    void parseDhlRateResponseFlattensProductsArray() {
        String canned = """
                {
                  "products": [
                    {
                      "productCode": "P",
                      "productName": "EXPRESS WORLDWIDE",
                      "totalPrice": [
                        {"price": 15.00, "priceCurrency": "USD", "typeCode": "PULCL"},
                        {"price": 12.50, "priceCurrency": "USD", "typeCode": "BILLC"}
                      ],
                      "deliveryCapabilities": {
                        "totalTransitDays": 3,
                        "estimatedDeliveryDateAndTime": "2024-01-18T13:00:00 GMT+00:00"
                      }
                    },
                    {
                      "productCode": "N",
                      "productName": "DOMESTIC EXPRESS",
                      "totalPrice": [
                        {"price": 42.75, "priceCurrency": "USD", "typeCode": "BILLC"}
                      ],
                      "deliveryCapabilities": {"totalTransitDays": 1}
                    }
                  ]
                }""";
        List<CarrierConnector.RateOption> options = connector.parseDhlRateResponse(canned);
        assertEquals(2, options.size());

        CarrierConnector.RateOption express = options.get(0);
        assertEquals("DHL", express.carrierCode());
        assertEquals("P", express.serviceCode());
        assertEquals("EXPRESS WORLDWIDE", express.serviceName());
        assertEquals(0, new BigDecimal("12.50").compareTo(express.totalAmount()),
                "BILLC entry should win over PULCL when both present");
        assertEquals("USD", express.currency());
        assertEquals(Integer.valueOf(3), express.transitDays());
        assertNotNull(express.estimatedDelivery());

        CarrierConnector.RateOption domestic = options.get(1);
        assertEquals("N", domestic.serviceCode());
        assertEquals(0, new BigDecimal("42.75").compareTo(domestic.totalAmount()));
        assertEquals(Integer.valueOf(1), domestic.transitDays());
    }

    @Test
    void parseDhlRateResponseFallsBackToFirstEntryWhenNoBillc() {
        // No BILLC → use the first totalPrice entry as-is.
        String canned = """
                {
                  "products": [
                    {
                      "productCode": "P",
                      "productName": "EXPRESS WORLDWIDE",
                      "totalPrice": [
                        {"price": 20.00, "priceCurrency": "EUR", "typeCode": "PULCL"}
                      ]
                    }
                  ]
                }""";
        List<CarrierConnector.RateOption> options = connector.parseDhlRateResponse(canned);
        assertEquals(1, options.size());
        assertEquals(0, new BigDecimal("20.00").compareTo(options.get(0).totalAmount()));
        assertEquals("EUR", options.get(0).currency());
    }

    @Test
    void parseDhlRateResponseHandlesStringPriceShape() {
        // Some DHL responses emit price as a string; both should work.
        String canned = """
                {
                  "products": [
                    {
                      "productCode": "P",
                      "productName": "EXPRESS WORLDWIDE",
                      "totalPrice": [
                        {"price": "18.75", "priceCurrency": "GBP", "typeCode": "BILLC"}
                      ]
                    }
                  ]
                }""";
        List<CarrierConnector.RateOption> options = connector.parseDhlRateResponse(canned);
        assertEquals(0, new BigDecimal("18.75").compareTo(options.get(0).totalAmount()));
        assertEquals("GBP", options.get(0).currency());
    }

    @Test
    void parseDhlRateResponseHandlesEmptyAndMalformedResponses() {
        assertTrue(connector.parseDhlRateResponse("{}").isEmpty());
        assertTrue(connector.parseDhlRateResponse("").isEmpty());
        assertTrue(connector.parseDhlRateResponse(null).isEmpty());
    }

    @Test
    void parseDhlRateResponseSkipsEntriesWithoutProductCode() {
        String canned = """
                {
                  "products": [
                    {
                      "productName": "Mystery service",
                      "totalPrice": [{"price": 10, "priceCurrency": "USD", "typeCode": "BILLC"}]
                    },
                    {
                      "productCode": "P",
                      "productName": "EXPRESS WORLDWIDE",
                      "totalPrice": [{"price": 12.50, "priceCurrency": "USD", "typeCode": "BILLC"}]
                    }
                  ]
                }""";
        List<CarrierConnector.RateOption> options = connector.parseDhlRateResponse(canned);
        assertEquals(1, options.size(),
                "Entry without productCode should be silently dropped");
        assertEquals("P", options.get(0).serviceCode());
    }

    @Test
    void parseDhlRateResponseSkipsEntriesWithoutPrice() {
        String canned = """
                {
                  "products": [
                    {
                      "productCode": "P",
                      "productName": "EXPRESS WORLDWIDE",
                      "totalPrice": []
                    }
                  ]
                }""";
        assertTrue(connector.parseDhlRateResponse(canned).isEmpty());
    }

    @Test
    void parseDhlRateResponseDefaultsCurrencyToUsd() {
        String canned = """
                {
                  "products": [
                    {
                      "productCode": "P",
                      "totalPrice": [{"price": 12.50, "typeCode": "BILLC"}]
                    }
                  ]
                }""";
        assertEquals("USD", connector.parseDhlRateResponse(canned).get(0).currency());
    }

    @Test
    void parseDhlRateResponseFallsBackToProductCodeWhenNameMissing() {
        String canned = """
                {
                  "products": [
                    {
                      "productCode": "P",
                      "totalPrice": [{"price": 12.50, "priceCurrency": "USD", "typeCode": "BILLC"}]
                    }
                  ]
                }""";
        assertEquals("P", connector.parseDhlRateResponse(canned).get(0).serviceName());
    }

    @Test
    void parseDhlRateResponseParsesEstimatedDeliveryStrippedOfTimezone() {
        String canned = """
                {
                  "products": [
                    {
                      "productCode": "P",
                      "totalPrice": [{"price": 12.50, "priceCurrency": "USD", "typeCode": "BILLC"}],
                      "deliveryCapabilities": {
                        "estimatedDeliveryDateAndTime": "2024-01-18T13:00:00 GMT+00:00"
                      }
                    }
                  ]
                }""";
        assertNotNull(connector.parseDhlRateResponse(canned).get(0).estimatedDelivery(),
                "The trailing ' GMT+00:00' should be stripped so LocalDateTime.parse succeeds");
    }
}
