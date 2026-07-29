package com.multiship.backend.service;

import com.multiship.backend.dto.CustomsCommodityDTO;
import com.multiship.backend.dto.IntlShipmentBlockDTO;
import com.multiship.backend.dto.ShipmentRequestDTO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HS code shape check on {@link IntlShipmentValidator}. Separate class from
 * IntlShipmentValidatorTest so the HS-specific test names read cleanly.
 */
class HsCodeValidationTest {

    private static CustomsCommodityDTO commodity(String hsCode) {
        return CustomsCommodityDTO.builder()
                .description("Widget")
                .hsCode(hsCode)
                .countryOfOrigin("US")
                .quantity(1)
                .unitValue(new BigDecimal("10.00"))
                .build();
    }

    private static ShipmentRequestDTO withCommodity(CustomsCommodityDTO c) {
        return ShipmentRequestDTO.builder()
                .intl(IntlShipmentBlockDTO.builder()
                        .international(true)
                        .incoterms("DDP")
                        .customsCurrency("USD")
                        .customsTotalValue(new BigDecimal("10.00"))
                        .commodities(List.of(c))
                        .build())
                .build();
    }

    private static boolean hasHsError(ShipmentRequestDTO request) {
        return IntlShipmentValidator.validate(request).stream()
                .anyMatch(e -> IntlShipmentValidator.CODE_BAD_HS_CODE.equals(e.code()));
    }

    @Test
    void sixDigitCodeAccepted() {
        assertFalse(hasHsError(withCommodity(commodity("610462"))));
    }

    @Test
    void tenDigitCodeAccepted() {
        assertFalse(hasHsError(withCommodity(commodity("6104623000"))));
    }

    @Test
    void dotSeparatedCodeAccepted() {
        // Common human-readable form — the validator strips dots.
        assertFalse(hasHsError(withCommodity(commodity("6104.62.20"))));
    }

    @Test
    void spaceSeparatedCodeAccepted() {
        assertFalse(hasHsError(withCommodity(commodity("6104 62 20"))));
    }

    @Test
    void hyphenSeparatedCodeAccepted() {
        assertFalse(hasHsError(withCommodity(commodity("6104-62-20"))));
    }

    @Test
    void fiveDigitCodeRejected() {
        assertTrue(hasHsError(withCommodity(commodity("61046"))));
    }

    @Test
    void elevenDigitCodeRejected() {
        assertTrue(hasHsError(withCommodity(commodity("61046230001"))));
    }

    @Test
    void alphaCharactersRejected() {
        assertTrue(hasHsError(withCommodity(commodity("6104AB"))));
    }

    @Test
    void blankHsCodeDoesNotAddHsError() {
        // Blank HS surfaces via the missing-fields check, not the shape check
        // — this test guards against double-reporting the same problem.
        assertFalse(hasHsError(withCommodity(commodity(""))));
        assertFalse(hasHsError(withCommodity(commodity(null))));
    }

    @Test
    void badHsIsSeparateFromMissingFieldsError() {
        // A line that's otherwise complete but has a bad HS code should
        // surface CODE_BAD_HS_CODE without CODE_ITEM_INCOMPLETE.
        var errors = IntlShipmentValidator.validate(withCommodity(commodity("XX")));
        assertTrue(errors.stream()
                .anyMatch(e -> IntlShipmentValidator.CODE_BAD_HS_CODE.equals(e.code())));
        assertFalse(errors.stream()
                .anyMatch(e -> IntlShipmentValidator.CODE_ITEM_INCOMPLETE.equals(e.code())));
    }
}
