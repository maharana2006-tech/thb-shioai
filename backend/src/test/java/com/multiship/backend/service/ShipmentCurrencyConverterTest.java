package com.multiship.backend.service;

import com.multiship.backend.dto.CustomsCommodityDTO;
import com.multiship.backend.dto.IntlShipmentBlockDTO;
import com.multiship.backend.dto.PackageDetailDTO;
import com.multiship.backend.dto.ShipmentRequestDTO;
import com.multiship.backend.model.CarrierAccountRef;
import com.multiship.backend.service.ShipmentDefaultsResolver.ShipmentDefaultsException;
import com.multiship.backend.service.fx.FxRateService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * F6-D unit coverage for {@link ShipmentCurrencyConverter}. Uses a mocked
 * {@link FxRateService} so tests are hermetic and don't need to hit the ECB
 * feed — the converter's own logic (target selection, per-field iteration,
 * fail-closed on empty rate) is the surface under test, not the FX itself.
 *
 * <p>The stubbed rate throughout most tests is EUR → USD @ 1.10 to make
 * verification arithmetic obvious.
 */
@ExtendWith(MockitoExtension.class)
class ShipmentCurrencyConverterTest {

    @Mock
    FxRateService fx;

    @InjectMocks
    ShipmentCurrencyConverter converter;

    // ===== target-currency resolution =====

    @Test
    void skips_when_source_and_target_match() {
        // Same source & target => no FX read, no field change. USD is the
        // resolved home for UPS when the account carries no override.
        ShipmentRequestDTO dto = usdRequestNoIntl();
        dto.setDeclaredValueCurrency("USD");

        ShipmentRequestDTO out = converter.apply(dto, refWithCurrency(null));

        assertEquals(new BigDecimal("500"), out.getDeclaredValue());
        assertEquals("USD", out.getDeclaredValueCurrency());
        verify(fx, never()).convert(any(), any(), any());
    }

    @Test
    void account_currency_wins_over_carrier_home() {
        // Account overrides to GBP even though UPS's home is USD; source EUR
        // must convert to GBP, not USD.
        stubRate("EUR", "GBP", "0.85");
        ShipmentRequestDTO dto = eurRequestNoIntl(new BigDecimal("100"));

        ShipmentRequestDTO out = converter.apply(dto, refWithCurrency("GBP"));

        assertEquals(new BigDecimal("85.00"), out.getDeclaredValue());
        assertEquals("GBP", out.getDeclaredValueCurrency());
    }

    @Test
    void carrier_home_used_when_account_currency_null() {
        // Account row present but currency = null → per-carrier hardcode.
        // DHL's home is EUR, so a USD request converts down to EUR.
        stubRate("USD", "EUR", "0.90");
        ShipmentRequestDTO dto = usdRequestNoIntl();
        dto.setCarrierCode("DHL");

        ShipmentRequestDTO out = converter.apply(dto, refWithCurrency(null));

        assertEquals(new BigDecimal("450.00"), out.getDeclaredValue());
        assertEquals("EUR", out.getDeclaredValueCurrency());
    }

    @Test
    void null_account_falls_back_to_carrier_home() {
        // Platform / bulk-shopping path — no CarrierAccountRef available.
        // Converter must still resolve target from carrierCode.
        stubRate("EUR", "USD", "1.10");
        ShipmentRequestDTO dto = eurRequestNoIntl(new BigDecimal("100"));

        ShipmentRequestDTO out = converter.apply(dto, null);

        assertEquals(new BigDecimal("110.00"), out.getDeclaredValue());
        assertEquals("USD", out.getDeclaredValueCurrency());
    }

    @Test
    void carrier_home_covers_all_four_carriers() {
        // Sanity that USPS/STAMPS/UPS/FEDEX all target USD from EUR, DHL EUR.
        // Uses one shared stub for the USD legs.
        stubRate("EUR", "USD", "1.10");
        for (String usdCarrier : List.of("USPS", "STAMPS", "UPS", "FEDEX")) {
            ShipmentRequestDTO dto = eurRequestNoIntl(new BigDecimal("100"));
            dto.setCarrierCode(usdCarrier);
            ShipmentRequestDTO out = converter.apply(dto, null);
            assertEquals("USD", out.getDeclaredValueCurrency(),
                    usdCarrier + " should target USD");
            assertEquals(new BigDecimal("110.00"), out.getDeclaredValue(),
                    usdCarrier + " should convert 100 EUR to 110 USD");
        }
        // DHL should NOT touch the EUR request (same source & target).
        ShipmentRequestDTO dhl = eurRequestNoIntl(new BigDecimal("100"));
        dhl.setCarrierCode("DHL");
        ShipmentRequestDTO dhlOut = converter.apply(dhl, null);
        assertEquals("EUR", dhlOut.getDeclaredValueCurrency());
        assertEquals(new BigDecimal("100"), dhlOut.getDeclaredValue());
    }

    // ===== field iteration =====

    @Test
    void converts_declared_and_insured_together() {
        stubRate("EUR", "USD", "1.10");
        ShipmentRequestDTO dto = eurRequestNoIntl(new BigDecimal("500"));
        dto.setInsuredValue(new BigDecimal("200"));
        dto.setInsuredValueCurrency("EUR");

        ShipmentRequestDTO out = converter.apply(dto, refWithCurrency("USD"));

        assertEquals(new BigDecimal("550.00"), out.getDeclaredValue());
        assertEquals("USD", out.getDeclaredValueCurrency());
        assertEquals(new BigDecimal("220.00"), out.getInsuredValue());
        assertEquals("USD", out.getInsuredValueCurrency());
    }

    @Test
    void null_insured_stays_null_currency_unchanged() {
        // No insurance requested → don't clobber the currency field with the
        // target. Connectors that key off (insuredValue == null) must still
        // see "no insurance requested".
        stubRate("EUR", "USD", "1.10");
        ShipmentRequestDTO dto = eurRequestNoIntl(new BigDecimal("500"));
        // insuredValue and insuredValueCurrency both null

        ShipmentRequestDTO out = converter.apply(dto, refWithCurrency("USD"));

        assertNull(out.getInsuredValue());
        assertNull(out.getInsuredValueCurrency());
    }

    @Test
    void converts_per_package_declared_values() {
        stubRate("EUR", "USD", "1.10");
        ShipmentRequestDTO dto = eurRequestNoIntl(null);
        dto.setPackages(List.of(
                PackageDetailDTO.builder().sequenceNumber(1).declaredValue(new BigDecimal("300")).build(),
                PackageDetailDTO.builder().sequenceNumber(2).declaredValue(new BigDecimal("200")).build(),
                PackageDetailDTO.builder().sequenceNumber(3).declaredValue(null).build()));

        converter.apply(dto, refWithCurrency("USD"));

        assertEquals(new BigDecimal("330.00"), dto.getPackages().get(0).getDeclaredValue());
        assertEquals(new BigDecimal("220.00"), dto.getPackages().get(1).getDeclaredValue());
        assertNull(dto.getPackages().get(2).getDeclaredValue(),
                "null declaredValue must pass through untouched");
    }

    @Test
    void converts_commodities_and_recomputes_customs_total() {
        stubRate("EUR", "USD", "1.10");
        ShipmentRequestDTO dto = eurRequestWithIntl(
                List.of(
                        commodity(2, new BigDecimal("50")),   // line total 100
                        commodity(1, new BigDecimal("30"))),  // line total 30
                new BigDecimal("130"));                          // stale total pre-convert

        converter.apply(dto, refWithCurrency("USD"));

        IntlShipmentBlockDTO intl = dto.getIntl();
        assertEquals("USD", intl.getCustomsCurrency());
        assertEquals(new BigDecimal("55.00"), intl.getCommodities().get(0).getUnitValue());
        assertEquals(new BigDecimal("33.00"), intl.getCommodities().get(1).getUnitValue());
        // customsTotalValue recomputed post-conversion:
        //   2 * 55.00 + 1 * 33.00 = 143.00
        assertEquals(new BigDecimal("143.00"), intl.getCustomsTotalValue());
    }

    @Test
    void null_commodity_line_and_empty_list_are_no_ops() {
        // Sanity: null entries in commodities[] don't NPE. customsTotalValue
        // stays null when every line is missing quantity or unitValue.
        stubRate("EUR", "USD", "1.10");
        ShipmentRequestDTO dto = eurRequestWithIntl(List.of(), null);

        converter.apply(dto, refWithCurrency("USD"));

        assertEquals("USD", dto.getIntl().getCustomsCurrency());
        assertNull(dto.getIntl().getCustomsTotalValue(),
                "empty commodities → recomputed total is null");
    }

    // ===== failure mode =====

    @Test
    void throws_when_rate_unavailable() {
        // FX returns empty (rate feed down or unsupported code). Must throw
        // rather than silently omit the field or ship an unconverted value.
        stubRateEmpty("EUR", "USD");
        ShipmentRequestDTO dto = eurRequestNoIntl(new BigDecimal("500"));

        ShipmentDefaultsException ex = assertThrows(
                ShipmentDefaultsException.class,
                () -> converter.apply(dto, refWithCurrency("USD")));
        assertNotNull(ex.getMessage());
        // Message must name the field so the operator can trace which field
        // failed if the exception leaks into a manual review UI.
        assertEquals(true, ex.getMessage().contains("declaredValue"),
                "exception must name the failing field. got: " + ex.getMessage());
    }

    // ===== fixtures =====

    private void stubRate(String from, String to, String rate) {
        lenient().when(fx.convert(any(BigDecimal.class), eq(from), eq(to)))
                .thenAnswer(inv -> {
                    BigDecimal amt = inv.getArgument(0);
                    return Optional.of(amt.multiply(new BigDecimal(rate)).setScale(2, java.math.RoundingMode.HALF_UP));
                });
    }

    private void stubRateEmpty(String from, String to) {
        lenient().when(fx.convert(any(BigDecimal.class), eq(from), eq(to)))
                .thenReturn(Optional.empty());
    }

    private static CarrierAccountRef refWithCurrency(String currency) {
        CarrierAccountRef ref = new CarrierAccountRef();
        ref.setAccountNumber("A123");
        ref.setCarrierCode("UPS");
        ref.setCurrency(currency);
        return ref;
    }

    private static ShipmentRequestDTO usdRequestNoIntl() {
        return baseRequest("UPS")
                .declaredValue(new BigDecimal("500")).declaredValueCurrency("USD").build();
    }

    private static ShipmentRequestDTO eurRequestNoIntl(BigDecimal declared) {
        return baseRequest("UPS")
                .declaredValue(declared).declaredValueCurrency("EUR").build();
    }

    private static ShipmentRequestDTO eurRequestWithIntl(
            List<CustomsCommodityDTO> commodities, BigDecimal preConvertTotal) {
        IntlShipmentBlockDTO intl = IntlShipmentBlockDTO.builder()
                .international(true).incoterms("DAP")
                .customsCurrency("EUR").customsTotalValue(preConvertTotal)
                .commodities(new java.util.ArrayList<>(commodities))
                .build();
        return baseRequest("UPS")
                .declaredValue(preConvertTotal).declaredValueCurrency("EUR")
                .intl(intl).build();
    }

    private static CustomsCommodityDTO commodity(int qty, BigDecimal unit) {
        return CustomsCommodityDTO.builder()
                .description("Widget").quantity(qty).unitValue(unit)
                .countryOfOrigin("DE").build();
    }

    private static ShipmentRequestDTO.ShipmentRequestDTOBuilder baseRequest(String carrier) {
        return ShipmentRequestDTO.builder()
                .carrierCode(carrier).accountNumber("A1234567")
                .serviceType("STANDARD").packageType("YOUR_PACKAGING")
                .weight(new BigDecimal("2.5")).weightUnit("LB")
                .length(new BigDecimal("10")).width(new BigDecimal("10")).height(new BigDecimal("10"))
                .dimUnit("IN")
                .shipperName("Sender").shipperAddressLine1("1 A St")
                .shipperCity("Denver").shipperState("CO").shipperPostalCode("80202").shipperCountryCode("US")
                .recipientName("Recipient").recipientAddressLine1("2 B St")
                .recipientCity("London").recipientPostalCode("SW1A 1AA").recipientCountryCode("GB")
                .referenceNumber("PO-CUR-1");
    }
}
