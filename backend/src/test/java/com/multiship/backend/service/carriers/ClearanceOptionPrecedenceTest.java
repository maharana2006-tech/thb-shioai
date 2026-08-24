package com.multiship.backend.service.carriers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.dto.CustomsCommodityDTO;
import com.multiship.backend.dto.IntlShipmentBlockDTO;
import com.multiship.backend.dto.ShipmentRequestDTO;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F6-C regression tests — the account-level {@code clearanceOption}
 * (resolved by {@link com.multiship.backend.service.ShipmentDefaultsResolver}
 * from {@code CarrierAccountRef.clearanceOption}) now wins over the
 * per-customs-profile {@code dutyBillTo} in every consuming connector.
 *
 * <p>Pre-fix, the account-level clearance was orphaned — operators could set
 * it in the CarrierConnections drawer but no envelope ever consumed it. Now
 * connectors read {@code intl.getClearanceOption()} first, falling back to
 * {@code intl.getDutyBillTo()} (customs-profile source) if unset. Rationale:
 * account overrides say "THIS account bills X party for duties on THIS lane"
 * — more specific than the client's per-country profile default.
 *
 * <p>Stamps.com (USPS) intentionally skipped — SWSIM encodes DDU/DDP via
 * service class, not an envelope field. See appendCustomsInfo javadoc.
 */
class ClearanceOptionPrecedenceTest {

    // ===== UPS — payment info via reflection =====

    @Test
    void ups_clearanceOptionWins_overDutyBillTo() throws Exception {
        UpsConnector connector = new UpsConnector(new CarrierProperties(), new ObjectMapper());
        Method method = UpsConnector.class.getDeclaredMethod("buildPaymentInformation", ShipmentRequestDTO.class);
        method.setAccessible(true);

        // Account clearance = THIRD_PARTY (wins). Profile dutyBillTo =
        // SENDER (should be ignored because account is set).
        ShipmentRequestDTO request = upsRequest(IntlShipmentBlockDTO.builder()
                .international(true).incoterms("DAP")
                .customsCurrency("EUR").customsTotalValue(new BigDecimal("500"))
                .commodities(List.of(sampleCommodity()))
                .clearanceOption("THIRD_PARTY")
                .dutyBillTo("SENDER")               // should be overridden
                .dutyAccount("PAYER-999")
                .build());

        @SuppressWarnings("unchecked")
        Map<String, Object> payment = (Map<String, Object>) method.invoke(connector, request);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> charges = (List<Map<String, Object>>) payment.get("ShipmentCharge");

        // Type 01 (freight) always present with shipper account.
        assertEquals("01", charges.get(0).get("Type"));
        // Type 02 (duties) should be BillThirdParty since clearanceOption wins.
        assertEquals("02", charges.get(1).get("Type"));
        assertTrue(charges.get(1).containsKey("BillThirdParty"),
                "clearanceOption=THIRD_PARTY must produce BillThirdParty duties block, "
                        + "not the BillShipper block that dutyBillTo=SENDER alone would produce");
    }

    @Test
    void ups_dutyBillToStillWorks_whenClearanceOptionAbsent() throws Exception {
        UpsConnector connector = new UpsConnector(new CarrierProperties(), new ObjectMapper());
        Method method = UpsConnector.class.getDeclaredMethod("buildPaymentInformation", ShipmentRequestDTO.class);
        method.setAccessible(true);

        ShipmentRequestDTO request = upsRequest(IntlShipmentBlockDTO.builder()
                .international(true).incoterms("DAP")
                .customsCurrency("EUR").customsTotalValue(new BigDecimal("500"))
                .commodities(List.of(sampleCommodity()))
                .dutyBillTo("SENDER")                // account clearance not set
                .build());

        @SuppressWarnings("unchecked")
        Map<String, Object> payment = (Map<String, Object>) method.invoke(connector, request);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> charges = (List<Map<String, Object>>) payment.get("ShipmentCharge");

        assertEquals(2, charges.size(), "SENDER duties → 2 charges (Type 01 freight + Type 02 duties)");
        assertTrue(charges.get(1).containsKey("BillShipper"),
                "back-compat: dutyBillTo=SENDER still resolves to BillShipper when clearanceOption is null");
    }

    // ===== FedEx — duties payment via reflection =====

    @Test
    void fedex_clearanceOptionWins_overDutyBillTo() throws Exception {
        FedExConnector connector = new FedExConnector(
                new CarrierProperties(), new ObjectMapper(), null);
        Method method = FedExConnector.class.getDeclaredMethod("buildDutiesPayment",
                IntlShipmentBlockDTO.class, ShipmentRequestDTO.class);
        method.setAccessible(true);

        IntlShipmentBlockDTO intl = IntlShipmentBlockDTO.builder()
                .international(true).incoterms("DAP")
                .clearanceOption("THIRD_PARTY")
                .dutyBillTo("SENDER")               // should be ignored
                .dutyAccount("PAYER-999")
                .build();

        @SuppressWarnings("unchecked")
        Map<String, Object> duties = (Map<String, Object>) method.invoke(connector, intl, fedexRequest());
        assertEquals("THIRD_PARTY", duties.get("paymentType"),
                "account clearance THIRD_PARTY wins over profile dutyBillTo SENDER");
    }

    @Test
    void fedex_dutyBillToStillWorks_whenClearanceOptionAbsent() throws Exception {
        FedExConnector connector = new FedExConnector(
                new CarrierProperties(), new ObjectMapper(), null);
        Method method = FedExConnector.class.getDeclaredMethod("buildDutiesPayment",
                IntlShipmentBlockDTO.class, ShipmentRequestDTO.class);
        method.setAccessible(true);

        IntlShipmentBlockDTO intl = IntlShipmentBlockDTO.builder()
                .international(true).incoterms("DAP")
                .dutyBillTo("SENDER")
                .build();

        @SuppressWarnings("unchecked")
        Map<String, Object> duties = (Map<String, Object>) method.invoke(connector, intl, fedexRequest());
        assertEquals("SENDER", duties.get("paymentType"),
                "back-compat: dutyBillTo=SENDER used when clearanceOption is null");
    }

    // ===== DHL — export declaration via reflection =====

    @Test
    void dhl_clearanceOptionDDP_wiresCustomsDocuments() throws Exception {
        DhlConnector connector = new DhlConnector(new CarrierProperties(), new ObjectMapper());
        Method method = DhlConnector.class.getDeclaredMethod("buildExportDeclaration", ShipmentRequestDTO.class);
        method.setAccessible(true);

        // Account clearance = DDP (DHL vocabulary). Profile dutyBillTo empty.
        // Incoterms = DAP (would normally NOT trigger customsDocuments per
        // pre-F6-C branch), but clearanceOption=DDP does.
        ShipmentRequestDTO request = dhlRequest(IntlShipmentBlockDTO.builder()
                .international(true).incoterms("DAP")
                .customsCurrency("EUR").customsTotalValue(new BigDecimal("500"))
                .commodities(List.of(sampleCommodity()))
                .clearanceOption("DDP")
                .build());

        @SuppressWarnings("unchecked")
        Map<String, Object> ed = (Map<String, Object>) method.invoke(connector, request);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> docs = (List<Map<String, Object>>) ed.get("customsDocuments");
        assertTrue(docs != null && !docs.isEmpty(),
                "clearanceOption=DDP must add customsDocuments (INV) even when incoterms=DAP");
        assertEquals("INV", docs.get(0).get("typeCode"));
    }

    @Test
    void dhl_clearanceOptionAbsent_fallsBackToDutyBillTo() throws Exception {
        DhlConnector connector = new DhlConnector(new CarrierProperties(), new ObjectMapper());
        Method method = DhlConnector.class.getDeclaredMethod("buildExportDeclaration", ShipmentRequestDTO.class);
        method.setAccessible(true);

        // No clearanceOption, dutyBillTo=SENDER, incoterms=DAP.
        // Pre-F6-C branch used SENDER-or-incoterms=DDP; back-compat preserved.
        ShipmentRequestDTO request = dhlRequest(IntlShipmentBlockDTO.builder()
                .international(true).incoterms("DAP")
                .customsCurrency("EUR").customsTotalValue(new BigDecimal("500"))
                .commodities(List.of(sampleCommodity()))
                .dutyBillTo("SENDER")
                .build());

        @SuppressWarnings("unchecked")
        Map<String, Object> ed = (Map<String, Object>) method.invoke(connector, request);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> docs = (List<Map<String, Object>>) ed.get("customsDocuments");
        assertTrue(docs != null && !docs.isEmpty(),
                "back-compat: dutyBillTo=SENDER still adds customsDocuments when clearanceOption null");
    }

    @Test
    void dhl_noClearanceNoBillTo_incotermsDAP_skipsCustomsDocuments() throws Exception {
        // Sanity: an intl block with NO duty preference at all should not
        // add customsDocuments (matches pre-fix DAP default of consignee-pays).
        DhlConnector connector = new DhlConnector(new CarrierProperties(), new ObjectMapper());
        Method method = DhlConnector.class.getDeclaredMethod("buildExportDeclaration", ShipmentRequestDTO.class);
        method.setAccessible(true);

        ShipmentRequestDTO request = dhlRequest(IntlShipmentBlockDTO.builder()
                .international(true).incoterms("DAP")
                .customsCurrency("EUR").customsTotalValue(new BigDecimal("500"))
                .commodities(List.of(sampleCommodity()))
                .build());

        @SuppressWarnings("unchecked")
        Map<String, Object> ed = (Map<String, Object>) method.invoke(connector, request);
        assertFalse(ed.containsKey("customsDocuments"),
                "DAP + no billTo + no clearanceOption → no customsDocuments (unchanged behavior)");
    }

    // ===== fixtures =====

    private static CustomsCommodityDTO sampleCommodity() {
        return CustomsCommodityDTO.builder()
                .description("Widget").quantity(1)
                .unitValue(new BigDecimal("500"))
                .countryOfOrigin("US")
                .build();
    }

    private static ShipmentRequestDTO upsRequest(IntlShipmentBlockDTO intl) {
        return baseIntlRequest("UPS", "A1234567")
                .intl(intl).build();
    }

    private static ShipmentRequestDTO fedexRequest() {
        return baseIntlRequest("FEDEX", "740561111").build();
    }

    private static ShipmentRequestDTO dhlRequest(IntlShipmentBlockDTO intl) {
        return baseIntlRequest("DHL", "A123456789")
                .intl(intl).build();
    }

    private static ShipmentRequestDTO.ShipmentRequestDTOBuilder baseIntlRequest(String carrier, String account) {
        return ShipmentRequestDTO.builder()
                .carrierCode(carrier).accountNumber(account)
                .serviceType("STANDARD").packageType("YOUR_PACKAGING")
                .weight(new BigDecimal("2.5")).weightUnit("LB")
                .length(new BigDecimal("10")).width(new BigDecimal("10")).height(new BigDecimal("10"))
                .dimUnit("IN")
                .shipperName("Sender").shipperAddressLine1("1 A St")
                .shipperCity("Denver").shipperState("CO").shipperPostalCode("80202").shipperCountryCode("US")
                .recipientName("Recipient").recipientAddressLine1("2 B St")
                .recipientCity("London").recipientPostalCode("SW1A 1AA").recipientCountryCode("GB")
                .referenceNumber("PO-CLEAR-1")
                .declaredValue(new BigDecimal("500")).declaredValueCurrency("EUR");
    }
}
