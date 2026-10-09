package com.multiship.backend.service.carriers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.dto.CustomsCommodityDTO;
import com.multiship.backend.dto.IntlShipmentBlockDTO;
import com.multiship.backend.dto.PackageDetailDTO;
import com.multiship.backend.dto.ShipmentRequestDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** PR-T7 — pins the SERA {@code customs.sender_info} + {@code recipient_info}
 *  + operator-configurable {@code non_delivery_option} contract. Protects
 *  the US-export-compliance filing path (EEI / AES) + the EU IOSS / UK VAT
 *  recipient-tax-id path from silent regressions. */
class StampsSeraSenderRecipientInfoTest {

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

    // ===== sender_info =====

    @Test
    void senderInfo_populatedWhenAesCitationSet() throws Exception {
        ShipmentRequestDTO req = intlRequestWith(block -> block.setAesCitation("X20260108123456"));
        JsonNode customs = emitAndRead(req);
        JsonNode sender = customs.path("sender_info");
        assertTrue(sender.isObject(), "sender_info must emit when AES ITN present");
        assertEquals("X20260108123456", sender.path("license_number").asText(),
                "aesCitation (ITN) must land on license_number");
    }

    @Test
    void senderInfo_fallsBackToExportDeclarationReference() throws Exception {
        ShipmentRequestDTO req = intlRequestWith(block -> {
            block.setAesCitation(null);
            block.setExportDeclarationReference("GB-CDS-REF-999");
        });
        JsonNode customs = emitAndRead(req);
        assertEquals("GB-CDS-REF-999", customs.path("sender_info").path("license_number").asText());
    }

    @Test
    void senderInfo_ftrExemptionIsNotMappedToCertificateNumber() throws Exception {
        // Pre-fix: ftrExemption → sender_info.certificate_number, which
        // SERA rejects with carrier error 4522242 "certificate_number
        // specified is invalid." USPS auto-derives §30.37(a) from
        // shipment value, so we drop the wire mapping entirely. The
        // AES ITN (aesCitation) continues to ride on license_number.
        ShipmentRequestDTO req = intlRequestWith(block -> {
            block.setAesCitation(null);
            block.setExportDeclarationReference(null);
            block.setFtrExemption("NO_EEI_30_37_a");
        });
        JsonNode customs = emitAndRead(req);
        // Entire sender_info block should be omitted when only ftrExemption
        // was set (there's no other populated certificate / license field).
        assertTrue(customs.path("sender_info").isMissingNode()
                        || customs.path("sender_info").isNull(),
                "ftrExemption alone must NOT emit sender_info — SERA rejects it as certificate_number");
    }

    @Test
    void senderInfo_omittedWhenNoFields() throws Exception {
        ShipmentRequestDTO req = intlRequestWith(block -> {
            block.setAesCitation(null);
            block.setExportDeclarationReference(null);
            block.setFtrExemption(null);
        });
        JsonNode customs = emitAndRead(req);
        assertTrue(customs.path("sender_info").isMissingNode()
                        || customs.path("sender_info").isNull(),
                "no export references → omit sender_info entirely");
    }

    // ===== recipient_info.tax_id =====

    @Test
    void recipientInfo_populatedFromImporterTaxId() throws Exception {
        ShipmentRequestDTO req = intlRequestWith(block -> block.setImporterTaxId("TAX-123"));
        JsonNode customs = emitAndRead(req);
        assertEquals("TAX-123", customs.path("recipient_info").path("tax_id").asText());
    }

    @Test
    void recipientInfo_fallsBackToIoss() throws Exception {
        ShipmentRequestDTO req = intlRequestWith(block -> {
            block.setImporterTaxId(null);
            block.setImporterVat(null);
            block.setImporterIoss("IM1234567890");
        });
        JsonNode customs = emitAndRead(req);
        assertEquals("IM1234567890", customs.path("recipient_info").path("tax_id").asText());
    }

    @Test
    void recipientInfo_precedenceVatOverIossOverEori() throws Exception {
        ShipmentRequestDTO req = intlRequestWith(block -> {
            block.setImporterVat("VAT-FIRST");
            block.setImporterIoss("IOSS-SECOND");
            block.setImporterEori("EORI-THIRD");
        });
        JsonNode customs = emitAndRead(req);
        assertEquals("VAT-FIRST", customs.path("recipient_info").path("tax_id").asText(),
                "VAT wins over IOSS wins over EORI");
    }

    @Test
    void recipientInfo_omittedWhenNoTaxId() throws Exception {
        ShipmentRequestDTO req = intlRequestWith(block -> {
            block.setImporterTaxId(null); block.setImporterVat(null);
            block.setImporterIoss(null);  block.setImporterEori(null);
            block.setImporterGstin(null);
        });
        JsonNode customs = emitAndRead(req);
        assertTrue(customs.path("recipient_info").isMissingNode()
                        || customs.path("recipient_info").isNull());
    }

    // ===== non_delivery_option =====

    @Test
    void nonDeliveryOption_defaultsToReturnToSender() throws Exception {
        ShipmentRequestDTO req = intlRequestWith(block -> block.setNonDeliveryOption(null));
        JsonNode customs = emitAndRead(req);
        assertEquals("return_to_sender", customs.path("non_delivery_option").asText());
    }

    @Test
    void nonDeliveryOption_treatAsAbandonedPassesThrough() throws Exception {
        ShipmentRequestDTO req = intlRequestWith(block -> block.setNonDeliveryOption("treat_as_abandoned"));
        JsonNode customs = emitAndRead(req);
        assertEquals("treat_as_abandoned", customs.path("non_delivery_option").asText());
    }

    @Test
    void nonDeliveryOption_unknownValueCoercedToDefault() throws Exception {
        // Stale enum / typo / operator freeform typing → coerce to the
        // safer default rather than let SERA 400 at validation time.
        ShipmentRequestDTO req = intlRequestWith(block -> block.setNonDeliveryOption("destroy"));
        JsonNode customs = emitAndRead(req);
        assertEquals("return_to_sender", customs.path("non_delivery_option").asText());
    }

    // ===== helpers =====

    private JsonNode emitAndRead(ShipmentRequestDTO req) throws Exception {
        String json = connector.buildSeraCreateLabelBody(
                req, req.effectivePackages().get(0), 1, 1);
        return mapper.readTree(json).path("customs");
    }

    private ShipmentRequestDTO intlRequestWith(java.util.function.Consumer<IntlShipmentBlockDTO> customiser) {
        ShipmentRequestDTO req = baseIntl();
        customiser.accept(req.getIntl());
        return req;
    }

    private ShipmentRequestDTO baseIntl() {
        ShipmentRequestDTO req = new ShipmentRequestDTO();
        req.setReferenceNumber("ORD-" + System.nanoTime());
        req.setShipperName("Acme"); req.setShipperAddressLine1("1 Way");
        req.setShipperCity("LOU"); req.setShipperState("KY");
        req.setShipperPostalCode("40209"); req.setShipperCountryCode("US");
        req.setRecipientName("Dest"); req.setRecipientAddressLine1("1 Rue");
        req.setRecipientCity("PAR"); req.setRecipientState("");
        req.setRecipientPostalCode("75000"); req.setRecipientCountryCode("FR");
        req.setServiceType("usps_priority_mail_international");
        req.setWeight(new BigDecimal("1.5"));
        req.setWeightUnit("kg");
        req.setPackages(List.of(PackageDetailDTO.builder()
                .weight(new BigDecimal("1.5")).weightUnit("kg")
                .packageType("package").build()));
        req.setIntl(IntlShipmentBlockDTO.builder()
                .international(true)
                .incoterms("DDU")
                .reasonForExport("SALE")
                .customsCurrency("EUR")
                .weightUnit("kg")
                .commodities(new java.util.ArrayList<>(List.of(CustomsCommodityDTO.builder()
                        .description("Widget").quantity(2)
                        .unitValue(new BigDecimal("20.00")).unitWeight(new BigDecimal("0.5"))
                        .hsCode("61091012").countryOfOrigin("CN")
                        .sku("SKU-1").build())))
                .build());
        return req;
    }
}
