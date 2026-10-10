package com.multiship.backend.service.carriers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.dto.CustomsCommodityDTO;
import com.multiship.backend.dto.IntlShipmentBlockDTO;
import com.multiship.backend.dto.PackageDetailDTO;
import com.multiship.backend.dto.ShipmentRequestDTO;
import com.multiship.backend.service.carriers.StampsSeraRates.SeraRateQuote;
import com.multiship.backend.service.carriers.exceptions.CarrierException;
import com.multiship.backend.service.carriers.exceptions.CarrierRateLimitException;
import com.multiship.backend.service.carriers.exceptions.CarrierValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PR-T3 — focused unit tests for {@link StampsSeraRates} ({@code POST
 * /sera/v1/rates}). Three shapes under test:
 * <ol>
 *   <li><b>Request JSON</b> — SERA snake-case ({@code from_address},
 *       {@code to_address}, {@code package.packaging_type},
 *       {@code service_type}, {@code ship_date}, {@code is_return_label},
 *       {@code customs}). Field-name regressions break every live rate
 *       quote silently.</li>
 *   <li><b>Response parsing</b> — each quote entry unpacks into one
 *       {@link SeraRateQuote} carrying {@code service_type},
 *       {@code shipment_cost.total_amount},
 *       {@code estimated_delivery_days}, and the new PR-T3
 *       {@code is_customs_required} slot. Missing/empty quotes list
 *       returns an empty list rather than throwing.</li>
 *   <li><b>Error-code classification</b> — 429 maps to
 *       {@link CarrierRateLimitException}; 400 + SERA 800000 maps to
 *       {@link CarrierValidationException} with the SERA
 *       {@code error_message} surfaced.</li>
 * </ol>
 *
 * <p>No real HTTP fires — we drive {@code buildSeraRatesBody},
 * {@code parseSeraRatesResponse}, and the exception-mapper
 * ({@code CarrierExceptionMapper.map}) directly, same hermetic pattern
 * as {@link StampsSeraCreateLabelTest} + {@link StampsSeraVoidTest}.
 */
class StampsSeraRatesTest {

    private StampsSeraRates rates;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        CarrierProperties props = new CarrierProperties();
        CarrierProperties.Stamps s = props.getStamps();
        // Point at an unreachable host so any test that forgets to short-circuit
        // on effectivePackages()/token fails loud locally instead of silently
        // hitting api.stampsendicia.com with a bogus token.
        s.setSeraApiBaseUrl("http://localhost:1/sera");
        s.setSeraSandboxApiBaseUrl("http://localhost:1/sera-sandbox");
        s.setApiFlavor("SERA");
        rates = new StampsSeraRates(props, objectMapper);
    }

    // ===== request JSON shape =====

    @Test
    void requestBody_usesSeraSnakeCaseFieldNames() throws Exception {
        ShipmentRequestDTO req = baseDomesticRequest();
        String json = rates.buildSeraRatesBody(req, req.effectivePackages().get(0),
                LocalDate.of(2026, 10, 9));
        JsonNode root = objectMapper.readTree(json);

        // SERA REQUIRES from_address/to_address (snake_case) — any sender/recipient
        // naming regresses the wire shape and SERA silently returns zero quotes.
        assertTrue(root.has("from_address"), "must emit from_address (SERA convention)");
        assertTrue(root.has("to_address"), "must emit to_address");
        assertEquals("US", root.path("from_address").path("country_code").asText());
        assertEquals("40209", root.path("from_address").path("postal_code").asText());
        assertEquals("10001", root.path("to_address").path("postal_code").asText());

        // Package block — SERA packaging_type + weight unit.
        assertEquals("package", root.path("package").path("packaging_type").asText());
        assertEquals("kilogram", root.path("package").path("weight_unit").asText());
        assertEquals(new BigDecimal("1.5"), root.path("package").path("weight").decimalValue());

        // service_type is OPTIONAL on rates — when the caller supplies a
        // service it narrows the quote set; otherwise SERA returns everything
        // the lane allows. Our request carries "USPS GA" so we emit the mapped
        // snake-case value.
        assertEquals("usps_ground_advantage", root.path("service_type").asText());

        // Ship date — ISO-8601 (yyyy-MM-dd) so the wire body doesn't depend
        // on JavaTimeModule being registered on the shared ObjectMapper.
        assertEquals("2026-10-09", root.path("ship_date").asText());

        // Domestic shipment → no customs block.
        assertTrue(root.path("customs").isMissingNode() || root.path("customs").isNull(),
                "domestic rate request must omit customs");
    }

    @Test
    void requestBody_intlShipment_populatesCustomsBlock() throws Exception {
        ShipmentRequestDTO req = baseIntlRequest();
        String json = rates.buildSeraRatesBody(req, req.effectivePackages().get(0),
                LocalDate.of(2026, 10, 9));
        JsonNode root = objectMapper.readTree(json);
        JsonNode customs = root.path("customs");
        assertTrue(customs.isObject(), "intl rate request must emit customs for is_customs_required pre-check");
        assertEquals("merchandise", customs.path("contents_type").asText());
        assertTrue(customs.path("customs_items").isArray(), "customs_items[] must be present");
    }

    @Test
    void requestBody_returnLabel_setsIsReturnFlag() throws Exception {
        ShipmentRequestDTO req = baseDomesticRequest();
        req.setIsReturn(Boolean.TRUE);
        String json = rates.buildSeraRatesBody(req, req.effectivePackages().get(0),
                LocalDate.of(2026, 10, 9));
        JsonNode root = objectMapper.readTree(json);
        assertTrue(root.path("is_return_label").asBoolean(),
                "return rate-shop must flag is_return_label so SERA quotes the return-service price");
    }

    // ===== response parsing =====

    @Test
    void parseResponse_twoRateQuotes_unpackIntoTwoRateOptions() {
        // Happy path: SERA returns an array of rate quotes under `rates`,
        // each with service_type / packaging_type / cost / delivery days.
        String json = """
                {
                  "rates": [
                    {
                      "carrier": "usps",
                      "service_type": "usps_priority_mail",
                      "packaging_type": "package",
                      "estimated_delivery_days": 2,
                      "estimated_delivery_date": "2026-10-11",
                      "is_guaranteed_service": false,
                      "trackable": true,
                      "is_customs_required": false,
                      "shipment_cost": {"total_amount": 8.50, "currency": "usd"}
                    },
                    {
                      "carrier": "usps",
                      "service_type": "usps_ground_advantage",
                      "packaging_type": "package",
                      "estimated_delivery_days": 4,
                      "estimated_delivery_date": "2026-10-13",
                      "is_guaranteed_service": false,
                      "trackable": true,
                      "is_customs_required": false,
                      "shipment_cost": {"total_amount": 5.75, "currency": "usd"}
                    }
                  ]
                }
                """;
        List<SeraRateQuote> options = rates.parseSeraRatesResponse(json);
        assertEquals(2, options.size(), "two quotes → two SeraRateQuote rows");

        SeraRateQuote first = options.get(0);
        assertEquals("USPS", first.option().carrierCode(), "carrier code is our canonical USPS, not SERA's lowercase");
        // serviceCode back-mapped to our local shape so ShipmentValidationService
        // can compare against shipping_service.service_code. serviceName keeps
        // the SERA shape for carrier-authoritative display.
        assertEquals("PRIORITY", first.option().serviceCode());
        assertEquals("usps_priority_mail", first.option().serviceName());
        // compareTo rather than equals — Jackson strips BigDecimal's trailing
        // zero ("8.50" → scale 1 "8.5") and we care about value, not scale.
        assertEquals(0, new BigDecimal("8.50").compareTo(first.option().totalAmount()),
                "total must equal 8.50 (value-equal); got: " + first.option().totalAmount());
        assertEquals("USD", first.option().currency(), "currency uppercased from SERA's lowercase 'usd'");
        assertEquals(Integer.valueOf(2), first.option().transitDays());
        assertEquals(Boolean.FALSE, first.isCustomsRequired(),
                "is_customs_required=false must land on SeraRateQuote so the FE can skip the customs-missing banner");

        SeraRateQuote second = options.get(1);
        assertEquals("GROUND_ADVANTAGE", second.option().serviceCode());
        assertEquals("usps_ground_advantage", second.option().serviceName());
        assertEquals(0, new BigDecimal("5.75").compareTo(second.option().totalAmount()),
                "total must equal 5.75 (value-equal); got: " + second.option().totalAmount());
        assertEquals(Integer.valueOf(4), second.option().transitDays());
    }

    @Test
    void parseResponse_emptyRatesArray_returnsEmptyList() {
        // Lane with no USPS service → SERA returns an empty rates[]; caller
        // must treat this as "this carrier has nothing for the lane" rather
        // than throwing. Matches the FedEx/UPS rate-shop convention.
        assertEquals(List.of(), rates.parseSeraRatesResponse("{\"rates\": []}"));
    }

    @Test
    void parseResponse_missingRatesField_returnsEmptyList() {
        // Pathological but defensive — SERA responded 200 with no rates
        // key at all. Zero quotes, no exception.
        assertEquals(List.of(), rates.parseSeraRatesResponse("{}"));
    }

    @Test
    void parseResponse_bareTopLevelArray_returnsQuotes() {
        // SERA actually returns a bare top-level array, not {"rates":[...]}.
        // Observed live during QA run #3 (2026-10-10). Regression guard so
        // the parser's shape-tolerance survives future rewrites.
        String json = """
                [{"carrier":"usps","service_type":"usps_priority_mail_express",
                  "packaging_type":"package","is_customs_required":false,
                  "shipment_cost":{"total_amount":54.41,"currency":"usd"}}]
                """;
        List<SeraRateQuote> options = rates.parseSeraRatesResponse(json);
        assertEquals(1, options.size());
        assertEquals("PRIORITY_EXPRESS", options.get(0).option().serviceCode());
        assertEquals("usps_priority_mail_express", options.get(0).option().serviceName());
        assertEquals(0, options.get(0).option().totalAmount().compareTo(new BigDecimal("54.41")));
    }

    @Test
    void parseResponse_blankBody_returnsEmptyList() {
        // Blank 200 body (SERA's answer when the account has no live
        // service linked) — zero quotes, no exception.
        assertEquals(List.of(), rates.parseSeraRatesResponse(""));
        assertEquals(List.of(), rates.parseSeraRatesResponse(null));
    }

    @Test
    void parseResponse_customsRequiredTrue_preservedOnOption() {
        // The whole reason PR-T3 added isCustomsRequired to SeraRateQuote:
        // an intl corridor's rates response flags customs=required, and the
        // FE pre-commit banner reads this to warn the operator BEFORE they
        // click Generate Label (closes audit T-C4).
        String json = """
                {
                  "rates": [
                    {
                      "carrier": "usps",
                      "service_type": "usps_priority_mail_international",
                      "packaging_type": "package",
                      "estimated_delivery_days": 7,
                      "is_customs_required": true,
                      "shipment_cost": {"total_amount": 42.95, "currency": "usd"}
                    }
                  ]
                }
                """;
        List<SeraRateQuote> options = rates.parseSeraRatesResponse(json);
        assertEquals(1, options.size());
        assertEquals(Boolean.TRUE, options.get(0).isCustomsRequired(),
                "is_customs_required=true MUST propagate so the FE shows the customs-required banner "
                        + "before the operator pays for a label");
    }

    @Test
    void parseResponse_missingCustomsRequiredField_leavesSlotNull() {
        // Spec doesn't require every quote to carry is_customs_required
        // (SERA may omit it for domestic lanes). Caller treats null as
        // "unknown" rather than "false".
        String json = """
                {
                  "rates": [
                    {"service_type": "usps_priority_mail",
                     "shipment_cost": {"total_amount": 8.50, "currency": "usd"}}
                  ]
                }
                """;
        List<SeraRateQuote> options = rates.parseSeraRatesResponse(json);
        assertEquals(1, options.size());
        assertNull(options.get(0).isCustomsRequired(),
                "missing is_customs_required field → null (unknown), not false");
    }

    // ===== error-code classification =====

    @Test
    void http429_mapsToCarrierRateLimitException() {
        // SERA /rates has a separate rate-limit window from /oauth/token
        // (per spec); a bulk rate-shop that clears OAuth can still hit the
        // /rates throttle. The HTTP 429 must surface as the typed
        // CarrierRateLimitException so BulkRateShopService can honour the
        // Retry-After and back off, rather than treating it as a generic
        // 5xx and dropping the quote.
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.RETRY_AFTER, "30");
        HttpClientErrorException ex = HttpClientErrorException.create(
                HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", headers,
                "{\"error_code\": \"899999\", \"error_message\": \"rate limit\"}"
                        .getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
        assertTrue(CarrierRateLimit.isRateLimited(ex),
                "sanity: CarrierRateLimit must recognise 429 before the mapper runs");
        CarrierException mapped = com.multiship.backend.service.carriers.exceptions
                .CarrierExceptionMapper.map("STAMPS", ex, "getRatesSera");
        assertTrue(mapped instanceof CarrierRateLimitException,
                "429 must map to CarrierRateLimitException; got " + mapped.getClass().getSimpleName());
        assertEquals(Integer.valueOf(30), ((CarrierRateLimitException) mapped).getRetryAfterSeconds());
    }

    @Test
    void sera800000_validationError_surfacesInExceptionMessage() {
        // 400 + SERA error_code=800000 → caller bug (bad request body).
        // The mapper classifies as CarrierValidationException and the
        // free-form error_message is embedded in the exception so the
        // operator sees SERA's own complaint, not a generic HTTP 400.
        HttpClientErrorException ex = HttpClientErrorException.create(
                HttpStatus.BAD_REQUEST, "Bad Request", HttpHeaders.EMPTY,
                ("{\"error_code\": \"800000\", "
                        + "\"error_message\": \"from_address.country_code is required\"}")
                        .getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
        // Parser surface — SERA error_message threaded through our shared parser.
        StampsSeraErrorCode.SeraError parsed = rates.parseSeraError(ex.getResponseBodyAsString());
        assertEquals(StampsSeraErrorCode.VALIDATION, parsed.code());
        assertEquals("from_address.country_code is required", parsed.message());

        CarrierException mapped = com.multiship.backend.service.carriers.exceptions
                .CarrierExceptionMapper.map("STAMPS", ex, "getRatesSera");
        assertTrue(mapped instanceof CarrierValidationException,
                "400 must map to CarrierValidationException; got " + mapped.getClass().getSimpleName());
        assertTrue(mapped.getMessage().contains("from_address.country_code is required")
                        || mapped.getMessage().contains("800000"),
                "mapped message must carry SERA's error_message so the operator sees the real cause; got: "
                        + mapped.getMessage());
    }

    // ===== dispatcher input guards =====

    @Test
    void getRates_blankToken_throwsAuthRejectionWithPointerToOAuth() {
        // -local-* tokens are placeholder tokens for unverified accounts.
        // Calling SERA with them wastes an API call and returns a 401 that
        // the caller surfaces as a confusing generic error. Fail-fast with
        // an actionable message instead.
        ShipmentRequestDTO req = baseDomesticRequest();
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> rates.getRates(req, "", "PRODUCTION"));
        assertTrue(ex.getMessage().contains("not authorized")
                        || ex.getMessage().toLowerCase().contains("authoriz"),
                "blank token must point the operator at the authorization flow; got: " + ex.getMessage());

        assertThrows(IllegalStateException.class,
                () -> rates.getRates(req, "stamps-local-abc", "PRODUCTION"));
    }

    @Test
    void getRates_blankRecipientCountry_throwsIllegalArgument() {
        // FDX-B4 — recipient country is required. Pre-fix, blank silently
        // defaulted to "US" downstream and the operator got US-domestic
        // quotes for a lane that was actually intl. Reject early.
        ShipmentRequestDTO req = baseDomesticRequest();
        req.setRecipientCountryCode(null);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> rates.getRates(req, "real-sera-token", "PRODUCTION"));
        assertTrue(ex.getMessage().contains("country"),
                "message must call out the missing country; got: " + ex.getMessage());
    }

    // ===== helpers =====

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
                .referenceNumber("PO-RATE-1001")
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
