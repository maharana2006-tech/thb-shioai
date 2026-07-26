package com.multiship.backend.service;

import com.multiship.backend.dto.DangerousCommodityDTO;
import com.multiship.backend.dto.DangerousGoodsBlockDTO;
import com.multiship.backend.dto.ShipmentRequestDTO;
import com.multiship.backend.service.IntlShipmentValidator.ValidationError;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards {@link DangerousGoodsValidator}. Same shape as
 * IntlShipmentValidatorTest — one file, one method per case.
 */
class DangerousGoodsValidatorTest {

    private static DangerousCommodityDTO validLithium() {
        return DangerousCommodityDTO.builder()
                .unNumber("UN3480")
                .properShippingName("Lithium ion batteries")
                .hazardClass("9")
                .packingGroup("II")
                .quantity(new BigDecimal("2.5"))
                .quantityUnit("KG")
                .packageCount(1)
                .build();
    }

    private static ShipmentRequestDTO withDg(DangerousGoodsBlockDTO dg) {
        return ShipmentRequestDTO.builder()
                .dangerousGoods(dg)
                .build();
    }

    private static DangerousGoodsBlockDTO validBlock() {
        return DangerousGoodsBlockDTO.builder()
                .regulationSet("IATA")
                .accessibility("INACCESSIBLE")
                .emergencyContactName("Chem Response Ltd")
                .emergencyContactPhone("+1-800-424-9300")
                .signatoryName("Jane Doe")
                .signatoryTitle("Compliance Officer")
                .commodities(List.of(validLithium()))
                .build();
    }

    @Test
    void nullBlockPassesValidation() {
        assertTrue(DangerousGoodsValidator.validate(
                ShipmentRequestDTO.builder().build()).isEmpty(),
                "Domestic non-hazmat shipments must not surface DG errors");
    }

    @Test
    void nullRequestPassesValidation() {
        assertTrue(DangerousGoodsValidator.validate(null).isEmpty());
    }

    @Test
    void fullyPopulatedBlockPassesValidation() {
        List<ValidationError> errors = DangerousGoodsValidator.validate(withDg(validBlock()));
        assertTrue(errors.isEmpty(), "Expected no errors, got: " + errors);
    }

    @Test
    void missingRegulationSetFlagged() {
        DangerousGoodsBlockDTO dg = validBlock();
        dg.setRegulationSet(null);
        List<ValidationError> errors = DangerousGoodsValidator.validate(withDg(dg));
        assertTrue(errors.stream().anyMatch(e ->
                DangerousGoodsValidator.CODE_BAD_REGULATION.equals(e.code())));
    }

    @Test
    void unrecognisedRegulationSetFlagged() {
        DangerousGoodsBlockDTO dg = validBlock();
        dg.setRegulationSet("IMDG");  // Maritime — not in our matrix
        List<ValidationError> errors = DangerousGoodsValidator.validate(withDg(dg));
        assertTrue(errors.stream().anyMatch(e ->
                DangerousGoodsValidator.CODE_BAD_REGULATION.equals(e.code())));
    }

    @Test
    void badAccessibilityFlagged() {
        DangerousGoodsBlockDTO dg = validBlock();
        dg.setAccessibility("MAYBE");
        List<ValidationError> errors = DangerousGoodsValidator.validate(withDg(dg));
        assertTrue(errors.stream().anyMatch(e ->
                DangerousGoodsValidator.CODE_BAD_REGULATION.equals(e.code())
                && e.message().contains("Accessibility")));
    }

    @Test
    void blankAccessibilityIsAllowed() {
        // Optional field — blank/null means the caller left it up to the carrier default.
        DangerousGoodsBlockDTO dg = validBlock();
        dg.setAccessibility(null);
        assertTrue(DangerousGoodsValidator.validate(withDg(dg)).isEmpty());
    }

    @Test
    void missingEmergencyContactFlagged() {
        DangerousGoodsBlockDTO dg = validBlock();
        dg.setEmergencyContactPhone(null);
        List<ValidationError> errors = DangerousGoodsValidator.validate(withDg(dg));
        assertTrue(errors.stream().anyMatch(e ->
                DangerousGoodsValidator.CODE_MISSING_CONTACT.equals(e.code())));
    }

    @Test
    void missingSignatoryFlagged() {
        DangerousGoodsBlockDTO dg = validBlock();
        dg.setSignatoryName(null);
        List<ValidationError> errors = DangerousGoodsValidator.validate(withDg(dg));
        assertTrue(errors.stream().anyMatch(e ->
                DangerousGoodsValidator.CODE_MISSING_SIGNATORY.equals(e.code())));
    }

    @Test
    void emptyCommoditiesListFlagged() {
        DangerousGoodsBlockDTO dg = validBlock();
        dg.setCommodities(List.of());
        List<ValidationError> errors = DangerousGoodsValidator.validate(withDg(dg));
        assertTrue(errors.stream().anyMatch(e ->
                DangerousGoodsValidator.CODE_NO_COMMODITIES.equals(e.code())));
    }

    @Test
    void malformedUnNumberFlagged() {
        DangerousCommodityDTO c = validLithium();
        c.setUnNumber("3480");  // Missing "UN" prefix
        DangerousGoodsBlockDTO dg = validBlock();
        dg.setCommodities(List.of(c));
        assertTrue(DangerousGoodsValidator.validate(withDg(dg)).stream()
                .anyMatch(e -> DangerousGoodsValidator.CODE_BAD_UN_NUMBER.equals(e.code())));
    }

    @Test
    void missingProperShippingNameFlagged() {
        DangerousCommodityDTO c = validLithium();
        c.setProperShippingName(null);
        DangerousGoodsBlockDTO dg = validBlock();
        dg.setCommodities(List.of(c));
        assertTrue(DangerousGoodsValidator.validate(withDg(dg)).stream()
                .anyMatch(e -> DangerousGoodsValidator.CODE_MISSING_SHIPPING_NAME.equals(e.code())));
    }

    @Test
    void hazardClassSubclassAcceptedByPattern() {
        DangerousCommodityDTO c = validLithium();
        c.setHazardClass("4.1");
        DangerousGoodsBlockDTO dg = validBlock();
        dg.setCommodities(List.of(c));
        assertTrue(DangerousGoodsValidator.validate(withDg(dg)).isEmpty());
    }

    @Test
    void badHazardClassFlagged() {
        DangerousCommodityDTO c = validLithium();
        c.setHazardClass("X");
        DangerousGoodsBlockDTO dg = validBlock();
        dg.setCommodities(List.of(c));
        assertTrue(DangerousGoodsValidator.validate(withDg(dg)).stream()
                .anyMatch(e -> DangerousGoodsValidator.CODE_BAD_HAZARD_CLASS.equals(e.code())));
    }

    @Test
    void badPackingGroupFlagged() {
        DangerousCommodityDTO c = validLithium();
        c.setPackingGroup("IV");  // No such thing
        DangerousGoodsBlockDTO dg = validBlock();
        dg.setCommodities(List.of(c));
        assertTrue(DangerousGoodsValidator.validate(withDg(dg)).stream()
                .anyMatch(e -> DangerousGoodsValidator.CODE_BAD_PACKING_GROUP.equals(e.code())));
    }

    @Test
    void nullPackingGroupIsAllowed() {
        // Class 1 explosives / Class 7 radioactives don't have a packing group.
        DangerousCommodityDTO c = validLithium();
        c.setPackingGroup(null);
        DangerousGoodsBlockDTO dg = validBlock();
        dg.setCommodities(List.of(c));
        assertTrue(DangerousGoodsValidator.validate(withDg(dg)).isEmpty());
    }

    @Test
    void zeroQuantityFlagged() {
        DangerousCommodityDTO c = validLithium();
        c.setQuantity(BigDecimal.ZERO);
        DangerousGoodsBlockDTO dg = validBlock();
        dg.setCommodities(List.of(c));
        assertTrue(DangerousGoodsValidator.validate(withDg(dg)).stream()
                .anyMatch(e -> DangerousGoodsValidator.CODE_ZERO_QUANTITY.equals(e.code())));
    }

    @Test
    void multipleErrorsAccumulate() {
        DangerousCommodityDTO c = validLithium();
        c.setUnNumber("nope");
        c.setHazardClass("");
        c.setQuantity(null);
        DangerousGoodsBlockDTO dg = validBlock();
        dg.setEmergencyContactPhone(null);
        dg.setSignatoryName(null);
        dg.setCommodities(List.of(c));

        List<ValidationError> errors = DangerousGoodsValidator.validate(withDg(dg));
        // 2 block-level (contact, signatory) + 3 commodity-level = 5.
        assertEquals(5, errors.size(),
                "Expected 5 errors, got " + errors.size() + ": " + errors);
    }
}
