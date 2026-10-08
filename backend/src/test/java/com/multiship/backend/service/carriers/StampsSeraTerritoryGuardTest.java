package com.multiship.backend.service.carriers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.dto.CustomsCommodityDTO;
import com.multiship.backend.dto.IntlShipmentBlockDTO;
import com.multiship.backend.dto.PackageDetailDTO;
import com.multiship.backend.dto.ShipmentRequestDTO;
import com.multiship.backend.util.UsTerritoryNormalizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** PR-T11 — pins the US-territory + military customs-guard contract on
 *  the SERA label-build path. Protects against the T-TR1 anti-pattern
 *  (operator flags US→PR as intl, SERA 400s) and the T-TR2 silent
 *  omission (APO/FPO parcel without CN22 missing — WARN at least). */
class StampsSeraTerritoryGuardTest {

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

    // ===== UsTerritoryNormalizer.isMilitaryState =====

    @Test
    void militaryStateCodesRecognised() {
        assertTrue(UsTerritoryNormalizer.isMilitaryState("AA"));
        assertTrue(UsTerritoryNormalizer.isMilitaryState("AE"));
        assertTrue(UsTerritoryNormalizer.isMilitaryState("AP"));
        assertTrue(UsTerritoryNormalizer.isMilitaryState("ae"));
        assertTrue(UsTerritoryNormalizer.isMilitaryState("  AP  "));
    }

    @Test
    void nonMilitaryStateCodesRejected() {
        assertFalse(UsTerritoryNormalizer.isMilitaryState("CA"));
        assertFalse(UsTerritoryNormalizer.isMilitaryState("NY"));
        assertFalse(UsTerritoryNormalizer.isMilitaryState("PR"));
        assertFalse(UsTerritoryNormalizer.isMilitaryState(null));
        assertFalse(UsTerritoryNormalizer.isMilitaryState(""));
    }

    // ===== customs-emit guards on buildSeraCreateLabelBody =====

    @Test
    void customsOmittedForUsTerritoryRecipient() throws Exception {
        // US → PR with intl.isReadyForCarrier() = true should still
        // DROP the customs block (USPS treats PR as domestic; SERA
        // would reject with 800000 if we sent customs).
        ShipmentRequestDTO req = intlShipmentToUsTerritory("PR");
        String json = connector.buildSeraCreateLabelBody(
                req, req.effectivePackages().get(0), 1, 1);
        JsonNode root = mapper.readTree(json);
        assertTrue(root.path("customs").isMissingNode() || root.path("customs").isNull(),
                "US-territory destinations must NOT emit customs (USPS domestic lane)");
    }

    @Test
    void customsOmittedForEveryUsTerritory() throws Exception {
        for (String terr : new String[]{"PR", "VI", "GU", "AS", "MP", "UM"}) {
            ShipmentRequestDTO req = intlShipmentToUsTerritory(terr);
            String json = connector.buildSeraCreateLabelBody(
                    req, req.effectivePackages().get(0), 1, 1);
            JsonNode root = mapper.readTree(json);
            assertTrue(root.path("customs").isMissingNode() || root.path("customs").isNull(),
                    "territory " + terr + " must drop customs block");
        }
    }

    @Test
    void customsEmittedForTrueIntlDestination() throws Exception {
        // Sanity check — US → CA with intl ready still emits customs.
        ShipmentRequestDTO req = intlShipmentToUsTerritory("PR");
        req.setRecipientCountryCode("CA");
        req.setRecipientState("ON");
        String json = connector.buildSeraCreateLabelBody(
                req, req.effectivePackages().get(0), 1, 1);
        JsonNode root = mapper.readTree(json);
        assertTrue(root.path("customs").isObject(),
                "true intl destination must emit customs block");
    }

    @Test
    void militaryStateWithoutIntlBlockDoesNotEmitCustoms() throws Exception {
        // APO without intl block → no customs on wire (SERA has no slot
        // for a "customs form required but no commodities" signal).
        // Operator sees the WARN log; parcel ships but may be rejected
        // at acceptance.
        ShipmentRequestDTO req = baseDomesticToAPO("AE");
        String json = connector.buildSeraCreateLabelBody(
                req, req.effectivePackages().get(0), 1, 1);
        JsonNode root = mapper.readTree(json);
        assertTrue(root.path("customs").isMissingNode() || root.path("customs").isNull(),
                "APO without intl block → customs omitted (WARN logged, not asserted here)");
    }

    @Test
    void domesticShipmentUnchanged() throws Exception {
        // US → CA (California, not Canada) — ordinary domestic lane,
        // customs not emitted, no WARN.
        ShipmentRequestDTO req = baseDomesticToAPO("CA");
        String json = connector.buildSeraCreateLabelBody(
                req, req.effectivePackages().get(0), 1, 1);
        JsonNode root = mapper.readTree(json);
        assertTrue(root.path("customs").isMissingNode() || root.path("customs").isNull());
    }

    // ===== helpers =====

    private ShipmentRequestDTO intlShipmentToUsTerritory(String territoryState) {
        ShipmentRequestDTO req = baseShipment();
        req.setRecipientCountryCode("US");
        req.setRecipientState(territoryState);
        req.setRecipientPostalCode("00901"); // Puerto Rico-shape ZIP
        // Fully-ready intl block as if operator mistakenly flagged it as intl.
        req.setIntl(IntlShipmentBlockDTO.builder()
                .international(true)
                .incoterms("DDU")
                .reasonForExport("SALE")
                .customsCurrency("USD")
                .weightUnit("kg")
                .commodities(new java.util.ArrayList<>(List.of(CustomsCommodityDTO.builder()
                        .description("Widget").quantity(1)
                        .unitValue(new BigDecimal("10.00")).unitWeight(new BigDecimal("0.5"))
                        .hsCode("61091012").countryOfOrigin("CN")
                        .sku("SKU-1").build())))
                .build());
        return req;
    }

    private ShipmentRequestDTO baseDomesticToAPO(String state) {
        ShipmentRequestDTO req = baseShipment();
        req.setRecipientCountryCode("US");
        req.setRecipientState(state);
        req.setRecipientPostalCode("09123");
        return req;
    }

    private ShipmentRequestDTO baseShipment() {
        ShipmentRequestDTO req = new ShipmentRequestDTO();
        req.setReferenceNumber("ORD-TR-" + System.nanoTime());
        req.setShipperName("Acme"); req.setShipperAddressLine1("1 Way");
        req.setShipperCity("LOU"); req.setShipperState("KY");
        req.setShipperPostalCode("40209"); req.setShipperCountryCode("US");
        req.setRecipientName("Dest"); req.setRecipientAddressLine1("1 Rd");
        req.setRecipientCity("City");
        req.setServiceType("usps_priority_mail");
        req.setWeight(new BigDecimal("1.0"));
        req.setWeightUnit("kg");
        req.setPackages(List.of(PackageDetailDTO.builder()
                .weight(new BigDecimal("1.0")).weightUnit("kg")
                .packageType("package").build()));
        return req;
    }
}
