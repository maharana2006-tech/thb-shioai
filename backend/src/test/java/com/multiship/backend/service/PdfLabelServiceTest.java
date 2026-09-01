package com.multiship.backend.service;

import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.dto.LabelPackageDTO;
import com.multiship.backend.dto.OrderAccountResolutionDTO;
import com.multiship.backend.dto.OrderResponseDTO;
import com.multiship.backend.dto.OrderWithLinesDTO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 52 PR A — PdfLabelService test. Verifies the facsimile PDF
 * renders at 4x6" (thermal-label size), carries the same fields as the
 * ZPL sibling, and produces one page per package on multi-box shipments.
 */
class PdfLabelServiceTest {

    private CarrierProperties carrierProperties;
    private PdfLabelService service;

    @BeforeEach
    void setUp() {
        carrierProperties = new CarrierProperties();
        // CarrierProperties.shipper is a final field initialised at
        // declaration — no setter. Populate the existing ShipperDefaults
        // instance in place.
        CarrierProperties.ShipperDefaults shipper = carrierProperties.getShipper();
        shipper.setName("Acme Warehouse");
        shipper.setAddressLine1("100 Main St");
        shipper.setCity("Denver");
        shipper.setState("CO");
        shipper.setPostalCode("80202");
        shipper.setCountryCode("US");

        service = new PdfLabelService(carrierProperties);
    }

    // ─── Basic shape ───────────────────────────────────────────────────

    @Test
    void singlePackage_producesNonEmptyPdfAtFourBySixInches() throws Exception {
        byte[] pdf = service.buildLabel(baseOrder(), fedexResolution(), labelDetails());

        assertNotNull(pdf);
        assertTrue(pdf.length > 0, "PDF must have bytes");

        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertEquals(1, doc.getNumberOfPages(), "single package = 1 page");
            var page = doc.getPage(0);
            // 4x6" thermal label: 288pt x 432pt (72dpi). Allow ±0.5pt for
            // float rounding in PDFBox's PDRectangle constructors.
            assertEquals(288f, page.getMediaBox().getWidth(), 0.5f,
                    "page width must be 4 inches (288pt)");
            assertEquals(432f, page.getMediaBox().getHeight(), 0.5f,
                    "page height must be 6 inches (432pt)");
        }
    }

    @Test
    void multiPagePdf_pageCountMatchesPackageRange() throws Exception {
        // 3 packages, request all -> 3 pages. Mirrors the ZPL endpoint's
        // "no ?pkg on multi-box returns all boxes" behaviour.
        List<LabelPackageDTO> pkgs = List.of(
                pkg(1, "TRACK-A", new BigDecimal("2.5")),
                pkg(2, "TRACK-B", new BigDecimal("2.5")),
                pkg(3, "TRACK-C", new BigDecimal("2.5")));

        byte[] pdf = service.buildMultiPagePdf(
                baseOrder(), fedexResolution(), labelDetails(), 1, 3, 3, pkgs);

        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertEquals(3, doc.getNumberOfPages(),
                    "3-package shipment must produce 3 pages");
        }
    }

    @Test
    void singlePackageOfMultiRange_producesOnePage() throws Exception {
        // ?pkg=2 on a 3-box shipment -> firstPkg=lastPkg=2, one page.
        List<LabelPackageDTO> pkgs = List.of(
                pkg(1, "TRACK-A", new BigDecimal("2.5")),
                pkg(2, "TRACK-B", new BigDecimal("2.5")),
                pkg(3, "TRACK-C", new BigDecimal("2.5")));

        byte[] pdf = service.buildMultiPagePdf(
                baseOrder(), fedexResolution(), labelDetails(), 2, 2, 3, pkgs);

        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertEquals(1, doc.getNumberOfPages(),
                    "single-package pick on multi-box must be 1 page");
        }
    }

    // ─── Field rendering ───────────────────────────────────────────────

    @Test
    void carriesRecipientTrackingCarrierPkgIndex_onThePage() throws Exception {
        byte[] pdf = service.buildLabel(baseOrder(), fedexResolution(), labelDetails());
        String text;
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            text = new PDFTextStripper().getText(doc);
        }

        assertTrue(text.contains("Jane Doe"), "recipient name must render");
        assertTrue(text.contains("1 Market St"), "recipient street must render");
        assertTrue(text.contains("San Francisco"), "recipient city must render");
        assertTrue(text.contains("94105"), "recipient postal must render");
        assertTrue(text.contains("1Z999AA10123456784"), "tracking number must render");
        assertTrue(text.contains("FEDEX"), "carrier badge must render");
        assertTrue(text.contains("Acme Warehouse"), "shipper name must render from CarrierProperties.ShipperDefaults");
    }

    @Test
    void multiPackage_pageShowsPkgNumberOverN() throws Exception {
        List<LabelPackageDTO> pkgs = List.of(
                pkg(1, "TRACK-A", new BigDecimal("2.5")),
                pkg(2, "TRACK-B", new BigDecimal("2.5")));
        byte[] pdf = service.buildMultiPagePdf(
                baseOrder(), fedexResolution(), labelDetails(), 1, 2, 2, pkgs);

        try (PDDocument doc = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(doc);
            assertTrue(text.contains("PKG 1/2"), "page 1 must carry PKG 1/2");
            assertTrue(text.contains("PKG 2/2"), "page 2 must carry PKG 2/2");
            // Per-package tracking overrides the shipment master.
            assertTrue(text.contains("TRACK-A"), "per-pkg tracking A must render");
            assertTrue(text.contains("TRACK-B"), "per-pkg tracking B must render");
        }
    }

    @Test
    void ungeneratedLabel_rendersPlaceholderInsteadOfNull() throws Exception {
        // Label preview before a Generate Label call — no tracking yet.
        // Facsimile must render a legible placeholder rather than "null".
        byte[] pdf = service.buildLabel(baseOrder(), fedexResolution(), null);
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(doc);
            assertTrue(text.contains("not generated"),
                    "ungenerated label must show a legible placeholder in the tracking slot");
        }
    }

    // ─── Helpers ───────────────────────────────────────────────────────

    private OrderWithLinesDTO baseOrder() {
        OrderWithLinesDTO o = new OrderWithLinesDTO();
        o.setOrderNo(900007);
        o.setShipName("Jane Doe");
        o.setShipAttn(null);
        o.setShipAddr1("1 Market St");
        o.setShiptoCity("San Francisco");
        o.setShiptoState("CA");
        o.setShiptoZip("94105");
        o.setShiptoCountryCd("US");
        o.setShipviaCd("FEDEX_GROUND");
        o.setWeight(new BigDecimal("2.5"));
        o.setCreatedDate(java.time.LocalDate.of(2026, 8, 29));
        o.setPackageCount(1);
        return o;
    }

    private OrderAccountResolutionDTO fedexResolution() {
        OrderAccountResolutionDTO r = new OrderAccountResolutionDTO();
        r.setCarrierCode("FEDEX");
        r.setAccountNumber("ACC1");
        return r;
    }

    private OrderResponseDTO.LabelDetails labelDetails() {
        return OrderResponseDTO.LabelDetails.builder()
                .isGenerated(true)
                .trackingNumber("1Z999AA10123456784")
                .generatedAt(LocalDateTime.of(2026, 8, 29, 10, 0))
                .build();
    }

    private LabelPackageDTO pkg(int seq, String tracking, BigDecimal weight) {
        LabelPackageDTO p = new LabelPackageDTO();
        p.setSequenceNumber(seq);
        p.setTrackingNumber(tracking);
        p.setWeight(weight);
        return p;
    }
}
