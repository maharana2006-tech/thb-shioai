package com.multiship.backend.service.carriers.usps;

import com.multiship.backend.dto.CustomsCommodityDTO;
import com.multiship.backend.dto.IntlShipmentBlockDTO;
import com.multiship.backend.dto.IntlShipmentBlockDTO.CustomsSplitStrategy;
import com.multiship.backend.dto.ShipmentRequestDTO;
import com.multiship.backend.service.ShipmentSplitter;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PR-F3 — pure-Mockito unit tests for {@link UspsCustomsLineItemSplitter}.
 *
 * <p>Covers strategy dispatch, cap-boundary behaviour, and the
 * {@link UspsCustomsLineItemSplitter#requiresSplit} predicate.
 *
 * <p><b>REGULATORY_REFERENCE.</b> {@link UspsCustomsLineItemSplitter#USPS_CUSTOMS_FORM_LINE_CAP}
 * = 30 mirrors USPS Publication 52 §12.4 / eVS integrator guide ceilings
 * on PS-2976-A customs forms; changing the constant requires
 * compliance-officer sign-off.
 */
class UspsCustomsLineItemSplitterTest {

    private final UspsCustomsFormBuilder builder = new UspsCustomsFormBuilder();
    private final ShipmentSplitter shipmentSplitter = new ShipmentSplitter();
    private final UspsCustomsLineItemSplitter splitter =
            new UspsCustomsLineItemSplitter(shipmentSplitter, builder);

    // ================================================================
    // requiresSplit
    // ================================================================

    @Test
    void requiresSplit_null_request_is_false() {
        assertFalse(splitter.requiresSplit(null));
    }

    @Test
    void requiresSplit_domestic_no_intl_is_false() {
        ShipmentRequestDTO req = ShipmentRequestDTO.builder()
                .carrierCode("USPS").recipientCountryCode("US").build();
        assertFalse(splitter.requiresSplit(req));
    }

    @Test
    void requiresSplit_intl_with_25_commodities_is_false() {
        ShipmentRequestDTO req = intlRequest(commodities(25), null);
        assertFalse(splitter.requiresSplit(req),
                "25 ≤ 30 line cap → no split needed");
    }

    @Test
    void requiresSplit_intl_with_30_commodities_is_false_at_boundary() {
        ShipmentRequestDTO req = intlRequest(commodities(30), null);
        assertFalse(splitter.requiresSplit(req),
                "30 == cap → still fits (soft cap is inclusive)");
    }

    @Test
    void requiresSplit_intl_with_31_commodities_is_true() {
        ShipmentRequestDTO req = intlRequest(commodities(31), null);
        assertTrue(splitter.requiresSplit(req),
                "31 > 30 line cap → split required");
    }

    @Test
    void requiresSplit_intl_with_100_commodities_is_true() {
        ShipmentRequestDTO req = intlRequest(commodities(100), null);
        assertTrue(splitter.requiresSplit(req));
    }

    // ================================================================
    // applyStrategy — under-cap short-circuits
    // ================================================================

    @Test
    void applyStrategy_non_intl_returns_single_element_unchanged() {
        ShipmentRequestDTO req = ShipmentRequestDTO.builder()
                .carrierCode("USPS").recipientCountryCode("US").build();
        List<ShipmentRequestDTO> subs = splitter.applyStrategy(req, CustomsSplitStrategy.SPLIT);
        assertEquals(1, subs.size());
        assertSame(req, subs.get(0));
    }

    @Test
    void applyStrategy_intl_with_25_commodities_returns_single_element_unchanged() {
        ShipmentRequestDTO req = intlRequest(commodities(25), CustomsSplitStrategy.SPLIT);
        List<ShipmentRequestDTO> subs = splitter.applyStrategy(req, CustomsSplitStrategy.SPLIT);
        assertEquals(1, subs.size(), "under-cap → no split");
        assertSame(req, subs.get(0));
    }

    @Test
    void applyStrategy_intl_with_30_commodities_returns_single_element_at_boundary() {
        ShipmentRequestDTO req = intlRequest(commodities(30), CustomsSplitStrategy.SPLIT);
        List<ShipmentRequestDTO> subs = splitter.applyStrategy(req, CustomsSplitStrategy.SPLIT);
        assertEquals(1, subs.size(),
                "30 (== cap) fits on one form; no split even with SPLIT strategy");
    }

    // ================================================================
    // applyStrategy — SPLIT
    // ================================================================

    @Test
    void applyStrategy_intl_with_31_commodities_split_yields_two_subrequests() {
        ShipmentRequestDTO req = intlRequest(commodities(31), CustomsSplitStrategy.SPLIT);
        List<ShipmentRequestDTO> subs = splitter.applyStrategy(req, CustomsSplitStrategy.SPLIT);
        assertEquals(2, subs.size(), "31 → ceil(31/30) = 2 sub-parcels");
        assertEquals(30, subs.get(0).getIntl().getCommodities().size(),
                "first sub-parcel carries the full 30-line customs form");
        assertEquals(1, subs.get(1).getIntl().getCommodities().size(),
                "second sub-parcel carries the remainder (1 line)");
    }

    @Test
    void applyStrategy_intl_with_100_commodities_split_yields_four_subrequests() {
        ShipmentRequestDTO req = intlRequest(commodities(100), CustomsSplitStrategy.SPLIT);
        List<ShipmentRequestDTO> subs = splitter.applyStrategy(req, CustomsSplitStrategy.SPLIT);
        assertEquals(4, subs.size(), "100 → ceil(100/30) = 4 sub-parcels");
        assertEquals(30, subs.get(0).getIntl().getCommodities().size());
        assertEquals(30, subs.get(1).getIntl().getCommodities().size());
        assertEquals(30, subs.get(2).getIntl().getCommodities().size());
        assertEquals(10, subs.get(3).getIntl().getCommodities().size(),
                "final sub-parcel picks up the remainder (100 - 3*30 = 10)");
    }

    @Test
    void applyStrategy_split_null_strategy_defaults_to_split_over_cap() {
        // Callers should have already invoked the connector boundary guard
        // (which throws on null strategy + over-cap). This fallback is for
        // any test / non-USPS caller that reaches the splitter without
        // going through the guard.
        ShipmentRequestDTO req = intlRequest(commodities(31), null);
        List<ShipmentRequestDTO> subs = splitter.applyStrategy(req, null);
        assertEquals(2, subs.size(),
                "null strategy over-cap → treated as SPLIT (safe default)");
    }

    // ================================================================
    // applyStrategy — INVOICE_REFERENCE
    // ================================================================

    @Test
    void applyStrategy_invoice_reference_with_100_commodities_returns_single_element_unchanged() {
        ShipmentRequestDTO req = intlRequest(commodities(100), CustomsSplitStrategy.INVOICE_REFERENCE);
        List<ShipmentRequestDTO> subs = splitter.applyStrategy(req, CustomsSplitStrategy.INVOICE_REFERENCE);
        assertEquals(1, subs.size(),
                "INVOICE_REFERENCE keeps the shipment as ONE parcel; connector transforms customs form at build time");
        assertSame(req, subs.get(0),
                "no cloning under INVOICE_REFERENCE — the request rides through to the connector unchanged");
    }

    @Test
    void applyStrategy_invoice_reference_with_31_commodities_still_single_element() {
        ShipmentRequestDTO req = intlRequest(commodities(31), CustomsSplitStrategy.INVOICE_REFERENCE);
        List<ShipmentRequestDTO> subs = splitter.applyStrategy(req, CustomsSplitStrategy.INVOICE_REFERENCE);
        assertEquals(1, subs.size());
    }

    // ================================================================
    // applyStrategy — SPLIT clones intl block so parent is unmutated
    // ================================================================

    @Test
    void applyStrategy_split_does_not_mutate_parent_intl_block() {
        ShipmentRequestDTO req = intlRequest(commodities(31), CustomsSplitStrategy.SPLIT);
        int parentBefore = req.getIntl().getCommodities().size();
        List<ShipmentRequestDTO> subs = splitter.applyStrategy(req, CustomsSplitStrategy.SPLIT);
        int parentAfter = req.getIntl().getCommodities().size();
        assertEquals(parentBefore, parentAfter,
                "parent request's commodity list must remain untouched");
        // Sub-shipments' intl block must be a distinct instance (cloned
        // by ShipmentSplitter.toBuilder().build() semantics).
        assertNotSame(req.getIntl(), subs.get(0).getIntl(),
                "sub-request must carry its own intl block clone");
    }

    // ================================================================
    // Fixtures
    // ================================================================

    private static List<CustomsCommodityDTO> commodities(int count) {
        List<CustomsCommodityDTO> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            out.add(CustomsCommodityDTO.builder()
                    .description("Item " + (i + 1))
                    .quantity(1)
                    .unitValue(new BigDecimal("10.00"))
                    .unitWeight(new BigDecimal("0.5"))
                    .hsCode("610910")
                    .countryOfOrigin("US")
                    .build());
        }
        return out;
    }

    private static ShipmentRequestDTO intlRequest(List<CustomsCommodityDTO> commodities,
                                                    CustomsSplitStrategy strategy) {
        IntlShipmentBlockDTO intl = IntlShipmentBlockDTO.builder()
                .reasonForExport("SALE")
                .customsCurrency("USD")
                .incoterms("DAP")
                .commodities(commodities)
                .customsSplitStrategy(strategy)
                .build();
        return ShipmentRequestDTO.builder()
                .carrierCode("USPS")
                .accountNumber("ACCT-1")
                .serviceType("PRIORITY_MAIL_INTERNATIONAL")
                .packageType("USPS_PACKAGE")
                .weight(new BigDecimal("2"))
                .weightUnit("LB")
                .shipperCountryCode("US")
                .shipperPostalCode("80202")
                .recipientCountryCode("GB")
                .recipientPostalCode("SW1A 2AA")
                .referenceNumber("PO-SPLIT-1")
                .intl(intl)
                .build();
    }
}
