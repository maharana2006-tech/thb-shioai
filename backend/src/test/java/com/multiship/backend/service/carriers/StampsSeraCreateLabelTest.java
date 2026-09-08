package com.multiship.backend.service.carriers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.dto.CustomsCommodityDTO;
import com.multiship.backend.dto.IntlShipmentBlockDTO;
import com.multiship.backend.dto.PackageDetailDTO;
import com.multiship.backend.dto.ShipmentRequestDTO;
import com.multiship.backend.service.carriers.CarrierConnector.PackageTracking;
import com.multiship.backend.service.carriers.CarrierConnector.ShipmentResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Focused unit tests for the SERA {@code POST /sera/v1/labels} branch
 * added to {@link StampsConnector}. Two contract shapes need pinning:
 * <ol>
 *   <li><b>Request JSON</b> — SERA is picky about snake-case field
 *       names ({@code from_address}, {@code to_address}, {@code service_type},
 *       {@code label_options.label_output_type=base64}, ...). A rename or
 *       casing regression breaks every live call silently at the
 *       "we sent the wrong field" level.</li>
 *   <li><b>Response parsing</b> — {@code label_id} MUST persist onto
 *       {@link PackageTracking#carrierLabelRef()} so PR 2 (SERA void)
 *       + PR 5 (SERA reprint) can key off it in the DB. Missing
 *       {@code label_id} or {@code tracking_number} must throw so
 *       the caller-side rollback path fires (mirrors the SWSIM
 *       parseCreateIndicium contract).</li>
 * </ol>
 *
 * <p>No real HTTP fires — we drive {@code buildSeraCreateLabelBody}
 * and {@code parseSeraCreateLabelResponse} directly to keep the tests
 * hermetic + fast.
 */
class StampsSeraCreateLabelTest {

    private StampsConnector connector;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        CarrierProperties props = new CarrierProperties();
        CarrierProperties.Stamps s = props.getStamps();
        s.setSeraApiBaseUrl("https://api.stampsendicia.com/sera/v1");
        s.setSeraSandboxApiBaseUrl("https://api.testing.stampsendicia.com/sera/v1");
        s.setApiFlavor("SERA");
        connector = new StampsConnector(props, objectMapper);
    }

    // ===== request JSON shape =====

    @Test
    void requestBody_usesSeraSnakeCaseFieldNames() throws Exception {
        ShipmentRequestDTO req = baseDomesticRequest();
        String json = connector.buildSeraCreateLabelBody(req, req.effectivePackages().get(0), 1, 1);
        JsonNode root = objectMapper.readTree(json);

        // SERA REQUIRES from_address/to_address (snake_case) — sender/recipient
        // would silently be ignored by the server.
        assertTrue(root.has("from_address"), "must emit from_address (SERA convention)");
        assertTrue(root.has("to_address"), "must emit to_address (SERA convention)");
        // Address sub-fields — SERA schema uses these exact names.
        assertEquals("Acme Warehouse", root.path("from_address").path("name").asText());
        assertEquals("1 Warehouse Way", root.path("from_address").path("address_line1").asText());
        assertEquals("KY", root.path("from_address").path("state_province").asText());
        assertEquals("US", root.path("from_address").path("country_code").asText());
        assertEquals("40209", root.path("from_address").path("postal_code").asText());
        // Service code + package block.
        assertEquals("usps_ground_advantage", root.path("service_type").asText());
        assertEquals("package", root.path("package").path("packaging_type").asText());
        // Package uses SERA's weight_unit (pound/ounce/gram/kilogram).
        assertEquals("kilogram", root.path("package").path("weight_unit").asText());
        assertEquals(new BigDecimal("1.5"), root.path("package").path("weight").decimalValue());
        // Label options — output type MUST be base64 so the persister writes
        // bytes to disk (SWSIM parity).
        assertEquals("base64", root.path("label_options").path("label_output_type").asText());
        assertEquals("pdf", root.path("label_options").path("label_format").asText());
        // Ship date is populated (LabelDates.today).
        assertNotNull(root.path("ship_date").asText(null));
    }

    @Test
    void requestBody_intlCustoms_populatesCustomsBlockWithItems() throws Exception {
        ShipmentRequestDTO req = baseIntlRequest();
        String json = connector.buildSeraCreateLabelBody(req, req.effectivePackages().get(0), 1, 1);
        JsonNode root = objectMapper.readTree(json);

        JsonNode customs = root.path("customs");
        assertTrue(customs.isObject(), "intl shipment must emit customs block");
        assertEquals("merchandise", customs.path("contents_type").asText());
        assertEquals("return_to_sender", customs.path("non_delivery_option").asText());
        JsonNode items = customs.path("customs_items");
        assertTrue(items.isArray() && items.size() == 1, "one commodity → one item entry");
        assertEquals("Widget", items.get(0).path("item_description").asText());
        assertEquals(2, items.get(0).path("quantity").asInt());
        assertEquals("eur", items.get(0).path("unit_value").path("currency").asText());
        assertEquals("HS12345", items.get(0).path("harmonized_tariff_code").asText());
        assertEquals("CN", items.get(0).path("country_of_origin").asText());
        assertEquals("SKU-1", items.get(0).path("sku").asText());
    }

    @Test
    void requestBody_domesticShipment_omitsCustomsBlock() throws Exception {
        ShipmentRequestDTO req = baseDomesticRequest();
        String json = connector.buildSeraCreateLabelBody(req, req.effectivePackages().get(0), 1, 1);
        JsonNode root = objectMapper.readTree(json);
        assertTrue(root.path("customs").isMissingNode() || root.path("customs").isNull(),
                "domestic shipment must NOT emit customs block");
    }

    @Test
    void requestBody_insurance_emittedOnlyWhenPositiveValue() throws Exception {
        ShipmentRequestDTO req = baseDomesticRequest();
        req.setInsuredValue(new BigDecimal("75.00"));
        req.setInsuredValueCurrency("USD");
        String json = connector.buildSeraCreateLabelBody(req, req.effectivePackages().get(0), 1, 1);
        JsonNode root = objectMapper.readTree(json);
        assertEquals("stamps_com", root.path("insurance").path("insurance_provider").asText());
        assertEquals("usd", root.path("insurance").path("insured_value").path("currency").asText());

        // Zero / null → block omitted.
        req.setInsuredValue(BigDecimal.ZERO);
        String json2 = connector.buildSeraCreateLabelBody(req, req.effectivePackages().get(0), 1, 1);
        JsonNode root2 = objectMapper.readTree(json2);
        assertTrue(root2.path("insurance").isMissingNode() || root2.path("insurance").isNull());
    }

    // ===== response parsing =====

    @Test
    void parseResponse_persistsLabelIdIntoCarrierLabelRef() throws Exception {
        String responseJson = """
                {
                  "label_id": "abc-123-uuid",
                  "tracking_number": "9400111899223197428301",
                  "labels": [{"href": "BASE64PDFCONTENT"}],
                  "shipment_cost": {"total_amount": 4.85, "currency": "usd"},
                  "estimated_delivery_date": "2026-09-10T12:00:00"
                }
                """;
        ShipmentResult r = connector.parseSeraCreateLabelResponse(responseJson, baseDomesticRequest());
        assertEquals("9400111899223197428301", r.trackingNumber());
        assertEquals("BASE64PDFCONTENT", r.labelUrl());
        assertEquals(new BigDecimal("4.85"), r.shippingCost());
        // The label_id UUID must land on the piece's carrierLabelRef so
        // downstream persistence (V44 column) picks it up.
        assertNotNull(r.packages());
        assertEquals(1, r.packages().size());
        PackageTracking piece = r.packages().get(0);
        assertEquals("abc-123-uuid", piece.carrierLabelRef(),
                "SERA label_id MUST persist to PackageTracking.carrierLabelRef "
                        + "so PR 2 (void) + PR 5 (reprint) can key off it in the DB");
    }

    @Test
    void parseResponse_missingLabelId_throws() {
        // A response without a label_id can't drive void/reprint downstream.
        // Fail loud so the createShipment loop rolls back + surfaces an error
        // instead of silently persisting a null carrierLabelRef.
        String badJson = """
                {"tracking_number": "9400", "labels": [{"href": "x"}]}
                """;
        assertThrows(IllegalStateException.class,
                () -> connector.parseSeraCreateLabelResponse(badJson, baseDomesticRequest()));
    }

    @Test
    void parseResponse_missingTrackingNumber_throws() {
        String badJson = """
                {"label_id": "abc-123"}
                """;
        assertThrows(IllegalStateException.class,
                () -> connector.parseSeraCreateLabelResponse(badJson, baseDomesticRequest()));
    }

    @Test
    void parseResponse_emptyBody_throws() {
        assertThrows(IllegalStateException.class,
                () -> connector.parseSeraCreateLabelResponse("", baseDomesticRequest()));
        assertThrows(IllegalStateException.class,
                () -> connector.parseSeraCreateLabelResponse(null, baseDomesticRequest()));
    }

    // ===== SERA base URL routing =====

    @Test
    void seraApiBaseUrl_sandboxEnv_routesToSandboxHost() {
        assertEquals("https://api.testing.stampsendicia.com/sera/v1",
                connector.seraApiBaseUrl("SANDBOX"));
    }

    @Test
    void seraApiBaseUrl_productionEnv_routesToProductionHost() {
        assertEquals("https://api.stampsendicia.com/sera/v1",
                connector.seraApiBaseUrl("PRODUCTION"));
    }

    @Test
    void seraApiBaseUrl_trailingSlash_stripped() {
        connector = new StampsConnector(propsWithBases(
                "https://api.stampsendicia.com/sera/v1/",
                "https://api.testing.stampsendicia.com/sera/v1/"),
                new ObjectMapper());
        assertEquals("https://api.stampsendicia.com/sera/v1",
                connector.seraApiBaseUrl("PRODUCTION"),
                "trailing slash on base must be stripped so callers can concat paths cleanly");
    }

    // ===== service code + packaging mapping tables =====

    @Test
    void mapSwsimServiceToSera_translatesLegacyCodes() {
        assertEquals("usps_priority_mail", StampsConnector.mapSwsimServiceToSera("USPS PM"));
        assertEquals("usps_ground_advantage", StampsConnector.mapSwsimServiceToSera("USPS GA"));
        assertEquals("usps_priority_mail_express", StampsConnector.mapSwsimServiceToSera("USPS PME"));
        assertEquals("usps_priority_mail_international", StampsConnector.mapSwsimServiceToSera("USPS PMI"));
        // Snake_case already → passthrough.
        assertEquals("usps_ground_advantage", StampsConnector.mapSwsimServiceToSera("usps_ground_advantage"));
        // Blank → null (caller omits service_type).
        assertNull(StampsConnector.mapSwsimServiceToSera("  "));
    }

    @Test
    void mapSignatureToSera_normalisesLevels() {
        assertEquals("signature", StampsConnector.mapSignatureToSera("INDIRECT"));
        assertEquals("signature", StampsConnector.mapSignatureToSera("DIRECT"));
        assertEquals("adult_signature", StampsConnector.mapSignatureToSera("ADULT"));
        assertNull(StampsConnector.mapSignatureToSera("NONE"));
        assertNull(StampsConnector.mapSignatureToSera(null));
        assertNull(StampsConnector.mapSignatureToSera("something-else"));
    }

    @Test
    void normaliseSeraWeightUnit_speaksSeraVocabulary() {
        assertEquals("pound", StampsConnector.normaliseSeraWeightUnit("LB"));
        assertEquals("kilogram", StampsConnector.normaliseSeraWeightUnit("KG"));
        assertEquals("gram", StampsConnector.normaliseSeraWeightUnit("g"));
        assertEquals("ounce", StampsConnector.normaliseSeraWeightUnit("OZ"));
        // Unknown → default ounce (SERA won't accept a made-up unit).
        assertEquals("ounce", StampsConnector.normaliseSeraWeightUnit("stones"));
    }

    // ===== helpers =====

    private CarrierProperties propsWithBases(String prod, String sandbox) {
        CarrierProperties p = new CarrierProperties();
        p.getStamps().setSeraApiBaseUrl(prod);
        p.getStamps().setSeraSandboxApiBaseUrl(sandbox);
        p.getStamps().setApiFlavor("SERA");
        return p;
    }

    private ShipmentRequestDTO baseDomesticRequest() {
        ShipmentRequestDTO req = ShipmentRequestDTO.builder()
                .carrierCode("USPS")
                .serviceType("USPS GA")
                .packageType("Package")
                .weight(new BigDecimal("1.5"))
                .weightUnit("KG")
                .length(new BigDecimal("10")).width(new BigDecimal("8")).height(new BigDecimal("4"))
                .dimUnit("IN")
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
        req.setPackages(List.of(PackageDetailDTO.builder()
                .sequenceNumber(1)
                .packageType("Package")
                .weight(new BigDecimal("1.5")).weightUnit("KG")
                .length(new BigDecimal("10")).width(new BigDecimal("8")).height(new BigDecimal("4"))
                .dimUnit("IN")
                .build()));
        return req;
    }

    private ShipmentRequestDTO baseIntlRequest() {
        ShipmentRequestDTO req = baseDomesticRequest();
        req.setServiceType("USPS PMI");
        req.setRecipientCountryCode("GB");
        req.setRecipientState("");
        req.setRecipientPostalCode("SW1A 1AA");
        req.setRecipientCity("London");
        req.setIntl(IntlShipmentBlockDTO.builder()
                .international(true)
                .incoterms("DDP")
                .customsCurrency("EUR")
                .customsTotalValue(new BigDecimal("40.00"))
                .reasonForExport("SALE")
                .weightUnit("KG")
                .commodities(List.of(CustomsCommodityDTO.builder()
                        .description("Widget")
                        .quantity(2)
                        .unitValue(new BigDecimal("20.00"))
                        .unitWeight(new BigDecimal("0.5"))
                        .hsCode("HS12345")
                        .countryOfOrigin("CN")
                        .sku("SKU-1")
                        .build()))
                .build());
        return req;
    }
}
