package com.multiship.backend.service;

import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.dto.LabelPackageDTO;
import com.multiship.backend.dto.OrderAccountResolutionDTO;
import com.multiship.backend.dto.OrderResponseDTO;
import com.multiship.backend.dto.OrderWithLinesDTO;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Sprint 52 PR A — 4x6" (thermal-label size) PDF facsimile of the
 * shipping label, mirroring the same source data as
 * {@link ZplLabelService} so the two downloads (PDF + ZPL) show
 * identical content in their respective formats.
 *
 * <p>This is a FACSIMILE, not the carrier's canonical label. Order
 * 900007 showed the divergence between the on-screen HTML preview and
 * the ZPL download; this service is the sibling of the ZPL renderer,
 * pouring the same order fields into a PDF layout so operators can
 * download and preview in either format. PR B will layer in
 * carrier-artifact passthrough (return the carrier's real bytes when
 * they exist in the requested format).
 *
 * <p>Layout is intentionally minimal — no fake barcodes, no decorative
 * carrier logos. The point is data legibility: recipient block,
 * shipper block, tracking, service, package N of M, date. What
 * operators need to verify before printing. PDFBox is already a
 * project dep (see {@link com.multiship.backend.service.PackingSlipServiceImpl}
 * and {@link com.multiship.backend.service.template.TemplatePdfRenderer}).
 */
@Service
@RequiredArgsConstructor
public class PdfLabelService {

    /** 4x6" thermal-label size in points (1 in = 72 pt). */
    private static final float LABEL_WIDTH_PT = 4f * 72f;
    private static final float LABEL_HEIGHT_PT = 6f * 72f;
    /** Inner margin so text doesn't crash into the thermal printer's
     *  unprintable edge zone (~1/8" on Zebra printers). */
    private static final float MARGIN_PT = 9f; // ~1/8 inch

    private static final DateTimeFormatter LABEL_DATE = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.US);

    private static final PDType1Font HELVETICA =
            new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private static final PDType1Font HELVETICA_BOLD =
            new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

    private static final Color BLACK = new Color(0, 0, 0);
    private static final Color MUTED = new Color(0x70, 0x70, 0x70);
    private static final Color RULE = new Color(0xc0, 0xc0, 0xc0);

    private final CarrierProperties carrierProperties;

    /** Single-page overload — matches ZplLabelService's back-compat pair. */
    public byte[] buildLabel(OrderWithLinesDTO order,
                              OrderAccountResolutionDTO resolution,
                              OrderResponseDTO.LabelDetails label) {
        return buildLabel(order, resolution, label, 1, 1, null);
    }

    /**
     * Full signature — matches {@link ZplLabelService#buildLabel} so the
     * controller can call either service with the same args and pipe
     * per-package overrides through unchanged.
     *
     * <p>For multi-package: caller invokes this once per package (or uses
     * {@link #buildMultiPagePdf} which loops internally and concatenates
     * to a single PDF).
     */
    public byte[] buildLabel(OrderWithLinesDTO order,
                              OrderAccountResolutionDTO resolution,
                              OrderResponseDTO.LabelDetails label,
                              int pkgIndex,
                              int pkgCount,
                              LabelPackageDTO perPkg) {
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            addPage(doc, order, resolution, label, pkgIndex, pkgCount, perPkg);
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            // Never throw from a label renderer — carriers reject retries.
            // A blank one-page PDF is preferable to a 500 that leaves the
            // operator stuck. Matches PackingSlipServiceImpl's error handling.
            return blankPdf();
        }
    }

    /**
     * Multi-package: one PDF with N pages, one per package. Matches the
     * ZPL endpoint's behaviour where {@code ?pkg} omitted on a multi-box
     * shipment returns all boxes' labels concatenated.
     */
    public byte[] buildMultiPagePdf(OrderWithLinesDTO order,
                                     OrderAccountResolutionDTO resolution,
                                     OrderResponseDTO.LabelDetails label,
                                     int firstPkg,
                                     int lastPkg,
                                     int pkgCount,
                                     List<LabelPackageDTO> perPkgs) {
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int p = firstPkg; p <= lastPkg; p++) {
                final int currentPkg = p;
                LabelPackageDTO perPkg = perPkgs == null ? null : perPkgs.stream()
                        .filter(x -> x.getSequenceNumber() != null && x.getSequenceNumber() == currentPkg)
                        .findFirst().orElse(null);
                addPage(doc, order, resolution, label, currentPkg, pkgCount, perPkg);
            }
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            return blankPdf();
        }
    }

    // ─── Page rendering ─────────────────────────────────────────────────────

    private void addPage(PDDocument doc,
                          OrderWithLinesDTO order,
                          OrderAccountResolutionDTO resolution,
                          OrderResponseDTO.LabelDetails label,
                          int pkgIndex,
                          int pkgCount,
                          LabelPackageDTO perPkg) throws IOException {
        PDPage page = new PDPage(new PDRectangle(LABEL_WIDTH_PT, LABEL_HEIGHT_PT));
        doc.addPage(page);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            float y = LABEL_HEIGHT_PT - MARGIN_PT;

            // ─── Header band: carrier + service + pkg ───────────────────
            String carrier = displayCarrier(resolution);
            String service = displayService(order, resolution);
            y = drawText(cs, HELVETICA_BOLD, 14f, BLACK, MARGIN_PT, y - 12f, carrier);
            y = drawText(cs, HELVETICA, 10f, MUTED, MARGIN_PT, y - 12f, service);
            if (pkgCount > 1) {
                drawText(cs, HELVETICA_BOLD, 10f, BLACK,
                        LABEL_WIDTH_PT - MARGIN_PT - 40f, LABEL_HEIGHT_PT - MARGIN_PT - 12f,
                        "PKG " + pkgIndex + "/" + pkgCount);
            }
            y = rule(cs, y - 6f);

            // ─── SHIP TO block ──────────────────────────────────────────
            y = drawText(cs, HELVETICA_BOLD, 8f, MUTED, MARGIN_PT, y - 12f, "SHIP TO");
            y -= 2f;
            String shipName = firstNonBlank(order.getShipName(), order.getShipAttn(), "—");
            y = drawText(cs, HELVETICA_BOLD, 12f, BLACK, MARGIN_PT, y - 12f, shipName);
            y = drawText(cs, HELVETICA, 10f, BLACK, MARGIN_PT, y - 12f,
                    firstNonBlank(order.getShipAddr1(), ""));
            String cityStateZip = String.join(", ",
                    firstNonBlank(order.getShiptoCity(), ""),
                    firstNonBlank(order.getShiptoState(), "") + " " + firstNonBlank(order.getShiptoZip(), ""));
            y = drawText(cs, HELVETICA, 10f, BLACK, MARGIN_PT, y - 12f, cityStateZip.trim());
            String destCountry = firstNonBlank(order.getShiptoCountryCd(), "");
            if (StringUtils.hasText(destCountry)) {
                y = drawText(cs, HELVETICA, 10f, BLACK, MARGIN_PT, y - 12f, destCountry.toUpperCase(Locale.ROOT));
            }
            y = rule(cs, y - 8f);

            // ─── SHIP FROM block ────────────────────────────────────────
            CarrierProperties.ShipperDefaults shipper = carrierProperties.getShipper();
            y = drawText(cs, HELVETICA_BOLD, 8f, MUTED, MARGIN_PT, y - 12f, "SHIP FROM");
            y -= 2f;
            y = drawText(cs, HELVETICA, 10f, BLACK, MARGIN_PT, y - 12f, firstNonBlank(shipper.getName(), ""));
            y = drawText(cs, HELVETICA, 10f, BLACK, MARGIN_PT, y - 12f, firstNonBlank(shipper.getAddressLine1(), ""));
            y = drawText(cs, HELVETICA, 10f, BLACK, MARGIN_PT, y - 12f,
                    String.join(", ",
                            firstNonBlank(shipper.getCity(), ""),
                            firstNonBlank(shipper.getState(), "") + " " + firstNonBlank(shipper.getPostalCode(), "")).trim());
            y = rule(cs, y - 8f);

            // ─── Tracking + reference ───────────────────────────────────
            String trackingNumber = perPkg != null && StringUtils.hasText(perPkg.getTrackingNumber())
                    ? perPkg.getTrackingNumber()
                    : (label != null ? label.getTrackingNumber() : null);
            y = drawText(cs, HELVETICA_BOLD, 8f, MUTED, MARGIN_PT, y - 12f, "TRACKING #");
            y = drawText(cs, HELVETICA_BOLD, 12f, BLACK, MARGIN_PT, y - 14f,
                    StringUtils.hasText(trackingNumber) ? trackingNumber : "— not generated —");

            String reference = firstNonBlank(
                    order.getOrderNo() != null ? String.valueOf(order.getOrderNo()) : null,
                    "");
            y = drawText(cs, HELVETICA, 9f, MUTED, MARGIN_PT, y - 14f, "REF " + reference);

            // ─── Footer: date + weight ──────────────────────────────────
            float footerY = MARGIN_PT + 24f;
            rule(cs, footerY + 12f);
            // order.getCreatedDate() is LocalDate; label.getGeneratedAt is
            // LocalDateTime. Format each with the same day-precision pattern.
            String dateStr;
            if (label != null && label.getGeneratedAt() != null) {
                dateStr = label.getGeneratedAt().format(LABEL_DATE);
            } else if (order.getCreatedDate() != null) {
                dateStr = order.getCreatedDate().format(LABEL_DATE);
            } else {
                dateStr = "";
            }
            drawText(cs, HELVETICA, 9f, MUTED, MARGIN_PT, footerY, "SHIP DATE  " + dateStr);
            java.math.BigDecimal weight = perPkg != null && perPkg.getWeight() != null
                    ? perPkg.getWeight() : order.getWeight();
            String weightStr = weight != null ? weight.stripTrailingZeros().toPlainString() + " LB" : "";
            drawText(cs, HELVETICA, 9f, MUTED, LABEL_WIDTH_PT - MARGIN_PT - 60f, footerY, "WT " + weightStr);
        }
    }

    // ─── Draw helpers ───────────────────────────────────────────────────────

    private float drawText(PDPageContentStream cs, PDType1Font font, float size, Color color,
                            float x, float y, String text) throws IOException {
        String safe = text == null ? "" : text;
        cs.setNonStrokingColor(color);
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, y);
        cs.showText(safe);
        cs.endText();
        return y;
    }

    private float rule(PDPageContentStream cs, float y) throws IOException {
        cs.setStrokingColor(RULE);
        cs.setLineWidth(0.5f);
        cs.moveTo(MARGIN_PT, y);
        cs.lineTo(LABEL_WIDTH_PT - MARGIN_PT, y);
        cs.stroke();
        return y - 2f;
    }

    // ─── Data resolution helpers ────────────────────────────────────────────

    private String displayCarrier(OrderAccountResolutionDTO resolution) {
        if (resolution == null || !StringUtils.hasText(resolution.getCarrierCode())) return "CARRIER";
        String c = resolution.getCarrierCode().toUpperCase(Locale.ROOT);
        if (c.startsWith("FEDEX") || "F77".equals(c)) return "FEDEX";
        if (c.startsWith("UPS") || "P80".equals(c)) return "UPS";
        if (c.startsWith("USPS") || "L01".equals(c)) return "USPS";
        if (c.startsWith("DHL")) return "DHL";
        return c;
    }

    private String displayService(OrderWithLinesDTO order, OrderAccountResolutionDTO resolution) {
        String service = order != null ? order.getShipviaCd() : null;
        if (!StringUtils.hasText(service) && resolution != null) service = resolution.getCarrierCode();
        return StringUtils.hasText(service) ? service.replace('_', ' ') : "";
    }

    private String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String v : values) if (StringUtils.hasText(v)) return v;
        return "";
    }

    private byte[] blankPdf() {
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.addPage(new PDPage(new PDRectangle(LABEL_WIDTH_PT, LABEL_HEIGHT_PT)));
            doc.save(out);
            return out.toByteArray();
        } catch (IOException fatal) {
            return new byte[0];
        }
    }
}
