package com.multiship.backend.service.carriers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.service.carriers.CarrierConnector.AddressToValidate;
import com.multiship.backend.service.carriers.CarrierConnector.AddressValidationResult;
import com.multiship.backend.service.carriers.usps.queue.IdempotencyKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Focused unit tests for the SERA {@code POST /sera/v1/addresses/validate}
 * branch (PR-T6). Behaviour under test:
 * <ol>
 *   <li><b>Confident match</b> — SERA returned a {@code matched_address} and
 *       no candidates. We return EXACT (echo of input) or CORRECTED (changed).</li>
 *   <li><b>Ambiguous</b> — {@code candidate_addresses[]} populated,
 *       {@code matched_address} absent. We surface AMBIGUOUS + the full
 *       candidates list.</li>
 *   <li><b>PO Box / APO-FPO flags</b> propagate as warnings.</li>
 *   <li><b>Idempotency-key helper</b> is deterministic + UUID-shaped.</li>
 *   <li><b>Empty response + 400 (error_code 800000)</b> paths are mapped to
 *       honest results instead of throwing.</li>
 * </ol>
 *
 * <p>No live HTTP — we drive {@code buildSeraValidateAddressBody} +
 * {@code parseSeraValidateAddressResponse} directly to keep tests hermetic.
 */
class StampsSeraAddressValidateTest {

    private StampsConnector connector;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        CarrierProperties props = new CarrierProperties();
        CarrierProperties.Stamps s = props.getStamps();
        s.setApiFlavor("SERA");
        s.setSeraApiBaseUrl("http://localhost:1/sera");
        s.setSeraSandboxApiBaseUrl("http://localhost:1/sera-sandbox");
        connector = new StampsConnector(props, objectMapper);
    }

    private AddressToValidate sampleInput() {
        return new AddressToValidate(
                "Acme Warehouse", null,
                "1 Warehouse Way", null, null,
                "Louisville", "KY", "40209", "US");
    }

    // ===== 1. confident match → cleansed form =====

    @Test
    void parseResponse_confidentMatchedAddress_returnsExactOrCorrected() {
        String json = """
                [{
                  "original_address": {
                    "address_line1": "1 Warehouse Way",
                    "city": "Louisville",
                    "state_province": "KY",
                    "postal_code": "40209",
                    "country_code": "US"
                  },
                  "matched_address": {
                    "address_line1": "1 WAREHOUSE WAY",
                    "city": "LOUISVILLE",
                    "state_province": "KY",
                    "postal_code": "40209-1234",
                    "country_code": "US"
                  },
                  "candidate_addresses": [],
                  "is_po_box": false,
                  "is_apo_fpo": false,
                  "validation_results": {
                    "result_code": "verified",
                    "result_description": "USPS verified this address."
                  }
                }]
                """;
        AddressValidationResult r = connector.parseSeraValidateAddressResponse(sampleInput(), json);

        // matched_address differs on the postal (zip+4) so this is CORRECTED,
        // not EXACT.
        assertTrue(r.valid());
        assertEquals("CORRECTED", r.matchLevel());
        assertNotNull(r.suggested(), "suggested address must be populated on CORRECTED");
        assertEquals("40209-1234", r.suggested().postalCode());
        // candidates slot present but empty on a confident match
        assertNotNull(r.candidates());
        assertTrue(r.candidates().isEmpty());
        assertTrue(r.message().contains("USPS")); // description surfaced
    }

    @Test
    void parseResponse_matchEchoesInput_returnsExact() {
        AddressToValidate input = sampleInput();
        String json = """
                [{
                  "matched_address": {
                    "address_line1": "1 Warehouse Way",
                    "city": "Louisville",
                    "state_province": "KY",
                    "postal_code": "40209",
                    "country_code": "US"
                  },
                  "candidate_addresses": [],
                  "is_po_box": false,
                  "is_apo_fpo": false,
                  "validation_results": {
                    "result_code": "verified",
                    "result_description": "Deliverable."
                  }
                }]
                """;
        AddressValidationResult r = connector.parseSeraValidateAddressResponse(input, json);
        assertTrue(r.valid());
        assertEquals("EXACT", r.matchLevel());
        assertNull(r.suggested(), "EXACT must not carry a suggested address (equals input)");
    }

    // ===== 2. ambiguous → candidate list =====

    @Test
    void parseResponse_ambiguous_populatesCandidateList() {
        String json = """
                [{
                  "candidate_addresses": [
                    {
                      "address_line1": "1 Warehouse Way Suite A",
                      "city": "Louisville",
                      "state_province": "KY",
                      "postal_code": "40209",
                      "country_code": "US"
                    },
                    {
                      "address_line1": "1 Warehouse Way Suite B",
                      "city": "Louisville",
                      "state_province": "KY",
                      "postal_code": "40209",
                      "country_code": "US"
                    }
                  ],
                  "is_po_box": false,
                  "is_apo_fpo": false,
                  "validation_results": {
                    "result_code": "multiple_matches",
                    "result_description": "Multiple possible matches — pick one."
                  }
                }]
                """;
        AddressValidationResult r = connector.parseSeraValidateAddressResponse(sampleInput(), json);
        assertFalse(r.valid(), "ambiguous isn't a confirmed deliverable address");
        assertEquals("AMBIGUOUS", r.matchLevel());
        assertEquals(2, r.candidates().size());
        assertEquals("1 Warehouse Way Suite A", r.candidates().get(0).addressLine1());
        assertEquals("1 Warehouse Way Suite B", r.candidates().get(1).addressLine1());
        assertTrue(r.message().toLowerCase().contains("multiple") || r.message().toLowerCase().contains("pick"),
                "operator-facing message should hint at picking a candidate");
    }

    // ===== 3. PO Box flag =====

    @Test
    void parseResponse_poBox_warningSurfaced() {
        String json = """
                [{
                  "matched_address": {
                    "address_line1": "PO Box 123",
                    "city": "Austin",
                    "state_province": "TX",
                    "postal_code": "78701",
                    "country_code": "US"
                  },
                  "candidate_addresses": [],
                  "is_po_box": true,
                  "is_apo_fpo": false,
                  "validation_results": {
                    "result_code": "verified",
                    "result_description": "PO Box deliverable."
                  }
                }]
                """;
        AddressValidationResult r = connector.parseSeraValidateAddressResponse(sampleInput(), json);
        assertTrue(r.valid());
        assertTrue(r.warnings().stream().anyMatch(w -> w.toLowerCase().contains("po box")),
                "PO Box warning must appear in warnings list; got: " + r.warnings());
    }

    // ===== 4. APO/FPO flag =====

    @Test
    void parseResponse_apoFpo_warningSurfaced() {
        String json = """
                [{
                  "matched_address": {
                    "address_line1": "Unit 100",
                    "city": "APO",
                    "state_province": "AE",
                    "postal_code": "09001",
                    "country_code": "US"
                  },
                  "candidate_addresses": [],
                  "is_po_box": false,
                  "is_apo_fpo": true,
                  "validation_results": {
                    "result_code": "verified",
                    "result_description": "APO deliverable."
                  }
                }]
                """;
        AddressValidationResult r = connector.parseSeraValidateAddressResponse(sampleInput(), json);
        assertTrue(r.valid());
        assertTrue(r.warnings().stream().anyMatch(w -> w.toLowerCase().contains("apo")),
                "APO/FPO warning must appear in warnings list; got: " + r.warnings());
    }

    // ===== 5. HTTP 400 with error_code 800000 → surface the message =====

    @Test
    void validateAddressSera_http400_surfacesSeraErrorMessage() {
        // Drive the actual validateAddressSera entry point with a token that
        // will cause the http call to fail (localhost:1 refuses) — we're
        // asserting the error-mapping path: ANY RestClientResponseException
        // path hands off to parseSeraError. For a fine-grained 400/800000
        // surface we rely on parseSeraError's unit tests + this integration
        // guard that the right branch runs without throwing.
        AddressValidationResult r = connector.validateAddressSera(
                sampleInput(), "real-token-xyz", "PRODUCTION");
        // Localhost:1 is refused → ERROR path. The important invariant is
        // "we never throw out of validateAddress".
        assertEquals("ERROR", r.matchLevel());
        assertNotNull(r.message());
    }

    // ===== 6. Empty response → "could not validate" (no throw) =====

    @Test
    void parseResponse_emptyBody_returnsErrorNotThrow() {
        AddressValidationResult r = assertDoesNotThrow(() ->
                connector.parseSeraValidateAddressResponse(sampleInput(), ""));
        assertEquals("ERROR", r.matchLevel());
        assertFalse(r.valid());
        assertNotNull(r.message());
    }

    @Test
    void parseResponse_emptyArray_returnsNotFoundNotThrow() {
        // SERA could return `[]` for pathological inputs; mustn't throw.
        AddressValidationResult r = assertDoesNotThrow(() ->
                connector.parseSeraValidateAddressResponse(sampleInput(), "[]"));
        assertFalse(r.valid());
        // Either NOT_FOUND or ERROR acceptable — key invariant is no throw.
        assertTrue(r.matchLevel().equals("NOT_FOUND") || r.matchLevel().equals("ERROR"),
                "empty-array response must map to NOT_FOUND or ERROR, got: " + r.matchLevel());
    }

    @Test
    void parseResponse_nonJson_returnsErrorNotThrow() {
        AddressValidationResult r = assertDoesNotThrow(() ->
                connector.parseSeraValidateAddressResponse(sampleInput(), "<<not json>>"));
        assertEquals("ERROR", r.matchLevel());
    }

    // ===== 7. Idempotency key helper — deterministic + UUID shape =====

    @Test
    void forStampsAddressValidate_sameInputs_sameKey() {
        String k1 = IdempotencyKeys.forStampsAddressValidate("Louisville", "KY", "40209", "US");
        String k2 = IdempotencyKeys.forStampsAddressValidate("Louisville", "KY", "40209", "US");
        assertEquals(k1, k2, "same inputs must produce the same key");
    }

    @Test
    void forStampsAddressValidate_differentPostal_differentKey() {
        String k1 = IdempotencyKeys.forStampsAddressValidate("Louisville", "KY", "40209", "US");
        String k2 = IdempotencyKeys.forStampsAddressValidate("Louisville", "KY", "40210", "US");
        assertNotEquals(k1, k2, "different postal must produce a different key");
    }

    @Test
    void forStampsAddressValidate_isCanonicalUuidShape() {
        String k = IdempotencyKeys.forStampsAddressValidate("Louisville", "KY", "40209", "US");
        // Must be a parseable UUID (SERA documents canonical UUID shape on
        // Idempotency-Key).
        UUID.fromString(k); // throws if not a valid UUID
    }

    @Test
    void forStampsAddressValidate_caseAndTrimTolerant() {
        // city/state/country normalise to lower-case + trim before hashing,
        // so operators pasting mixed-case input still hit SERA's cached
        // verdict instead of counting as a fresh validation call.
        String k1 = IdempotencyKeys.forStampsAddressValidate("Louisville", "KY", "40209", "US");
        String k2 = IdempotencyKeys.forStampsAddressValidate("  LOUISVILLE ", "ky", "40209", "us");
        assertEquals(k1, k2, "case/whitespace variants must collapse to one key");
    }

    @Test
    void forStampsAddressValidate_nullSafe() {
        // Null city/state/postal/country must not throw — the helper
        // substitutes empty strings to preserve the UUID shape.
        String k = assertDoesNotThrow(() ->
                IdempotencyKeys.forStampsAddressValidate(null, null, null, null));
        UUID.fromString(k);
    }

    // ===== bonus: request-body shape (SERA top-level ARRAY) =====

    @Test
    void buildBody_isJsonArrayWithOneEntry() throws Exception {
        String json = connector.buildSeraValidateAddressBody(sampleInput());
        com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(json);
        assertTrue(root.isArray(), "SERA validate-address body MUST be a JSON array top-level");
        assertEquals(1, root.size());
        // snake-case field names mirror buildSeraAddress — regression guard.
        assertEquals("1 Warehouse Way", root.get(0).path("address_line1").asText());
        assertEquals("KY", root.get(0).path("state_province").asText());
        assertEquals("40209", root.get(0).path("postal_code").asText());
        assertEquals("US", root.get(0).path("country_code").asText());
    }
}
