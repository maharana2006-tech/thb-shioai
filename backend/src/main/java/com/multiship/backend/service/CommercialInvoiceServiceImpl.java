package com.multiship.backend.service;

import com.multiship.backend.model.Address;
import com.multiship.backend.model.CarrierAccountRef;
import com.multiship.backend.model.Client;
import com.multiship.backend.model.ClientCustomsProfile;
import com.multiship.backend.model.Order;
import com.multiship.backend.model.OrderCustoms;
import com.multiship.backend.model.OrderCustomsItem;
import com.multiship.backend.model.OrderTracking;
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
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The platform's commercial invoice — the customs declaration document
 * that travels with every international parcel.
 *
 * <p>Content follows what the carriers (FedEx / UPS / DHL) and 19 CFR
 * 141.86 ask of a commercial invoice: exporter and consignee with phone
 * and tax IDs, importer of record and customs broker when they differ from
 * the consignee, invoice number and date, order and customer references,
 * carrier and air-waybill / tracking number, Incoterms, currency, reason
 * for export, who settles duties and taxes, the export-declaration
 * citation (AES ITN / FTR exemption / national reference), an itemised
 * table (plain-language description, SKU, HS code, country of origin,
 * quantity and unit, net weight, unit and total value), package count and
 * gross / net weight, freight and insurance charges, the total invoice
 * value, a signed declaration, and consecutively numbered pages.
 *
 * <p>Layout: espresso/cream, print-friendly (no solid dark bands), A4 by
 * default with a per-client LETTER override. Every drawing routine runs
 * in a dry "measure" mode (null content stream) so pagination is exact.
 */
@Service
public class CommercialInvoiceServiceImpl implements CommercialInvoiceService {

    // ---- palette (mirrors the app's espresso/cream theme) ----
    private static final Color INK = new Color(0x1f, 0x15, 0x0c);
    private static final Color ESPRESSO = new Color(0x3b, 0x2a, 0x16);
    private static final Color TAUPE = new Color(0x6b, 0x5c, 0x42);
    private static final Color RULE = new Color(0xd6, 0xcd, 0xbd);
    private static final Color CREAM = new Color(0xf5, 0xf0, 0xe6);
    private static final Color CREAM_DEEP = new Color(0xea, 0xe2, 0xd1);
    private static final Color ZEBRA = new Color(0xfa, 0xf7, 0xf2);

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.US);
    private static final DateTimeFormatter STAMP_FMT = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm", Locale.US);
    private static final PDType1Font HELVETICA =
            new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private static final PDType1Font HELVETICA_BOLD =
            new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
    private static final PDType1Font HELVETICA_OBLIQUE =
            new PDType1Font(Standard14Fonts.FontName.HELVETICA_OBLIQUE);

    private static final float MARGIN = 36f;
    private static final float ROW_H = 14f;
    private static final float FOOTER_H = 26f;

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(CommercialInvoiceServiceImpl.class);

    private final OrderRepository orderRepository;
    private final OrderCustomsRepository orderCustomsRepository;
    private final ClientRepository clientRepository;
    private final ClientCustomsProfileRepository customsProfileRepository;
    private final OrderTrackingRepository orderTrackingRepository;
    private final CarrierAccountRefRepository accountRefRepository;
    private final com.multiship.backend.repository.LabelPackageRepository labelPackageRepository;

    public CommercialInvoiceServiceImpl(OrderRepository orderRepository,
                                        OrderCustomsRepository orderCustomsRepository,
                                        ClientRepository clientRepository,
                                        ClientCustomsProfileRepository customsProfileRepository,
                                        OrderTrackingRepository orderTrackingRepository,
                                        CarrierAccountRefRepository accountRefRepository,
                                        com.multiship.backend.repository.LabelPackageRepository labelPackageRepository) {
        this.labelPackageRepository = labelPackageRepository;
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
        String acct = orderTrackingRepository.findByOrderNo(orderNo)
                .map(t -> t.getAccountNumber()).orElse(null);
        if (hasText(acct)) {
            String fromAccount = accountRefRepository
                    .findFirstByAccountNumberIgnoreCaseOrderByUpdatedAtDesc(acct.trim())
                    .map(CarrierAccountRef::getCustomerNo).orElse(null);
            if (hasText(fromAccount)) return fromAccount;
        }
        return code;
    }

    // =====================================================================
    // Model
    // =====================================================================

    /** A titled address block. */
    private record Party(String title, List<String> lines) {}

    /** One key/value cell of the meta panel. */
    private record Meta(String label, String value) {}

    /** One priced line of the items table. */
    private record Line(int no, String description, String sku, String hs, String origin, int qty,
                        String netWeight, boolean netEstimated, BigDecimal net, BigDecimal unit, BigDecimal amount,
                        Integer box) {}

    /** One physical piece of a multi-package shipment (label_package row). */
    private record Pkg(int seq, String tracking, String packaging, String dims, String weight, String contents,
                       String value) {}

    /** Everything the renderer needs, resolved once. */
    private record Model(Order order, OrderCustoms customs, Client client,
                         String exporterName, Party exporter, Party consignee, Party importer, Party broker,
                         List<Meta> meta, List<Line> lines, String currency,
                         int packages, int totalQty, BigDecimal gross, String grossUnit,
                         BigDecimal net, boolean netEstimated, String lineUnit,
                         Set<String> origins, BigDecimal goods, BigDecimal freight, String freightNote,
                         String notes, String signerName, String stamp, List<Pkg> pieces) {}

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
        ClientCustomsProfile profile = resolveProfile(clientCode, order.getShiptoCountryCd());
        OrderTracking tracking = orderTrackingRepository.findByOrderNo(orderNo).orElse(null);

        return renderPdf(buildModel(order, customs, client, profile, tracking));
    }

    private Model buildModel(Order order, OrderCustoms customs, Client client,
                             ClientCustomsProfile profile, OrderTracking tracking) {
        String currency = firstNonBlank(customs.getCurrency(),
                profile == null ? null : profile.getCurrency(), "USD");
        String incoterms = firstNonBlank(customs.getIncoterms(),
                profile == null ? null : profile.getIncoterms(), "DAP");
        String trackingNo = firstNonBlank(tracking == null ? null : tracking.getTrackingNumber(), order.getTrack());
        String carrier = carrierAndService(order, tracking);
        LocalDateTime shippedAt = tracking == null ? null
                : (tracking.getLabelGeneratedAt() != null ? tracking.getLabelGeneratedAt() : tracking.getCreatedAt());
        String shipDate = shippedAt != null ? DATE_FMT.format(shippedAt)
                : (order.getCreatedDate() == null ? "-" : DATE_FMT.format(order.getCreatedDate()));
        String invoiceDate = order.getCreatedDate() == null ? "-" : DATE_FMT.format(order.getCreatedDate());

        // --- weights -------------------------------------------------------
        String lineUnit = firstNonBlank(customs.getWeightUnit(), order.getWeightUnit(),
                client == null ? null : client.getDefaultWeightUnit(), "KG").toUpperCase();
        String grossUnit = firstNonBlank(order.getWeightUnit(), lineUnit).toUpperCase();
        BigDecimal gross = shipmentGrossWeight(order);
        List<OrderCustomsItem> items = customs.getItems() == null ? List.of() : customs.getItems();
        int totalQty = 0;
        for (OrderCustomsItem it : items) totalQty += it.getQuantity() == null ? 1 : it.getQuantity();

        // --- lines -----------------------------------------------------------
        List<Line> lines = new ArrayList<>(items.size());
        BigDecimal goods = BigDecimal.ZERO;
        BigDecimal net = BigDecimal.ZERO;
        boolean anyEstimated = false;
        Set<String> origins = new LinkedHashSet<>();
        // Lines without a declared per-unit weight share whatever part of the
        // shipment gross the declared lines do not account for, by quantity —
        // so declared + estimated never exceeds the gross.
        BigDecimal declaredNet = BigDecimal.ZERO;
        int undeclaredQty = 0;
        for (OrderCustomsItem it : items) {
            int qty = it.getQuantity() == null ? 1 : it.getQuantity();
            if (it.getWeight() != null && it.getWeight().signum() > 0) {
                declaredNet = declaredNet.add(it.getWeight().multiply(BigDecimal.valueOf(qty)));
            } else {
                undeclaredQty += qty;
            }
        }
        BigDecimal estPerUnit = null;
        BigDecimal estRemaining = BigDecimal.ZERO;   // what the estimated lines may still add up to
        int estQtyLeft = undeclaredQty;
        if (gross != null && gross.signum() > 0 && undeclaredQty > 0) {
            BigDecimal remainder = gross.subtract(declaredNet);
            if (remainder.signum() > 0) {
                estPerUnit = remainder.divide(BigDecimal.valueOf(undeclaredQty), 4, RoundingMode.HALF_UP);
                estRemaining = remainder.setScale(2, RoundingMode.DOWN);
            }
        }
        int n = 0;
        for (OrderCustomsItem it : items) {
            n++;
            int qty = it.getQuantity() == null ? 1 : it.getQuantity();
            BigDecimal unit = it.getUnitValue() == null ? BigDecimal.ZERO : it.getUnitValue();
            BigDecimal amount = unit.multiply(BigDecimal.valueOf(qty));
            goods = goods.add(amount);
            BigDecimal lineNet;
            boolean est;
            if (it.getWeight() != null && it.getWeight().signum() > 0) {
                lineNet = it.getWeight().multiply(BigDecimal.valueOf(qty)).setScale(2, RoundingMode.HALF_UP);
                est = false;
            } else if (estPerUnit != null) {
                // Apportioned, and marked so — an estimate must never read as
                // a declared figure. The last estimated line takes whatever is
                // left, so rounding can never push net above gross (12.01 LB
                // net on a 12 LB gross is the kind of thing customs queries).
                BigDecimal share = estPerUnit.multiply(BigDecimal.valueOf(qty)).setScale(2, RoundingMode.HALF_UP);
                lineNet = estQtyLeft <= qty ? estRemaining : share.min(estRemaining);
                if (lineNet.signum() < 0) lineNet = BigDecimal.ZERO;
                estRemaining = estRemaining.subtract(lineNet);
                estQtyLeft -= qty;
                est = true;
                anyEstimated = true;
            } else {
                lineNet = null;
                est = false;
            }
            if (lineNet != null) net = net.add(lineNet);
            String origin = safe(it.getCountryOfOrigin()).trim().toUpperCase();
            if (hasText(origin)) origins.add(origin);
            lines.add(new Line(n, safe(it.getDescription()), safe(it.getSku()), safe(it.getHsCode()),
                    origin, qty, lineNet == null ? "" : lineNet.toPlainString() + (est ? "*" : ""),
                    est, lineNet, unit, amount, it.getBoxSeq()));
        }

        // --- freight / insurance ------------------------------------------
        // The persisted freight cost is in the account's billing currency;
        // only itemise it when that matches the invoice currency, otherwise
        // state the Incoterm treatment so the charge is still declared.
        BigDecimal freight = null;
        String freightNote;
        String billingCurrency = firstNonBlank(client == null ? null : client.getDefaultCurrency(), "USD");
        boolean sellerPaysFreight = !Set.of("EXW", "FCA", "FAS", "FOB").contains(incoterms.toUpperCase());
        if (order.getFreightCost() != null && order.getFreightCost().signum() > 0
                && billingCurrency.equalsIgnoreCase(currency) && sellerPaysFreight) {
            freight = order.getFreightCost().setScale(2, RoundingMode.HALF_UP);
            freightNote = null;
        } else {
            freightNote = sellerPaysFreight ? "Prepaid by shipper (" + incoterms + ")" : "Collect (" + incoterms + ")";
        }

        // --- parties ----------------------------------------------------------
        Party exporter = exporterParty(order, client);
        String exporterName = hasText(order.getShipFromAddr1()) ? exporterEntity(order, client)
                : exporter.lines().isEmpty() ? "" : exporter.lines().get(0);
        Party consignee = new Party("CONSIGNEE / SHIP TO", shipToLines(order));
        Party importer = resolveImporter(order, customs, profile);
        Party broker = resolveBroker(order, profile);

        // --- meta panel ---------------------------------------------------------
        int packages = order.getPackageCount() == null ? 1 : Math.max(1, order.getPackageCount());
        List<Meta> meta = new ArrayList<>();
        meta.add(new Meta("Invoice no.", String.valueOf(order.getOrderNo())));
        meta.add(new Meta("Invoice date", invoiceDate));
        meta.add(new Meta("Order / reference", firstNonBlank(order.getCustomerRef(), order.getWmsExternalId(), String.valueOf(order.getOrderNo()))));
        meta.add(new Meta("Customer ref.", firstNonBlank(
                "MANUAL".equalsIgnoreCase(order.getCustNo()) ? null : order.getCustNo(),
                client == null ? null : client.getClientCode(), "-")));
        meta.add(new Meta("Carrier / service", firstNonBlank(carrier, "-")));
        meta.add(new Meta("Tracking / AWB no.", firstNonBlank(trackingNo, "-")));
        meta.add(new Meta("Ship date", shipDate));
        meta.add(new Meta("Packages", packages + (packages == 1 ? " piece" : " pieces")));
        meta.add(new Meta("Incoterms 2020", incoterms.toUpperCase()));
        meta.add(new Meta("Currency of sale", currency.toUpperCase()));
        meta.add(new Meta("Reason for export", firstNonBlank(customs.getReasonForExport(),
                profile == null ? null : profile.getReasonForExport(), "SALE").toUpperCase()));
        meta.add(new Meta("Duties & taxes", dutyTerms(incoterms, customs.getDutiesPaidBy(), customs.getDutiesAccount())));
        String exportDecl = exportDeclaration(customs);
        String dutiesAccount = dutiesAccount(incoterms, customs, profile);
        meta.add(new Meta("Export declaration", firstNonBlank(exportDecl, "None declared")));
        meta.add(new Meta("Duties billed to", firstNonBlank(dutiesAccount, dutyPayer(incoterms))));
        meta.add(new Meta("Country of destination", firstNonBlank(order.getCountryName(), order.getShiptoCountryCd(), "-")));
        meta.add(new Meta("Country of export", firstNonBlank(order.getShipFromCountryCd(),
                client == null || client.getShipFrom() == null ? null : client.getShipFrom().getCountry(), "-")));

        String signer = firstNonBlank(order.getShipFromName(), client == null ? null : client.getName(), "");
        String stamp = STAMP_FMT.format(LocalDateTime.now());
        return new Model(order, customs, client, exporterName, exporter, consignee, importer, broker,
                meta, lines, currency.toUpperCase(), packages, totalQty, gross, grossUnit, net, anyEstimated, lineUnit,
                origins, goods, freight, freightNote, safe(customs.getNotes()).trim(), signer, stamp,
                packages > 1 ? pieces(order, items) : List.of());
    }

    /**
     * "UPS Worldwide Expedited" / "FedEx International Economy" rather than
     * the raw service code ("08", "INTERNATIONAL_ECONOMY") the order stores.
     * A human ship-via saved on the order wins; otherwise the carrier comes
     * from the labelling account (then the connect-code map) and the service
     * from the carrier's own naming.
     */
    private String carrierAndService(Order order, OrderTracking tracking) {
        String via = safe(order.getShipVia()).trim();
        if (hasText(via) && via.contains(" ") && !via.equals(via.toUpperCase(Locale.ROOT))) return via;
        String code = firstNonBlank(order.getShipviaCd(), tracking == null ? null : tracking.getShipViaCd(), via);
        String carrier = null;
        if (tracking != null && hasText(tracking.getAccountNumber()) && accountRefRepository != null) {
            carrier = accountRefRepository
                    .findFirstByAccountNumberIgnoreCaseOrderByUpdatedAtDesc(tracking.getAccountNumber().trim())
                    .map(CarrierAccountRef::getCarrierCode).orElse(null);
        }
        if (!hasText(carrier)) {
            String canon = ShippingConfigService.canonicalCarrierFor(code);
            if (Set.of("UPS", "FEDEX", "USPS", "DHL", "STAMPS").contains(canon)) carrier = canon;
        }
        if (!hasText(code)) return hasText(carrier) ? carrierLabel(carrier) : "-";
        String c = safe(carrier).trim().toUpperCase(Locale.ROOT);
        if (c.equals("UPS")) return titleCase(ZplLabelService.upsService(code)[0]);
        if (c.equals("FEDEX")) {
            String svc = code.toUpperCase(Locale.ROOT).replaceFirst("^FEDEX[_ ]", "");
            return "FedEx " + titleCase(svc.replace('_', ' '));
        }
        String label = hasText(carrier) ? carrierLabel(carrier) + " " : "";
        return label + titleCase(code.replace('_', ' '));
    }

    private static String carrierLabel(String carrier) {
        return switch (carrier.trim().toUpperCase(Locale.ROOT)) {
            case "FEDEX" -> "FedEx";
            case "UPS" -> "UPS";
            case "USPS" -> "USPS";
            case "DHL" -> "DHL";
            default -> titleCase(carrier);
        };
    }

    /** "WORLDWIDE EXPEDITED" → "Worldwide Expedited"; keeps UPS / FedEx / A.M. / 2nd intact. */
    private static String titleCase(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder();
        for (String w : s.trim().split("\\s+")) {
            if (w.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            String u = w.toUpperCase(Locale.ROOT);
            if (u.equals("UPS") || u.equals("USPS") || u.equals("DHL") || u.equals("A.M.") || u.equals("P.M.")) out.append(u);
            else if (u.equals("FEDEX")) out.append("FedEx");
            else if (u.equals("2ND") || u.equals("3RD")) out.append(u.toLowerCase(Locale.ROOT));
            else out.append(u.charAt(0)).append(u.substring(1).toLowerCase(Locale.ROOT));
        }
        return out.toString();
    }

    /**
     * Per-piece annex for multi-package shipments — 19 CFR 141.86(e) wants
     * the contents of each package; carriers want the piece tracking numbers
     * on the paperwork. Contents come from the customs items' box index
     * (boxSeq); pieces with no assigned lines print "-".
     */
    private List<Pkg> pieces(Order order, List<OrderCustomsItem> items) {
        if (labelPackageRepository == null || order.getOrderNo() == null) return List.of();
        List<com.multiship.backend.model.LabelPackage> allRows =
                labelPackageRepository.findByOrderNoOrderBySequenceNumberAsc(order.getOrderNo());
        List<com.multiship.backend.model.LabelPackage> rows = physicalPieces(order);
        if (rows.isEmpty()) return List.of();
        int packageCount = rows.size();
        Map<Integer, List<Integer>> linesByBox = new java.util.HashMap<>();
        Map<Integer, BigDecimal> valueByBox = new java.util.HashMap<>();
        Map<Integer, Integer> unitsByBox = new java.util.HashMap<>();
        for (int i = 0; i < items.size(); i++) {
            OrderCustomsItem it = items.get(i);
            Integer box = it.getBoxSeq();
            if (box == null) continue;
            linesByBox.computeIfAbsent(box, k -> new ArrayList<>()).add(i + 1);
            BigDecimal unit = it.getUnitValue() == null ? BigDecimal.ZERO : it.getUnitValue();
            int qty = it.getQuantity() == null ? 1 : it.getQuantity();
            valueByBox.merge(box, unit.multiply(BigDecimal.valueOf(qty)), BigDecimal::add);
            unitsByBox.merge(box, qty, Integer::sum);
        }
        List<Pkg> out = new ArrayList<>(rows.size());
        int n = 0;
        for (com.multiship.backend.model.LabelPackage r : rows) {
            n++;
            int seq = n; // physical box index — label rows beyond packageCount are re-labels of these boxes
            String dims = (r.getLength() != null && r.getWidth() != null && r.getHeight() != null)
                    ? plain(r.getLength()) + " x " + plain(r.getWidth()) + " x " + plain(r.getHeight())
                      + " " + firstNonBlank(r.getDimUnit(), "").toUpperCase(Locale.ROOT)
                    : "-";
            String weight = r.getWeight() == null ? "-"
                    : plain(r.getWeight()) + " " + firstNonBlank(r.getWeightUnit(), order.getWeightUnit(), "").toUpperCase(Locale.ROOT);
            String packaging = hasText(r.getPackageType())
                    ? titleCase(r.getPackageType().replace('_', ' ')) : "-";
            List<Integer> ls = linesByBox.get(seq);
            String contents = ls == null || ls.isEmpty() ? "-"
                    : "Lines " + compactRanges(ls) + " (" + unitsByBox.getOrDefault(seq, 0) + " units)";
            String value = valueByBox.containsKey(seq) ? money(valueByBox.get(seq)) : "-";
            List<String> trackings = trackingsForBox(allRows == null ? rows : allRows, packageCount, seq);
            String tracking = trackings.isEmpty() ? "-" : String.join(", ", trackings);
            out.add(new Pkg(seq, tracking, packaging, dims.trim(), weight.trim(), contents, value));
        }
        return out;
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().scale() <= 0 ? v.stripTrailingZeros().toPlainString()
                : v.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    /** [1,2,3,7,9,10] → "1-3, 7, 9-10". */
    private static String compactRanges(List<Integer> sorted) {
        List<Integer> xs = new ArrayList<>(sorted);
        java.util.Collections.sort(xs);
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < xs.size()) {
            int start = xs.get(i), end = start;
            while (i + 1 < xs.size() && xs.get(i + 1) == end + 1) { end = xs.get(++i); }
            if (sb.length() > 0) sb.append(", ");
            sb.append(start);
            if (end != start) sb.append('-').append(end);
            i++;
        }
        return sb.toString();
    }

    private ClientCustomsProfile resolveProfile(String clientCode, String destCountry) {
        if (customsProfileRepository == null || !hasText(clientCode) || !hasText(destCountry)) return null;
        return customsProfileRepository.findByClientAndCountry(clientCode, destCountry).orElse(null);
    }

    // ---- party resolution ------------------------------------------------

    private Party resolveImporter(Order order, OrderCustoms customs, ClientCustomsProfile profile) {
        // 1) Explicit importer captured on the order's customs record.
        Address ia = customs.getImporterAddress();
        String iaName = ia == null ? null : ia.getName();
        if (hasText(iaName) || hasText(customs.getImporterCompany())
                || (ia != null && hasText(ia.getLine1()))) {
            List<String> lines = new ArrayList<>(8);
            String name = firstNonBlank(iaName, customs.getImporterCompany(), "");
            lines.add(name);
            if (hasText(customs.getImporterCompany())
                    && !customs.getImporterCompany().trim().equalsIgnoreCase(name.trim())) {
                lines.add(customs.getImporterCompany().trim());
            }
            if (ia != null) {
                lines.add(safe(ia.getLine1()));
                lines.add(safe(ia.getLine2()));
                lines.add(joinCityStateZip(ia.getCity(), ia.getState(), ia.getZip()));
                lines.add(safe(ia.getCountry()));
                if (hasText(ia.getPhone())) lines.add("Tel " + ia.getPhone().trim());
            }
            lines.add(taxLine(customs.getImporterTaxId(), customs.getImporterVat(), customs.getImporterEori()));
            return new Party("IMPORTER OF RECORD", lines);
        }
        // 2) Client's customs profile for the destination country.
        if (profile != null && (hasText(profile.getImporterName()) || hasText(profile.getImporterAddress1()))) {
            List<String> lines = new ArrayList<>(9);
            lines.add(safe(profile.getImporterName()));
            if (hasText(profile.getImporterContact())) lines.add("Attn " + profile.getImporterContact().trim());
            lines.add(safe(profile.getImporterAddress1()));
            lines.add(safe(profile.getImporterAddress2()));
            lines.add(joinCityStateZip(profile.getImporterCity(), profile.getImporterState(), profile.getImporterPostcode()));
            lines.add(safe(profile.getImporterCountry()));
            if (hasText(profile.getImporterPhone())) lines.add("Tel " + profile.getImporterPhone().trim());
            String idLabel = hasText(profile.getImporterTaxIdType()) ? profile.getImporterTaxIdType().trim() : "Tax ID";
            StringBuilder ids = new StringBuilder();
            if (hasText(profile.getImporterTaxId())) ids.append(idLabel).append(": ").append(profile.getImporterTaxId().trim());
            if (hasText(profile.getImporterGstin())) { sep(ids); ids.append("GSTIN: ").append(profile.getImporterGstin().trim()); }
            if (hasText(profile.getImporterIec())) { sep(ids); ids.append("IEC: ").append(profile.getImporterIec().trim()); }
            if (hasText(profile.getImporterEori())) { sep(ids); ids.append("EORI: ").append(profile.getImporterEori().trim()); }
            if (hasText(profile.getImporterIoss())) { sep(ids); ids.append("IOSS: ").append(profile.getImporterIoss().trim()); }
            if (hasText(profile.getImporterCompanyReg())) { sep(ids); ids.append("Reg: ").append(profile.getImporterCompanyReg().trim()); }
            lines.add(ids.toString());
            return new Party("IMPORTER OF RECORD", lines);
        }
        // 3) Consignee is the importer of record (DAP default).
        return new Party("IMPORTER OF RECORD (CONSIGNEE)", shipToLines(order));
    }

    private BigDecimal shipmentGrossWeight(Order order) {
        List<com.multiship.backend.model.LabelPackage> physical = physicalPieces(order);
        if (!physical.isEmpty()) {
            BigDecimal sum = physical.stream()
                    .map(p -> p.getWeight()).filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (sum.signum() > 0) return sum;
        }
        return order.getWeight();
    }

    /**
     * The shipment's PHYSICAL boxes. When a carrier commodity cap forces the
     * booking to be split (UPS: 50 lines), the SAME_PACKAGES strategy books
     * every box once per sub-shipment, so label_package holds N × packageCount
     * rows numbered globally — the same boxes labelled again, not more boxes.
     * Row r maps to physical box ((r - 1) mod packageCount) + 1; the first
     * cycle carries the physical weights and dimensions.
     */
    private List<com.multiship.backend.model.LabelPackage> physicalPieces(Order order) {
        if (labelPackageRepository == null || order.getOrderNo() == null) return List.of();
        List<com.multiship.backend.model.LabelPackage> rows =
                labelPackageRepository.findByOrderNoOrderBySequenceNumberAsc(order.getOrderNo());
        if (rows == null) return List.of();
        int n = order.getPackageCount() == null ? 0 : order.getPackageCount();
        if (n > 0 && rows.size() > n) return new ArrayList<>(rows.subList(0, n));
        return rows;
    }

    /** All tracking numbers issued for physical box {@code box} (1-based), in label order. */
    private static List<String> trackingsForBox(List<com.multiship.backend.model.LabelPackage> allRows, int packageCount, int box) {
        List<String> out = new ArrayList<>(2);
        for (int r = 0; r < allRows.size(); r++) {
            int physical = packageCount > 0 ? (r % packageCount) + 1 : r + 1;
            String t = allRows.get(r).getTrackingNumber();
            if (physical == box && hasText(t) && !out.contains(t.trim())) out.add(t.trim());
        }
        return out;
    }

    /**
     * Customs broker: the per-shipment override saved on the order wins,
     * then the client's profile for the destination. Null when the
     * carrier's own brokerage clears.
     */
    @SuppressWarnings("unchecked")
    private Party resolveBroker(Order order, ClientCustomsProfile profile) {
        if (hasText(order.getImporterBrokerOverride())) {
            try {
                Map<String, Object> ov = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue(order.getImporterBrokerOverride(), Map.class);
                Object b = ov.get("broker");
                if (b instanceof Map<?, ?> bm) {
                    String name = firstNonBlank(str(bm.get("name")), str(bm.get("company")), "");
                    if (hasText(name)) {
                        return brokerParty(name, str(bm.get("company")), str(bm.get("addressLine1")),
                                str(bm.get("addressLine2")), str(bm.get("city")), str(bm.get("state")),
                                str(bm.get("postalCode")), str(bm.get("countryCode")), str(bm.get("phone")),
                                firstNonBlank(str(bm.get("brokerId")), str(bm.get("license")), ""));
                    }
                }
            } catch (Exception ignore) {
                // malformed override → fall through to the profile
            }
        }
        if (profile == null) return null;
        String name = firstNonBlank(safe(profile.getBrokerName()), safe(profile.getBrokerCompany()), "");
        if (!hasText(name)) return null;
        return brokerParty(name, profile.getBrokerCompany(), profile.getBrokerAddress1(), profile.getBrokerAddress2(),
                profile.getBrokerCity(), profile.getBrokerState(), profile.getBrokerPostcode(), profile.getBrokerCountry(),
                profile.getBrokerPhone(), firstNonBlank(safe(profile.getBrokerId()), safe(profile.getBrokerLicense()), ""));
    }

    private static Party brokerParty(String name, String company, String addr1, String addr2, String city,
                                     String state, String postcode, String country, String phone, String id) {
        List<String> lines = new ArrayList<>(8);
        lines.add(name.trim());
        if (hasText(company) && !company.trim().equalsIgnoreCase(name.trim())) lines.add(company.trim());
        lines.add(safe(addr1));
        lines.add(safe(addr2));
        lines.add(joinCityStateZip(city, state, postcode));
        lines.add(safe(country));
        if (hasText(phone)) lines.add("Tel " + phone.trim());
        if (hasText(id)) lines.add("Broker ID / licence: " + id.trim());
        return new Party("CUSTOMS BROKER", lines);
    }

    private static List<String> shipToLines(Order order) {
        String name = firstNonBlank(order.getShipName(), order.getShipAttn(), "");
        String company = hasText(order.getShipAttn()) && !order.getShipAttn().trim().equalsIgnoreCase(name.trim())
                ? order.getShipAttn().trim() : null;
        List<String> lines = new ArrayList<>(7);
        lines.add(name);
        if (company != null) lines.add(company);
        lines.add(safe(order.getShipAddr1()));
        if (hasText(order.getLocation())) lines.add(order.getLocation().trim());
        lines.add(joinCityStateZip(order.getShiptoCity(), order.getShiptoState(), order.getShiptoZip()));
        lines.add(firstNonBlank(order.getCountryName(), order.getShiptoCountryCd(), ""));
        if (hasText(order.getPhone())) lines.add("Tel " + order.getPhone().trim());
        return lines;
    }

    /**
     * The exporter is where THIS parcel shipped from — the ship-from
     * persisted on the order (what the label prints); the client's
     * registered address is only the fallback.
     */
    private static Party exporterParty(Order order, Client client) {
        List<String> lines = new ArrayList<>(8);
        if (order != null && hasText(order.getShipFromAddr1())) {
            // The exporter is the legal entity (ship-from company, else the
            // client); the ship-from name is the sender / warehouse location.
            String entity = exporterEntity(order, client);
            lines.add(entity);
            if (hasText(order.getShipFromName())
                    && !order.getShipFromName().trim().equalsIgnoreCase(entity.trim())) {
                lines.add(order.getShipFromName().trim());
            }
            lines.add(order.getShipFromAddr1().trim());
            lines.add(safe(order.getShipFromAddr2()));
            lines.add(joinCityStateZip(order.getShipFromCity(), order.getShipFromState(), order.getShipFromZip()));
            lines.add(safe(order.getShipFromCountryCd()));
            String phone = firstNonBlank(order.getShipFromPhone(),
                    client == null || client.getShipFrom() == null ? null : client.getShipFrom().getPhone(),
                    client == null ? null : client.getPhone());
            if (hasText(phone)) lines.add("Tel " + phone);
        } else if (client != null && client.getShipFrom() != null) {
            Address a = client.getShipFrom();
            lines.add(firstNonBlank(a.getName(), client.getName(), ""));
            if (hasText(a.getName()) && hasText(client.getName())
                    && !a.getName().trim().equalsIgnoreCase(client.getName().trim())) {
                lines.add(client.getName().trim());
            }
            lines.add(safe(a.getLine1()));
            lines.add(safe(a.getLine2()));
            lines.add(joinCityStateZip(a.getCity(), a.getState(), a.getZip()));
            lines.add(safe(a.getCountry()));
            String phone = firstNonBlank(a.getPhone(), client.getPhone());
            if (hasText(phone)) lines.add("Tel " + phone);
        } else if (client != null) {
            lines.add(safe(client.getName()));
        } else {
            lines.add("-");
        }
        if (client != null && hasText(client.getEmail())) lines.add(client.getEmail().trim());
        return new Party("EXPORTER / SHIPPER", lines);
    }

    /**
     * The exporting legal entity. The ship-from company when it is a real
     * company; when the warehouse label was saved into both the name and
     * company slots ("Main Fulfillment Center" twice) the client is the
     * exporter and the warehouse becomes the location line.
     */
    private static String exporterEntity(Order order, Client client) {
        String company = safe(order.getShipFromCompany()).trim();
        String name = safe(order.getShipFromName()).trim();
        String clientName = client == null ? "" : safe(client.getName()).trim();
        if (hasText(company) && !(company.equalsIgnoreCase(name) && hasText(clientName))) return company;
        return firstNonBlank(clientName, company, name, "");
    }

    // =====================================================================
    // Rendering
    // =====================================================================

    /** One placeable row of the body. {@code line} is set on item rows only. */
    private interface RowDrawer { float draw(Pen pen, float y) throws IOException; }
    private record Row(String section, boolean header, boolean keepWithNext, float height, RowDrawer drawer, Line line) {}

    private byte[] renderPdf(Model m) {
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            PDRectangle pageSize = resolvePageSize(m.client());
            float pageW = pageSize.getWidth();
            float pageH = pageSize.getHeight();
            float contentW = pageW - 2 * MARGIN;
            PkgTable pkgTable = new PkgTable(MARGIN, contentW);

            // The body is a flat list of rows: ONE continuous, numbered table of
            // every line item (the client's requirement — all lines on one
            // invoice, never split per package), then the per-piece annex for a
            // multi-package shipment. When lines are assigned to boxes the
            // table carries a PKG column; the annex breaks the shipment down by
            // piece. Section headers repeat on continuation pages.
            boolean anyBox = m.lines().stream().anyMatch(l -> l.box() != null);
            Table table = new Table(MARGIN, contentW, m.lineUnit(), anyBox);
            List<Row> rows = new ArrayList<>();
            rows.add(new Row("items", true, false, table.headerH(), table::drawHeader, null));
            for (int i = 0; i < m.lines().size(); i++) {
                Line ln = m.lines().get(i);
                boolean zebra = i % 2 == 1;
                rows.add(new Row("items", false, false, ROW_H, (pen, y) -> table.drawRow(pen, y, ln, zebra), ln));
            }
            int lastItemRow = -1;
            for (int i = 0; i < rows.size(); i++) if ("items".equals(rows.get(i).section()) && !rows.get(i).header()) lastItemRow = i;
            if (!m.pieces().isEmpty()) {
                rows.add(new Row("pkgs", true, false, pkgTable.headerH(), pkgTable::drawHeader, null));
                for (int i = 0; i < m.pieces().size(); i++) {
                    Pkg pk = m.pieces().get(i);
                    boolean zebra = i % 2 == 1;
                    rows.add(new Row("pkgs", false, false, ROW_H, (pen, y) -> pkgTable.drawRow(pen, y, pk, zebra), null));
                }
                if (!anyBox) {
                    String note = "Line-to-package allocation was not declared for this shipment; the item lines above cover all "
                            + m.packages() + " packages together.";
                    rows.add(new Row("pkgs", false, false, 13f, (pen, y) -> {
                        pen.text(HELVETICA_OBLIQUE, 7.2f, TAUPE, fit(HELVETICA_OBLIQUE, 7.2f, note, contentW), MARGIN, y - 10f, 0f);
                        return y - 13f;
                    }, null));
                }
            }

            // Pass 1 — measure the fixed blocks with a dry pen, then place rows
            // page by page so the closing block always fits on the last page.
            Pen dry = new Pen(null);
            float firstHeaderH = drawFirstHeader(dry, m, pageW, pageH);
            float contHeaderH = drawContinuationHeader(dry, m, pageW, pageH);
            float closingH = drawClosing(dry, m, pageW, MARGIN, contentW, 0f);
            float carryH = 20f;
            float bottom = MARGIN + FOOTER_H;

            List<int[]> pages = new ArrayList<>();
            int start = 0;
            int idx = 0;
            float y = pageH - MARGIN - firstHeaderH;
            while (idx < rows.size()) {
                Row r = rows.get(idx);
                boolean itemsBody = "items".equals(r.section()) && !r.header();
                boolean lastRow = idx == rows.size() - 1;
                float reserve = (itemsBody ? carryH : 0f) + (lastRow ? closingH : 0f);
                float need = r.height();
                // Never strand a section header or a package band at a page foot;
                // a section header also needs room for a few rows (a two-row
                // annex split 2 + 1 across pages reads as two tables).
                if ((r.header() || r.keepWithNext()) && idx + 1 < rows.size()) {
                    int keep = r.header() ? 3 : 1;
                    for (int k = 1; k <= keep && idx + k < rows.size(); k++) need += rows.get(idx + k).height();
                    // A short packages annex (the final section) moves as one
                    // unit together with the closing block — otherwise the
                    // closing reserve strands its last row on the next page.
                    if ("pkgs".equals(r.section()) && r.header() && rows.size() - idx - 1 <= 6) {
                        need = r.height();
                        for (int k = idx + 1; k < rows.size(); k++) need += rows.get(k).height();
                        need += closingH;
                    }
                }
                if (y - need < bottom + reserve && idx > start) {
                    pages.add(new int[]{start, idx});
                    start = idx;
                    y = pageH - MARGIN - contHeaderH;
                    if (!r.header()) y -= headerFor(r.section(), table, pkgTable); // repeated header
                    continue;
                }
                y -= r.height();
                if (idx == lastItemRow) y -= carryH;
                idx++;
            }
            pages.add(new int[]{start, rows.size()});
            {
                // The closing block may still not fit under the final rows —
                // push it to one more page (measured, so it is exact).
                int[] last = pages.get(pages.size() - 1);
                float yEnd = pageH - MARGIN - (pages.size() == 1 ? firstHeaderH : contHeaderH);
                if (last[0] < rows.size() && !rows.get(last[0]).header()) yEnd -= headerFor(rows.get(last[0]).section(), table, pkgTable);
                for (int i = last[0]; i < last[1]; i++) {
                    yEnd -= rows.get(i).height();
                    if (i == lastItemRow) yEnd -= carryH;
                }
                if (yEnd - closingH < bottom) pages.add(new int[]{rows.size(), rows.size()});
            }
            int totalPages = pages.size();
            log.info("CommercialInvoice — order={} items={} pieces={} grouped={} pages={} paper={}", m.order().getOrderNo(),
                    m.lines().size(), m.pieces().size(), anyBox, totalPages, pageSize == PDRectangle.A4 ? "A4" : "LETTER");

            // Pass 2 — emit.
            BigDecimal carried = BigDecimal.ZERO;
            for (int p = 0; p < totalPages; p++) {
                int[] range = pages.get(p);
                boolean first = p == 0;
                boolean last = p == totalPages - 1;
                int lastItemsRowOnPage = -1;
                for (int i = range[0]; i < range[1]; i++) {
                    if ("items".equals(rows.get(i).section()) && !rows.get(i).header()) lastItemsRowOnPage = i;
                }
                PDPage page = new PDPage(pageSize);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    Pen pen = new Pen(cs);
                    float top = pageH - MARGIN;
                    y = top - (first ? drawFirstHeader(pen, m, pageW, pageH) : drawContinuationHeader(pen, m, pageW, pageH));
                    if (range[0] < rows.size() && !rows.get(range[0]).header()) {
                        // Continuation of a section: repeat its header.
                        y = "items".equals(rows.get(range[0]).section()) ? table.drawHeader(pen, y) : pkgTable.drawHeader(pen, y);
                    }
                    BigDecimal pageSum = BigDecimal.ZERO;
                    for (int i = range[0]; i < range[1]; i++) {
                        Row r = rows.get(i);
                        y = r.drawer().draw(pen, y);
                        if (r.line() != null) pageSum = pageSum.add(r.line().amount());
                        if (i == lastItemsRowOnPage) {
                            // Carry line straight after the items on this page.
                            carried = carried.add(pageSum);
                            boolean finalItems = i == lastItemRow;
                            pen.rule(MARGIN, MARGIN + contentW, y, RULE, 0.5f);
                            y -= 13f;
                            pen.text(HELVETICA_BOLD, 8f, TAUPE,
                                    (finalItems ? "SUBTOTAL THIS PAGE (" : "CARRIED FORWARD (") + m.currency() + ")",
                                    MARGIN + contentW - 200f, y, 0.4f);
                            pen.rightText(HELVETICA_BOLD, 8.5f, INK, money(finalItems ? pageSum : carried), MARGIN + contentW - 4f, y);
                            y -= 7f;
                        }
                    }
                    if (range[1] > range[0] && "pkgs".equals(rows.get(range[1] - 1).section())) {
                        pen.rule(MARGIN, MARGIN + contentW, y, RULE, 0.5f);
                    }
                    if (m.lines().isEmpty() && first) pen.rule(MARGIN, MARGIN + contentW, y, RULE, 0.5f);
                    if (last) drawClosing(pen, m, pageW, MARGIN, contentW, y);
                    drawFooter(pen, m, pageW, p + 1, totalPages);
                }
            }
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to render commercial invoice for order "
                    + m.order().getOrderNo(), e);
        }
    }

    private static float headerFor(String section, Table table, PkgTable pkgTable) {
        return "items".equals(section) ? table.headerH() : pkgTable.headerH();
    }

    private static PDRectangle resolvePageSize(Client client) {
        if (client != null && client.getDefaultPaperSize() != null
                && "LETTER".equals(client.getDefaultPaperSize().trim().toUpperCase())) {
            return PDRectangle.LETTER;
        }
        return PDRectangle.A4;
    }

    // ---- page 1 header: title, meta panel, party grid --------------------

    /** Draws (or measures) the page-1 header; returns its height. */
    private float drawFirstHeader(Pen pen, Model m, float pageW, float pageH) throws IOException {
        float contentW = pageW - 2 * MARGIN;
        float top = pageH - MARGIN;
        float y = top;

        // Title row
        pen.text(HELVETICA_BOLD, 21f, INK, "COMMERCIAL INVOICE", MARGIN, y - 18f, 0.3f);
        pen.text(HELVETICA, 7.5f, TAUPE, "CUSTOMS DECLARATION FOR INTERNATIONAL SHIPMENT  -  ORIGINAL", MARGIN, y - 30f, 0.6f);
        pen.rightText(HELVETICA_BOLD, 11f, ESPRESSO, m.exporterName(), MARGIN + contentW, y - 16f);
        pen.rightText(HELVETICA, 7.5f, TAUPE, "Invoice no. " + m.order().getOrderNo()
                + "   -   " + m.currency() + "   -   " + m.meta().get(1).value(), MARGIN + contentW, y - 29f);
        y -= 40f;
        pen.rule(MARGIN, MARGIN + contentW, y, INK, 1.1f);
        y -= 8f;

        // Meta panel: 4 columns × N rows of label/value cells on cream.
        int cols = 4;
        int rowsN = (m.meta().size() + cols - 1) / cols;
        float cellH = 27f;
        float panelH = rowsN * cellH + 6f;
        pen.fill(MARGIN, y - panelH, contentW, panelH, CREAM);
        float colW = contentW / cols;
        for (int i = 0; i < m.meta().size(); i++) {
            Meta cell = m.meta().get(i);
            float cx = MARGIN + 9f + (i % cols) * colW;
            float cy = y - 3f - (i / cols) * cellH;
            pen.text(HELVETICA_BOLD, 6.6f, TAUPE, cell.label().toUpperCase(), cx, cy - 9f, 0.55f);
            // Shrink long values (service names, references) two steps before truncating.
            float vs = 9f;
            float maxW = colW - 14f;
            if (textWidth(HELVETICA, vs, cell.value()) > maxW) vs = 8f;
            if (textWidth(HELVETICA, vs, cell.value()) > maxW) vs = 7.2f;
            pen.text(HELVETICA, vs, INK, fit(HELVETICA, vs, cell.value(), maxW), cx, cy - 21f, 0f);
        }
        y -= panelH + 14f;

        // Party grid: exporter | importer, consignee | broker (or sold-to).
        float gap = 18f;
        float colWp = (contentW - gap) / 2f;
        Party rightBottom = m.broker() != null ? m.broker()
                : new Party("SOLD TO / BUYER", List.of("Same as consignee"));
        float h1 = Math.max(partyHeight(m.exporter()), partyHeight(m.importer()));
        drawParty(pen, m.exporter(), MARGIN, y, colWp);
        drawParty(pen, m.importer(), MARGIN + colWp + gap, y, colWp);
        y -= h1 + 10f;
        float h2 = Math.max(partyHeight(m.consignee()), partyHeight(rightBottom));
        drawParty(pen, m.consignee(), MARGIN, y, colWp);
        drawParty(pen, rightBottom, MARGIN + colWp + gap, y, colWp);
        y -= h2 + 12f;
        return top - y;
    }

    private float drawContinuationHeader(Pen pen, Model m, float pageW, float pageH) throws IOException {
        float contentW = pageW - 2 * MARGIN;
        float top = pageH - MARGIN;
        pen.text(HELVETICA_BOLD, 12.5f, INK, "COMMERCIAL INVOICE", MARGIN, top - 12f, 0.3f);
        float tw = textWidth(HELVETICA_BOLD, 12.5f, "COMMERCIAL INVOICE") + 0.3f * 18;
        pen.text(HELVETICA, 8.5f, TAUPE, "No. " + m.order().getOrderNo() + "  -  continued", MARGIN + tw + 8f, top - 12f, 0f);
        pen.rightText(HELVETICA, 8.5f, ESPRESSO, m.exporterName() + "  -  " + m.meta().get(1).value(), MARGIN + contentW, top - 12f);
        pen.rule(MARGIN, MARGIN + contentW, top - 20f, INK, 0.8f);
        return 32f;
    }

    private static float partyHeight(Party p) {
        int n = 0;
        for (String l : p.lines()) if (hasText(l)) n++;
        return 17f + n * 11f;
    }

    private void drawParty(Pen pen, Party p, float x, float y, float w) throws IOException {
        pen.text(HELVETICA_BOLD, 6.8f, TAUPE, p.title(), x, y - 7f, 0.6f);
        pen.rule(x, x + w, y - 11f, RULE, 0.6f);
        float ly = y - 23f;
        boolean firstLine = true;
        for (String line : p.lines()) {
            if (!hasText(line)) continue;
            pen.text(firstLine ? HELVETICA_BOLD : HELVETICA, 8.8f, INK, fit(firstLine ? HELVETICA_BOLD : HELVETICA, 8.8f, line, w), x, ly, 0f);
            firstLine = false;
            ly -= 11f;
        }
    }

    // ---- items table -----------------------------------------------------

    /** Column geometry + header/row drawing for the itemised table. */
    private static final class Table {
        final float left;
        final float width;
        final String unit;
        final boolean showPkg;
        // x positions (left edge of each column) and widths
        final float xNo, xPkg, xDesc, xHs, xOrig, xQty, xUom, xNet, xUnit, xAmt;
        final float wPkg, wDesc, wHs, wOrig, wQty, wUom, wNet, wUnit, wAmt;

        Table(float left, float width, String unit, boolean showPkg) {
            this.left = left;
            this.width = width;
            this.unit = unit;
            this.showPkg = showPkg;
            float pad = 5f;
            wHs = 68f; wOrig = 34f; wQty = 28f; wUom = 26f; wNet = 54f; wUnit = 60f; wAmt = 66f;
            float wNo = 18f;
            wPkg = showPkg ? 30f : 0f;
            wDesc = width - (wNo + wPkg + wHs + wOrig + wQty + wUom + wNet + wUnit + wAmt);
            xNo = left + pad;
            xPkg = left + wNo;
            xDesc = left + wNo + wPkg;
            xHs = xDesc + wDesc;
            xOrig = xHs + wHs;
            xQty = xOrig + wOrig;
            xUom = xQty + wQty;
            xNet = xUom + wUom;
            xUnit = xNet + wNet;
            xAmt = xUnit + wUnit;
        }

        float headerH() { return 20f; }

        float drawHeader(Pen pen, float y) throws IOException {
            float h = 18f;
            pen.fill(left, y - h, width, h, CREAM_DEEP);
            float ty = y - 12f;
            pen.text(HELVETICA_BOLD, 6.6f, TAUPE, "#", xNo, ty, 0.5f);
            if (showPkg) pen.text(HELVETICA_BOLD, 6.6f, TAUPE, "PKG", xPkg, ty, 0.5f);
            pen.text(HELVETICA_BOLD, 6.6f, TAUPE, "DESCRIPTION OF GOODS", xDesc, ty, 0.5f);
            pen.text(HELVETICA_BOLD, 6.6f, TAUPE, "HS CODE", xHs, ty, 0.5f);
            pen.text(HELVETICA_BOLD, 6.6f, TAUPE, "ORIGIN", xOrig, ty, 0.5f);
            pen.rightText(HELVETICA_BOLD, 6.6f, TAUPE, "QTY", xQty + wQty - 4f, ty);
            pen.text(HELVETICA_BOLD, 6.6f, TAUPE, "UOM", xUom + 2f, ty, 0.5f);
            pen.rightText(HELVETICA_BOLD, 6.6f, TAUPE, "NET WT " + unit, xNet + wNet - 4f, ty);
            pen.rightText(HELVETICA_BOLD, 6.6f, TAUPE, "UNIT VALUE", xUnit + wUnit - 4f, ty);
            pen.rightText(HELVETICA_BOLD, 6.6f, TAUPE, "TOTAL VALUE", xAmt + wAmt - 4f, ty);
            return y - headerH();
        }

        float drawRow(Pen pen, float y, Line ln, boolean zebra) throws IOException {
            if (zebra) pen.fill(left, y - ROW_H, width, ROW_H, ZEBRA);
            float ty = y - 10f;
            pen.text(HELVETICA, 7.5f, TAUPE, String.valueOf(ln.no()), xNo, ty, 0f);
            if (showPkg) pen.text(HELVETICA, 8f, INK, ln.box() == null ? "-" : String.valueOf(ln.box()), xPkg, ty, 0f);
            float descW = wDesc - 8f;
            if (hasText(ln.sku())) {
                String sku = fit(HELVETICA, 7f, ln.sku(), 70f);
                float skuW = textWidth(HELVETICA, 7f, sku);
                pen.rightText(HELVETICA, 7f, TAUPE, sku, xDesc + wDesc - 6f, ty);
                descW -= skuW + 8f;
            }
            pen.text(HELVETICA, 8.5f, INK, fit(HELVETICA, 8.5f, ln.description(), descW), xDesc, ty, 0f);
            // A 10-digit HS code (6109.10.0010) must never be cut on a customs
            // document: shrink the type before truncating.
            float hsSize = textWidth(HELVETICA, 8.5f, ln.hs()) <= wHs - 6f ? 8.5f
                    : textWidth(HELVETICA, 7.6f, ln.hs()) <= wHs - 6f ? 7.6f : 6.8f;
            pen.text(HELVETICA, hsSize, INK, fit(HELVETICA, hsSize, ln.hs(), wHs - 6f), xHs, ty, 0f);
            pen.text(HELVETICA, 8.5f, INK, ln.origin(), xOrig, ty, 0f);
            pen.rightText(HELVETICA, 8.5f, INK, String.valueOf(ln.qty()), xQty + wQty - 4f, ty);
            pen.text(HELVETICA, 8.5f, INK, "EA", xUom + 2f, ty, 0f);
            pen.rightText(HELVETICA, 8.5f, ln.netEstimated() ? TAUPE : INK, ln.netWeight(), xNet + wNet - 4f, ty);
            pen.rightText(HELVETICA, 8.5f, INK, money(ln.unit()), xUnit + wUnit - 4f, ty);
            pen.rightText(HELVETICA_BOLD, 8.5f, INK, money(ln.amount()), xAmt + wAmt - 4f, ty);
            return y - ROW_H;
        }
    }

    /** Per-piece annex table for multi-package shipments. */
    private static final class PkgTable {
        final float left, width;
        final float xNo, xTrack, xPack, xDims, xWt, xCont, xVal;
        final float wNo, wTrack, wPack, wDims, wWt, wCont, wVal;

        PkgTable(float left, float width) {
            this.left = left;
            this.width = width;
            wNo = 30f; wTrack = 118f; wPack = 74f; wDims = 86f; wWt = 52f; wVal = 60f;
            wCont = width - (wNo + wTrack + wPack + wDims + wWt + wVal);
            xNo = left + 5f;
            xTrack = left + wNo;
            xPack = xTrack + wTrack;
            xDims = xPack + wPack;
            xWt = xDims + wDims;
            xCont = xWt + wWt;
            xVal = xCont + wCont;
        }

        float headerH() { return 34f; }

        float drawHeader(Pen pen, float y) throws IOException {
            pen.text(HELVETICA_BOLD, 6.8f, TAUPE, "PACKAGES IN THIS SHIPMENT", left, y - 9f, 0.6f);
            y -= 14f;
            float h = 18f;
            pen.fill(left, y - h, width, h, CREAM_DEEP);
            float ty = y - 12f;
            pen.text(HELVETICA_BOLD, 6.6f, TAUPE, "PIECE", xNo, ty, 0.5f);
            pen.text(HELVETICA_BOLD, 6.6f, TAUPE, "TRACKING NO.", xTrack, ty, 0.5f);
            pen.text(HELVETICA_BOLD, 6.6f, TAUPE, "PACKAGING", xPack, ty, 0.5f);
            pen.text(HELVETICA_BOLD, 6.6f, TAUPE, "DIMENSIONS", xDims, ty, 0.5f);
            pen.rightText(HELVETICA_BOLD, 6.6f, TAUPE, "WEIGHT", xWt + wWt - 4f, ty);
            pen.text(HELVETICA_BOLD, 6.6f, TAUPE, "CONTENTS", xCont + 4f, ty, 0.5f);
            pen.rightText(HELVETICA_BOLD, 6.6f, TAUPE, "VALUE", xVal + wVal - 4f, ty);
            return y - 20f;
        }

        float drawRow(Pen pen, float y, Pkg pk, boolean zebra) throws IOException {
            if (zebra) pen.fill(left, y - ROW_H, width, ROW_H, ZEBRA);
            float ty = y - 10f;
            pen.text(HELVETICA, 8.5f, INK, String.valueOf(pk.seq()), xNo, ty, 0f);
            pen.text(HELVETICA, 8.5f, INK, fit(HELVETICA, 8.5f, pk.tracking(), wTrack - 6f), xTrack, ty, 0f);
            pen.text(HELVETICA, 8.5f, INK, fit(HELVETICA, 8.5f, pk.packaging(), wPack - 6f), xPack, ty, 0f);
            pen.text(HELVETICA, 8.5f, INK, fit(HELVETICA, 8.5f, pk.dims(), wDims - 6f), xDims, ty, 0f);
            pen.rightText(HELVETICA, 8.5f, INK, pk.weight(), xWt + wWt - 4f, ty);
            pen.text(HELVETICA, 8.5f, INK, fit(HELVETICA, 8.5f, pk.contents(), wCont - 8f), xCont + 4f, ty, 0f);
            pen.rightText(HELVETICA, 8.5f, INK, pk.value(), xVal + wVal - 4f, ty);
            return y - ROW_H;
        }
    }

    // ---- closing: shipment summary, totals, declaration, signatures ------

    /** Draws (or measures) the last-page closing block from y downward; returns its height. */
    private float drawClosing(Pen pen, Model m, float pageW, float left, float contentW, float y) throws IOException {
        float top = y;
        y -= 6f;
        pen.rule(left, left + contentW, y, INK, 0.9f);
        y -= 6f;

        float gap = 18f;
        float leftW = contentW * 0.55f - gap / 2f;
        float rightX = left + leftW + gap;
        float rightW = contentW - leftW - gap;

        // -- left: shipment summary + notes
        float ly = y;
        pen.text(HELVETICA_BOLD, 6.8f, TAUPE, "SHIPMENT SUMMARY", left, ly - 7f, 0.6f);
        pen.rule(left, left + leftW, ly - 11f, RULE, 0.6f);
        ly -= 23f;
        List<String[]> summary = new ArrayList<>();
        summary.add(new String[]{"Packages", m.packages() + (m.packages() == 1 ? " piece" : " pieces")});
        summary.add(new String[]{"Total quantity", m.totalQty() + " units"});
        if (m.gross() != null) {
            summary.add(new String[]{"Gross weight", m.gross().stripTrailingZeros().toPlainString() + " " + m.grossUnit()});
        }
        if (m.net() != null && m.net().signum() > 0) {
            summary.add(new String[]{"Net weight", m.net().setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
                    + " " + m.lineUnit() + (m.netEstimated() ? " *" : "")});
        }
        if (!m.origins().isEmpty()) summary.add(new String[]{"Country of origin", String.join(", ", m.origins())});
        summary.add(new String[]{"Declared value for customs", money(m.goods()) + " " + m.currency()});
        for (String[] kv : summary) {
            pen.text(HELVETICA, 8.5f, TAUPE, kv[0], left, ly, 0f);
            pen.text(HELVETICA_BOLD, 8.5f, INK, fit(HELVETICA_BOLD, 8.5f, kv[1], leftW - 132f), left + 128f, ly, 0f);
            ly -= 11.5f;
        }
        if (m.netEstimated()) {
            ly -= 2f;
            for (String w : wrap(HELVETICA_OBLIQUE, 7.2f,
                    "* Net weight apportioned from the shipment gross by quantity where no per-item weight was declared.", leftW)) {
                pen.text(HELVETICA_OBLIQUE, 7.2f, TAUPE, w, left, ly, 0f);
                ly -= 9f;
            }
        }
        if (hasText(m.notes())) {
            ly -= 4f;
            pen.text(HELVETICA_BOLD, 6.8f, TAUPE, "NOTES", left, ly, 0.6f);
            ly -= 11f;
            for (String w : wrap(HELVETICA, 8f, m.notes(), leftW)) {
                pen.text(HELVETICA, 8f, INK, w, left, ly, 0f);
                ly -= 10f;
            }
        }

        // -- right: totals ladder
        float ry = y;
        pen.text(HELVETICA_BOLD, 6.8f, TAUPE, "INVOICE TOTALS", rightX, ry - 7f, 0.6f);
        pen.rule(rightX, rightX + rightW, ry - 11f, RULE, 0.6f);
        ry -= 23f;
        BigDecimal total = m.goods();
        pen.text(HELVETICA, 8.5f, TAUPE, "Subtotal - goods", rightX, ry, 0f);
        pen.rightText(HELVETICA, 8.5f, INK, money(m.goods()), rightX + rightW - 4f, ry);
        ry -= 11.5f;
        pen.text(HELVETICA, 8.5f, TAUPE, "Freight", rightX, ry, 0f);
        if (m.freight() != null) {
            total = total.add(m.freight());
            pen.rightText(HELVETICA, 8.5f, INK, money(m.freight()), rightX + rightW - 4f, ry);
        } else {
            pen.rightText(HELVETICA, 8f, TAUPE, m.freightNote(), rightX + rightW - 4f, ry);
        }
        ry -= 11.5f;
        pen.text(HELVETICA, 8.5f, TAUPE, "Insurance", rightX, ry, 0f);
        pen.rightText(HELVETICA, 8f, TAUPE, "Not declared", rightX + rightW - 4f, ry);
        ry -= 8f;
        float totalRowH = 22f;
        pen.fill(rightX, ry - totalRowH, rightW, totalRowH, CREAM_DEEP);
        pen.text(HELVETICA_BOLD, 8.2f, ESPRESSO, "TOTAL INVOICE VALUE (" + m.currency() + ")", rightX + 6f, ry - 14f, 0.4f);
        pen.rightText(HELVETICA_BOLD, 11.5f, INK, money(total), rightX + rightW - 6f, ry - 15f);
        ry -= totalRowH;

        y = Math.min(ly, ry) - 14f;

        // -- declaration + signatures (full width)
        pen.rule(left, left + contentW, y, RULE, 0.6f);
        y -= 12f;
        String decl = "I declare the information on this invoice to be true and correct to the best of my knowledge, "
                + "that the goods described are of the origin stated, and that this invoice shows the actual price of the "
                + "goods and all charges relating to the sale.";
        for (String w : wrap(HELVETICA_OBLIQUE, 8.2f, decl, contentW)) {
            pen.text(HELVETICA_OBLIQUE, 8.2f, ESPRESSO, w, left, y, 0f);
            y -= 10.5f;
        }
        y -= 22f;
        float sigW = (contentW - 2 * gap) / 3f;
        String[] sigLabels = {"Authorised signature of exporter", "Printed name and title", "Date"};
        String[] sigValues = {"", "", ""};
        for (int i = 0; i < 3; i++) {
            float sx = left + i * (sigW + gap);
            if (hasText(sigValues[i])) pen.text(HELVETICA, 8.5f, INK, fit(HELVETICA, 8.5f, sigValues[i], sigW), sx, y + 4f, 0f);
            pen.rule(sx, sx + sigW, y, INK, 0.6f);
            pen.text(HELVETICA, 6.8f, TAUPE, sigLabels[i].toUpperCase(), sx, y - 9f, 0.5f);
        }
        y -= 14f;
        return top - y;
    }

    private void drawFooter(Pen pen, Model m, float pageW, int pageNo, int totalPages) throws IOException {
        float contentW = pageW - 2 * MARGIN;
        float y = MARGIN + 6f;
        pen.rule(MARGIN, MARGIN + contentW, y + 10f, RULE, 0.5f);
        pen.text(HELVETICA, 7f, TAUPE, "Commercial invoice no. " + m.order().getOrderNo()
                + "  -  " + m.exporterName() + "  -  generated " + m.stamp() + " by Multiship", MARGIN, y, 0f);
        pen.rightText(HELVETICA_BOLD, 7.5f, INK, "Page " + pageNo + " of " + totalPages, MARGIN + contentW, y);
    }

    // =====================================================================
    // Low-level drawing (null stream = measure only)
    // =====================================================================

    private static final class Pen {
        private final PDPageContentStream cs;
        Pen(PDPageContentStream cs) { this.cs = cs; }

        void text(PDType1Font font, float size, Color color, String text, float x, float y, float spacing) throws IOException {
            if (cs == null || text == null || text.isBlank()) return;
            cs.setNonStrokingColor(color);
            cs.beginText();
            cs.setFont(font, size);
            if (spacing > 0f) cs.setCharacterSpacing(spacing);
            cs.newLineAtOffset(x, y);
            cs.showText(sanitise(text));
            if (spacing > 0f) cs.setCharacterSpacing(0f);
            cs.endText();
            cs.setNonStrokingColor(Color.BLACK);
        }

        void rightText(PDType1Font font, float size, Color color, String text, float rightX, float y) throws IOException {
            if (text == null) return;
            text(font, size, color, text, rightX - textWidth(font, size, text), y, 0f);
        }

        void rule(float x1, float x2, float y, Color c, float w) throws IOException {
            if (cs == null) return;
            cs.setStrokingColor(c);
            cs.setLineWidth(w);
            cs.moveTo(x1, y);
            cs.lineTo(x2, y);
            cs.stroke();
            cs.setStrokingColor(Color.BLACK);
        }

        void fill(float x, float y, float w, float h, Color c) throws IOException {
            if (cs == null) return;
            cs.setNonStrokingColor(c);
            cs.addRect(x, y, w, h);
            cs.fill();
            cs.setNonStrokingColor(Color.BLACK);
        }
    }

    private static float textWidth(PDType1Font font, float size, String text) {
        try {
            return font.getStringWidth(sanitise(text)) / 1000f * size;
        } catch (Exception e) {
            return size * 0.5f * (text == null ? 0 : text.length());
        }
    }

    /** Truncate with an ellipsis so the text fits within {@code maxW} points. */
    private static String fit(PDType1Font font, float size, String text, float maxW) {
        if (text == null) return "";
        String t = text.trim();
        if (textWidth(font, size, t) <= maxW) return t;
        String ell = "...";
        float ellW = textWidth(font, size, ell);
        int lo = 0;
        while (lo < t.length() && textWidth(font, size, t.substring(0, lo + 1)) + ellW <= maxW) lo++;
        return t.substring(0, Math.max(0, lo)).stripTrailing() + ell;
    }

    /** Greedy word wrap to {@code maxW} points. */
    private static List<String> wrap(PDType1Font font, float size, String text, float maxW) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String para : text.replace("\r", "").split("\n")) {
            StringBuilder line = new StringBuilder();
            for (String word : para.trim().split("\\s+")) {
                if (word.isEmpty()) continue;
                String candidate = line.length() == 0 ? word : line + " " + word;
                if (textWidth(font, size, candidate) <= maxW) {
                    line.setLength(0);
                    line.append(candidate);
                } else {
                    if (line.length() > 0) out.add(line.toString());
                    line.setLength(0);
                    line.append(fit(font, size, word, maxW));
                }
            }
            if (line.length() > 0) out.add(line.toString());
        }
        return out;
    }

    /** Standard-14 Helvetica only supports WinAnsi — strip anything else. */
    private static String sanitise(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 32 && c <= 126) out.append(c);
            else if (c == '–' || c == '—' || c == '·' || c == '•') out.append('-');
            else if (c == '…') out.append("...");
            else if (c == ' ') out.append(' ');
            else out.append('?');
        }
        return out.toString();
    }

    // =====================================================================
    // Formatting helpers
    // =====================================================================

    private static final DecimalFormat MONEY =
            new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.US));

    private static String money(BigDecimal v) {
        if (v == null) return "0.00";
        synchronized (MONEY) {
            return MONEY.format(v.setScale(2, RoundingMode.HALF_UP));
        }
    }

    /**
     * Who settles duties & taxes. The payer the carrier was actually told
     * (recorded at label time) wins over the Incoterm's default, so a DDP
     * invoice whose duties were deliberately billed to the recipient says so
     * instead of promising "prepaid by shipper".
     */
    private static String dutyTerms(String incoterms, String paidBy, String account) {
        String code = incoterms == null || incoterms.isBlank() ? "DAP" : incoterms.trim().toUpperCase();
        String who = paidBy == null || paidBy.isBlank()
                ? (code.equals("DDP") ? "SENDER" : "RECIPIENT")
                : paidBy.trim().toUpperCase();
        return switch (who) {
            case "SENDER" -> "Prepaid by shipper (" + code + ")";
            case "THIRD_PARTY" -> "Billed to third party" + (hasText(account) ? " acct " + maskAccount(account) : "") + " (" + code + ")";
            // Keep it short enough for the meta cell: the 'Duties billed to' cell
            // beside it names the consignee, so no suffix is needed.
            default -> "Payable by consignee (" + code + ")";
        };
    }

    private static String maskAccount(String account) {
        String a = account.trim();
        return a.length() <= 4 ? a : "***" + a.substring(a.length() - 4);
    }

    private static String dutyPayer(String incoterms) {
        return "DDP".equalsIgnoreCase(incoterms == null ? "" : incoterms.trim()) ? "Shipper" : "Consignee";
    }

    private static String dutiesAccount(String incoterms, OrderCustoms customs, ClientCustomsProfile profile) {
        // The recorded payer (label time) first; the profile only as a fallback.
        if (customs != null && hasText(customs.getDutiesPaidBy())) {
            String recorded = switch (customs.getDutiesPaidBy().trim().toUpperCase()) {
                case "SENDER" -> "Shipper";
                case "THIRD_PARTY" -> "Third party";
                default -> "Consignee";
            };
            String acct = safe(customs.getDutiesAccount()).trim();
            return recorded + (hasText(acct) ? " - acct " + maskAccount(acct) : "");
        }
        // Nothing recorded: the order's own Incoterm decides (the call site falls
        // back to dutyPayer(incoterms)). A client-profile default such as "DDP,
        // duties billed to shipper" for Canada must not contradict an order that
        // shipped DAP; the profile only speaks for orders with no Incoterm.
        if (customs != null && hasText(customs.getIncoterms())) return null;
        if (profile == null) return null;
        String who = safe(profile.getDutiesBillTo()).trim();
        String acct = safe(profile.getDutiesAccount()).trim();
        if (!hasText(who) && !hasText(acct)) return null;
        String whoLabel = switch (who.toUpperCase(Locale.ROOT)) {
            case "SENDER", "SHIPPER" -> "Shipper";
            case "RECIPIENT", "RECEIVER", "CONSIGNEE" -> "Consignee";
            case "THIRD_PARTY", "THIRDPARTY" -> "Third party";
            case "" -> dutyPayer(incoterms);
            default -> titleCase(who.replace('_', ' '));
        };
        StringBuilder sb = new StringBuilder(whoLabel);
        if (hasText(acct)) sb.append(" - acct ").append(acct);
        return sb.toString();
    }

    private static String exportDeclaration(OrderCustoms c) {
        if (hasText(c.getAesCitation())) return "AES ITN " + c.getAesCitation().trim();
        if (hasText(c.getFtrExemption())) {
            String code = c.getFtrExemption().trim();
            String human = switch (code) {
                case "NO_EEI_30_37_a" -> "NO EEI 30.37(a)";
                case "NO_EEI_30_37_h" -> "NO EEI 30.37(h)";
                case "NO_EEI_30_36" -> "NO EEI 30.36";
                default -> code.replace('_', ' ');
            };
            return human + " (FTR exemption)";
        }
        if (hasText(c.getExportDeclarationReference())) return "Ref " + c.getExportDeclarationReference().trim();
        return null;
    }

    private static String taxLine(String taxId, String vat, String eori) {
        StringBuilder sb = new StringBuilder();
        if (hasText(taxId)) sb.append("Tax ID: ").append(taxId.trim());
        if (hasText(vat)) { sep(sb); sb.append("VAT: ").append(vat.trim()); }
        if (hasText(eori)) { sep(sb); sb.append("EORI: ").append(eori.trim()); }
        return sb.toString();
    }

    private static void sep(StringBuilder sb) { if (sb.length() > 0) sb.append("   "); }

    private static String joinCityStateZip(String city, String state, String zip) {
        StringBuilder sb = new StringBuilder();
        if (hasText(city)) sb.append(city.trim());
        if (hasText(state)) { if (sb.length() > 0) sb.append(", "); sb.append(state.trim()); }
        if (hasText(zip)) { if (sb.length() > 0) sb.append(' '); sb.append(zip.trim()); }
        return sb.toString();
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }

    private static boolean hasText(String s) { return s != null && !s.isBlank(); }

    private static String safe(String v) { return v == null ? "" : v; }

    private static String firstNonBlank(String... vals) {
        if (vals == null) return null;
        for (String v : vals) if (hasText(v)) return v.trim();
        return null;
    }
}
