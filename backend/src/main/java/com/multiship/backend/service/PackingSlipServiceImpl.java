package com.multiship.backend.service;

import com.multiship.backend.model.Address;
import com.multiship.backend.model.Client;
import com.multiship.backend.model.LabelTemplate;
import com.multiship.backend.model.Order;
import com.multiship.backend.model.OrderLine;
import com.multiship.backend.repository.ClientRepository;
import com.multiship.backend.repository.OrderLineRepository;
import com.multiship.backend.repository.OrderRepository;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Sprint 42 — renders the tenant-branded packing slip PDF.
 *
 * <p>Layout (US Letter, 612x792 pt):
 * <pre>
 *   [logo]              [primary-coloured header text]
 *   [horizontal rule]
 *
 *   SHIP TO:                          ORDER: 12345
 *   name                              DATE: 2026-07-27
 *   line1                             CARRIER: UPS
 *   city, state zip                   TRACK: 1Z...
 *   country
 *
 *   [Items table — SKU / description / qty]
 *
 *   [footer text — thank-you, return instructions]
 * </pre>
 *
 * <p>The renderer never throws for missing template pieces: nulls
 * fall back to sensible defaults so we always ship something usable.
 */
@Service
public class PackingSlipServiceImpl implements PackingSlipService {

    private static final Color DEFAULT_PRIMARY = new Color(0x1f, 0x15, 0x0c);
    private static final Color LIGHT_RULE = new Color(0xcc, 0xcc, 0xcc);
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");

    // PDFBox 3.x removed the static font instances — construct per call.
    private static final PDType1Font HELVETICA =
            new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private static final PDType1Font HELVETICA_BOLD =
            new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
    private static final PDType1Font HELVETICA_OBLIQUE =
            new PDType1Font(Standard14Fonts.FontName.HELVETICA_OBLIQUE);

    private final OrderRepository orderRepository;
    private final OrderLineRepository orderLineRepository;
    private final ClientRepository clientRepository;
    private final LabelTemplateService labelTemplateService;

    @Autowired
    public PackingSlipServiceImpl(OrderRepository orderRepository,
                                  OrderLineRepository orderLineRepository,
                                  ClientRepository clientRepository,
                                  LabelTemplateService labelTemplateService) {
        this.orderRepository = orderRepository;
        this.orderLineRepository = orderLineRepository;
        this.clientRepository = clientRepository;
        this.labelTemplateService = labelTemplateService;
    }

    @Override
    public byte[] render(Integer orderNo) {
        Order order = orderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Order not found: " + orderNo));

        String tenantId = order.getTenantId() != null ? order.getTenantId() : order.getCustNo();
        LabelTemplate template = labelTemplateService
                .resolve(tenantId, "PACKING_SLIP")
                .orElse(null);

        List<OrderLine> lines = orderLineRepository.findOrderLinesByOrderNo(orderNo);
        Client client = tenantId == null ? null
                : clientRepository.findByClientCodeIgnoreCase(tenantId).orElse(null);

        return renderInternal(order, lines, client, template);
    }

    /**
     * Package-private so unit tests can drive it directly without
     * touching the repositories.
     */
    byte[] renderInternal(Order order,
                          List<OrderLine> lines,
                          Client client,
                          LabelTemplate template) {
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);

            Color primary = parseColor(template != null ? template.getPrimaryColor() : null);
            String headerText = template != null && template.getHeaderText() != null
                    ? template.getHeaderText()
                    : "PACKING SLIP";
            String footerText = template != null ? template.getFooterText() : null;
            boolean showItems = template == null || Boolean.TRUE.equals(template.getShowItems());
            byte[] logo = decodeLogo(template);

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                float pageWidth = PDRectangle.LETTER.getWidth();
                float pageHeight = PDRectangle.LETTER.getHeight();
                float margin = 40f;
                float y = pageHeight - margin;

                // === Header row: logo left, headerText right ===
                if (logo != null) {
                    PDImageXObject img = PDImageXObject.createFromByteArray(doc, logo, "logo");
                    float logoH = 50f;
                    float logoW = img.getWidth() * (logoH / img.getHeight());
                    if (logoW > 150f) {
                        logoW = 150f;
                        logoH = img.getHeight() * (logoW / img.getWidth());
                    }
                    cs.drawImage(img, margin, y - logoH, logoW, logoH);
                }
                cs.setNonStrokingColor(primary);
                drawText(cs, HELVETICA_BOLD, 20f, headerText,
                        pageWidth - margin - textWidth(HELVETICA_BOLD, 20f, headerText),
                        y - 20f);
                cs.setNonStrokingColor(Color.BLACK);

                y -= 65f;
                cs.setStrokingColor(primary);
                cs.setLineWidth(1.2f);
                cs.moveTo(margin, y);
                cs.lineTo(pageWidth - margin, y);
                cs.stroke();
                cs.setStrokingColor(Color.BLACK);

                // === Two-column info block ===
                float colY = y - 20f;
                float leftCol = margin;
                float rightCol = pageWidth / 2f + 20f;

                drawText(cs, HELVETICA_BOLD, 10f, "SHIP TO",
                        leftCol, colY);
                float leftY = colY - 14f;
                for (String line : formatShipTo(order)) {
                    drawText(cs, HELVETICA, 10f, line, leftCol, leftY);
                    leftY -= 12f;
                }
                if (client != null && client.getShipFrom() != null
                        && client.getShipFrom().hasValue()) {
                    leftY -= 8f;
                    drawText(cs, HELVETICA_BOLD, 10f, "FROM",
                            leftCol, leftY);
                    leftY -= 14f;
                    for (String line : formatAddress(client.getShipFrom())) {
                        drawText(cs, HELVETICA, 10f, line, leftCol, leftY);
                        leftY -= 12f;
                    }
                }

                drawText(cs, HELVETICA_BOLD, 10f, "ORDER DETAILS",
                        rightCol, colY);
                float rightY = colY - 14f;
                for (String line : formatOrderInfo(order)) {
                    drawText(cs, HELVETICA, 10f, line, rightCol, rightY);
                    rightY -= 12f;
                }

                // === Items table ===
                float tableTop = Math.min(leftY, rightY) - 24f;
                if (showItems && !lines.isEmpty()) {
                    cs.setStrokingColor(LIGHT_RULE);
                    cs.setLineWidth(0.5f);
                    cs.moveTo(margin, tableTop + 14f);
                    cs.lineTo(pageWidth - margin, tableTop + 14f);
                    cs.stroke();
                    cs.setStrokingColor(Color.BLACK);

                    cs.setNonStrokingColor(primary);
                    drawText(cs, HELVETICA_BOLD, 10f, "SKU",
                            margin, tableTop);
                    drawText(cs, HELVETICA_BOLD, 10f, "DESCRIPTION",
                            margin + 90f, tableTop);
                    drawText(cs, HELVETICA_BOLD, 10f, "QTY",
                            pageWidth - margin - 30f, tableTop);
                    cs.setNonStrokingColor(Color.BLACK);

                    float itemY = tableTop - 16f;
                    for (OrderLine line : lines) {
                        if (itemY < margin + 80f) break;  // keep footer room
                        String sku = safe(line.getItemNo(), "-");
                        String desc = truncate(safe(line.getItemDescription(),
                                safe(line.getDescription(), "")), 55);
                        String qty = line.getQtyShipped() == null ? "1"
                                : String.valueOf(line.getQtyShipped());
                        drawText(cs, HELVETICA, 10f, sku, margin, itemY);
                        drawText(cs, HELVETICA, 10f, desc, margin + 90f, itemY);
                        drawText(cs, HELVETICA, 10f, qty,
                                pageWidth - margin - 30f, itemY);
                        itemY -= 14f;
                    }
                }

                // === Footer ===
                float footerY = margin + 40f;
                cs.setStrokingColor(LIGHT_RULE);
                cs.moveTo(margin, footerY + 12f);
                cs.lineTo(pageWidth - margin, footerY + 12f);
                cs.stroke();
                cs.setStrokingColor(Color.BLACK);
                if (footerText != null && !footerText.isBlank()) {
                    cs.setNonStrokingColor(primary);
                    for (String line : footerText.split("\\r?\\n")) {
                        drawText(cs, HELVETICA_OBLIQUE, 9f,
                                truncate(line, 110), margin, footerY);
                        footerY -= 11f;
                        if (footerY < margin) break;
                    }
                    cs.setNonStrokingColor(Color.BLACK);
                }
            }

            doc.save(out);
            return out.toByteArray();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to render packing slip", e);
        }
    }

    private static void drawText(PDPageContentStream cs, PDType1Font font,
                                 float size, String text, float x, float y)
            throws java.io.IOException {
        if (text == null || text.isBlank()) return;
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, y);
        cs.showText(sanitise(text));
        cs.endText();
    }

    private static float textWidth(PDType1Font font, float size, String text) {
        try {
            return font.getStringWidth(sanitise(text)) / 1000f * size;
        } catch (Exception e) {
            return size * 0.5f * (text == null ? 0 : text.length());
        }
    }

    /** Standard 14 Helvetica only supports WinAnsi — strip anything else. */
    private static String sanitise(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 32 && c <= 126) out.append(c);
            else if (c == '–' || c == '—') out.append('-');
            else out.append('?');
        }
        return out.toString();
    }

    private static Color parseColor(String hex) {
        if (hex == null || hex.isBlank()) return DEFAULT_PRIMARY;
        try {
            String h = hex.startsWith("#") ? hex.substring(1) : hex;
            if (h.length() == 3) {
                h = "" + h.charAt(0) + h.charAt(0)
                       + h.charAt(1) + h.charAt(1)
                       + h.charAt(2) + h.charAt(2);
            }
            if (h.length() != 6) return DEFAULT_PRIMARY;
            int r = Integer.parseInt(h.substring(0, 2), 16);
            int g = Integer.parseInt(h.substring(2, 4), 16);
            int b = Integer.parseInt(h.substring(4, 6), 16);
            return new Color(r, g, b);
        } catch (Exception e) {
            return DEFAULT_PRIMARY;
        }
    }

    private static byte[] decodeLogo(LabelTemplate template) {
        if (template == null || template.getLogoBase64() == null
                || template.getLogoBase64().isBlank()) return null;
        String data = template.getLogoBase64();
        int comma = data.indexOf(',');
        if (data.startsWith("data:") && comma > 0) data = data.substring(comma + 1);
        try {
            return Base64.getDecoder().decode(data);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static List<String> formatShipTo(Order order) {
        return List.of(
                safe(order.getShipName(), safe(order.getShipAttn(), "")),
                safe(order.getShipAddr1(), ""),
                joinCityStateZip(order.getShiptoCity(), order.getShiptoState(),
                        order.getShiptoZip()),
                safe(order.getCountryName(), safe(order.getShiptoCountryCd(), ""))
        );
    }

    private static List<String> formatAddress(Address a) {
        return List.of(
                safe(a.getName(), ""),
                safe(a.getLine1(), ""),
                joinCityStateZip(a.getCity(), a.getState(), a.getZip()),
                safe(a.getCountry(), "")
        );
    }

    private static List<String> formatOrderInfo(Order order) {
        return List.of(
                "ORDER: " + safe(order.getOrderNo() == null ? null
                        : order.getOrderNo().toString(), "-"),
                "DATE:  " + (order.getCreatedDate() == null ? "-"
                        : DATE_FMT.format(order.getCreatedDate())),
                "CARRIER: " + safe(order.getShipVia(),
                        safe(order.getShipviaCd(), "-")),
                "TRACK: " + safe(order.getTrack(), "-")
        );
    }

    private static String joinCityStateZip(String city, String state, String zip) {
        StringBuilder sb = new StringBuilder();
        if (city != null && !city.isBlank()) sb.append(city);
        if (state != null && !state.isBlank()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(state);
        }
        if (zip != null && !zip.isBlank()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(zip);
        }
        return sb.toString();
    }

    private static String safe(String v, String fallback) {
        return v == null || v.isBlank() ? fallback : v;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
