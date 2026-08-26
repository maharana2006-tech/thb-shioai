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
 * Golden-value tests for {@link UpsConnector#buildShipmentPayload} — the
 * translation from our carrier-neutral DTO into UPS Ship API 2205 JSON is
 * the layer most likely to drift silently, so we assert on the exact JSON
 * shape UPS validates against.
 *
 * <p>Uses reflection to call the private payload builder rather than going
 * through {@code createShipment} — the network call isn't the SUT here.
 */
class UpsConnectorPayloadTest {

    private UpsConnector connector;
    private Method buildShipmentPayload;

    @BeforeEach
    void setUp() throws Exception {
        connector = new UpsConnector(new CarrierProperties(), new ObjectMapper());
        buildShipmentPayload = UpsConnector.class.getDeclaredMethod("buildShipmentPayload", ShipmentRequestDTO.class);
        buildShipmentPayload.setAccessible(true);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> build(ShipmentRequestDTO request) throws Exception {
        return (Map<String, Object>) buildShipmentPayload.invoke(connector, request);
    }

    private ShipmentRequestDTO domesticRequest() {
        return ShipmentRequestDTO.builder()
                .carrierCode("UPS")
                .accountNumber("A12345")
                .serviceType("03")
                .packageType("02")
                .weight(new BigDecimal("2.5"))
                .weightUnit("LB")
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
                .build();
    }

    private IntlShipmentBlockDTO validIntl() {
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
                        .unitWeight(new BigDecimal("0.5"))
                        .build()))
                .build();
    }

    @SuppressWarnings("unchecked")
    @Test
    void domesticPayloadOmitsInternationalForms() throws Exception {
        Map<String, Object> payload = build(domesticRequest());
        Map<String, Object> shipment = (Map<String, Object>) ((Map<String, Object>)
                payload.get("ShipmentRequest")).get("Shipment");
        assertNull(shipment.get("ShipmentServiceOptions"), "Domestic payload should not include InternationalForms");
        assertNull(shipment.get("SoldTo"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void kgWeightPreservedOnTheWire() throws Exception {
        ShipmentRequestDTO r = domesticRequest();
        r.setWeightUnit("KG");
        r.setWeight(new BigDecimal("1.5"));

        Map<String, Object> payload = build(r);
        Map<String, Object> pkg = (Map<String, Object>) ((List<Object>) ((Map<String, Object>)
                ((Map<String, Object>) payload.get("ShipmentRequest")).get("Shipment")).get("Package")).get(0);
        Map<String, Object> weight = (Map<String, Object>) pkg.get("PackageWeight");
        Map<String, Object> uom = (Map<String, Object>) weight.get("UnitOfMeasurement");
        assertEquals("KGS", uom.get("Code"), "KG on the DTO should serialize as KGS to UPS");
        assertEquals("1.5", weight.get("Weight"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void internationalPayloadEmitsInternationalForms() throws Exception {
        ShipmentRequestDTO r = domesticRequest();
        r.setRecipientCountryCode("GB");
        r.setIntl(validIntl());

        Map<String, Object> payload = build(r);
        Map<String, Object> shipment = (Map<String, Object>) ((Map<String, Object>)
                payload.get("ShipmentRequest")).get("Shipment");
        Map<String, Object> forms = (Map<String, Object>) ((Map<String, Object>)
                shipment.get("ShipmentServiceOptions")).get("InternationalForms");
        assertEquals("01", forms.get("FormType"));
        assertEquals("DDP", forms.get("TermsOfShipment"));
        assertEquals("SALE", forms.get("ReasonForExport"));
        assertEquals("EUR", forms.get("CurrencyCode"));
        List<Map<String, Object>> products = (List<Map<String, Object>>) forms.get("Product");
        assertEquals(1, products.size());
        Map<String, Object> product = products.get(0);
        assertEquals("Widget", product.get("Description"));
        assertEquals("6104.62.20", product.get("CommodityCode"));
        assertEquals("US", product.get("OriginCountryCode"));
        Map<String, Object> unit = (Map<String, Object>) product.get("Unit");
        assertEquals("10", unit.get("Number"));
        assertEquals("50.00", unit.get("Value"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void ddpAddsSecondBillShipperShipmentCharge() throws Exception {
        ShipmentRequestDTO r = domesticRequest();
        r.setRecipientCountryCode("GB");
        r.setIntl(validIntl()); // incoterms=DDP already

        Map<String, Object> shipment = (Map<String, Object>) ((Map<String, Object>)
                build(r).get("ShipmentRequest")).get("Shipment");
        List<Map<String, Object>> charges = (List<Map<String, Object>>) ((Map<String, Object>)
                shipment.get("PaymentInformation")).get("ShipmentCharge");
        assertEquals(2, charges.size(), "DDP should split freight + duties into two charges");
        assertEquals("01", charges.get(0).get("Type"));
        assertEquals("02", charges.get(1).get("Type"));
        Map<String, Object> dutyCharge = charges.get(1);
        assertNotNull(dutyCharge.get("BillShipper"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void dapKeepsSingleFreightCharge() throws Exception {
        ShipmentRequestDTO r = domesticRequest();
        r.setRecipientCountryCode("GB");
        IntlShipmentBlockDTO intl = validIntl();
        intl.setIncoterms("DAP");
        r.setIntl(intl);

        List<Map<String, Object>> charges = (List<Map<String, Object>>) ((Map<String, Object>) ((Map<String, Object>)
                ((Map<String, Object>) build(r).get("ShipmentRequest")).get("Shipment")).get("PaymentInformation"))
                .get("ShipmentCharge");
        assertEquals(1, charges.size(), "DAP should leave duties to consignee — freight only");
    }

    @SuppressWarnings("unchecked")
    @Test
    void thirdPartyDutyRoutesToBillThirdParty() throws Exception {
        ShipmentRequestDTO r = domesticRequest();
        r.setRecipientCountryCode("GB");
        IntlShipmentBlockDTO intl = validIntl();
        intl.setIncoterms("DAP");
        intl.setDutyBillTo("THIRD_PARTY");
        intl.setDutyAccount("PAYER-999");
        r.setIntl(intl);

        List<Map<String, Object>> charges = (List<Map<String, Object>>) ((Map<String, Object>) ((Map<String, Object>)
                ((Map<String, Object>) build(r).get("ShipmentRequest")).get("Shipment")).get("PaymentInformation"))
                .get("ShipmentCharge");
        assertEquals(2, charges.size());
        Map<String, Object> billThirdParty = (Map<String, Object>) charges.get(1).get("BillThirdParty");
        assertNotNull(billThirdParty);
        assertEquals("PAYER-999", billThirdParty.get("AccountNumber"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void soldToOnlyEmittedWhenImporterHasIdentity() throws Exception {
        ShipmentRequestDTO r = domesticRequest();
        r.setRecipientCountryCode("GB");
        r.setIntl(validIntl()); // no importer fields set

        assertNull(((Map<String, Object>) ((Map<String, Object>) build(r).get("ShipmentRequest")).get("Shipment"))
                .get("SoldTo"), "SoldTo should not be emitted when importer identity is blank");

        // Now add importer identity
        IntlShipmentBlockDTO intl = validIntl();
        intl.setImporterName("Acme UK Ltd");
        intl.setImporterAddressLine1("1 Kings Way");
        intl.setImporterCity("London");
        intl.setImporterPostcode("W1 1AA");
        intl.setImporterCountry("GB");
        r.setIntl(intl);

        Map<String, Object> soldTo = (Map<String, Object>) ((Map<String, Object>)
                ((Map<String, Object>) build(r).get("ShipmentRequest")).get("Shipment")).get("SoldTo");
        assertNotNull(soldTo);
        assertEquals("02", soldTo.get("Option"));
        assertEquals("Acme UK Ltd", soldTo.get("Name"));
    }

    @Test
    void requestSubVersionMatchesUpsShipApiTwentyTwoOhFive() throws Exception {
        Map<String, Object> shipmentRequest = (Map<String, Object>) build(domesticRequest()).get("ShipmentRequest");
        @SuppressWarnings("unchecked")
        Map<String, Object> requestBlock = (Map<String, Object>) shipmentRequest.get("Request");
        assertEquals("2205", requestBlock.get("SubVersion"));
    }

    @Test
    void intlReadyGateBlocksIncompleteBlock() throws Exception {
        ShipmentRequestDTO r = domesticRequest();
        r.setRecipientCountryCode("GB");
        IntlShipmentBlockDTO intl = IntlShipmentBlockDTO.builder()
                .international(true) // no commodities/currency/incoterms
                .build();
        r.setIntl(intl);
        assertFalse(intl.isReadyForCarrier());

        @SuppressWarnings("unchecked")
        Map<String, Object> shipment = (Map<String, Object>) ((Map<String, Object>)
                build(r).get("ShipmentRequest")).get("Shipment");
        assertNull(shipment.get("ShipmentServiceOptions"),
                "Incomplete intl block should skip InternationalForms silently");
    }

    // ===== UPS-9 — reasonForExport mapping =====

    @Test
    void reasonForExportMapsToUpsEnum() {
        // Pre-UPS-9 the ReasonForExport field was firstNonBlank(x, "SALE").toUpperCase()
        // — the resolver's 8-value SHIPPING_PURPOSE_ENUM was passed through
        // as-is. UPS accepts only 7 values (SALE/GIFT/SAMPLE/RETURN/REPAIR/
        // INTERCOMPANYDATA/DOCUMENTS), so MERCHANDISE, PERSONAL_USE, and
        // REPAIR_AND_RETURN reached UPS as unsupported strings. Now the
        // connector maps to UPS's enum explicitly. Mirrors FDX-D on FedEx.
        java.util.LinkedHashMap<String, String> mapping = new java.util.LinkedHashMap<>();
        mapping.put("SALE", "SALE");
        mapping.put("MERCHANDISE", "SALE");           // UPS-9 — was passed as MERCHANDISE (invalid); commercial = SALE
        mapping.put("GIFT", "GIFT");
        mapping.put("SAMPLE", "SAMPLE");
        mapping.put("PERSONAL_USE", "SAMPLE");        // UPS-9 — was passed as PERSONAL_USE (invalid); UPS has no PERSONAL_EFFECTS
        mapping.put("RETURN", "RETURN");
        mapping.put("REPAIR", "REPAIR");
        mapping.put("REPAIR_AND_RETURN", "REPAIR");   // UPS-9 — was passed as REPAIR_AND_RETURN (invalid); consolidate
        mapping.put("DOCUMENTS", "DOCUMENTS");
        for (java.util.Map.Entry<String, String> entry : mapping.entrySet()) {
            assertEquals(entry.getValue(),
                    UpsConnector.mapUpsReasonForExport(entry.getKey()),
                    "Reason " + entry.getKey());
        }
    }

    @Test
    void reasonForExportUnknownFallsToSaleWithWarning() {
        // Unknown values still default to SALE (matches pre-UPS-9 default);
        // helper logs a warning via log.warn so future audits catch drift.
        assertEquals("SALE", UpsConnector.mapUpsReasonForExport(null));
        assertEquals("SALE", UpsConnector.mapUpsReasonForExport("GARBAGE"));
        assertEquals("SALE", UpsConnector.mapUpsReasonForExport(""));
    }

    @Test
    void reasonForExportIsCaseInsensitive() {
        assertEquals("REPAIR", UpsConnector.mapUpsReasonForExport("repair_and_return"));
        assertEquals("GIFT", UpsConnector.mapUpsReasonForExport("Gift"));
        assertEquals("SAMPLE", UpsConnector.mapUpsReasonForExport("personal_use"));
    }

    // ===== UPS-4b — LabelImageFormat wired from ShipmentRequestDTO =====

    @SuppressWarnings("unchecked")
    @Test
    void labelImageFormat_defaultsToGIF_whenDtoLeavesItNull() throws Exception {
        // Pre-UPS-4b behavior preserved for callers that don't populate
        // the new DTO field. Matches the pre-fix hardcode exactly.
        ShipmentRequestDTO r = domesticRequest();
        r.setLabelImageFormat(null);
        Map<String, Object> shipmentRequest = (Map<String, Object>) build(r).get("ShipmentRequest");
        Map<String, Object> labelSpec = (Map<String, Object>) shipmentRequest.get("LabelSpecification");
        Map<String, Object> format = (Map<String, Object>) labelSpec.get("LabelImageFormat");
        assertEquals("GIF", format.get("Code"),
                "null labelImageFormat must fall to the pre-UPS-4b hardcode for back-compat");
    }

    @SuppressWarnings("unchecked")
    @Test
    void labelImageFormat_pdfOnDtoLandsOnUpsWire() throws Exception {
        // Operator-set PDF (high-quality printer) must reach the wire so
        // UPS returns a sharp vector label instead of rasterised GIF.
        // Fixes the pre-UPS-4b bug where ZPL-printer shippers had no way
        // to override the fuzzy default.
        ShipmentRequestDTO r = domesticRequest();
        r.setLabelImageFormat("PDF");
        Map<String, Object> shipmentRequest = (Map<String, Object>) build(r).get("ShipmentRequest");
        Map<String, Object> labelSpec = (Map<String, Object>) shipmentRequest.get("LabelSpecification");
        Map<String, Object> format = (Map<String, Object>) labelSpec.get("LabelImageFormat");
        assertEquals("PDF", format.get("Code"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void labelImageFormat_lowerCaseNormalisedToUpper() throws Exception {
        // Defensive normalisation for programmatic callers that pass
        // lower-case — UPS's enum is strict on case.
        ShipmentRequestDTO r = domesticRequest();
        r.setLabelImageFormat("zpl");
        Map<String, Object> shipmentRequest = (Map<String, Object>) build(r).get("ShipmentRequest");
        Map<String, Object> labelSpec = (Map<String, Object>) shipmentRequest.get("LabelSpecification");
        Map<String, Object> format = (Map<String, Object>) labelSpec.get("LabelImageFormat");
        assertEquals("ZPL", format.get("Code"));
    }
}
