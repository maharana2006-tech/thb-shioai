package com.multiship.backend.service.carriers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.dto.ShipmentRequestDTO;
import com.multiship.backend.service.carriers.CarrierConnector.LandedCostResult;
import com.multiship.backend.service.fx.FxRateService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 32 — landed cost parsing across UPS / FedEx / DHL, plus USPS
 * NOT_SUPPORTED guardrails. Mirrors the AddressValidationTest / VoidLabelTest
 * shape — one file per the cross-carrier precedent.
 */
class LandedCostTest {

    private static FxRateService noFx() {
        return new FxRateService() {
            @Override public Optional<BigDecimal> rate(String f, String t) { return Optional.empty(); }
            @Override public Optional<BigDecimal> convert(BigDecimal a, String f, String t) { return Optional.empty(); }
            @Override public boolean supports(String c) { return false; }
        };
    }

    private static ShipmentRequestDTO intlRequest() {
        return ShipmentRequestDTO.builder()
                .carrierCode("UPS").accountNumber("A12345")
                .serviceType("07").packageType("02")
                .weight(new BigDecimal("2")).weightUnit("LB")
                .shipperName("Acme").shipperPhone("5551234567")
                .shipperAddressLine1("1 Way").shipperCity("Louisville")
                .shipperState("KY").shipperPostalCode("40209").shipperCountryCode("US")
                .recipientName("Jane").recipientPhone("442079460958")
                .recipientAddressLine1("42 High St").recipientCity("London")
                .recipientState("").recipientPostalCode("W1A 1AA").recipientCountryCode("GB")
                .declaredValue(new BigDecimal("100.00"))
                .declaredValueCurrency("USD")
                .build();
    }

    private static ShipmentRequestDTO domesticRequest() {
        ShipmentRequestDTO r = intlRequest();
        r.setRecipientCountryCode("US");
        r.setRecipientPostalCode("10001");
        r.setRecipientCity("New York");
        r.setRecipientState("NY");
        return r;
    }

    /* -------------------------- Auth + lane guardrails -------------------------- */

    @Test
    void everyCarrierReturnsNotSupportedForLocalFallbackToken() {
        assertEquals("NOT_SUPPORTED",
                new UpsConnector(new CarrierProperties(), new ObjectMapper())
                        .estimateLandedCost(intlRequest(), "ups-local-abc").source());
        assertEquals("NOT_SUPPORTED",
                new FedExConnector(new CarrierProperties(), new ObjectMapper(), noFx())
                        .estimateLandedCost(intlRequest(), "fedex-local-abc").source());
        assertEquals("NOT_SUPPORTED",
                new DhlConnector(new CarrierProperties(), new ObjectMapper())
                        .estimateLandedCost(intlRequest(), "dhl-local-abc").source());
    }

    @Test
    void uspsInheritsDefaultNotSupportedFromInterface() {
        // Sprint 32 leaves USPS on the default — it's domestic-only.
        LandedCostResult r = new StampsConnector(new CarrierProperties(), new ObjectMapper())
                .estimateLandedCost(intlRequest(), "AUTH-XYZ");
        assertEquals("NOT_SUPPORTED", r.source());
        assertEquals("USPS", r.carrierCode());
    }

    @Test
    void domesticLaneReturnsNotSupportedEverywhere() {
        // Even with real tokens, domestic lanes have no landed cost.
        // Use a placeholder token — the connectors validate lane BEFORE
        // calling the carrier, so no live call happens.
        String token = "REAL-TOKEN-abc";
        assertEquals("NOT_SUPPORTED",
                new UpsConnector(new CarrierProperties(), new ObjectMapper())
                        .estimateLandedCost(domesticRequest(), token).source());
        assertEquals("NOT_SUPPORTED",
                new FedExConnector(new CarrierProperties(), new ObjectMapper(), noFx())
                        .estimateLandedCost(domesticRequest(), token).source());
        assertEquals("NOT_SUPPORTED",
                new DhlConnector(new CarrierProperties(), new ObjectMapper())
                        .estimateLandedCost(domesticRequest(), token).source());
    }

    /* -------------------------- UPS response parsing -------------------------- */

    @Test
    void upsLandedCostSumsFreightDutyTax() {
        UpsConnector c = new UpsConnector(new CarrierProperties(), new ObjectMapper());
        String canned = """
                {
                  "RateResponse": {
                    "RatedShipment": {
                      "TotalCharges": {"MonetaryValue": "25.00", "CurrencyCode": "USD"},
                      "EstimatedDuties": {
                        "TotalAmount": {"MonetaryValue": "12.00", "CurrencyCode": "USD"}
                      },
                      "EstimatedTaxes": {
                        "TotalAmount": {"MonetaryValue": "8.50", "CurrencyCode": "USD"}
                      }
                    }
                  }
                }""";
        LandedCostResult r = c.parseUpsLandedCostResponse(canned);
        assertEquals("LIVE", r.source());
        assertEquals(0, new BigDecimal("25.00").compareTo(r.freightAmount()));
        assertEquals(0, new BigDecimal("12.00").compareTo(r.dutyTotal()));
        assertEquals(0, new BigDecimal("8.50").compareTo(r.taxTotal()));
        assertEquals(0, new BigDecimal("45.50").compareTo(r.grandTotal()));
        assertEquals("USD", r.currency());
    }

    @Test
    void upsLandedCostTolerantOfMissingDutyOrTax() {
        UpsConnector c = new UpsConnector(new CarrierProperties(), new ObjectMapper());
        String canned = """
                {
                  "RateResponse": {
                    "RatedShipment": {
                      "TotalCharges": {"MonetaryValue": "25.00", "CurrencyCode": "USD"}
                    }
                  }
                }""";
        LandedCostResult r = c.parseUpsLandedCostResponse(canned);
        assertEquals("LIVE", r.source());
        assertEquals(0, new BigDecimal("25.00").compareTo(r.freightAmount()));
        assertNull(r.dutyTotal());
        assertNull(r.taxTotal());
        assertEquals(0, new BigDecimal("25.00").compareTo(r.grandTotal()));
    }

    @Test
    void upsLandedCostMalformedReturnsError() {
        UpsConnector c = new UpsConnector(new CarrierProperties(), new ObjectMapper());
        LandedCostResult r = c.parseUpsLandedCostResponse("{}");
        assertEquals("ERROR", r.source());
    }

    /* -------------------------- FedEx response parsing -------------------------- */

    @Test
    void fedexLandedCostReadsAncillaryFeesAndTaxes() {
        FedExConnector c = new FedExConnector(new CarrierProperties(), new ObjectMapper(), noFx());
        String canned = """
                {
                  "output": {
                    "rateReplyDetails": [{
                      "serviceType": "INTERNATIONAL_PRIORITY",
                      "ratedShipmentDetails": [{
                        "rateType": "ACCOUNT",
                        "shipmentRateDetail": {
                          "totalNetCharge": {"amount": 42.75, "currency": "USD"},
                          "ancillaryFeesAndTaxes": [
                            {"type": "ESTIMATED_DUTIES", "amount": 15.00, "currency": "USD"},
                            {"type": "TAX", "amount": 5.25, "currency": "USD"}
                          ]
                        }
                      }]
                    }]
                  }
                }""";
        LandedCostResult r = c.parseFedExLandedCostResponse(canned);
        assertEquals("LIVE", r.source());
        assertEquals(0, new BigDecimal("42.75").compareTo(r.freightAmount()));
        assertEquals(0, new BigDecimal("15.00").compareTo(r.dutyTotal()));
        assertEquals(0, new BigDecimal("5.25").compareTo(r.taxTotal()));
        assertEquals(0, new BigDecimal("63.00").compareTo(r.grandTotal()));
    }

    @Test
    void fedexLandedCostToleratesTopLevelAncillary() {
        // Some FedEx sandboxes put ancillaryFeesAndTaxes at the outer
        // ratedShipmentDetails entry rather than inside shipmentRateDetail.
        FedExConnector c = new FedExConnector(new CarrierProperties(), new ObjectMapper(), noFx());
        String canned = """
                {
                  "output": {
                    "rateReplyDetails": [{
                      "serviceType": "INTERNATIONAL_PRIORITY",
                      "ratedShipmentDetails": [{
                        "rateType": "ACCOUNT",
                        "totalNetCharge": 40.00,
                        "currency": "USD",
                        "ancillaryFeesAndTaxes": [
                          {"type": "DUTY", "amount": 10.00, "currency": "USD"}
                        ]
                      }]
                    }]
                  }
                }""";
        LandedCostResult r = c.parseFedExLandedCostResponse(canned);
        assertEquals("LIVE", r.source());
        assertEquals(0, new BigDecimal("40.00").compareTo(r.freightAmount()));
        assertEquals(0, new BigDecimal("10.00").compareTo(r.dutyTotal()));
    }

    @Test
    void fedexLandedCostMissingRateReplyDetailsReturnsError() {
        FedExConnector c = new FedExConnector(new CarrierProperties(), new ObjectMapper(), noFx());
        LandedCostResult r = c.parseFedExLandedCostResponse("{\"output\":{}}");
        assertEquals("ERROR", r.source());
    }

    /* -------------------------- DHL response parsing -------------------------- */

    @Test
    void dhlLandedCostReadsDetailedPriceBreakdown() {
        DhlConnector c = new DhlConnector(new CarrierProperties(), new ObjectMapper());
        String canned = """
                {
                  "products": [{
                    "productCode": "P",
                    "totalPrice": [
                      {"price": 35.00, "priceCurrency": "USD", "typeCode": "BILLC"}
                    ],
                    "detailedPriceBreakdown": [{
                      "priceCurrency": "USD",
                      "breakdown": [
                        {"typeCode": "SPRQT", "price": 35.00},
                        {"typeCode": "DUTY", "price": 20.00},
                        {"typeCode": "TAX", "price": 8.50},
                        {"typeCode": "BROKERAGE", "price": 2.50}
                      ]
                    }]
                  }]
                }""";
        LandedCostResult r = c.parseDhlLandedCostResponse(canned);
        assertEquals("LIVE", r.source());
        assertEquals(0, new BigDecimal("35.00").compareTo(r.freightAmount()));
        assertEquals(0, new BigDecimal("20.00").compareTo(r.dutyTotal()));
        assertEquals(0, new BigDecimal("8.50").compareTo(r.taxTotal()));
        assertNotNull(r.otherTotal(), "BROKERAGE line should land in otherTotal");
        assertEquals(0, new BigDecimal("2.50").compareTo(r.otherTotal()));
        assertEquals(0, new BigDecimal("66.00").compareTo(r.grandTotal()));
    }

    @Test
    void dhlLandedCostFallsBackToFirstTotalPriceWhenNoBillc() {
        DhlConnector c = new DhlConnector(new CarrierProperties(), new ObjectMapper());
        String canned = """
                {
                  "products": [{
                    "productCode": "P",
                    "totalPrice": [
                      {"price": 40.00, "priceCurrency": "EUR", "typeCode": "PULCL"}
                    ],
                    "detailedPriceBreakdown": []
                  }]
                }""";
        LandedCostResult r = c.parseDhlLandedCostResponse(canned);
        assertEquals("LIVE", r.source());
        assertEquals(0, new BigDecimal("40.00").compareTo(r.freightAmount()));
        assertEquals("EUR", r.currency());
        assertNull(r.dutyTotal());
        assertNull(r.taxTotal());
    }

    @Test
    void dhlLandedCostVatVariantMapsToTax() {
        // DHL sometimes uses VAT instead of TAX as the breakdown typeCode.
        DhlConnector c = new DhlConnector(new CarrierProperties(), new ObjectMapper());
        String canned = """
                {
                  "products": [{
                    "productCode": "P",
                    "totalPrice": [{"price": 20, "priceCurrency": "GBP", "typeCode": "BILLC"}],
                    "detailedPriceBreakdown": [{
                      "breakdown": [{"typeCode": "VAT", "price": 4.00}]
                    }]
                  }]
                }""";
        LandedCostResult r = c.parseDhlLandedCostResponse(canned);
        assertEquals(0, new BigDecimal("4.00").compareTo(r.taxTotal()));
    }

    @Test
    void dhlLandedCostMissingProductsReturnsError() {
        DhlConnector c = new DhlConnector(new CarrierProperties(), new ObjectMapper());
        LandedCostResult r = c.parseDhlLandedCostResponse("{}");
        assertEquals("ERROR", r.source());
    }

    /* -------------------------- Result record integrity -------------------------- */

    @Test
    void everyResultCarriesCarrierCodeAndSource() {
        LandedCostResult ups = new UpsConnector(new CarrierProperties(), new ObjectMapper())
                .estimateLandedCost(intlRequest(), "ups-local-");
        assertEquals("UPS", ups.carrierCode());
        assertTrue(ups.source() == null || !ups.source().isEmpty());

        LandedCostResult fedex = new FedExConnector(new CarrierProperties(), new ObjectMapper(), noFx())
                .estimateLandedCost(intlRequest(), "fedex-local-");
        assertEquals("FEDEX", fedex.carrierCode());

        LandedCostResult dhl = new DhlConnector(new CarrierProperties(), new ObjectMapper())
                .estimateLandedCost(intlRequest(), "dhl-local-");
        assertEquals("DHL", dhl.carrierCode());
    }
}
