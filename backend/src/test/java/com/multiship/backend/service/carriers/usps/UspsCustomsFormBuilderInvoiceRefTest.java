package com.multiship.backend.service.carriers.usps;

import com.multiship.backend.dto.CustomsCommodityDTO;
import com.multiship.backend.dto.IntlShipmentBlockDTO;
import com.multiship.backend.dto.IntlShipmentBlockDTO.CustomsSplitStrategy;
import com.multiship.backend.dto.ShipmentRequestDTO;
import com.multiship.backend.service.carriers.usps.dto.UspsCommodity;
import com.multiship.backend.service.carriers.usps.dto.UspsCustomsForm;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PR-F3 — pure-Mockito unit tests for
 * {@link UspsCustomsFormBuilder#build(ShipmentRequestDTO, CustomsSplitStrategy)}
 * under the {@link CustomsSplitStrategy#INVOICE_REFERENCE} path.
 *
 * <p>Covers: summary-line generation (single commodity line), aggregate
 * arithmetic (quantity, weight, value), majority-HS + majority-origin
 * selection, invoice-reference generation vs passthrough,
 * contentComments wording, and SPLIT-path unchanged behaviour.
 *
 * <p><b>REGULATORY_REFERENCE.</b> The summary-line wording mirrors USPS
 * Publication 52 §12.4's "See attached invoice" convention; the fallback
 * HS ({@code 999900}) is the WCO catch-all bucket USPS accepts as a
 * last-resort declaration.
 */
class UspsCustomsFormBuilderInvoiceRefTest {

    private final UspsCustomsFormBuilder builder = new UspsCustomsFormBuilder();

    // ================================================================
    // Baseline — SPLIT (default) path unchanged
    // ================================================================

    @Test
    void split_strategy_still_yields_one_commodity_per_line() {
        ShipmentRequestDTO req = intlRequest(commodities(3), CustomsSplitStrategy.SPLIT, null);
        UspsCustomsForm form = builder.build(req);
        assertNotNull(form);
        assertEquals(3, form.getCommodities().size(),
                "SPLIT path preserves 1-line-per-commodity mapping");
        assertNull(form.getInvoiceReference(),
                "SPLIT path never populates invoiceReference");
    }

    @Test
    void null_strategy_falls_back_to_split_path_behaviour() {
        ShipmentRequestDTO req = intlRequest(commodities(3), null, null);
        UspsCustomsForm form = builder.build(req);
        assertNotNull(form);
        assertEquals(3, form.getCommodities().size(),
                "null strategy defaults to SPLIT-shape output");
        assertNull(form.getInvoiceReference());
    }

    // ================================================================
    // INVOICE_REFERENCE — single summary line + aggregates
    // ================================================================

    @Test
    void invoice_reference_collapses_100_commodities_to_single_summary_line() {
        ShipmentRequestDTO req = intlRequest(commodities(100), CustomsSplitStrategy.INVOICE_REFERENCE, null);
        UspsCustomsForm form = builder.build(req);
        assertNotNull(form);
        assertEquals(1, form.getCommodities().size(),
                "INVOICE_REFERENCE collapses to exactly ONE summary commodity line");
        UspsCommodity summary = form.getCommodities().get(0);
        assertTrue(summary.getDescription().startsWith("See attached invoice"),
                "summary description must open with the USPS 'See attached invoice' convention; got: "
                        + summary.getDescription());
        assertTrue(summary.getDescription().contains("100 line items"),
                "summary description must name the collapsed line count; got: " + summary.getDescription());
    }

    @Test
    void invoice_reference_summary_quantity_equals_sum_of_input_quantities() {
        List<CustomsCommodityDTO> inputs = new ArrayList<>();
        inputs.add(commodity("A", 5, "10.00", "0.5", "610910", "US"));
        inputs.add(commodity("B", 3, "10.00", "0.5", "610910", "US"));
        inputs.add(commodity("C", 7, "10.00", "0.5", "610910", "US"));
        ShipmentRequestDTO req = intlRequest(inputs, CustomsSplitStrategy.INVOICE_REFERENCE, null);
        UspsCustomsForm form = builder.build(req);
        assertEquals(15, (int) form.getCommodities().get(0).getQuantity(),
                "summary.quantity = 5 + 3 + 7 = 15");
    }

    @Test
    void invoice_reference_summary_weight_equals_sum_of_qty_times_unitWeight() {
        List<CustomsCommodityDTO> inputs = new ArrayList<>();
        inputs.add(commodity("A", 2, "10.00", "0.50", "610910", "US"));  // 2 * 0.50 = 1.00
        inputs.add(commodity("B", 4, "10.00", "0.25", "610910", "US"));  // 4 * 0.25 = 1.00
        ShipmentRequestDTO req = intlRequest(inputs, CustomsSplitStrategy.INVOICE_REFERENCE, null);
        UspsCustomsForm form = builder.build(req);
        assertEquals(0, form.getCommodities().get(0).getWeight().compareTo(new BigDecimal("2.00")),
                "summary.weight = 1.00 + 1.00 = 2.00 (with BigDecimal scale preserved)");
    }

    @Test
    void invoice_reference_summary_value_equals_sum_of_qty_times_unitValue() {
        List<CustomsCommodityDTO> inputs = new ArrayList<>();
        inputs.add(commodity("A", 5, "10.00", "0.5", "610910", "US"));  // 5 * 10 = 50.00
        inputs.add(commodity("B", 3, "20.00", "0.5", "610910", "US"));  // 3 * 20 = 60.00
        ShipmentRequestDTO req = intlRequest(inputs, CustomsSplitStrategy.INVOICE_REFERENCE, null);
        UspsCustomsForm form = builder.build(req);
        assertEquals(0, form.getCommodities().get(0).getValue().compareTo(new BigDecimal("110.00")),
                "summary.value = 50 + 60 = 110.00");
    }

    // ================================================================
    // Majority HS + origin selection
    // ================================================================

    @Test
    void invoice_reference_summary_hs_is_most_common_hs6() {
        List<CustomsCommodityDTO> inputs = new ArrayList<>();
        // 3 t-shirts, 1 hat, 1 pants → most common HS6 = 610910
        inputs.add(commodity("Shirt-1", 1, "10", "0.5", "610910", "US"));
        inputs.add(commodity("Shirt-2", 1, "10", "0.5", "610910", "US"));
        inputs.add(commodity("Shirt-3", 1, "10", "0.5", "610910", "US"));
        inputs.add(commodity("Hat", 1, "10", "0.5", "650100", "US"));
        inputs.add(commodity("Pants", 1, "10", "0.5", "620342", "US"));
        ShipmentRequestDTO req = intlRequest(inputs, CustomsSplitStrategy.INVOICE_REFERENCE, null);
        UspsCustomsForm form = builder.build(req);
        assertEquals("610910", form.getCommodities().get(0).getHsTariffNumber(),
                "majority HS-6 across 3+1+1 inputs = 610910");
    }

    @Test
    void invoice_reference_summary_hs_falls_back_to_999900_when_no_hs_on_inputs() {
        List<CustomsCommodityDTO> inputs = new ArrayList<>();
        inputs.add(commodity("Unknown-1", 1, "10", "0.5", null, "US"));
        inputs.add(commodity("Unknown-2", 1, "10", "0.5", "", "US"));
        ShipmentRequestDTO req = intlRequest(inputs, CustomsSplitStrategy.INVOICE_REFERENCE, null);
        UspsCustomsForm form = builder.build(req);
        assertEquals("999900", form.getCommodities().get(0).getHsTariffNumber(),
                "no input HS → summary uses USPS 999900 catch-all bucket");
    }

    @Test
    void invoice_reference_summary_origin_is_most_common_iso_alpha2() {
        List<CustomsCommodityDTO> inputs = new ArrayList<>();
        inputs.add(commodity("A", 1, "10", "0.5", "610910", "US"));
        inputs.add(commodity("B", 1, "10", "0.5", "610910", "US"));
        inputs.add(commodity("C", 1, "10", "0.5", "610910", "CN"));
        inputs.add(commodity("D", 1, "10", "0.5", "610910", "MX"));
        ShipmentRequestDTO req = intlRequest(inputs, CustomsSplitStrategy.INVOICE_REFERENCE, null);
        UspsCustomsForm form = builder.build(req);
        assertEquals("US", form.getCommodities().get(0).getCountryOfOrigin(),
                "majority ISO alpha-2 origin = US");
    }

    @Test
    void invoice_reference_summary_origin_is_null_when_no_origin_on_inputs() {
        List<CustomsCommodityDTO> inputs = new ArrayList<>();
        inputs.add(commodity("A", 1, "10", "0.5", "610910", null));
        inputs.add(commodity("B", 1, "10", "0.5", "610910", ""));
        ShipmentRequestDTO req = intlRequest(inputs, CustomsSplitStrategy.INVOICE_REFERENCE, null);
        UspsCustomsForm form = builder.build(req);
        assertNull(form.getCommodities().get(0).getCountryOfOrigin(),
                "no input origin → summary origin is null");
    }

    // ================================================================
    // Invoice reference generation vs passthrough
    // ================================================================

    @Test
    void invoice_reference_uses_caller_supplied_ref_when_present() {
        ShipmentRequestDTO req = intlRequest(commodities(5),
                CustomsSplitStrategy.INVOICE_REFERENCE, "INV-42-ABC");
        UspsCustomsForm form = builder.build(req);
        assertEquals("INV-42-ABC", form.getInvoiceReference(),
                "caller-supplied invoice reference must ride through verbatim");
        assertTrue(form.getContentComments().contains("INV-42-ABC"),
                "contentComments must reference the supplied invoice number; got: "
                        + form.getContentComments());
    }

    @Test
    void invoice_reference_generates_ref_when_caller_did_not_supply_one() {
        ShipmentRequestDTO req = intlRequest(commodities(5),
                CustomsSplitStrategy.INVOICE_REFERENCE, null);
        UspsCustomsForm form = builder.build(req);
        String ref = form.getInvoiceReference();
        assertNotNull(ref);
        assertTrue(ref.startsWith("USPS-PO-INTL-42-"),
                "generated reference must follow USPS-{orderRef}-{timestamp}; got: " + ref);
    }

    @Test
    void invoice_reference_content_comments_are_operator_friendly() {
        ShipmentRequestDTO req = intlRequest(commodities(5),
                CustomsSplitStrategy.INVOICE_REFERENCE, "INV-42-ABC");
        UspsCustomsForm form = builder.build(req);
        assertTrue(form.getContentComments().startsWith("See attached invoice for full itemization"),
                "contentComments must explain the operator's physical-attachment obligation; got: "
                        + form.getContentComments());
    }

    // ================================================================
    // Content-type + defaults still populated under INVOICE_REFERENCE
    // ================================================================

    @Test
    void invoice_reference_preserves_content_type_and_defaults() {
        ShipmentRequestDTO req = intlRequest(commodities(3),
                CustomsSplitStrategy.INVOICE_REFERENCE, null);
        UspsCustomsForm form = builder.build(req);
        assertEquals("MERCHANDISE", form.getContentType(),
                "reasonForExport=SALE → MERCHANDISE, regardless of strategy");
        assertEquals("NONE", form.getRestriction());
        assertEquals("RETURN", form.getNonDeliveryOption());
    }

    // ================================================================
    // Fixtures
    // ================================================================

    private static CustomsCommodityDTO commodity(String desc, int qty, String unitValue,
                                                   String unitWeight, String hsCode, String coo) {
        return CustomsCommodityDTO.builder()
                .description(desc)
                .quantity(qty)
                .unitValue(new BigDecimal(unitValue))
                .unitWeight(new BigDecimal(unitWeight))
                .hsCode(hsCode)
                .countryOfOrigin(coo)
                .build();
    }

    private static List<CustomsCommodityDTO> commodities(int count) {
        List<CustomsCommodityDTO> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            out.add(commodity("Item " + (i + 1), 1, "10.00", "0.5", "610910", "US"));
        }
        return out;
    }

    private static ShipmentRequestDTO intlRequest(List<CustomsCommodityDTO> commodities,
                                                    CustomsSplitStrategy strategy,
                                                    String customsInvoiceReference) {
        IntlShipmentBlockDTO intl = IntlShipmentBlockDTO.builder()
                .reasonForExport("SALE")
                .customsCurrency("USD")
                .incoterms("DAP")
                .commodities(commodities)
                .customsSplitStrategy(strategy)
                .customsInvoiceReference(customsInvoiceReference)
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
                .referenceNumber("PO-INTL-42")
                .intl(intl)
                .build();
    }
}
