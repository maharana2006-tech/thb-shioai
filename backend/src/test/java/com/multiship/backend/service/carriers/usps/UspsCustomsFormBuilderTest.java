package com.multiship.backend.service.carriers.usps;

import com.multiship.backend.dto.CustomsCommodityDTO;
import com.multiship.backend.dto.IntlShipmentBlockDTO;
import com.multiship.backend.dto.ShipmentRequestDTO;
import com.multiship.backend.service.carriers.usps.dto.UspsCommodity;
import com.multiship.backend.service.carriers.usps.dto.UspsCustomsForm;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-Mockito unit tests for {@link UspsCustomsFormBuilder}.
 *
 * <p>Covers: enum mapping (contentType / restriction / nonDeliveryOption
 * in and out), HS-code normalisation (strip dashes / spaces, reject
 * shorter than 6 after normalisation is the connector's job — the
 * builder just normalises), missing-optional handling (nulls → omit,
 * not empty-string), and the null-safe empty-list boundary.
 */
class UspsCustomsFormBuilderTest {

    private final UspsCustomsFormBuilder builder = new UspsCustomsFormBuilder();

    // ================================================================
    // contentType mapping — every reason-for-export enum value in
    // ================================================================

    @Test
    void mapContentType_sale_yields_merchandise() {
        assertEquals("MERCHANDISE", UspsCustomsFormBuilder.mapContentType("SALE"));
    }

    @Test
    void mapContentType_merchandise_yields_merchandise() {
        assertEquals("MERCHANDISE", UspsCustomsFormBuilder.mapContentType("MERCHANDISE"));
    }

    @Test
    void mapContentType_gift_yields_gift() {
        assertEquals("GIFT", UspsCustomsFormBuilder.mapContentType("GIFT"));
    }

    @Test
    void mapContentType_sample_yields_sample() {
        assertEquals("SAMPLE", UspsCustomsFormBuilder.mapContentType("SAMPLE"));
    }

    @Test
    void mapContentType_return_yields_returned_goods() {
        assertEquals("RETURNED_GOODS", UspsCustomsFormBuilder.mapContentType("RETURN"));
    }

    @Test
    void mapContentType_documents_yields_documents() {
        assertEquals("DOCUMENTS", UspsCustomsFormBuilder.mapContentType("DOCUMENTS"));
    }

    @Test
    void mapContentType_repair_yields_humanitarian_bucket() {
        // REPAIR isn't a USPS enum — humanitarian is the closest bucket,
        // per the mapping table in UspsCustomsFormBuilder.
        assertEquals("HUMANITARIAN", UspsCustomsFormBuilder.mapContentType("REPAIR"));
    }

    @Test
    void mapContentType_other_yields_other() {
        assertEquals("OTHER", UspsCustomsFormBuilder.mapContentType("OTHER"));
    }

    @Test
    void mapContentType_null_yields_merchandise_default() {
        assertEquals("MERCHANDISE", UspsCustomsFormBuilder.mapContentType(null));
    }

    @Test
    void mapContentType_blank_yields_merchandise_default() {
        assertEquals("MERCHANDISE", UspsCustomsFormBuilder.mapContentType("  "));
    }

    @Test
    void mapContentType_unknown_yields_merchandise_default_and_warns() {
        // Unknown values fall back to the default; the WARN log line is
        // observable in the surefire output but not asserted here — the
        // return value is the contract test.
        assertEquals("MERCHANDISE",
                UspsCustomsFormBuilder.mapContentType("SOMETHING_WEIRD"));
    }

    @Test
    void mapContentType_lowercase_is_normalized() {
        assertEquals("GIFT", UspsCustomsFormBuilder.mapContentType("gift"));
    }

    // ================================================================
    // restriction mapping
    // ================================================================

    @Test
    void mapRestriction_none_yields_none() {
        assertEquals("NONE", UspsCustomsFormBuilder.mapRestriction("NONE"));
    }

    @Test
    void mapRestriction_null_yields_none_default() {
        assertEquals("NONE", UspsCustomsFormBuilder.mapRestriction(null));
    }

    @Test
    void mapRestriction_quarantine_yields_quarantine() {
        assertEquals("QUARANTINE", UspsCustomsFormBuilder.mapRestriction("QUARANTINE"));
    }

    @Test
    void mapRestriction_sps_alias_yields_full_enum() {
        assertEquals("SANITARY_PHYTOSANITARY_INSPECTION",
                UspsCustomsFormBuilder.mapRestriction("SPS"));
    }

    @Test
    void mapRestriction_other_yields_other() {
        assertEquals("OTHER", UspsCustomsFormBuilder.mapRestriction("OTHER"));
    }

    @Test
    void mapRestriction_unknown_yields_none_default() {
        assertEquals("NONE", UspsCustomsFormBuilder.mapRestriction("UNKNOWN_VALUE"));
    }

    // ================================================================
    // nonDeliveryOption mapping
    // ================================================================

    @Test
    void mapNonDelivery_return_yields_return() {
        assertEquals("RETURN", UspsCustomsFormBuilder.mapNonDelivery("RETURN"));
    }

    @Test
    void mapNonDelivery_return_to_sender_alias_yields_return() {
        assertEquals("RETURN", UspsCustomsFormBuilder.mapNonDelivery("RETURN_TO_SENDER"));
    }

    @Test
    void mapNonDelivery_abandon_yields_abandon() {
        assertEquals("ABANDON", UspsCustomsFormBuilder.mapNonDelivery("ABANDON"));
    }

    @Test
    void mapNonDelivery_redirect_yields_redirect() {
        assertEquals("REDIRECT", UspsCustomsFormBuilder.mapNonDelivery("REDIRECT"));
    }

    @Test
    void mapNonDelivery_null_yields_return_default() {
        assertEquals("RETURN", UspsCustomsFormBuilder.mapNonDelivery(null));
    }

    @Test
    void mapNonDelivery_unknown_yields_return_default() {
        assertEquals("RETURN", UspsCustomsFormBuilder.mapNonDelivery("KEEP_IT"));
    }

    // ================================================================
    // HS-code normalisation
    // ================================================================

    @Test
    void normaliseHs_null_yields_null() {
        assertNull(UspsCustomsFormBuilder.normaliseHs(null));
    }

    @Test
    void normaliseHs_empty_yields_empty() {
        assertEquals("", UspsCustomsFormBuilder.normaliseHs(""));
    }

    @Test
    void normaliseHs_pure_digits_passes_through() {
        assertEquals("610910", UspsCustomsFormBuilder.normaliseHs("610910"));
    }

    @Test
    void normaliseHs_strips_dashes() {
        assertEquals("610910", UspsCustomsFormBuilder.normaliseHs("6109-10"));
    }

    @Test
    void normaliseHs_strips_dots() {
        assertEquals("6109100", UspsCustomsFormBuilder.normaliseHs("6109.10.0"));
    }

    @Test
    void normaliseHs_strips_whitespace() {
        assertEquals("610910", UspsCustomsFormBuilder.normaliseHs("  6109 10  "));
    }

    @Test
    void normaliseHs_strips_alpha() {
        // The builder normalises to digits only; the guard elsewhere
        // rejects short outputs. "AB123" → "123" (invalid, guard's job).
        assertEquals("123", UspsCustomsFormBuilder.normaliseHs("AB123"));
    }

    @Test
    void normaliseHs_all_alpha_yields_empty() {
        assertEquals("", UspsCustomsFormBuilder.normaliseHs("NOSPACE"));
    }

    @Test
    void normaliseHs_10_digit_full_hs_preserves_all() {
        // Real 10-digit HS codes exist for some jurisdictions; normalisation
        // doesn't truncate — the guard just enforces the minimum 6.
        assertEquals("6109100010",
                UspsCustomsFormBuilder.normaliseHs("6109.10.00.10"));
    }

    // ================================================================
    // build() — full-object round-trip
    // ================================================================

    @Test
    void build_null_request_returns_null() {
        assertNull(builder.build(null));
    }

    @Test
    void build_request_without_intl_block_returns_null() {
        ShipmentRequestDTO req = ShipmentRequestDTO.builder().build();
        assertNull(builder.build(req));
    }

    @Test
    void build_empty_commodities_yields_empty_list_not_exception() {
        IntlShipmentBlockDTO intl = IntlShipmentBlockDTO.builder()
                .reasonForExport("SALE")
                .commodities(new java.util.ArrayList<>())
                .build();
        ShipmentRequestDTO req = ShipmentRequestDTO.builder().intl(intl).build();
        UspsCustomsForm form = builder.build(req);
        assertNotNull(form);
        assertNotNull(form.getCommodities(), "commodities list must never be null");
        assertTrue(form.getCommodities().isEmpty());
        assertEquals("MERCHANDISE", form.getContentType());
    }

    @Test
    void build_populated_request_maps_all_fields() {
        CustomsCommodityDTO c1 = CustomsCommodityDTO.builder()
                .description("Cotton t-shirt")
                .quantity(2)
                .unitValue(new BigDecimal("15.00"))
                .unitWeight(new BigDecimal("0.5"))
                .hsCode("6109.10")
                .countryOfOrigin("us")  // lowercase → normalised uppercase
                .build();
        IntlShipmentBlockDTO intl = IntlShipmentBlockDTO.builder()
                .reasonForExport("SALE")
                .commodities(List.of(c1))
                .build();
        ShipmentRequestDTO req = ShipmentRequestDTO.builder().intl(intl).build();

        UspsCustomsForm form = builder.build(req);
        assertNotNull(form);
        assertEquals("MERCHANDISE", form.getContentType());
        assertEquals("NONE", form.getRestriction());
        assertEquals("RETURN", form.getNonDeliveryOption());
        assertEquals("Cotton t-shirt", form.getContentComments(),
                "contentComments should default to first commodity description");
        assertEquals(1, form.getCommodities().size());

        UspsCommodity mapped = form.getCommodities().get(0);
        assertEquals("Cotton t-shirt", mapped.getDescription());
        assertEquals(2, mapped.getQuantity());
        assertEquals(0, mapped.getWeight().compareTo(new BigDecimal("0.5")));
        assertEquals(0, mapped.getValue().compareTo(new BigDecimal("15.00")));
        assertEquals("610910", mapped.getHsTariffNumber(),
                "hsTariffNumber must be dash-stripped");
        assertEquals("US", mapped.getCountryOfOrigin(),
                "countryOfOrigin must be uppercase");
        assertNull(mapped.getUnitOfMeasure(),
                "unitOfMeasure isn't yet on CustomsCommodityDTO — must be null on output");
    }

    @Test
    void build_omits_optional_commodity_fields_when_blank() {
        // Commodity with only description + hs → other fields must be
        // absent on the output (not zero, not empty string).
        CustomsCommodityDTO c = CustomsCommodityDTO.builder()
                .description("Descript only")
                .hsCode("610910")
                .build();
        IntlShipmentBlockDTO intl = IntlShipmentBlockDTO.builder()
                .reasonForExport("GIFT")
                .commodities(List.of(c))
                .build();
        ShipmentRequestDTO req = ShipmentRequestDTO.builder().intl(intl).build();
        UspsCustomsForm form = builder.build(req);
        UspsCommodity mapped = form.getCommodities().get(0);
        assertEquals("Descript only", mapped.getDescription());
        assertEquals("610910", mapped.getHsTariffNumber());
        assertNull(mapped.getQuantity(), "quantity must be null (not 0) when unset");
        assertNull(mapped.getWeight(), "weight must be null when unset");
        assertNull(mapped.getValue(), "value must be null when unset");
        assertNull(mapped.getCountryOfOrigin(), "countryOfOrigin must be null when unset");
    }

    @Test
    void build_handles_null_commodity_entries_gracefully() {
        // Real customs blocks sometimes have null entries mid-list
        // (import from CSV where a row was empty). The builder must
        // skip them, not NPE.
        java.util.List<CustomsCommodityDTO> mixed = new java.util.ArrayList<>();
        mixed.add(CustomsCommodityDTO.builder().description("Real").hsCode("610910").countryOfOrigin("US").build());
        mixed.add(null);
        mixed.add(CustomsCommodityDTO.builder().description("Also real").hsCode("851713").countryOfOrigin("US").build());
        IntlShipmentBlockDTO intl = IntlShipmentBlockDTO.builder()
                .reasonForExport("SALE")
                .commodities(mixed)
                .build();
        ShipmentRequestDTO req = ShipmentRequestDTO.builder().intl(intl).build();
        UspsCustomsForm form = builder.build(req);
        assertEquals(2, form.getCommodities().size(),
                "null commodity must be skipped, not counted");
    }
}
