package com.multiship.backend.service.carriers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.dto.PackageDetailDTO;
import com.multiship.backend.dto.ShipmentRequestDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BUG-029 (QA 2026-10-24) — the SERA label wire builder must send
 * the recipient / sender name verbatim whenever present, and the
 * connector boundary must refuse a shipment whose address has
 * neither a name NOR a company.
 *
 * <p>Pre-fix an anchored prefix regex ({@code [A-Za-z]{2,}\s+[A-Za-z]{2,}.*})
 * silently stripped names like "Pat Q. Tester", "José Álvarez",
 * "O'Brien", "Anne-Marie Smith" and single-word names from the wire,
 * forcing SERA to 400 (6619396 / 4522242) whenever company_name was
 * also blank. QA's 34-call Carrier API Log audit (Appendix A) proved
 * SERA accepts every variant verbatim; this test pins that behaviour
 * so a future regression that re-adds the filter is caught locally.
 */
class StampsSeraNameFilterTest {

    private StampsConnector connector;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        CarrierProperties props = new CarrierProperties();
        CarrierProperties.Stamps s = props.getStamps();
        s.setApiFlavor("SERA");
        s.setSeraApiBaseUrl("https://api.stampsendicia.com/sera/v1");
        connector = new StampsConnector(props, mapper);
    }

    /** Every QA-tested name pattern that was previously filtered — the
     *  wire builder must now include each verbatim on {@code to_address.name}
     *  and {@code from_address.name}. One parameterised test closes 14 QA
     *  reproduction cases. */
    @ParameterizedTest
    @ValueSource(strings = {
            "Pat Q. Tester",
            "Pat Q Tester",
            "J Smith",
            "R Kumar",
            "Q. Pat Tester",
            "Pat O'Brien",
            "Sean O'Connor",
            "José Álvarez",
            "Zoë Müller",
            "Anne-Marie Smith",
            "Mary-Kate Olsen",
            "Ana R. Lopez",
            "Madonna",
            "Cher",
    })
    void recipientName_passesThroughToWireVerbatim(String name) throws Exception {
        ShipmentRequestDTO req = requestWithRecipientName(name);
        String json = connector.buildSeraCreateLabelBody(
                req, req.effectivePackages().get(0), 1, 1);
        JsonNode root = mapper.readTree(json);
        assertEquals(name, root.path("to_address").path("name").asText(),
                "Pre-fix regex silently dropped these names from the SERA "
                        + "wire. QA 2026-10-24 proved SERA accepts them verbatim "
                        + "(Appendix A of the test report).");
    }

    /** The same rule applies to the sender address — returns and the
     *  SERA from_address use the same buildSeraAddress helper. */
    @Test
    void senderName_passesThroughToWireVerbatim() throws Exception {
        ShipmentRequestDTO req = requestWithRecipientName("Jane Doe");
        req.setShipperName("José Álvarez"); // previously stripped
        String json = connector.buildSeraCreateLabelBody(
                req, req.effectivePackages().get(0), 1, 1);
        JsonNode root = mapper.readTree(json);
        assertEquals("José Álvarez", root.path("from_address").path("name").asText());
    }

    /** When the name is blank/null but company_name is present, the SERA
     *  wire should NOT include a name key — company_name carries the entity
     *  identity. Guards the fallback path for a company-only address. */
    @Test
    void blankName_omitsNameKey_whenCompanyPresent() throws Exception {
        ShipmentRequestDTO req = requestWithRecipientName("");
        req.setRecipientCompany("Acme Co");
        String json = connector.buildSeraCreateLabelBody(
                req, req.effectivePackages().get(0), 1, 1);
        JsonNode root = mapper.readTree(json);
        JsonNode toAddr = root.path("to_address");
        assertTrue(toAddr.path("name").isMissingNode() || toAddr.path("name").isNull(),
                "blank name must not emit a 'name' key");
        assertEquals("Acme Co", toAddr.path("company_name").asText(),
                "company_name must still carry the entity identity");
    }

    /** Pre-flight guard at the connector boundary: both-blank must throw
     *  BEFORE the HTTP call, with a message that names which field. */
    @Test
    void bothBlank_recipient_throwsAtConnectorBoundary() {
        ShipmentRequestDTO req = requestWithRecipientName("");
        req.setRecipientCompany("");
        IllegalArgumentException ex = org.junit.jupiter.api.Assertions
                .assertThrows(IllegalArgumentException.class,
                        () -> connector.createShipmentSera(req, "token", "sandbox"));
        assertTrue(ex.getMessage().toLowerCase().contains("recipient"),
                "message must name the field: " + ex.getMessage());
        assertTrue(ex.getMessage().toLowerCase().contains("name or company"),
                "message must say name or company: " + ex.getMessage());
    }

    @Test
    void bothBlank_sender_throwsAtConnectorBoundary() {
        ShipmentRequestDTO req = requestWithRecipientName("Jane Doe");
        req.setShipperName("");
        req.setShipperCompany("");
        IllegalArgumentException ex = org.junit.jupiter.api.Assertions
                .assertThrows(IllegalArgumentException.class,
                        () -> connector.createShipmentSera(req, "token", "sandbox"));
        assertTrue(ex.getMessage().toLowerCase().contains("sender"),
                "message must name the field: " + ex.getMessage());
    }

    /** The retired {@link StampsConnector#isSeraValidFullname} predicate
     *  stays public for the pickup wire. Pin its (unchanged) behaviour
     *  so a future refactor that deletes it also updates
     *  {@link StampsConnector#padToSeraFullname}. */
    @Test
    void isSeraValidFullname_stillUsedByPickupPath_notLabel() {
        // Positive case still passes — padToSeraFullname relies on it.
        assertTrue(StampsConnector.isSeraValidFullname("Jane Doe"));
        // Still false for short names (padToSeraFullname pads these with
        // " Pickup" for the SERA /pickups wire).
        assertFalse(StampsConnector.isSeraValidFullname("MKL"));
    }

    private ShipmentRequestDTO requestWithRecipientName(String name) {
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
                .recipientName(name)
                .recipientPhone("5559876543")
                .recipientAddressLine1("42 Broadway")
                .recipientCity("New York")
                .recipientState("NY")
                .recipientPostalCode("10001")
                .recipientCountryCode("US")
                .referenceNumber("QA-20261024-NAME-TEST")
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
}
