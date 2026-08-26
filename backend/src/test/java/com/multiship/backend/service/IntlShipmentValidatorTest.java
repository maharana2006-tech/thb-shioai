package com.multiship.backend.service;

import com.multiship.backend.dto.CustomsCommodityDTO;
import com.multiship.backend.dto.IntlShipmentBlockDTO;
import com.multiship.backend.dto.ShipmentRequestDTO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioral tests documenting what the validator rejects vs accepts. Each
 * negative test names the error code so if we ever reword messages we don't
 * silently drop a check.
 */
class IntlShipmentValidatorTest {

    private static CustomsCommodityDTO validCommodity() {
        return CustomsCommodityDTO.builder()
                .description("Widget")
                .hsCode("6104.62.20")
                .countryOfOrigin("US")
                .quantity(2)
                .unitValue(new BigDecimal("15.00"))
                .build();
    }

    private static IntlShipmentBlockDTO.IntlShipmentBlockDTOBuilder validIntl() {
        return IntlShipmentBlockDTO.builder()
                .international(true)
                .incoterms("DDP")
                .customsCurrency("USD")
                .customsTotalValue(new BigDecimal("30.00"))
                .reasonForExport("SALE")
                .commodities(List.of(validCommodity()));
    }

    private static ShipmentRequestDTO requestWith(IntlShipmentBlockDTO intl) {
        return ShipmentRequestDTO.builder().intl(intl).build();
    }

    @Test
    void domesticRequestNeverGeneratesErrors() {
        assertTrue(IntlShipmentValidator.validate(
                ShipmentRequestDTO.builder().build()).isEmpty());
        assertTrue(IntlShipmentValidator.validate(
                requestWith(IntlShipmentBlockDTO.builder().international(false).build())).isEmpty());
    }

    @Test
    void fullyValidBlockPassesCleanly() {
        assertTrue(IntlShipmentValidator.validate(requestWith(validIntl().build())).isEmpty());
    }

    @Test
    void emptyCommoditiesRejected() {
        List<IntlShipmentValidator.ValidationError> errors = IntlShipmentValidator.validate(
                requestWith(validIntl().commodities(List.of()).build()));
        assertEquals(IntlShipmentValidator.CODE_NO_COMMODITIES, errors.get(0).code());
    }

    /**
     * Sprint 52 — no single carrier accepts > 999 commodity lines per
     * shipment. Fail-fast at the validator with an actionable message
     * before the carrier RTT so the operator remodels the order.
     */
    @Test
    void tooManyCommoditiesRejected() {
        java.util.List<CustomsCommodityDTO> huge = new java.util.ArrayList<>();
        for (int i = 0; i < 1000; i++) huge.add(validCommodity());
        List<IntlShipmentValidator.ValidationError> errors = IntlShipmentValidator.validate(
                requestWith(validIntl().commodities(huge).build()));
        assertTrue(errors.stream().anyMatch(
                e -> IntlShipmentValidator.CODE_TOO_MANY_COMMODITIES.equals(e.code())),
                "1000 commodities should raise CODE_TOO_MANY_COMMODITIES");
    }

    @Test
    void ninetyNineOneCommoditiesAccepted() {
        // Boundary — hard ceiling is 999 lines.
        java.util.List<CustomsCommodityDTO> exactlyMax = new java.util.ArrayList<>();
        for (int i = 0; i < 999; i++) exactlyMax.add(validCommodity());
        List<IntlShipmentValidator.ValidationError> errors = IntlShipmentValidator.validate(
                requestWith(validIntl().commodities(exactlyMax).build()));
        assertTrue(errors.stream().noneMatch(
                e -> IntlShipmentValidator.CODE_TOO_MANY_COMMODITIES.equals(e.code())),
                "999 commodities is at the cap and must pass");
    }

    @Test
    void incompleteCommodityNamesMissingFields() {
        CustomsCommodityDTO bad = CustomsCommodityDTO.builder()
                .description("")
                .quantity(0)
                .unitValue(BigDecimal.ZERO)
                .build();
        List<IntlShipmentValidator.ValidationError> errors = IntlShipmentValidator.validate(
                requestWith(validIntl().commodities(List.of(bad)).build()));
        assertNotNull(errors.stream()
                .filter(e -> IntlShipmentValidator.CODE_ITEM_INCOMPLETE.equals(e.code()))
                .findFirst().orElse(null));
    }

    @Test
    void zeroInvoiceRejected() {
        List<IntlShipmentValidator.ValidationError> errors = IntlShipmentValidator.validate(
                requestWith(validIntl().customsTotalValue(BigDecimal.ZERO).build()));
        assertTrue(errors.stream().anyMatch(e -> IntlShipmentValidator.CODE_INVOICE_ZERO.equals(e.code())));
    }

    @Test
    void unknownIncotermsRejected() {
        List<IntlShipmentValidator.ValidationError> errors = IntlShipmentValidator.validate(
                requestWith(validIntl().incoterms("FOB").build()));
        assertTrue(errors.stream().anyMatch(e -> IntlShipmentValidator.CODE_BAD_INCOTERMS.equals(e.code())));
    }

    @Test
    void ddpDapDduAllAccepted() {
        for (String i : List.of("DDP", "DAP", "DDU")) {
            assertTrue(IntlShipmentValidator.validate(
                    requestWith(validIntl().incoterms(i).build())).isEmpty(),
                    "Incoterm " + i + " should be accepted");
        }
    }

    @Test
    void nonThreeLetterCurrencyRejected() {
        List<IntlShipmentValidator.ValidationError> errors = IntlShipmentValidator.validate(
                requestWith(validIntl().customsCurrency("US").build()));
        assertTrue(errors.stream().anyMatch(e -> IntlShipmentValidator.CODE_BAD_CURRENCY.equals(e.code())));
    }

    @Test
    void thirdPartyDutyWithoutAccountRejected() {
        List<IntlShipmentValidator.ValidationError> errors = IntlShipmentValidator.validate(
                requestWith(validIntl().dutyBillTo("THIRD_PARTY").dutyAccount(null).build()));
        assertTrue(errors.stream().anyMatch(e -> IntlShipmentValidator.CODE_DUTY_ACCOUNT_MISSING.equals(e.code())));
    }

    @Test
    void thirdPartyDutyWithAccountPasses() {
        assertTrue(IntlShipmentValidator.validate(
                requestWith(validIntl().dutyBillTo("THIRD_PARTY").dutyAccount("A12345").build()))
                .isEmpty());
    }

    @Test
    void concatenatedMessageContainsBulletPerError() {
        var errors = IntlShipmentValidator.validate(
                requestWith(validIntl().incoterms("FOB").customsCurrency("US").build()));
        String msg = IntlShipmentValidator.toMessage(errors);
        assertTrue(msg.contains("International shipment failed validation"));
        assertTrue(msg.contains(" • "));
    }

    // ===== UPS-9 — VALID_REASON aligned with resolver's 8-value enum =====

    @Test
    void merchandiseReasonIsValid() {
        // Pre-UPS-9, MERCHANDISE (valid resolver enum value) failed validation
        // with CODE_BAD_REASON because the validator's set was only 6 values.
        assertTrue(IntlShipmentValidator.validate(
                requestWith(validIntl().reasonForExport("MERCHANDISE").build()))
                .isEmpty(),
                "MERCHANDISE is a valid resolver purpose — validator must accept it");
    }

    @Test
    void personalUseReasonIsValid() {
        assertTrue(IntlShipmentValidator.validate(
                requestWith(validIntl().reasonForExport("PERSONAL_USE").build()))
                .isEmpty());
    }

    @Test
    void repairAndReturnReasonIsValid() {
        assertTrue(IntlShipmentValidator.validate(
                requestWith(validIntl().reasonForExport("REPAIR_AND_RETURN").build()))
                .isEmpty());
    }

    @Test
    void unknownReasonStillRejected() {
        // Regression guard — the enum extension didn't loosen the check for
        // genuinely bad values. GARBAGE isn't in the resolver's 8-value set
        // so the validator still rejects with CODE_BAD_REASON.
        var errors = IntlShipmentValidator.validate(
                requestWith(validIntl().reasonForExport("GARBAGE").build()));
        assertTrue(errors.stream().anyMatch(e -> IntlShipmentValidator.CODE_BAD_REASON.equals(e.code())));
    }
}
