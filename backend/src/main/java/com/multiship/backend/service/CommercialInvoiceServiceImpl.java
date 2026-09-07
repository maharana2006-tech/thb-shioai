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

        // Resolve the client's customs profile for this destination ONCE — it
        // feeds both the importer-of-record block and the Incoterms fallback.
        ClientCustomsProfile profile = resolveProfile(clientCode, order.getShiptoCountryCd());

        // Importer of record: the order's own customs importer wins; else the
        // client's customs profile for the destination country; else fall back
        // to the consignee (ship-to), which is the DAP default.
        Party importer = resolveImporter(order, customs, profile);

        // Sprint 51 wiring fix — the real tracking number lives on the
        // order_label_tracking row, NOT label_batch.track (which stays null for
        // bulk/manual orders), so the invoice always printed "Tracking: -".
        String tracking = firstNonBlank(
                orderTrackingRepository.findByOrderNo(orderNo)
                        .map(t -> t.getTrackingNumber()).orElse(null),
                order.getTrack());
        String carrier = firstNonBlank(order.getShipVia(), order.getShipviaCd(),
                orderTrackingRepository.findByOrderNo(orderNo)
                        .map(t -> t.getShipViaCd()).orElse(null));
        // Incoterms drive who pays duties; fall back to the client's profile,
        // then DAP (duties payable by the consignee — the B2C default).
        String incoterms = firstNonBlank(customs.getIncoterms(),
                profile == null ? null : profile.getIncoterms(), "DAP");

        return renderPdf(order, customs, client, importer, tracking, carrier, incoterms);
    }

    /** The client's customs profile for a destination country, or null. */
    private ClientCustomsProfile resolveProfile(String clientCode, String destCountry) {
        if (customsProfileRepository == null || !hasText(clientCode) || !hasText(destCountry)) {
            return null;
        }
        return customsProfileRepository.findByClientAndCountry(clientCode, destCountry).orElse(null);
    }

    // ---- party resolution ------------------------------------------------

    /** A rendered address block: a name/company line plus street lines. */
    private record Party(String title, List<String> lines) {}

    private Party resolveImporter(Order order, OrderCustoms customs, ClientCustomsProfile profile) {
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
        if (profile != null && (hasText(profile.getImporterName()) || hasText(profile.getImporterAddress1()))) {
            String ids = taxLine(
                    firstNonBlank(profile.getImporterGstin(), profile.getImporterIec(), profile.getImporterTaxId()),
                    null, profile.getImporterEori());
            return new Party("IMPORTER OF RECORD", List.of(
                    safe(profile.getImporterName()),
                    safe(profile.getImporterAddress1()),
                    joinCityStateZip(profile.getImporterCity(), profile.getImporterState(), profile.getImporterPostcode()),
                    safe(profile.getImporterCountry()),
                    ids));
        }

        // 3) Consignee (ship-to) is the importer of record (DAP default).
        return new Party("IMPORTER OF RECORD (CONSIGNEE)", shipToLines(order));
    }

    private static List<String> shipToLines(Order order) {
        // Consignee = person AND company: on a B2B entry the company is the
        // party customs clears to, and this PDF is what prints with the
        // parcel now. It used to emit the name only (dropping "Kalpana
        // Textiles Pvt Ltd" while the on-screen invoice showed it) and no
        // address line 2.
        String name = firstNonBlank(order.getShipName(), order.getShipAttn(), "");
        String company = hasText(order.getShipAttn()) && !order.getShipAttn().trim().equalsIgnoreCase(name.trim())
                ? order.getShipAttn().trim()
                : null;
        List<String> lines = new java.util.ArrayList<>(6);
        lines.add(name);
        if (company != null) lines.add(company);
        lines.add(safe(order.getShipAddr1()));
        if (hasText(order.getLocation())) lines.add(order.getLocation().trim());
        lines.add(joinCityStateZip(order.getShiptoCity(), order.getShiptoState(), order.getShiptoZip()));
        lines.add(firstNonBlank(order.getCountryName(), order.getShiptoCountryCd(), ""));
        return lines;
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

    private byte[] renderPdf(Order order, OrderCustoms customs, Client client, Party importer,
                             String tracking, String carrier, String incoterms) {
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            // Resolve paper size — client override (A4 default) per user
            // direction 2026-09-07. Anonymous / MANUAL orders and invalid
            // values fall to A4 to match the ISO standard used everywhere
            // except US/CA/MX.
            PDRectangle pageSize = resolvePageSize(client);
            float pageWidth = pageSize.getWidth();
            float pageHeight = pageSize.getHeight();
            float margin = 40f;

            String currency = firstNonBlank(customs.getCurrency(), "USD");
            List<OrderCustomsItem> items = customs.getItems() == null
                    ? List.of() : customs.getItems();

            // Row 1 of page 1 has less vertical space than a continuation
            // page because we render the full-header (parties + meta). Row
            // budgets are computed dynamically inside the loop — we start
            // page 1, draw the full header, then feed items until the row
            // cursor drops below `bottomReservedY`, at which point we close
            // the page and open a new one with the compact header.
            //
            // "Bottom reserved" preserves room for:
            //   - subtotal row  (~16f)
            //   - shipment summary + declaration + signature on the LAST
            //     page (~90f)
            //   - Page N of M footer (~24f)
            // Continuation pages only need footer + subtotal (~40f) so
            // they fit more rows.
            float footerReserve = 24f;
            float lastPageReserve = 90f;
            float subtotalReserve = 16f;
            float rowH = 13f;

            // Two-pass render: pass 1 counts pages by simulating row
            // placement, pass 2 emits actual pages with the known total
            // page count for "Page N of M" footers.
            int simPage = 1;
            int simRowIdx = 0;
            java.util.List<int[]> pageRanges = new java.util.ArrayList<>();
            int rangeStart = 0;
            float simY = pageHeight - margin - firstPageHeaderHeight();
            while (simRowIdx < items.size()) {
                float bottomReserve = (simRowIdx == items.size() - 1)
                        ? lastPageReserve + subtotalReserve + footerReserve
                        : subtotalReserve + footerReserve + rowH;
                if (simY - rowH < margin + bottomReserve) {
                    pageRanges.add(new int[]{rangeStart, simRowIdx});
                    rangeStart = simRowIdx;
                    simPage++;
                    simY = pageHeight - margin - continuationHeaderHeight();
                    continue;
                }
                simY -= rowH;
                simRowIdx++;
            }
            pageRanges.add(new int[]{rangeStart, items.size()});
            int totalPages = pageRanges.size();

            // Pass 2 — actually emit each page.
            BigDecimal runningTotal = BigDecimal.ZERO;
            for (int p = 0; p < totalPages; p++) {
                boolean firstPage = (p == 0);
                boolean lastPage = (p == totalPages - 1);
                int startIdx = pageRanges.get(p)[0];
                int endIdx = pageRanges.get(p)[1];

                PDPage page = new PDPage(pageSize);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    float y;
                    if (firstPage) {
                        y = renderFullHeader(cs, order, customs, client, importer,
                                tracking, carrier, incoterms, currency,
                                pageWidth, pageHeight, margin);
                    } else {
                        y = renderContinuationHeader(cs, order, client,
                                pageWidth, pageHeight, margin);
                    }
                    // Item table column header
                    y = drawItemsTableHeader(cs, margin, pageWidth - margin, y);
                    // Item rows for this page
                    BigDecimal pageSubtotal = BigDecimal.ZERO;
                    for (int i = startIdx; i < endIdx; i++) {
                        OrderCustomsItem it = items.get(i);
                        int qty = it.getQuantity() == null ? 1 : it.getQuantity();
                        BigDecimal unit = it.getUnitValue() == null ? BigDecimal.ZERO : it.getUnitValue();
                        BigDecimal amount = unit.multiply(BigDecimal.valueOf(qty));
                        pageSubtotal = pageSubtotal.add(amount);
                        drawItemRow(cs, margin, pageWidth - margin, y, it, qty, unit, amount);
                        y -= rowH;
                    }
                    runningTotal = runningTotal.add(pageSubtotal);

                    // Subtotal row (every page). Final total on last page.
                    rule(cs, margin, pageWidth - margin, y - 2f, LIGHT_RULE, 0.5f);
                    y -= subtotalReserve;
                    String subtotalLabel = "SUBTOTAL page " + (p + 1) + " (" + currency + ")";
                    cs.setNonStrokingColor(PRIMARY);
                    drawText(cs, HELVETICA_BOLD, 9.5f, subtotalLabel,
                            margin + 370f, y);
                    drawRightText(cs, HELVETICA_BOLD, 9.5f, money(pageSubtotal),
                            pageWidth - margin, y);
                    cs.setNonStrokingColor(Color.BLACK);

                    if (lastPage) {
                        y -= 16f;
                        String totalLabel = "TOTAL (" + currency + ")";
                        cs.setNonStrokingColor(PRIMARY);
                        drawText(cs, HELVETICA_BOLD, 11f, totalLabel, margin + 370f, y);
                        drawRightText(cs, HELVETICA_BOLD, 11f, money(runningTotal),
                                pageWidth - margin, y);
                        cs.setNonStrokingColor(Color.BLACK);

                        // Shipment summary + declaration + signature block.
                        int totalQty = 0;
                        for (OrderCustomsItem it : items) {
                            totalQty += it.getQuantity() == null ? 1 : it.getQuantity();
                        }
                        int pkgs = order.getPackageCount() == null ? 1 : order.getPackageCount();
                        String weightUnit = firstNonBlank(customs.getWeightUnit(),
                                client == null ? null : client.getDefaultWeightUnit(), "KG");
                        StringBuilder summary = new StringBuilder();
                        summary.append("Packages: ").append(pkgs)
                                .append("    Total quantity: ").append(totalQty);
                        if (order.getWeight() != null) {
                            summary.append("    Gross weight: ")
                                    .append(order.getWeight().stripTrailingZeros().toPlainString())
                                    .append(' ').append(weightUnit);
                        }
                        float sumY = y - 16f;
                        cs.setNonStrokingColor(new Color(0x5a, 0x45, 0x26));
                        drawText(cs, HELVETICA, 8.5f, summary.toString(), margin, sumY);
                        cs.setNonStrokingColor(Color.BLACK);

                        float decY = Math.max(sumY - 18f, margin + 46f);
                        cs.setNonStrokingColor(new Color(0x5a, 0x45, 0x26));
                        drawText(cs, HELVETICA_OBLIQUE, 8.5f,
                                "I declare the information on this invoice to be true and correct to the best of my knowledge.",
                                margin, decY);
                        cs.setNonStrokingColor(Color.BLACK);
                        rule(cs, pageWidth - margin - 180f, pageWidth - margin, margin + 24f, LIGHT_RULE, 0.6f);
                        drawText(cs, HELVETICA, 8f, "Authorised signature / date",
                                pageWidth - margin - 180f, margin + 12f);
                    }

                    // Page footer — "Invoice #N — Page X of Y".
                    String footer = "Invoice #" + order.getOrderNo() + " - Page " + (p + 1)
                            + " of " + totalPages;
                    float footerWidth = textWidth(HELVETICA, 8f, footer);
                    cs.setNonStrokingColor(new Color(0x5a, 0x45, 0x26));
                    drawText(cs, HELVETICA, 8f, footer,
                            (pageWidth - footerWidth) / 2f, margin - 12f);
                    cs.setNonStrokingColor(Color.BLACK);
                }
            }

            doc.save(out);
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed to render commercial invoice for order "
                    + order.getOrderNo(), e);
        }
    }

    /**
     * Resolve paper size — {@link Client#getDefaultPaperSize()} when set
     * ("A4" or "LETTER"), else A4. A4 default matches the ISO standard;
     * US/CA/MX tenants override to LETTER via the Client settings.
     */
    private static PDRectangle resolvePageSize(Client client) {
        if (client != null && client.getDefaultPaperSize() != null) {
            String size = client.getDefaultPaperSize().trim().toUpperCase();
            if ("LETTER".equals(size)) return PDRectangle.LETTER;
        }
        return PDRectangle.A4;
    }

    /** Approximate vertical space consumed by the page-1 full header
     *  (parties + meta + spacing). Used in the two-pass pagination sim. */
    private static float firstPageHeaderHeight() {
        // Empirical: title(40) + rule(2) + meta 2 lines(30) + parties(90) + spacing(12) = ~174
        return 190f;
    }

    /** Approximate vertical space consumed by the compact continuation
     *  header ("Invoice #N — continued" title only). */
    private static float continuationHeaderHeight() {
        // Title(20) + client name(15) + rule(2) + spacing(12) = ~50
        return 55f;
    }

    /**
     * Full-header renderer for page 1. Emits title + client name +
     * invoice meta line + 3 party blocks. Returns the y-cursor below.
     */
    private float renderFullHeader(PDPageContentStream cs, Order order, OrderCustoms customs,
            Client client, Party importer, String tracking, String carrier, String incoterms,
            String currency, float pageWidth, float pageHeight, float margin)
            throws java.io.IOException {
        float y = pageHeight - margin;
        cs.setNonStrokingColor(PRIMARY);
        drawText(cs, HELVETICA_BOLD, 20f, "COMMERCIAL INVOICE",
                pageWidth - margin - textWidth(HELVETICA_BOLD, 20f, "COMMERCIAL INVOICE"), y - 20f);
        cs.setNonStrokingColor(Color.BLACK);
        if (client != null) {
            drawText(cs, HELVETICA_BOLD, 12f, safe(client.getName()), margin, y - 12f);
        }
        y -= 40f;
        rule(cs, margin, pageWidth - margin, y, PRIMARY, 1.2f);
        y -= 18f;
        String meta = "Invoice No: " + order.getOrderNo()
                + "    Date: " + (order.getCreatedDate() == null ? "-" : DATE_FMT.format(order.getCreatedDate()))
                + "    Incoterms: " + firstNonBlank(incoterms, "DAP")
                + "    Currency: " + currency
                + "    Reason: " + firstNonBlank(customs.getReasonForExport(), "SALE");
        drawText(cs, HELVETICA, 9.5f, meta, margin, y);
        y -= 12f;
        String meta2 = "Carrier: " + firstNonBlank(carrier, "-")
                + "    Tracking: " + firstNonBlank(tracking, "-")
                + "    " + dutyTerms(incoterms);
        drawText(cs, HELVETICA, 9.5f, meta2, margin, y);
        y -= 26f;
        float leftCol = margin;
        float rightCol = pageWidth / 2f + 20f;
        float leftY = drawParty(cs, "EXPORTER / SHIP FROM", exporterLines(client), leftCol, y);
        float rightY = drawParty(cs, importer.title(), importer.lines(), rightCol, y);
        float consY = drawParty(cs, "CONSIGNEE / SHIP TO", shipToLines(order), leftCol, leftY - 8f);
        return Math.min(consY, rightY) - 12f;
    }

    /**
     * Compact continuation-page header. Just "COMMERCIAL INVOICE #N —
     * continued" + client name + rule. Enough context for a page that
     * gets separated during handling; keeps continuation pages
     * information-dense.
     */
    private float renderContinuationHeader(PDPageContentStream cs, Order order, Client client,
            float pageWidth, float pageHeight, float margin) throws java.io.IOException {
        float y = pageHeight - margin;
        cs.setNonStrokingColor(PRIMARY);
        String title = "COMMERCIAL INVOICE #" + order.getOrderNo() + " - continued";
        drawText(cs, HELVETICA_BOLD, 12f, title,
                pageWidth - margin - textWidth(HELVETICA_BOLD, 12f, title), y - 12f);
        cs.setNonStrokingColor(Color.BLACK);
        if (client != null) {
            drawText(cs, HELVETICA, 10f, safe(client.getName()), margin, y - 12f);
        }
        y -= 20f;
        rule(cs, margin, pageWidth - margin, y, PRIMARY, 0.8f);
        return y - 18f;
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

    /**
     * Draw the item-table column header — repeated on every page so
     * separated pages remain legible. Returns the y-cursor below.
     */
    private float drawItemsTableHeader(PDPageContentStream cs, float left, float right, float top)
            throws java.io.IOException {
        float xDesc = left;
        float xHs = left + 210f;
        float xOrig = left + 275f;
        float xQty = left + 320f;
        float xUnit = left + 370f;
        float xAmt = right - textWidth(HELVETICA_BOLD, 9f, "AMOUNT");
        cs.setNonStrokingColor(PRIMARY);
        drawText(cs, HELVETICA_BOLD, 9f, "DESCRIPTION", xDesc, top);
        drawText(cs, HELVETICA_BOLD, 9f, "HS CODE", xHs, top);
        drawText(cs, HELVETICA_BOLD, 9f, "ORIGIN", xOrig, top);
        drawText(cs, HELVETICA_BOLD, 9f, "QTY", xQty, top);
        drawText(cs, HELVETICA_BOLD, 9f, "UNIT", xUnit, top);
        drawText(cs, HELVETICA_BOLD, 9f, "AMOUNT", xAmt, top);
        cs.setNonStrokingColor(Color.BLACK);
        rule(cs, left, right, top - 4f, LIGHT_RULE, 0.5f);
        return top - 16f;
    }

    /** Draw one item row at the given y using the column positions
     *  matching {@link #drawItemsTableHeader}. */
    private void drawItemRow(PDPageContentStream cs, float left, float right, float y,
            OrderCustomsItem it, int qty, BigDecimal unit, BigDecimal amount)
            throws java.io.IOException {
        float xDesc = left;
        float xHs = left + 210f;
        float xOrig = left + 275f;
        float xQty = left + 320f;
        float xUnit = left + 370f;
        drawText(cs, HELVETICA, 9f, truncate(safe(it.getDescription()), 42), xDesc, y);
        drawText(cs, HELVETICA, 9f, safe(it.getHsCode()), xHs, y);
        drawText(cs, HELVETICA, 9f, safe(it.getCountryOfOrigin()), xOrig, y);
        drawText(cs, HELVETICA, 9f, String.valueOf(qty), xQty, y);
        drawText(cs, HELVETICA, 9f, money(unit), xUnit, y);
        drawRightText(cs, HELVETICA, 9f, money(amount), right, y);
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

    /** Who settles duties &amp; taxes, derived from the Incoterm. DDP = shipper
     *  prepays; everything else (DAP/DDU/…) = payable by the consignee. */
    private static String dutyTerms(String incoterms) {
        String code = incoterms == null ? "" : incoterms.trim().toUpperCase();
        if (code.equals("DDP")) return "Duties/Taxes: prepaid by shipper (DDP)";
        return "Duties/Taxes: payable by consignee (" + (code.isEmpty() ? "DAP" : code) + ")";
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
