package com.multiship.backend.service.carriers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.dto.CustomsCommodityDTO;
import com.multiship.backend.dto.IntlShipmentBlockDTO;
import com.multiship.backend.dto.PackageDetailDTO;
import com.multiship.backend.dto.ShipmentRequestDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** PR-T9 — pins MPS correctness: insurance split (no 3× premium),
 *  per-piece commodity filtering via boxSeq (no double-declaration to
 *  CBP), piece context no longer in body (moved to HTTP header). */
class StampsSeraMpsCorrectnessTest {

    private StampsConnector connector;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        CarrierProperties props = new CarrierProperties();
        props.getStamps().setApiFlavor("SERA");
        props.getStamps().setSeraApiBaseUrl("https://api.stampsendicia.com/sera/v1");
        connector = new StampsConnector(props, mapper);
    }

    // ===== insurance split =====

    @Test
    void insurance_singlePiece_unchanged() throws Exception {
        ShipmentRequestDTO req = baseRequest(1);
        req.setInsuredValue(new BigDecimal("300.00"));
        String json = connector.buildSeraCreateLabelBody(
                req, req.effectivePackages().get(0), 1, 1);
        JsonNode root = mapper.readTree(json);
        assertEquals(0, new BigDecimal("300.00").compareTo(
                root.path("insurance").path("insured_value").path("amount").decimalValue()),
                "Jackson may normalise trailing zeros; compare by value, not scale");
    }

    @Test
    void insurance_threePieces_splitsEvenlyAndReconciles() throws Exception {
        // 300 / 3 = 100 per piece; sum must equal exactly 300.
        ShipmentRequestDTO req = baseRequest(3);
        req.setInsuredValue(new BigDecimal("300.00"));
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 1; i <= 3; i++) {
            String json = connector.buildSeraCreateLabelBody(
                    req, req.effectivePackages().get(i - 1), i, 3);
            JsonNode root = mapper.readTree(json);
            sum = sum.add(root.path("insurance").path("insured_value").path("amount").decimalValue());
        }
        assertEquals(0, sum.compareTo(new BigDecimal("300.00")),
                "per-piece sum must equal declared total exactly (got " + sum + ")");
    }

    @Test
    void insurance_threePieces_lastPieceAbsorbsRemainder() throws Exception {
        // 100 / 3 = 33.33 share; last piece absorbs the extra cent
        // → pieces 1,2 = 33.33, piece 3 = 33.34, sum = 100.00.
        ShipmentRequestDTO req = baseRequest(3);
        req.setInsuredValue(new BigDecimal("100.00"));
        BigDecimal[] pieceValues = new BigDecimal[3];
        for (int i = 1; i <= 3; i++) {
            String json = connector.buildSeraCreateLabelBody(
                    req, req.effectivePackages().get(i - 1), i, 3);
            JsonNode root = mapper.readTree(json);
            pieceValues[i - 1] = root.path("insurance").path("insured_value").path("amount").decimalValue();
        }
        assertEquals(0, pieceValues[0].compareTo(new BigDecimal("33.33")));
        assertEquals(0, pieceValues[1].compareTo(new BigDecimal("33.33")));
        assertEquals(0, pieceValues[2].compareTo(new BigDecimal("33.34")),
                "last piece absorbs the rounding remainder (0.01 extra)");
        BigDecimal sum = pieceValues[0].add(pieceValues[1]).add(pieceValues[2]);
        assertEquals(0, sum.compareTo(new BigDecimal("100.00")));
    }

    // ===== per-piece commodities =====

    @Test
    void customs_perPieceFilter_byBoxSeq() throws Exception {
        // 3 commodities, 2 pieces:
        //   - "Socks"  boxSeq=1 → only piece 1
        //   - "Shoes"  boxSeq=2 → only piece 2
        //   - "Label"  boxSeq=null → both pieces
        ShipmentRequestDTO req = baseIntlMps(2, List.of(
                commodity("Socks", 1), commodity("Shoes", 2), commodity("Label", null)));
        // Piece 1
        JsonNode piece1 = mapper.readTree(connector.buildSeraCreateLabelBody(
                req, req.effectivePackages().get(0), 1, 2));
        JsonNode items1 = piece1.path("customs").path("customs_items");
        assertEquals(2, items1.size(), "piece 1 should see Socks + Label (not Shoes)");
        assertTrue(itemDescriptions(items1).containsAll(List.of("Socks", "Label")));
        assertFalse(itemDescriptions(items1).contains("Shoes"));
        // Piece 2
        JsonNode piece2 = mapper.readTree(connector.buildSeraCreateLabelBody(
                req, req.effectivePackages().get(1), 2, 2));
        JsonNode items2 = piece2.path("customs").path("customs_items");
        assertEquals(2, items2.size(), "piece 2 should see Shoes + Label (not Socks)");
        assertTrue(itemDescriptions(items2).containsAll(List.of("Shoes", "Label")));
        assertFalse(itemDescriptions(items2).contains("Socks"));
    }

    @Test
    void customs_singlePiece_emitsAllCommodities_regardlessOfBoxSeq() throws Exception {
        // Non-MPS short-circuit: boxSeq ignored, every commodity appears.
        ShipmentRequestDTO req = baseIntlMps(1, List.of(
                commodity("Socks", 1), commodity("Shoes", 2), commodity("Label", null)));
        JsonNode root = mapper.readTree(connector.buildSeraCreateLabelBody(
                req, req.effectivePackages().get(0), 1, 1));
        JsonNode items = root.path("customs").path("customs_items");
        assertEquals(3, items.size(), "single-piece: all commodities regardless of boxSeq");
    }

    // ===== _x_piece_context removed =====

    @Test
    void bodyNoLongerCarriesXPieceContextField() throws Exception {
        ShipmentRequestDTO req = baseRequest(3);
        String json = connector.buildSeraCreateLabelBody(
                req, req.effectivePackages().get(0), 2, 3);
        JsonNode root = mapper.readTree(json);
        assertTrue(root.path("_x_piece_context").isMissingNode(),
                "piece context moved to X-Piece-Context HTTP header, must not appear in body");
    }

    // ===== helpers =====

    private List<String> itemDescriptions(JsonNode arr) {
        List<String> out = new java.util.ArrayList<>();
        arr.forEach(e -> out.add(e.path("item_description").asText()));
        return out;
    }

    private CustomsCommodityDTO commodity(String desc, Integer boxSeq) {
        return CustomsCommodityDTO.builder()
                .description(desc).quantity(1)
                .unitValue(new BigDecimal("10.00")).unitWeight(new BigDecimal("0.3"))
                .hsCode("61091012").countryOfOrigin("CN")
                .sku("SKU-" + desc).boxSeq(boxSeq).build();
    }

    private ShipmentRequestDTO baseRequest(int pieceCount) {
        ShipmentRequestDTO req = new ShipmentRequestDTO();
        req.setReferenceNumber("ORD-MPS-" + System.nanoTime());
        req.setShipperName("Acme"); req.setShipperAddressLine1("1 Way");
        req.setShipperCity("LOU"); req.setShipperState("KY");
        req.setShipperPostalCode("40209"); req.setShipperCountryCode("US");
        req.setRecipientName("Dest"); req.setRecipientAddressLine1("1 Rd");
        req.setRecipientCity("City"); req.setRecipientState("CA");
        req.setRecipientPostalCode("94101"); req.setRecipientCountryCode("US");
        req.setServiceType("usps_priority_mail");
        req.setWeight(new BigDecimal("1.0"));
        req.setWeightUnit("kg");
        java.util.List<PackageDetailDTO> pkgs = new java.util.ArrayList<>();
        for (int i = 0; i < pieceCount; i++) {
            pkgs.add(PackageDetailDTO.builder()
                    .weight(new BigDecimal("1.0")).weightUnit("kg")
                    .packageType("package").build());
        }
        req.setPackages(pkgs);
        req.setInsuredValueCurrency("USD");
        return req;
    }

    private ShipmentRequestDTO baseIntlMps(int pieceCount, List<CustomsCommodityDTO> commodities) {
        ShipmentRequestDTO req = baseRequest(pieceCount);
        req.setRecipientCountryCode("FR");
        req.setRecipientState("");
        req.setRecipientPostalCode("75000");
        req.setServiceType("usps_priority_mail_international");
        req.setIntl(IntlShipmentBlockDTO.builder()
                .international(true)
                .incoterms("DDU")
                .reasonForExport("SALE")
                .customsCurrency("EUR")
                .weightUnit("kg")
                .commodities(new java.util.ArrayList<>(commodities))
                .build());
        return req;
    }
}
