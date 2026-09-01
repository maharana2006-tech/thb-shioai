package com.multiship.backend.service.carriers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.dto.CustomsCommodityDTO;
import com.multiship.backend.dto.IntlShipmentBlockDTO;
import com.multiship.backend.dto.ShipmentRequestDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden-value tests for {@link StampsConnector} SWSIM CreateIndicium
 * envelope building. Assertions are string-contains on the SOAP XML — SWSIM
 * is picky about element casing and ordering so a regression in either
 * would show up as either a rejected envelope from Stamps.com or a
 * missing customs form on the printed label.
 */
class StampsConnectorPayloadTest {

    private StampsConnector connector;
    private Method buildEnvelope;

    @BeforeEach
    void setUp() throws Exception {
        connector = new StampsConnector(new CarrierProperties(), new ObjectMapper());
        buildEnvelope = StampsConnector.class.getDeclaredMethod(
                "buildCreateIndiciumEnvelope", ShipmentRequestDTO.class, String.class);
        buildEnvelope.setAccessible(true);
    }

    private String build(ShipmentRequestDTO request, String authenticator) throws Exception {
        return (String) buildEnvelope.invoke(connector, request, authenticator);
    }

    private ShipmentRequestDTO baseRequest() {
        return ShipmentRequestDTO.builder()
                .carrierCode("USPS")
                .accountNumber("USPS-ACCT")
                .serviceType("USPS GA")
                .packageType("Package")
                .weight(new BigDecimal("1.5"))
                .weightUnit("KG")
                .shipperName("Acme Warehouse")
                .shipperPhone("5551234567")
                .shipperAddressLine1("1 Warehouse Way")
                .shipperCity("Louisville")
                .shipperState("KY")
                .shipperPostalCode("40209")
                .shipperCountryCode("US")
                .recipientName("Jane Doe")
                .recipientPhone("5559876543")
                .recipientAddressLine1("42 Broadway")
                .recipientCity("New York")
                .recipientState("NY")
                .recipientPostalCode("10001")
                .recipientCountryCode("US")
                .referenceNumber("PO-1001")
                .declaredValue(new BigDecimal("500.00"))
                .declaredValueCurrency("EUR")
                .build();
    }

    private IntlShipmentBlockDTO baseIntl() {
        return IntlShipmentBlockDTO.builder()
                .international(true)
                .incoterms("DDP")
                .customsCurrency("EUR")
                .customsTotalValue(new BigDecimal("500.00"))
                .reasonForExport("SALE")
                .weightUnit("KG")
                .commodities(List.of(CustomsCommodityDTO.builder()
                        .description("Widget")
                        .hsCode("6104.62.20")
                        .countryOfOrigin("US")
                        .quantity(10)
                        .unitValue(new BigDecimal("50.00"))
                        .unitWeight(new BigDecimal("0.15"))
                        .sku("SKU-1")
                        .build()))
                .build();
    }

    @Test
    void envelopeIsSoapWithSwsimV135Namespace() throws Exception {
        String soap = build(baseRequest(), "AUTH-TOKEN-1");
        assertTrue(soap.contains("<soap:Envelope"));
        assertTrue(soap.contains("<CreateIndicium xmlns=\"http://stamps.com/xml/namespace/2023/07/swsim/SwsimV135\">"));
        assertTrue(soap.contains("<Authenticator>AUTH-TOKEN-1</Authenticator>"));
    }

    @Test
    void weightGoesOutInOunces() throws Exception {
        // 1.5 kg = 52.91 oz per UnitConverter's HALF_UP rounding
        String soap = build(baseRequest(), "T");
        assertTrue(soap.contains("<WeightOz>52.91</WeightOz>"),
                "1.5 KG on the DTO should serialize as 52.91 oz on SWSIM");
    }

    @Test
    void domesticEnvelopeOmitsCustomsInfo() throws Exception {
        // baseRequest() has recipientCountry=US and no intl block
        String soap = build(baseRequest(), "T");
        assertFalse(soap.contains("<CustomsInfo>"),
                "Domestic shipment shouldn't include a CustomsInfo block");
    }

    @Test
    void internationalEnvelopeEmitsCustomsInfo() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setRecipientCountryCode("GB");
        r.setIntl(baseIntl());
        String soap = build(r, "T");
        assertTrue(soap.contains("<CustomsInfo>"));
        assertTrue(soap.contains("<ContentType>Merchandise</ContentType>"));
        assertTrue(soap.contains("<CustomsLines>"));
        assertTrue(soap.contains("<Description>Widget</Description>"));
        assertTrue(soap.contains("<Quantity>10</Quantity>"));
        assertTrue(soap.contains("<Value>500.00</Value>"),
                "Line value = quantity × unitValue, not unitValue alone");
        assertTrue(soap.contains("<HSTariffNumber>6104.62.20</HSTariffNumber>"));
        assertTrue(soap.contains("<CountryOfOrigin>US</CountryOfOrigin>"));
    }

    @Test
    void internationalEnvelopeIncludesToCountry() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setRecipientCountryCode("GB");
        r.setIntl(baseIntl());
        String soap = build(r, "T");
        assertTrue(soap.contains("<Country>GB</Country>"),
                "Non-US destinations should serialize a <Country> element");
    }

    @Test
    void domesticEnvelopeOmitsCountry() throws Exception {
        String soap = build(baseRequest(), "T");
        assertFalse(soap.contains("<Country>US</Country>"),
                "US destinations should omit <Country> (SWSIM defaults)");
    }

    @Test
    void reasonForExportMapsToSwsimContentType() throws Exception {
        record TC(String reason, String content) {}
        for (TC tc : List.of(
                new TC("SALE", "Merchandise"),
                new TC("GIFT", "Gift"),
                new TC("SAMPLE", "Sample"),
                new TC("RETURN", "ReturnedGoods"),
                new TC("REPAIR", "Other"),
                new TC("DOCUMENTS", "Documents"))) {
            ShipmentRequestDTO r = baseRequest();
            r.setRecipientCountryCode("GB");
            IntlShipmentBlockDTO intl = baseIntl();
            intl.setReasonForExport(tc.reason());
            r.setIntl(intl);
            String soap = build(r, "T");
            assertTrue(soap.contains("<ContentType>" + tc.content() + "</ContentType>"),
                    "Reason " + tc.reason() + " should map to " + tc.content());
        }
    }

    @Test
    void perLineWeightOzConvertedFromCommodityUnit() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setRecipientCountryCode("GB");
        r.setIntl(baseIntl()); // unitWeight 0.15 KG → 5.29 oz
        String soap = build(r, "T");
        assertTrue(soap.contains("<WeightOz>5.29</WeightOz>"),
                "0.15 KG unit weight should convert to 5.29 oz");
    }

    @Test
    void unitPreservationForOzInput() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setWeightUnit("OZ");
        r.setWeight(new BigDecimal("20"));
        String soap = build(r, "T");
        assertTrue(soap.contains("<WeightOz>20.00</WeightOz>"),
                "OZ already — no conversion, but 2dp scaling applied");
    }

    @Test
    void hsCodeOmittedWhenBlank() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setRecipientCountryCode("GB");
        IntlShipmentBlockDTO intl = baseIntl();
        intl.getCommodities().get(0).setHsCode(null);
        r.setIntl(intl);
        String soap = build(r, "T");
        assertFalse(soap.contains("<HSTariffNumber>"),
                "Missing HS code shouldn't emit an empty element");
    }

    @Test
    void skuGoesIntoPascalCaseSkuElement() throws Exception {
        // Bug 7 fix: SWSIM XML is case-sensitive and every sibling element
        // in CustomsLine is PascalCase (Description, Quantity, Value,
        // WeightOz, HSTariffNumber, CountryOfOrigin). Pre-fix this
        // emitted `<sku>` which SWSIM's parser silently dropped — the SKU
        // vanished from the printed customs form. Now emitting `<SKU>`.
        ShipmentRequestDTO r = baseRequest();
        r.setRecipientCountryCode("GB");
        r.setIntl(baseIntl());
        String soap = build(r, "T");
        assertTrue(soap.contains("<SKU>SKU-1</SKU>"),
                "SKU element must be PascalCase to match SWSIM's schema");
        assertFalse(soap.contains("<sku>"),
                "the pre-fix lowercase form must NOT appear — SWSIM drops it silently");
    }

    @Test
    void xmlEscapesAmpersandInDescription() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setRecipientCountryCode("GB");
        IntlShipmentBlockDTO intl = baseIntl();
        intl.getCommodities().get(0).setDescription("Widget A & B");
        r.setIntl(intl);
        String soap = build(r, "T");
        assertTrue(soap.contains("<Description>Widget A &amp; B</Description>"),
                "Ampersand in descriptions must be XML-escaped");
    }

    @Test
    void multipleCommoditiesEachGetLineBlock() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setRecipientCountryCode("GB");
        IntlShipmentBlockDTO intl = baseIntl();
        intl.setCommodities(List.of(
                CustomsCommodityDTO.builder()
                        .description("Widget A").quantity(5).unitValue(new BigDecimal("10.00")).build(),
                CustomsCommodityDTO.builder()
                        .description("Widget B").quantity(3).unitValue(new BigDecimal("20.00")).build()));
        r.setIntl(intl);
        String soap = build(r, "T");
        assertEquals(2, countMatches(soap, "<CustomsLine>"));
        assertTrue(soap.contains("<Description>Widget A</Description>"));
        assertTrue(soap.contains("<Description>Widget B</Description>"));
    }

    private static int countMatches(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    // ===================================================================
    // Sprint 51 — email + company on <From> / <To> address blocks
    // ===================================================================

    @Test
    void fromAddress_carriesCompanyAndEmail_whenSet() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setShipperCompany("Acme Fulfillment");
        r.setShipperEmail("ops@acme.example");
        String soap = build(r, "T");
        assertTrue(soap.contains("<Company>Acme Fulfillment</Company>"),
                "SWSIM <Company> must appear when shipperCompany is set");
        assertTrue(soap.contains("<EmailAddress>ops@acme.example</EmailAddress>"),
                "SWSIM <EmailAddress> must appear when shipperEmail is set");
    }

    @Test
    void toAddress_carriesCompanyAndEmail_whenSet() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setRecipientCompany("Zymeworks");
        r.setRecipientEmail("jane@acme.example");
        String soap = build(r, "T");
        assertTrue(soap.contains("<Company>Zymeworks</Company>"),
                "SWSIM <Company> must appear when recipientCompany is set");
        assertTrue(soap.contains("<EmailAddress>jane@acme.example</EmailAddress>"),
                "SWSIM <EmailAddress> must appear when recipientEmail is set");
    }

    @Test
    void companyAndEmail_omittedWhenBlank_backwardsCompatWireShape() throws Exception {
        // Pre-Sprint-51 there were no <Company> or <EmailAddress> elements
        // in the SWSIM envelope. Unchanged callers must NOT gain empty
        // elements — that would be a wire regression.
        String soap = build(baseRequest(), "T");
        assertFalse(soap.contains("<Company>"),
                "no shipperCompany / recipientCompany → no <Company> element");
        assertFalse(soap.contains("<EmailAddress>"),
                "no shipperEmail / recipientEmail → no <EmailAddress> element");
    }
}
