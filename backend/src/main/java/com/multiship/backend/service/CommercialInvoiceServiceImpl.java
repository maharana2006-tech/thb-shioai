package com.multiship.backend.service;

import com.multiship.backend.model.Address;
import com.multiship.backend.model.Client;
import com.multiship.backend.model.ClientCustomsProfile;
import com.multiship.backend.model.Order;
import com.multiship.backend.model.OrderCustoms;
import com.multiship.backend.model.OrderCustomsItem;
import com.multiship.backend.model.CarrierAccountRef;
import com.multiship.backend.repository.CarrierAccountRefRepository;
import com.multiship.backend.repository.ClientCustomsProfileRepository;
import com.multiship.backend.repository.ClientRepository;
import com.multiship.backend.repository.OrderCustomsRepository;
import com.multiship.backend.repository.OrderRepository;
import com.multiship.backend.repository.OrderTrackingRepository;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Sprint 51 fix #3 — in-house commercial-invoice PDF renderer (PDFBox),
 * modelled on {@link PackingSlipServiceImpl}. Assembles the three invoice
 * parties (exporter = client ship-from, importer of record = resolved from
 * the order's customs data / client profile / consignee, and the consignee =
 * order ship-to) plus the customs line items persisted on the order, and
 * lays them out on a single US-Letter page.
 */
@Service
public class CommercialInvoiceServiceImpl implements CommercialInvoiceService {

    private static final Color PRIMARY = new Color(0x1f, 0x15, 0x0c);
    private static final Color LIGHT_RULE = new Color(0xcc, 0xcc, 0xcc);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd MMM yyyy");
    private static final PDType1Font HELVETICA =
            new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private static final PDType1Font HELVETICA_BOLD =
            new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
    private static final PDType1Font HELVETICA_OBLIQUE =
            new PDType1Font(Standard14Fonts.FontName.HELVETICA_OBLIQUE);

    private final OrderRepository orderRepository;
    private final OrderCustomsRepository orderCustomsRepository;
    private final ClientRepository clientRepository;
    private final ClientCustomsProfileRepository customsProfileRepository;
    private final OrderTrackingRepository orderTrackingRepository;
    private final CarrierAccountRefRepository accountRefRepository;

    public CommercialInvoiceServiceImpl(OrderRepository orderRepository,
                                        OrderCustomsRepository orderCustomsRepository,
                                        ClientRepository clientRepository,
                                        ClientCustomsProfileRepository customsProfileRepository,
                                        OrderTrackingRepository orderTrackingRepository,
                                        CarrierAccountRefRepository accountRefRepository) {
        this.orderRepository = orderRepository;
        this.orderCustomsRepository = orderCustomsRepository;
        this.clientRepository = clientRepository;
        this.customsProfileRepository = customsProfileRepository;
        this.orderTrackingRepository = orderTrackingRepository;
        this.accountRefRepository = accountRefRepository;
    }

    /**
     * The order's owning client code. Tenant orders carry it in
     * {@code tenantId} / {@code custNo}; manual + bulk orders persist
     * {@code custNo="MANUAL"} and lose the linkage, so we recover it from
     * the billing account (the account book maps account number → client).
     */
    private String resolveClientCode(Order order, Integer orderNo) {
        String code = firstNonBlank(order.getTenantId(), order.getCustNo());
        if (code != null && !"MANUAL".equalsIgnoreCase(code)
                && clientRepository.findByClientCodeIgnoreCase(code).isPresent()) {
            return code;
        }
        // Fallback: order → tracking account number → account book → client.
        String acct = orderTrackingRepository.findByOrderNo(orderNo)
                .map(t -> t.getAccountNumber()).orElse(null);
        if (hasText(acct)) {
            String fromAccount = accountRefRepository
                    .findFirstByAccountNumberIgnoreCaseOrderByUpdatedAtDesc(acct.trim())
                    .map(CarrierAccountRef::getCustomerNo).orElse(null);
            if (hasText(fromAccount)) return fromAccount;
        }
        return code; // may be "MANUAL"/null — callers degrade gracefully
    }

    @Override
    public byte[] render(Integer orderNo) {
        Order order = orderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> new IllegalArgumentException("Order " + orderNo + " not found"));

        OrderCustoms customs = orderCustomsRepository
                .findByOrderNoIgnoreCase(String.valueOf(orderNo))
                .orElseThrow(() -> new IllegalStateException(
                        "Order " + orderNo + " has no customs data — a commercial invoice "
                        + "only applies to international shipments."));

        String clientCode = resolveClientCode(order, orderNo);
        Client client = clientCode == null ? null
                : clientRepository.findByClientCodeIgnoreCase(clientCode).orElse(null);

        // Importer of record: the order's own customs importer wins; else the
        // client's customs profile for the destination country; else fall back
        // to the consignee (ship-to), which is the DAP default.
        Party importer = resolveImporter(order, customs, clientCode);

        return renderPdf(order, customs, client, importer);
    }

    // ---- party resolution ------------------------------------------------

    /** A rendered address block: a name/company line plus street lines. */
    private record Party(String title, List<String> lines) {}

    private Party resolveImporter(Order order, OrderCustoms customs, String clientCode) {
        // 1) Explicit importer captured on the order's customs record.
        Address ia = customs.getImporterAddress();
        String iaName = ia == null ? null : ia.getName();
        if (hasText(iaName) || hasText(customs.getImporterCompany())
                || (ia != null && hasText(ia.getLine1()))) {
            return new Party("IMPORTER OF RECORD", List.of(
                    firstNonBlank(iaName, customs.getImporterCompany(), ""),
                    ia == null ? "" : safe(ia.getLine1()),
                    ia == null ? "" : joinCityStateZip(ia.getCity(), ia.getState(), ia.getZip()),
                    ia == null ? "" : safe(ia.getCountry()),
                    taxLine(customs.getImporterTaxId(), customs.getImporterVat(), customs.getImporterEori())));
        }

        // 2) Client's customs profile for the destination country.
        String dest = order.getShiptoCountryCd();
        if (customsProfileRepository != null && hasText(clientCode) && hasText(dest)) {
            ClientCustomsProfile p = customsProfileRepository
                    .findByClientAndCountry(clientCode, dest).orElse(null);
            if (p != null && (hasText(p.getImporterName()) || hasText(p.getImporterAddress1()))) {
                String ids = taxLine(
                        firstNonBlank(p.getImporterGstin(), p.getImporterIec(), p.getImporterTaxId()),
                        null, p.getImporterEori());
                return new Party("IMPORTER OF RECORD", List.of(
                        safe(p.getImporterName()),
                        safe(p.getImporterAddress1()),
                        joinCityStateZip(p.getImporterCity(), p.getImporterState(), p.getImporterPostcode()),
                        safe(p.getImporterCountry()),
                        ids));
            }
        }

        // 3) Consignee (ship-to) is the importer of record (DAP default).
        return new Party("IMPORTER OF RECORD (CONSIGNEE)", shipToLines(order));
    }

    private static List<String> shipToLines(Order order) {
        return List.of(
                firstNonBlank(order.getShipName(), order.getShipAttn(), ""),
                safe(order.getShipAddr1()),
                joinCityStateZip(order.getShiptoCity(), order.getShiptoState(), order.getShiptoZip()),
                firstNonBlank(order.getCountryName(), order.getShiptoCountryCd(), ""));
    }

    private static List<String> exporterLines(Client client) {
        if (client == null || client.getShipFrom() == null) {
            return List.of("—");
        }
        Address a = client.getShipFrom();
        return List.of(
                firstNonBlank(a.getName(), client.getName(), ""),
                safe(a.getLine1()),
                joinCityStateZip(a.getCity(), a.getState(), a.getZip()),
                safe(a.getCountry()));
    }

    // ---- rendering -------------------------------------------------------

    private byte[] renderPdf(Order order, OrderCustoms customs, Client client, Party importer) {
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            float pageWidth = PDRectangle.LETTER.getWidth();
            float pageHeight = PDRectangle.LETTER.getHeight();
            float margin = 40f;

            String currency = firstNonBlank(customs.getCurrency(), "USD");

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                float y = pageHeight - margin;

                // Header
                cs.setNonStrokingColor(PRIMARY);
                drawText(cs, HELVETICA_BOLD, 20f, "COMMERCIAL INVOICE",
                        pageWidth - margin - textWidth(HELVETICA_BOLD, 20f, "COMMERCIAL INVOICE"), y - 20f);
                cs.setNonStrokingColor(Color.BLACK);
                if (client != null) {
                    drawText(cs, HELVETICA_BOLD, 12f, safe(client.getName()), margin, y - 12f);
                }
                y -= 40f;
                rule(cs, margin, pageWidth - margin, y, PRIMARY, 1.2f);

                // Invoice meta (single line of key fields)
                y -= 18f;
                String meta = "Invoice No: " + order.getOrderNo()
                        + "    Date: " + (order.getCreatedDate() == null ? "-" : DATE_FMT.format(order.getCreatedDate()))
                        + "    Incoterms: " + firstNonBlank(customs.getIncoterms(), "-")
                        + "    Currency: " + currency
                        + "    Reason: " + firstNonBlank(customs.getReasonForExport(), "SALE");
                drawText(cs, HELVETICA, 9.5f, meta, margin, y);
                y -= 8f;
                String meta2 = "Carrier: " + firstNonBlank(order.getShipVia(), order.getShipviaCd(), "-")
                        + "    Tracking: " + firstNonBlank(order.getTrack(), "-");
                drawText(cs, HELVETICA, 9.5f, meta2, margin, y - 4f);

                // Three party blocks: exporter (left) + importer (right), consignee (left, lower)
                y -= 26f;
                float leftCol = margin;
                float rightCol = pageWidth / 2f + 20f;

                float leftY = drawParty(cs, "EXPORTER / SHIP FROM", exporterLines(client), leftCol, y);
                float rightY = drawParty(cs, importer.title(), importer.lines(), rightCol, y);
                float consY = drawParty(cs, "CONSIGNEE / SHIP TO", shipToLines(order), leftCol, leftY - 8f);

                // Items table
                float tableTop = Math.min(consY, rightY) - 12f;
                tableTop = drawItemsTable(cs, customs, currency, margin, pageWidth - margin, tableTop);

                // Declaration + signature
                float decY = Math.max(tableTop - 20f, margin + 46f);
                cs.setNonStrokingColor(new Color(0x5a, 0x45, 0x26));
                drawText(cs, HELVETICA_OBLIQUE, 8.5f,
                        "I declare the information on this invoice to be true and correct to the best of my knowledge.",
                        margin, decY);
                cs.setNonStrokingColor(Color.BLACK);
                rule(cs, pageWidth - margin - 180f, pageWidth - margin, margin + 24f, LIGHT_RULE, 0.6f);
                drawText(cs, HELVETICA, 8f, "Authorised signature / date", pageWidth - margin - 180f, margin + 12f);
            }

            doc.save(out);
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed to render commercial invoice for order "
                    + order.getOrderNo(), e);
        }
    }

    /** Draws a titled address block; returns the y just below it. */
    private float drawParty(PDPageContentStream cs, String title, List<String> lines, float x, float y)
            throws java.io.IOException {
        cs.setNonStrokingColor(PRIMARY);
        drawText(cs, HELVETICA_BOLD, 9.5f, title, x, y);
        cs.setNonStrokingColor(Color.BLACK);
        float ly = y - 13f;
        for (String line : lines) {
            if (line == null || line.isBlank()) continue;
            drawText(cs, HELVETICA, 9.5f, line, x, ly);
            ly -= 11.5f;
        }
        return ly;
    }

    /** Renders the line-item table + totals; returns the y below the totals. */
    private float drawItemsTable(PDPageContentStream cs, OrderCustoms customs, String currency,
                                 float left, float right, float top) throws java.io.IOException {
        // Column x-positions.
        float xDesc = left;
        float xHs = left + 210f;
        float xOrig = left + 275f;
        float xQty = left + 320f;
        float xUnit = left + 370f;
        float xAmt = right - textWidth(HELVETICA_BOLD, 9f, "AMOUNT");

        // Header row.
        cs.setNonStrokingColor(PRIMARY);
        drawText(cs, HELVETICA_BOLD, 9f, "DESCRIPTION", xDesc, top);
        drawText(cs, HELVETICA_BOLD, 9f, "HS CODE", xHs, top);
        drawText(cs, HELVETICA_BOLD, 9f, "ORIGIN", xOrig, top);
        drawText(cs, HELVETICA_BOLD, 9f, "QTY", xQty, top);
        drawText(cs, HELVETICA_BOLD, 9f, "UNIT", xUnit, top);
        drawText(cs, HELVETICA_BOLD, 9f, "AMOUNT", xAmt, top);
        cs.setNonStrokingColor(Color.BLACK);
        rule(cs, left, right, top - 4f, LIGHT_RULE, 0.5f);

        float rowY = top - 16f;
        BigDecimal total = BigDecimal.ZERO;
        List<OrderCustomsItem> items = customs.getItems() == null ? List.of() : customs.getItems();
        for (OrderCustomsItem it : items) {
            if (rowY < 90f) break; // preserve declaration/footer room
            int qty = it.getQuantity() == null ? 1 : it.getQuantity();
            BigDecimal unit = it.getUnitValue() == null ? BigDecimal.ZERO : it.getUnitValue();
            BigDecimal amount = unit.multiply(BigDecimal.valueOf(qty));
            total = total.add(amount);

            drawText(cs, HELVETICA, 9f, truncate(safe(it.getDescription()), 42), xDesc, rowY);
            drawText(cs, HELVETICA, 9f, safe(it.getHsCode()), xHs, rowY);
            drawText(cs, HELVETICA, 9f, safe(it.getCountryOfOrigin()), xOrig, rowY);
            drawText(cs, HELVETICA, 9f, String.valueOf(qty), xQty, rowY);
            drawText(cs, HELVETICA, 9f, money(unit), xUnit, rowY);
            drawRightText(cs, HELVETICA, 9f, money(amount), right, rowY);
            rowY -= 13f;
        }

        rule(cs, left, right, rowY - 2f, LIGHT_RULE, 0.5f);
        rowY -= 16f;
        String totalLabel = "TOTAL (" + currency + ")";
        cs.setNonStrokingColor(PRIMARY);
        drawText(cs, HELVETICA_BOLD, 10f, totalLabel, xUnit, rowY);
        drawRightText(cs, HELVETICA_BOLD, 10f, money(total), right, rowY);
        cs.setNonStrokingColor(Color.BLACK);
        return rowY;
    }

    // ---- low-level PDFBox + formatting helpers (mirrors PackingSlipServiceImpl) ----

    private static void rule(PDPageContentStream cs, float x1, float x2, float y, Color c, float w)
            throws java.io.IOException {
        cs.setStrokingColor(c);
        cs.setLineWidth(w);
        cs.moveTo(x1, y);
        cs.lineTo(x2, y);
        cs.stroke();
        cs.setStrokingColor(Color.BLACK);
    }

    private static void drawText(PDPageContentStream cs, PDType1Font font, float size, String text,
                                 float x, float y) throws java.io.IOException {
        if (text == null || text.isBlank()) return;
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, y);
        cs.showText(sanitise(text));
        cs.endText();
    }

    private static void drawRightText(PDPageContentStream cs, PDType1Font font, float size, String text,
                                      float rightX, float y) throws java.io.IOException {
        drawText(cs, font, size, text, rightX - textWidth(font, size, text), y);
    }

    private static float textWidth(PDType1Font font, float size, String text) {
        try {
            return font.getStringWidth(sanitise(text)) / 1000f * size;
        } catch (Exception e) {
            return size * 0.5f * (text == null ? 0 : text.length());
        }
    }

    /** Standard-14 Helvetica only supports WinAnsi — strip anything else. */
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

    private static String money(BigDecimal v) {
        if (v == null) return "0.00";
        return v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String taxLine(String taxId, String vat, String eori) {
        StringBuilder sb = new StringBuilder();
        if (hasText(taxId)) sb.append("Tax ID: ").append(taxId.trim());
        if (hasText(vat)) { if (sb.length() > 0) sb.append("  "); sb.append("VAT: ").append(vat.trim()); }
        if (hasText(eori)) { if (sb.length() > 0) sb.append("  "); sb.append("EORI: ").append(eori.trim()); }
        return sb.toString();
    }

    private static String joinCityStateZip(String city, String state, String zip) {
        StringBuilder sb = new StringBuilder();
        if (hasText(city)) sb.append(city.trim());
        if (hasText(state)) { if (sb.length() > 0) sb.append(", "); sb.append(state.trim()); }
        if (hasText(zip)) { if (sb.length() > 0) sb.append(' '); sb.append(zip.trim()); }
        return sb.toString();
    }

    private static boolean hasText(String s) { return s != null && !s.isBlank(); }

    private static String safe(String v) { return v == null ? "" : v; }

    private static String firstNonBlank(String... vals) {
        if (vals == null) return null;
        for (String v : vals) if (hasText(v)) return v.trim();
        return null;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
