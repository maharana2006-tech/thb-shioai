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

    // ===== US FTR §30.37 EEI gating — customs.eei.required =====
    // PR 3 — the inline US rule moved to UsFtr30_37Policy. These tests
    // now drive it via validatePolicies() with a registry that contains
    // just the US policy; behaviour is identical to the prior inline
    // implementation.

    /** Build a request with origin/dest countries so the EEI rule can fire. */
    private static ShipmentRequestDTO usToDe(IntlShipmentBlockDTO intl) {
        return ShipmentRequestDTO.builder()
                .shipperCountryCode("US")
                .recipientCountryCode("DE")
                .intl(intl).build();
    }

    /** Registry pre-loaded with just the US FTR policy. */
    private static com.multiship.backend.service.intl.ExportDeclarationPolicyRegistry usRegistry() {
        return new com.multiship.backend.service.intl.ExportDeclarationPolicyRegistry(
                java.util.List.of(new com.multiship.backend.service.intl.UsFtr30_37Policy()));
    }

    @Test
    void eeiRequiredAtOrAboveThresholdWithoutFtrOrAes() {
        List<IntlShipmentValidator.ValidationError> errors = IntlShipmentValidator.validatePolicies(
                usToDe(validIntl().customsTotalValue(new BigDecimal("2500.00")).build()),
                null, usRegistry());
        assertTrue(errors.stream().anyMatch(
                e -> IntlShipmentValidator.CODE_EEI_REQUIRED.equals(e.code())),
                "$2,500 USD US→DE with no FTR/AES must raise CODE_EEI_REQUIRED");
    }

    @Test
    void eeiSatisfiedByFtrExemption() {
        assertTrue(IntlShipmentValidator.validatePolicies(
                usToDe(validIntl()
                        .customsTotalValue(new BigDecimal("3000.00"))
                        .ftrExemption("NO_EEI_30_37_h")
                        .build()), null, usRegistry()).stream().noneMatch(
                                e -> IntlShipmentValidator.CODE_EEI_REQUIRED.equals(e.code())),
                "30.37(h) exemption at $3,000 satisfies the EEI rule");
    }

    @Test
    void eeiSatisfiedByAesCitation() {
        assertTrue(IntlShipmentValidator.validatePolicies(
                usToDe(validIntl()
                        .customsTotalValue(new BigDecimal("3000.00"))
                        .aesCitation("X20260101123456")
                        .build()), null, usRegistry()).stream().noneMatch(
                                e -> IntlShipmentValidator.CODE_EEI_REQUIRED.equals(e.code())),
                "AES ITN at $3,000 satisfies the EEI rule");
    }

    @Test
    void eeiNotRequiredUnderThreshold() {
        assertTrue(IntlShipmentValidator.validatePolicies(
                usToDe(validIntl().customsTotalValue(new BigDecimal("2499.99")).build()),
                null, usRegistry()).stream()
                .noneMatch(e -> IntlShipmentValidator.CODE_EEI_REQUIRED.equals(e.code())),
                "$2,499.99 sits just under the threshold — rule must not fire");
    }

    @Test
    void eeiNotRequiredForCanadaDestination() {
        // §30.36 is the Canada bilateral exemption — never gated by value here.
        assertTrue(IntlShipmentValidator.validatePolicies(
                ShipmentRequestDTO.builder()
                        .shipperCountryCode("US").recipientCountryCode("CA")
                        .intl(validIntl().customsTotalValue(new BigDecimal("5000.00")).build())
                        .build(), null, usRegistry()).stream()
                .noneMatch(e -> IntlShipmentValidator.CODE_EEI_REQUIRED.equals(e.code())),
                "US→CA is bilateral; rule must not fire regardless of value");
    }

    @Test
    void eeiNotRequiredForNonUsOrigin() {
        assertTrue(IntlShipmentValidator.validatePolicies(
                ShipmentRequestDTO.builder()
                        .shipperCountryCode("DE").recipientCountryCode("US")
                        .intl(validIntl().customsTotalValue(new BigDecimal("5000.00")).build())
                        .build(), null, usRegistry()).stream()
                .noneMatch(e -> IntlShipmentValidator.CODE_EEI_REQUIRED.equals(e.code())),
                "Non-US origin is out of scope for the US FTR policy");
    }

    @Test
    void eeiNotGatedOnNonUsdCurrency_whenFxAbsent() {
        // No FX plumbed → non-USD declarations skip the deterministic
        // check (safer than false-blocking on a broken/absent rate feed).
        assertTrue(IntlShipmentValidator.validatePolicies(
                usToDe(validIntl()
                        .customsCurrency("EUR")
                        .customsTotalValue(new BigDecimal("3000.00"))
                        .build()), null, usRegistry()).stream()
                .noneMatch(e -> IntlShipmentValidator.CODE_EEI_REQUIRED.equals(e.code())),
                "EUR-declared shipment must not fire the rule when no FX is available");
    }

    // ===== PR 2 — FX-normalized threshold via injected FxRateService =====

    /** Fixed-rate FX stub — enough for validator tests without pulling in Mockito. */
    private static com.multiship.backend.service.fx.FxRateService fixedRateFx(
            String from, String to, BigDecimal rate) {
        return new com.multiship.backend.service.fx.FxRateService() {
            @Override public java.util.Optional<BigDecimal> rate(String f, String t) {
                if (from.equalsIgnoreCase(f) && to.equalsIgnoreCase(t)) return java.util.Optional.of(rate);
                return java.util.Optional.empty();
            }
            @Override public java.util.Optional<BigDecimal> convert(BigDecimal amount, String f, String t) {
                return rate(f, t).map(r -> amount.multiply(r));
            }
            @Override public boolean supports(String currency) { return true; }
        };
    }

    /** FX stub whose rate feed is always down. */
    private static com.multiship.backend.service.fx.FxRateService fxOutage() {
        return new com.multiship.backend.service.fx.FxRateService() {
            @Override public java.util.Optional<BigDecimal> rate(String f, String t) { return java.util.Optional.empty(); }
            @Override public java.util.Optional<BigDecimal> convert(BigDecimal amount, String f, String t) { return java.util.Optional.empty(); }
            @Override public boolean supports(String currency) { return false; }
        };
    }

    @Test
    void eeiGatedOnEur3000_withFx_convertsOverThreshold() {
        // €3,000 EUR at 1 EUR = 1.08 USD → $3,240 USD → over threshold →
        // rule fires (same behaviour as a plain $3,000 USD shipment).
        var fx = fixedRateFx("EUR", "USD", new BigDecimal("1.08"));
        var errors = IntlShipmentValidator.validatePolicies(
                usToDe(validIntl()
                        .customsCurrency("EUR")
                        .customsTotalValue(new BigDecimal("3000.00"))
                        .build()), fx, usRegistry());
        assertTrue(errors.stream().anyMatch(
                e -> IntlShipmentValidator.CODE_EEI_REQUIRED.equals(e.code())),
                "€3,000 at 1.08 must convert to $3,240 USD and fire the rule");
    }

    @Test
    void eeiNotGated_onEur2300_withFx_convertsUnderThreshold() {
        // €2,300 EUR at 1 EUR = 1.08 USD → $2,484 USD → under threshold.
        var fx = fixedRateFx("EUR", "USD", new BigDecimal("1.08"));
        var errors = IntlShipmentValidator.validatePolicies(
                usToDe(validIntl()
                        .customsCurrency("EUR")
                        .customsTotalValue(new BigDecimal("2300.00"))
                        .build()), fx, usRegistry());
        assertTrue(errors.stream().noneMatch(
                e -> IntlShipmentValidator.CODE_EEI_REQUIRED.equals(e.code())),
                "€2,300 at 1.08 = $2,484 sits under the threshold");
    }

    @Test
    void eeiNotGated_onFxOutage_fallsThrough() {
        // Rate feed down → rule doesn't fire (safer than false-blocking).
        var fx = fxOutage();
        var errors = IntlShipmentValidator.validatePolicies(
                usToDe(validIntl()
                        .customsCurrency("GBP")
                        .customsTotalValue(new BigDecimal("5000.00"))
                        .build()), fx, usRegistry());
        assertTrue(errors.stream().noneMatch(
                e -> IntlShipmentValidator.CODE_EEI_REQUIRED.equals(e.code())),
                "FX outage must never turn into a false-positive block");
    }

    @Test
    void usdShipment_stillGated_whenFxProvided() {
        // FX plumbing doesn't change the USD path — policy short-circuits
        // on USD before ever consulting FX.
        var fx = fxOutage(); // even a broken fx doesn't matter for USD
        var errors = IntlShipmentValidator.validatePolicies(
                usToDe(validIntl().customsTotalValue(new BigDecimal("3000.00")).build()),
                fx, usRegistry());
        assertTrue(errors.stream().anyMatch(
                e -> IntlShipmentValidator.CODE_EEI_REQUIRED.equals(e.code())),
                "USD-native shipments must gate regardless of FX availability");
    }

    // ===== PR 3 — non-US corridors via registry =====

    private static com.multiship.backend.service.intl.ExportDeclarationPolicyRegistry allCorridors() {
        return new com.multiship.backend.service.intl.ExportDeclarationPolicyRegistry(java.util.List.of(
                new com.multiship.backend.service.intl.UsFtr30_37Policy(),
                new com.multiship.backend.service.intl.CaB13APolicy(),
                new com.multiship.backend.service.intl.GbCdsPolicy(),
                new com.multiship.backend.service.intl.AuEdnPolicy(),
                new com.multiship.backend.service.intl.JpDeclarationPolicy(),
                new com.multiship.backend.service.intl.InShippingBillPolicy()));
    }

    private static ShipmentRequestDTO from(String origin, String dest, IntlShipmentBlockDTO intl) {
        return ShipmentRequestDTO.builder()
                .shipperCountryCode(origin).recipientCountryCode(dest).intl(intl).build();
    }

    @Test
    void caB13A_firedAtCad2000_toDe() {
        // CAD-native, straight comparison — no FX needed.
        var intl = validIntl().customsCurrency("CAD").customsTotalValue(new BigDecimal("2000")).build();
        var errors = IntlShipmentValidator.validatePolicies(from("CA", "DE", intl), null, allCorridors());
        assertTrue(errors.stream().anyMatch(
                e -> IntlShipmentValidator.CODE_CA_B13A_REQUIRED.equals(e.code())),
                "CA→DE at CAD $2,000 must trigger B13A rule");
    }

    @Test
    void caB13A_notFiredForUsDestination() {
        // CA→US is exempt (continental bilateral).
        var intl = validIntl().customsCurrency("CAD").customsTotalValue(new BigDecimal("10000")).build();
        var errors = IntlShipmentValidator.validatePolicies(from("CA", "US", intl), null, allCorridors());
        assertTrue(errors.stream().noneMatch(
                e -> IntlShipmentValidator.CODE_CA_B13A_REQUIRED.equals(e.code())),
                "CA→US must never trigger B13A regardless of value");
    }

    @Test
    void caB13A_satisfiedByExportDeclarationReference() {
        var intl = validIntl().customsCurrency("CAD").customsTotalValue(new BigDecimal("5000"))
                .exportDeclarationReference("CA-B13A-2026-99999").build();
        var errors = IntlShipmentValidator.validatePolicies(from("CA", "DE", intl), null, allCorridors());
        assertTrue(errors.stream().noneMatch(
                e -> IntlShipmentValidator.CODE_CA_B13A_REQUIRED.equals(e.code())),
                "populated exportDeclarationReference must suppress the CA rule");
    }

    @Test
    void gbCds_firedAtGbp873_toUs() {
        var intl = validIntl().customsCurrency("GBP").customsTotalValue(new BigDecimal("873")).build();
        var errors = IntlShipmentValidator.validatePolicies(from("GB", "US", intl), null, allCorridors());
        assertTrue(errors.stream().anyMatch(
                e -> IntlShipmentValidator.CODE_GB_CDS_REQUIRED.equals(e.code())),
                "GB→US at £873 must trigger CDS rule");
    }

    @Test
    void auEdn_firedAtAud2000_toUs() {
        var intl = validIntl().customsCurrency("AUD").customsTotalValue(new BigDecimal("2000")).build();
        var errors = IntlShipmentValidator.validatePolicies(from("AU", "US", intl), null, allCorridors());
        assertTrue(errors.stream().anyMatch(
                e -> IntlShipmentValidator.CODE_AU_EDN_REQUIRED.equals(e.code())),
                "AU→US at AUD $2,000 must trigger EDN rule");
    }

    @Test
    void jpDeclaration_firedAt200000Yen() {
        var intl = validIntl().customsCurrency("JPY").customsTotalValue(new BigDecimal("200000")).build();
        var errors = IntlShipmentValidator.validatePolicies(from("JP", "US", intl), null, allCorridors());
        assertTrue(errors.stream().anyMatch(
                e -> IntlShipmentValidator.CODE_JP_DECLARATION_REQUIRED.equals(e.code())),
                "JP→US at ¥200,000 must trigger declaration rule");
    }

    @Test
    void inSb_firedForEveryExportRegardlessOfValue() {
        // IN policy has no value threshold.
        var intl = validIntl().customsCurrency("INR").customsTotalValue(new BigDecimal("100")).build();
        var errors = IntlShipmentValidator.validatePolicies(from("IN", "US", intl), null, allCorridors());
        assertTrue(errors.stream().anyMatch(
                e -> IntlShipmentValidator.CODE_IN_SB_REQUIRED.equals(e.code())),
                "IN→US at any value must trigger SB rule");
    }

    @Test
    void inSb_suppressedForDomesticIndiaShipment() {
        // Shouldn't fire on IN→IN even though the policy has no threshold.
        var intl = validIntl().customsCurrency("INR").customsTotalValue(new BigDecimal("100")).build();
        var errors = IntlShipmentValidator.validatePolicies(from("IN", "IN", intl), null, allCorridors());
        assertTrue(errors.stream().noneMatch(
                e -> IntlShipmentValidator.CODE_IN_SB_REQUIRED.equals(e.code())),
                "IN→IN is domestic; policy must not fire");
    }

    @Test
    void unknownOrigin_noRuleFires() {
        // No policy registered for BR — nothing fires.
        var intl = validIntl().customsCurrency("BRL").customsTotalValue(new BigDecimal("100000")).build();
        var errors = IntlShipmentValidator.validatePolicies(from("BR", "US", intl), null, allCorridors());
        assertTrue(errors.isEmpty(),
                "Unmapped origin BR must yield no errors — fallback is the generic advisory in the service layer");
    }
}
