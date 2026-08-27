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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden-value tests for {@link DhlConnector#buildShipmentPayload}. Mirrors
 * the UPS / FedEx / USPS suites — reflection into the private builder + JSON
 * shape assertions on what DHL will validate against.
 */
class DhlConnectorPayloadTest {

    private DhlConnector connector;
    private Method buildShipmentPayload;

    @BeforeEach
    void setUp() throws Exception {
        connector = new DhlConnector(new CarrierProperties(), new ObjectMapper());
        buildShipmentPayload = DhlConnector.class.getDeclaredMethod("buildShipmentPayload", ShipmentRequestDTO.class);
        buildShipmentPayload.setAccessible(true);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> build(ShipmentRequestDTO request) throws Exception {
        return (Map<String, Object>) buildShipmentPayload.invoke(connector, request);
    }

    private ShipmentRequestDTO baseRequest() {
        return ShipmentRequestDTO.builder()
                .carrierCode("DHL")
                .accountNumber("A123456789")
                .serviceType("P")
                .packageType("3BX")
                .weight(new BigDecimal("2.5"))
                .weightUnit("KG")
                .length(new BigDecimal("30"))
                .width(new BigDecimal("20"))
                .height(new BigDecimal("15"))
                .dimUnit("CM")
                .shipperName("Acme Warehouse")
                .shipperPhone("5551234567")
                .shipperAddressLine1("1 Warehouse Way")
                .shipperCity("Louisville")
                .shipperState("KY")
                .shipperPostalCode("40209")
                .shipperCountryCode("US")
                .recipientName("Jane Doe")
                .recipientPhone("20 7946 0958")
                .recipientAddressLine1("42 High Street")
                .recipientCity("London")
                .recipientPostalCode("W1A 1AA")
                .recipientCountryCode("GB")
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
                        .unitWeight(new BigDecimal("0.25"))
                        .sku("SKU-1")
                        .build()))
                .build();
    }

    @Test
    void productCodeAndPickupAlwaysPresent() throws Exception {
        Map<String, Object> payload = build(baseRequest());
        assertEquals("P", payload.get("productCode"));
        @SuppressWarnings("unchecked")
        Map<String, Object> pickup = (Map<String, Object>) payload.get("pickup");
        assertEquals(false, pickup.get("isRequested"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void shipperAndReceiverAddressBlocksBuilt() throws Exception {
        Map<String, Object> details = (Map<String, Object>) build(baseRequest()).get("customerDetails");
        Map<String, Object> shipper = (Map<String, Object>) details.get("shipperDetails");
        Map<String, Object> shipperAddr = (Map<String, Object>) shipper.get("postalAddress");
        assertEquals("Louisville", shipperAddr.get("cityName"));
        assertEquals("40209", shipperAddr.get("postalCode"));
        assertEquals("US", shipperAddr.get("countryCode"));

        Map<String, Object> receiver = (Map<String, Object>) details.get("receiverDetails");
        Map<String, Object> receiverAddr = (Map<String, Object>) receiver.get("postalAddress");
        assertEquals("London", receiverAddr.get("cityName"));
        assertEquals("GB", receiverAddr.get("countryCode"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void residentialAddressTypeEmittedWhenTrue() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setRecipientResidential(true);
        Map<String, Object> details = (Map<String, Object>) build(r).get("customerDetails");
        Map<String, Object> addr = (Map<String, Object>) ((Map<String, Object>)
                details.get("receiverDetails")).get("postalAddress");
        assertEquals("residential", addr.get("addressType"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void residentialAbsentByDefault() throws Exception {
        Map<String, Object> details = (Map<String, Object>) build(baseRequest()).get("customerDetails");
        Map<String, Object> addr = (Map<String, Object>) ((Map<String, Object>)
                details.get("receiverDetails")).get("postalAddress");
        assertNull(addr.get("addressType"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void recipientPhonePrependsCountryCode() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setRecipientPhoneCountryCode("44");
        Map<String, Object> details = (Map<String, Object>) build(r).get("customerDetails");
        Map<String, Object> contact = (Map<String, Object>) ((Map<String, Object>)
                details.get("receiverDetails")).get("contactInformation");
        assertEquals("+44 20 7946 0958", contact.get("phone"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void domesticContentSkipsCustomsBlock() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setRecipientCountryCode("US");
        Map<String, Object> content = (Map<String, Object>) build(r).get("content");
        assertEquals(false, content.get("isCustomsDeclarable"));
        assertNull(content.get("exportDeclaration"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void internationalContentEmitsExportDeclaration() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setIntl(baseIntl());
        Map<String, Object> content = (Map<String, Object>) build(r).get("content");
        assertEquals(true, content.get("isCustomsDeclarable"));
        assertEquals("EUR", content.get("declaredValueCurrency"));
        assertEquals("DDP", content.get("incoterm"));

        Map<String, Object> ed = (Map<String, Object>) content.get("exportDeclaration");
        List<Map<String, Object>> lines = (List<Map<String, Object>>) ed.get("lineItems");
        assertEquals(1, lines.size());
        Map<String, Object> line = lines.get(0);
        assertEquals(1, line.get("number"), "Line numbers must be 1-based per DHL spec");
        assertEquals("Widget", line.get("description"));
        assertEquals(new BigDecimal("50.00"), line.get("price"));

        Map<String, Object> quantity = (Map<String, Object>) line.get("quantity");
        assertEquals(10, quantity.get("value"));
        assertEquals("PCS", quantity.get("unitOfMeasurement"));

        List<Map<String, Object>> commodityCodes = (List<Map<String, Object>>) line.get("commodityCodes");
        assertNotNull(commodityCodes);
        assertEquals("outbound", commodityCodes.get(0).get("typeCode"));
        assertEquals("6104.62.20", commodityCodes.get(0).get("value"));
        assertEquals("US", line.get("manufacturerCountry"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void weightUnitMapsToMetricForKg() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        // KG on the DTO → DHL metric
        Map<String, Object> content = (Map<String, Object>) build(r).get("content");
        assertEquals("metric", content.get("unitOfMeasurement"));

        r.setWeightUnit("LB");
        content = (Map<String, Object>) build(r).get("content");
        assertEquals("imperial", content.get("unitOfMeasurement"));
    }

    @Test
    void exportReasonEnumMapping() throws Exception {
        record TC(String our, String dhl) {}
        for (TC tc : List.of(
                new TC("SALE", "commercial_purpose_or_sale"),
                new TC("GIFT", "gift"),
                new TC("SAMPLE", "sample"),
                new TC("RETURN", "return"),
                new TC("REPAIR", "repair_or_processing"),
                new TC("DOCUMENTS", "personal_effects"))) {
            ShipmentRequestDTO r = baseRequest();
            IntlShipmentBlockDTO intl = baseIntl();
            intl.setReasonForExport(tc.our());
            r.setIntl(intl);
            @SuppressWarnings("unchecked")
            Map<String, Object> ed = (Map<String, Object>) ((Map<String, Object>)
                    build(r).get("content")).get("exportDeclaration");
            assertEquals(tc.dhl(), ed.get("exportReason"),
                    "Our " + tc.our() + " → DHL " + tc.dhl());
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void ddpEmitsInvoiceCustomsDocument() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setIntl(baseIntl()); // DDP
        Map<String, Object> ed = (Map<String, Object>) ((Map<String, Object>)
                build(r).get("content")).get("exportDeclaration");
        List<Map<String, Object>> docs = (List<Map<String, Object>>) ed.get("customsDocuments");
        assertNotNull(docs);
        assertEquals("INV", docs.get(0).get("typeCode"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void packageBlockIncludesTypeCodeWeightAndDimensions() throws Exception {
        Map<String, Object> content = (Map<String, Object>) build(baseRequest()).get("content");
        List<Map<String, Object>> pkgs = (List<Map<String, Object>>) content.get("packages");
        assertEquals(1, pkgs.size());
        Map<String, Object> pkg = pkgs.get(0);
        assertEquals("3BX", pkg.get("typeCode"));
        assertEquals(new BigDecimal("2.5"), pkg.get("weight"));

        Map<String, Object> dims = (Map<String, Object>) pkg.get("dimensions");
        assertEquals(new BigDecimal("30"), dims.get("length"));
        assertEquals(new BigDecimal("20"), dims.get("width"));
        assertEquals(new BigDecimal("15"), dims.get("height"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void accountsSectionCarriesShipperNumber() throws Exception {
        List<Map<String, Object>> accounts = (List<Map<String, Object>>) build(baseRequest()).get("accounts");
        assertEquals(1, accounts.size());
        assertEquals("shipper", accounts.get(0).get("typeCode"));
        assertEquals("A123456789", accounts.get(0).get("number"));
    }

    @Test
    void outputImageFormatIsPdf() throws Exception {
        Map<String, Object> payload = build(baseRequest());
        @SuppressWarnings("unchecked")
        Map<String, Object> imgProps = (Map<String, Object>) payload.get("outputImageProperties");
        assertEquals("pdf", imgProps.get("encodingFormat"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void incompleteIntlBlockKeepsCustomsOff() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        // international=true but no commodities → not ready for carrier
        r.setIntl(IntlShipmentBlockDTO.builder().international(true).build());
        Map<String, Object> content = (Map<String, Object>) build(r).get("content");
        assertEquals(false, content.get("isCustomsDeclarable"),
                "Incomplete intl block should keep isCustomsDeclarable=false");
        assertNull(content.get("exportDeclaration"));
    }

    @Test
    void parseDhlShipmentEtaReturnsNullWhenAbsent() throws Exception {
        // DHL-9 — the pre-fix parseShipmentResult fabricated `now + 2 days`
        // for every DHL label result, misleading downstream code into
        // treating a made-up date as a real DHL SLA commitment. Post-fix
        // we read DHL's real estimatedDeliveryDate when present and
        // surface null otherwise so the FE / tracking widgets see
        // "no ETA yet".
        var mapper = new ObjectMapper();
        assertNull(connector.parseDhlShipmentEta(mapper.readTree("{}")),
                "response without ETA fields must surface null, not a fabricated date");
    }

    @Test
    void parseDhlShipmentEtaReadsEstimatedDeliveryDate() throws Exception {
        // DHL-9 — real DHL responses populate estimatedDeliveryDate on
        // accounts with EDD enabled. Read the carrier's own value.
        var mapper = new ObjectMapper();
        var eta = connector.parseDhlShipmentEta(mapper.readTree(
                "{\"estimatedDeliveryDate\":\"2026-08-05\"}"));
        assertNotNull(eta);
        assertEquals(2026, eta.getYear());
        assertEquals(8, eta.getMonthValue());
        assertEquals(5, eta.getDayOfMonth());
    }

    @Test
    void parseDhlShipmentEtaReadsEstimatedDeliveryDateAndTime() throws Exception {
        // DHL-9 — some accounts get the full datetime variant.
        var mapper = new ObjectMapper();
        var eta = connector.parseDhlShipmentEta(mapper.readTree(
                "{\"estimatedDeliveryDateAndTime\":\"2026-08-05T13:30:00 GMT+00:00\"}"));
        assertNotNull(eta);
        assertEquals(2026, eta.getYear());
        assertEquals(13, eta.getHour());
        assertEquals(30, eta.getMinute());
    }

    @Test
    void carrierMetadataIsSensible() {
        assertEquals("DHL", connector.getCarrierCode());
        assertEquals("DHL Express", connector.getCarrierName());
    }

    @Test
    void validateCredentialsRejectsBlank() {
        try {
            connector.validateCredentials("", "secret");
            assertFalse(true, "Blank client id should have thrown");
        } catch (Exception ex) {
            assertTrue(ex.getMessage().toLowerCase().contains("api key"));
        }
        try {
            connector.validateCredentials("key", "");
            assertFalse(true, "Blank client secret should have thrown");
        } catch (Exception ex) {
            assertTrue(ex.getMessage().toLowerCase().contains("api secret") || ex.getMessage().toLowerCase().contains("required"));
        }
    }
}
