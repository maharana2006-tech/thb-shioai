package com.multiship.backend.service;

import com.multiship.backend.model.CarrierAccountRef;
import com.multiship.backend.model.Client;
import com.multiship.backend.model.PackagePreset;
import com.multiship.backend.model.ShippingService;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Name;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFDataValidationHelper;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Sprint 48 — universal .xlsx template for the order-import upload flow.
 *
 * <p>One workbook covers every client in the platform. Each data row
 * picks its own {@code clientCode} from a dropdown; the {@code carrierCode},
 * {@code accountNumber}, {@code warehouseCode}, {@code serviceType}, and
 * {@code packageType} dropdowns cascade off it via Excel {@code INDIRECT()}
 * formulas on named ranges. Operators can prepare imports for multiple
 * clients in a single spreadsheet without downloading a template per client.
 *
 * <p>Design notes:
 * <ul>
 *   <li>Named ranges are the mechanism for cascading validation. For each
 *       client {@code C} we define {@code _Carriers_C}, {@code _Warehouses_C},
 *       and {@code _Accounts_C_K} (per carrier {@code K}) pointing at
 *       columns on the Reference sheet. Data validation on the row cells
 *       uses {@code INDIRECT("_Carriers_" & clientCell)} etc. so as soon
 *       as the client is picked the downstream dropdowns re-scope.</li>
 *   <li>Client codes may contain hyphens (regex {@code [A-Z0-9_-]+}); Excel
 *       named-range identifiers treat {@code -} as an operator, so we
 *       {@code SUBSTITUTE(clientCell, "-", "_")} inside the INDIRECT
 *       formula and normalize the same way when creating the named range.
 *       The dropdown still shows the natural client code.</li>
 *   <li>{@code accountNumber} validation uses <b>WARN</b> (info alert)
 *       rather than <b>STOP</b> so operators can enter a third-party
 *       account manually when {@code billTo=THIRD_PARTY}. Every other
 *       cascading dropdown uses STOP.</li>
 *   <li>Service + package dropdowns show human names ("UPS Ground") not
 *       wire codes ("03"). The parser resolves names → wire codes
 *       on preview / commit via {@link OrderImportServiceImpl#resolveNamesToCodes}.
 *       Ambiguity risk if two services share a name across carriers,
 *       but the row's carrier column disambiguates.</li>
 * </ul>
 */
final class OrderImportTemplateBuilder {

    private OrderImportTemplateBuilder() { /* static-only */ }

    /** ISO-2 destinations we ship to often — fits in Excel's inline
     *  validation-list source cap (~255 chars) for the countryCode dropdown. */
    static final List<String> COUNTRIES = List.of(
            "US", "CA", "MX", "GB", "IE",
            "DE", "FR", "IT", "ES", "NL", "BE", "PT", "AT", "FI", "GR", "PL", "CH",
            "AU", "NZ",
            "JP", "CN", "HK", "SG", "KR", "TW",
            "IN", "AE", "SA",
            "BR", "AR", "CL"
    );

    static final List<String> WEIGHT_UNITS = List.of("LB", "KG");

    static final List<String> CURRENCIES = List.of(
            "USD", "EUR", "GBP", "CAD", "AUD", "NZD", "JPY", "CNY", "HKD",
            "INR", "MXN", "BRL", "SGD", "CHF"
    );

    static final List<String> BILL_TO = List.of("SENDER", "RECIPIENT", "THIRD_PARTY");

    /** Highest data row on the Import sheet that gets validation applied.
     *  Anything past this the operator can still fill but the dropdowns
     *  won't be attached. 1000 is comfortable for a single upload. */
    private static final int DATA_ROW_LIMIT = 1000;

    /**
     * Build the universal template.
     *
     * @param headers   column ordering — must match {@link OrderImportServiceImpl#HEADERS}
     *                  so downstream parsing lines up.
     * @param clients   every client on the platform (drives per-client cascades).
     * @param accounts  every active + complete carrier account.
     * @param clientWarehouseCodes per-client warehouse codes (upper-case
     *                  client-code key → list of attached warehouse codes)
     *                  — drives the warehouseCode dropdown per client.
     * @param services  every enabled ShippingService (for per-carrier service
     *                  name dropdowns).
     * @param presets   every enabled PackagePreset (for per-carrier package
     *                  name dropdowns).
     */
    static byte[] build(List<String> headers,
                        List<Client> clients,
                        List<CarrierAccountRef> accounts,
                        Map<String, List<String>> clientWarehouseCodes,
                        List<ShippingService> services,
                        List<PackagePreset> presets) {
        try (XSSFWorkbook wb = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            XSSFSheet data = wb.createSheet("Import");
            XSSFSheet ref = wb.createSheet("Reference");
            XSSFSheet notes = wb.createSheet("How to use");

            CellStyle headerStyle = headerStyle(wb);
            CellStyle sampleStyle = sampleStyle(wb);
            CellStyle refHeaderStyle = refHeaderStyle(wb);

            // ===== Data sheet: header row + sample rows =====
            Row headerRow = data.createRow(0);
            for (int i = 0; i < headers.size(); i++) {
                Cell c = headerRow.createCell(i);
                c.setCellValue(headers.get(i));
                c.setCellStyle(headerStyle);
                data.setColumnWidth(i, columnWidthFor(headers.get(i)));
            }
            data.createFreezePane(0, 1);
            addSampleRows(data, headers, clients, accounts, sampleStyle);

            // ===== Reference sheet: build lookup ranges + name them =====
            Map<String, CellRangeInfo> namedRanges = writeReferenceSheet(
                    ref, refHeaderStyle, clients, accounts, clientWarehouseCodes, services, presets);
            for (Map.Entry<String, CellRangeInfo> entry : namedRanges.entrySet()) {
                Name n = wb.createName();
                n.setNameName(entry.getKey());
                n.setRefersToFormula(entry.getValue().toAbsoluteFormula(ref.getSheetName()));
            }

            // ===== Data validation on each column =====
            // clientCode → static list of client codes via _Clients name.
            applyListValidation(data, headers, "clientCode", "=_Clients", true);
            // billTo → static SENDER/RECIPIENT/THIRD_PARTY inline (short enough).
            applyExplicitListValidation(data, headers, "billTo", BILL_TO, true);
            // countryCode / countryOfOrigin → shared _Countries range.
            applyListValidation(data, headers, "countryCode", "=_Countries", true);
            applyListValidation(data, headers, "countryOfOrigin", "=_Countries", false);
            applyExplicitListValidation(data, headers, "weightUnit", WEIGHT_UNITS, true);
            applyExplicitListValidation(data, headers, "currency", CURRENCIES, true);

            // Cascading validations — each formula references the row's
            // client (col A of clientCode) and/or carrier (col A of
            // carrierCode) via INDIRECT. Client codes are SUBSTITUTE'd to
            // swap hyphens for underscores because named ranges parse "-"
            // as an operator.
            int clientCol = headers.indexOf("clientCode");
            int carrierCol = headers.indexOf("carrierCode");
            String clientRef = colLetter(clientCol) + "2"; // first data row cell
            String carrierRef = colLetter(carrierCol) + "2";
            String normalizedClient = "SUBSTITUTE(SUBSTITUTE(" + clientRef + ",\"-\",\"_\"),\".\",\"_\")";

            applyFormulaValidation(data, headers, "carrierCode",
                    "INDIRECT(\"_Carriers_\"&" + normalizedClient + ")", true);
            applyFormulaValidation(data, headers, "warehouseCode",
                    "INDIRECT(\"_Warehouses_\"&" + normalizedClient + ")", false);
            // accountNumber — WARN (info) not STOP so THIRD_PARTY free-text
            // works. Operator sees the client's account list as suggestions.
            applyFormulaValidation(data, headers, "accountNumber",
                    "INDIRECT(\"_Accounts_\"&" + normalizedClient + "&\"_\"&" + carrierRef + ")",
                    /*stop=*/ false);
            // Service + package cascade off carrier only (same for every client).
            applyFormulaValidation(data, headers, "serviceType",
                    "INDIRECT(\"_Services_\"&" + carrierRef + ")", true);
            applyFormulaValidation(data, headers, "packageType",
                    "INDIRECT(\"_Packages_\"&" + carrierRef + ")", true);

            // ===== Instructions sheet =====
            writeInstructionsSheet(notes, headerStyle);

            // Auto-size reference sheet + instructions for readability.
            for (int i = 0; i < 4; i++) notes.autoSizeColumn(i);
            // (Skip auto-size on the Reference sheet — it can have 40+ columns
            //  and auto-sizing all of them slows the download noticeably.)

            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to build order-import .xlsx template", e);
        }
    }

    // ===== Reference-sheet layout =====

    /** A single named range's rectangle on the Reference sheet. */
    private record CellRangeInfo(int firstCol, int firstRow, int lastRow) {
        String toAbsoluteFormula(String sheetName) {
            String col = colLetter(firstCol);
            return "'" + sheetName + "'!$" + col + "$" + (firstRow + 1)
                    + ":$" + col + "$" + (lastRow + 1);
        }
    }

    /**
     * Populate the Reference sheet with one column per named range, and
     * return the map of range-name → cell rectangle. Layout:
     * <pre>
     *   Col 0:  _Clients                    (all client codes)
     *   Col 1:  _Carriers_&lt;client-1&gt;
     *   Col 2:  _Warehouses_&lt;client-1&gt;
     *   Col 3+: _Accounts_&lt;client-1&gt;_&lt;carrier-A&gt;, _Accounts_&lt;client-1&gt;_&lt;carrier-B&gt;, ...
     *   ...    (repeat for each client)
     *   Then per carrier globally:
     *   Col X:  _Services_UPS
     *   Col X+1:_Packages_UPS
     *   ...
     * </pre>
     * Row 0 = a friendly header showing the range's name so operators
     * peeking at the Reference sheet can understand what's there.
     */
    private static Map<String, CellRangeInfo> writeReferenceSheet(
            XSSFSheet ref, CellStyle refHeaderStyle,
            List<Client> clients, List<CarrierAccountRef> accounts,
            Map<String, List<String>> clientWarehouseCodes,
            List<ShippingService> services, List<PackagePreset> presets) {

        Map<String, CellRangeInfo> out = new LinkedHashMap<>();
        int col = 0;

        // Col 0 — _Clients: sorted, distinct, active client codes.
        List<String> clientCodes = clients.stream()
                .map(Client::getClientCode)
                .filter(c -> c != null && !c.isBlank())
                .distinct()
                .sorted()
                .toList();
        col = writeRefColumn(ref, refHeaderStyle, col, "_Clients", clientCodes, out);

        // Per-client blocks.
        for (String clientCode : clientCodes) {
            String key = normalizeForName(clientCode);
            // Client's active + complete carrier codes (unique).
            Set<String> carriers = new LinkedHashSet<>();
            for (CarrierAccountRef a : accounts) {
                if (!Boolean.TRUE.equals(a.getActive()) || !a.isComplete()) continue;
                if (a.getCustomerNo() == null) continue;
                if (!a.getCustomerNo().equalsIgnoreCase(clientCode)) continue;
                if (a.getCarrierCode() != null) carriers.add(a.getCarrierCode().toUpperCase(Locale.ROOT));
            }
            col = writeRefColumn(ref, refHeaderStyle, col, "_Carriers_" + key, carriers, out);

            // Client's attached warehouse codes — caller resolved these
            // ClientWarehouse.warehouseId → Warehouse.code up-front so we
            // don't need a warehouse repo here.
            List<String> warehouseCodes = clientWarehouseCodes.getOrDefault(
                    clientCode.toUpperCase(Locale.ROOT), List.of());
            col = writeRefColumn(ref, refHeaderStyle, col, "_Warehouses_" + key, warehouseCodes, out);

            // Per-carrier account numbers for this client.
            for (String carrier : carriers) {
                List<String> accountNumbers = new ArrayList<>();
                for (CarrierAccountRef a : accounts) {
                    if (!Boolean.TRUE.equals(a.getActive()) || !a.isComplete()) continue;
                    if (a.getCustomerNo() == null
                            || !a.getCustomerNo().equalsIgnoreCase(clientCode)) continue;
                    if (a.getCarrierCode() == null
                            || !a.getCarrierCode().equalsIgnoreCase(carrier)) continue;
                    if (a.getAccountNumber() != null) accountNumbers.add(a.getAccountNumber());
                }
                col = writeRefColumn(ref, refHeaderStyle, col,
                        "_Accounts_" + key + "_" + carrier, accountNumbers, out);
            }
        }

        // Per-carrier globals — service + package NAMES (not wire codes).
        Set<String> carriers = new LinkedHashSet<>();
        for (ShippingService s : services) {
            if (s.isEnabled() && s.getCarrier() != null) carriers.add(s.getCarrier().toUpperCase(Locale.ROOT));
        }
        for (String carrier : carriers) {
            List<String> serviceNames = new ArrayList<>();
            for (ShippingService s : services) {
                if (!s.isEnabled()) continue;
                if (!carrier.equalsIgnoreCase(s.getCarrier())) continue;
                if (s.getName() != null && !s.getName().isBlank()) serviceNames.add(s.getName());
            }
            // Dedupe (names can repeat across origins).
            serviceNames = distinct(serviceNames);
            col = writeRefColumn(ref, refHeaderStyle, col, "_Services_" + carrier, serviceNames, out);

            List<String> packageNames = new ArrayList<>();
            for (PackagePreset p : presets) {
                if (!Boolean.TRUE.equals(p.getEnabled())) continue;
                // Preset carrier may be null (usable with any); include in every carrier's list.
                if (p.getCarrier() != null && !carrier.equalsIgnoreCase(p.getCarrier())) continue;
                if (p.getName() != null && !p.getName().isBlank()) packageNames.add(p.getName());
            }
            packageNames = distinct(packageNames);
            col = writeRefColumn(ref, refHeaderStyle, col, "_Packages_" + carrier, packageNames, out);
        }

        // Static _Countries and _Currencies too (so we can validate against a
        // named range even if the list grows past the inline cap in the
        // future).
        col = writeRefColumn(ref, refHeaderStyle, col, "_Countries", COUNTRIES, out);
        col = writeRefColumn(ref, refHeaderStyle, col, "_Currencies", CURRENCIES, out);

        return out;
    }

    /**
     * Write a single column on the Reference sheet — header row 0 = the
     * range name, then data rows 1..N. Registers the range in {@code out}
     * and returns the next free column index.
     *
     * <p>Empty lists still get a header cell + one blank data row so the
     * named range resolves (empty list = INDIRECT returns #REF! which
     * breaks the dropdown).
     */
    private static int writeRefColumn(XSSFSheet ref, CellStyle headerStyle,
                                       int col, String rangeName, Collection<String> values,
                                       Map<String, CellRangeInfo> out) {
        Row headerRow = ref.getRow(0);
        if (headerRow == null) headerRow = ref.createRow(0);
        Cell hc = headerRow.createCell(col);
        hc.setCellValue(rangeName);
        hc.setCellStyle(headerStyle);

        int rowIdx = 1;
        if (values.isEmpty()) {
            // Placeholder blank cell so the named range resolves to a
            // valid (empty) range — INDIRECT returns "" instead of #REF!
            // which keeps the dropdown from erroring out.
            Row r = ensureRow(ref, rowIdx);
            r.createCell(col).setCellValue("");
            out.put(rangeName, new CellRangeInfo(col, rowIdx, rowIdx));
        } else {
            int firstRow = rowIdx;
            for (String v : values) {
                Row r = ensureRow(ref, rowIdx);
                r.createCell(col).setCellValue(v);
                rowIdx++;
            }
            out.put(rangeName, new CellRangeInfo(col, firstRow, rowIdx - 1));
        }
        return col + 1;
    }

    // ===== Validation helpers =====

    /** Attach a dropdown backed by a named-range formula (e.g. {@code =_Clients}). */
    private static void applyListValidation(XSSFSheet sheet, List<String> headers,
                                             String col, String formula, boolean stop) {
        int idx = headers.indexOf(col);
        if (idx < 0) return;
        DataValidationHelper helper = new XSSFDataValidationHelper(sheet);
        DataValidationConstraint constraint = helper.createFormulaListConstraint(formula);
        CellRangeAddressList range = new CellRangeAddressList(1, DATA_ROW_LIMIT, idx, idx);
        DataValidation validation = helper.createValidation(constraint, range);
        validation.setSuppressDropDownArrow(false);
        validation.setShowErrorBox(stop);
        if (stop) {
            validation.setErrorStyle(DataValidation.ErrorStyle.STOP);
        }
        sheet.addValidationData(validation);
    }

    /** Attach a dropdown backed by an inline value list (short lists only —
     *  Excel caps the source string at ~255 chars). */
    private static void applyExplicitListValidation(XSSFSheet sheet, List<String> headers,
                                                     String col, List<String> values, boolean stop) {
        int idx = headers.indexOf(col);
        if (idx < 0 || values == null || values.isEmpty()) return;
        DataValidationHelper helper = new XSSFDataValidationHelper(sheet);
        DataValidationConstraint constraint = helper.createExplicitListConstraint(values.toArray(new String[0]));
        CellRangeAddressList range = new CellRangeAddressList(1, DATA_ROW_LIMIT, idx, idx);
        DataValidation validation = helper.createValidation(constraint, range);
        validation.setSuppressDropDownArrow(false);
        validation.setShowErrorBox(stop);
        if (stop) {
            validation.setErrorStyle(DataValidation.ErrorStyle.STOP);
        }
        sheet.addValidationData(validation);
    }

    /**
     * Attach a cascading dropdown driven by an INDIRECT() formula. The
     * formula is applied to every row in the validation range and Excel
     * auto-adjusts the cell references per row.
     *
     * <p>{@code stop=false} uses the INFO error style so operators can
     * type a value outside the list (needed for third-party accountNumber).
     */
    private static void applyFormulaValidation(XSSFSheet sheet, List<String> headers,
                                                String col, String formula, boolean stop) {
        int idx = headers.indexOf(col);
        if (idx < 0) return;
        DataValidationHelper helper = new XSSFDataValidationHelper(sheet);
        // POI's createFormulaListConstraint wraps the formula in the OOXML
        // <formula1> element automatically. Formulas here reference the
        // FIRST data row (e.g. B2); Excel adjusts subsequent rows.
        DataValidationConstraint constraint = helper.createFormulaListConstraint(formula);
        CellRangeAddressList range = new CellRangeAddressList(1, DATA_ROW_LIMIT, idx, idx);
        DataValidation validation = helper.createValidation(constraint, range);
        validation.setSuppressDropDownArrow(false);
        validation.setShowErrorBox(true);
        validation.setErrorStyle(stop
                ? DataValidation.ErrorStyle.STOP
                : DataValidation.ErrorStyle.INFO);
        sheet.addValidationData(validation);
    }

    // ===== Sample rows =====

    private static void addSampleRows(XSSFSheet data, List<String> headers,
                                       List<Client> clients, List<CarrierAccountRef> accounts,
                                       CellStyle sampleStyle) {
        // Pick a plausible sample client — the first client with at least
        // one active + complete account. Falls back to nothing when the
        // catalog is empty so we don't try to guess.
        String sampleClient = null;
        String sampleCarrier = null;
        String sampleAccount = null;
        for (Client cl : clients) {
            for (CarrierAccountRef a : accounts) {
                if (Boolean.TRUE.equals(a.getActive()) && a.isComplete()
                        && a.getCustomerNo() != null
                        && a.getCustomerNo().equalsIgnoreCase(cl.getClientCode())) {
                    sampleClient = cl.getClientCode();
                    sampleCarrier = a.getCarrierCode();
                    sampleAccount = a.getAccountNumber();
                    break;
                }
            }
            if (sampleClient != null) break;
        }

        Row r = data.createRow(1);
        setCell(r, headers, "clientCode", sampleClient == null ? "" : sampleClient, sampleStyle);
        setCell(r, headers, "billTo", "SENDER", sampleStyle);
        setCell(r, headers, "recipientName", "Ava Chen", sampleStyle);
        setCell(r, headers, "addressLine1", "42 Sample Way", sampleStyle);
        setCell(r, headers, "city", "Portland", sampleStyle);
        setCell(r, headers, "state", "OR", sampleStyle);
        setCell(r, headers, "postalCode", "97201", sampleStyle);
        setCell(r, headers, "countryCode", "US", sampleStyle);
        setCell(r, headers, "carrierCode", sampleCarrier == null ? "" : sampleCarrier, sampleStyle);
        setCell(r, headers, "accountNumber", sampleAccount == null ? "" : sampleAccount, sampleStyle);
        setCellNumber(r, headers, "weight", 2.5, sampleStyle);
        setCell(r, headers, "weightUnit", "LB", sampleStyle);
        setCell(r, headers, "currency", "USD", sampleStyle);
        setCell(r, headers, "goodsDescription", "General merchandise", sampleStyle);
    }

    // ===== styling =====

    private static CellStyle headerStyle(XSSFWorkbook wb) {
        CellStyle s = wb.createCellStyle();
        Font f = wb.createFont();
        f.setBold(true);
        f.setColor(IndexedColors.WHITE.getIndex());
        s.setFont(f);
        s.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        s.setAlignment(HorizontalAlignment.CENTER);
        s.setVerticalAlignment(VerticalAlignment.CENTER);
        s.setBorderBottom(BorderStyle.THIN);
        return s;
    }

    private static CellStyle sampleStyle(XSSFWorkbook wb) {
        CellStyle s = wb.createCellStyle();
        s.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return s;
    }

    private static CellStyle refHeaderStyle(XSSFWorkbook wb) {
        CellStyle s = wb.createCellStyle();
        Font f = wb.createFont();
        f.setBold(true);
        f.setColor(IndexedColors.WHITE.getIndex());
        s.setFont(f);
        s.setFillForegroundColor(IndexedColors.GREY_50_PERCENT.getIndex());
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return s;
    }

    // ===== instructions sheet =====

    private static void writeInstructionsSheet(XSSFSheet notes, CellStyle headerStyle) {
        Row head = notes.createRow(0);
        Cell hc = head.createCell(0);
        hc.setCellValue("How to fill this template");
        hc.setCellStyle(headerStyle);
        notes.createRow(1).createCell(0).setCellValue(
                "1. Pick a Client from the clientCode dropdown. The carrierCode, accountNumber, warehouseCode dropdowns re-scope automatically.");
        notes.createRow(2).createCell(0).setCellValue(
                "2. Pick a Carrier from that client's carriers. The serviceType and packageType dropdowns re-scope.");
        notes.createRow(3).createCell(0).setCellValue(
                "3. accountNumber accepts either a value from the dropdown OR a free-text third-party account when billTo = THIRD_PARTY.");
        notes.createRow(4).createCell(0).setCellValue(
                "4. serviceType and packageType show human names (\"UPS Ground\", \"UPS Letter\"). The importer resolves them to wire codes at commit time.");
        notes.createRow(5).createCell(0).setCellValue(
                "5. For international shipments with multiple line-items, set the same orderRef on every row of the group. The first row carries recipient + shipment fields; later rows only need orderRef + item columns.");
        notes.createRow(6).createCell(0).setCellValue(
                "6. hsCode + countryOfOrigin are required for international shipments; leave blank for domestic.");
        notes.createRow(7).createCell(0).setCellValue(
                "7. Save as CSV (UTF-8) before uploading — File → Save As → CSV UTF-8. The importer also accepts this .xlsx directly.");
    }

    // ===== small helpers =====

    private static int columnWidthFor(String header) {
        return switch (header) {
            case "orderRef", "clientCode", "billTo", "warehouseCode",
                    "reference" -> 3200;
            case "recipientName", "recipientCompany", "recipientEmail",
                    "addressLine1", "addressLine2", "goodsDescription",
                    "itemDescription" -> 6000;
            case "serviceType", "packageType", "city" -> 5000;
            case "state", "postalCode", "carrierCode",
                    "accountNumber", "countryCode", "countryOfOrigin",
                    "recipientPhone", "weightUnit", "currency",
                    "hsCode", "itemSku" -> 3000;
            case "weight", "declaredValue", "itemQuantity",
                    "itemUnitValue" -> 3200;
            default -> 4000;
        };
    }

    private static void setCell(Row row, List<String> headers, String col, String value, CellStyle style) {
        int idx = headers.indexOf(col);
        if (idx < 0) return;
        Cell c = row.createCell(idx);
        c.setCellValue(value);
        if (style != null) c.setCellStyle(style);
    }

    private static void setCellNumber(Row row, List<String> headers, String col, double value, CellStyle style) {
        int idx = headers.indexOf(col);
        if (idx < 0) return;
        Cell c = row.createCell(idx);
        c.setCellValue(value);
        if (style != null) c.setCellStyle(style);
    }

    private static Row ensureRow(XSSFSheet sheet, int rowIdx) {
        Row r = sheet.getRow(rowIdx);
        if (r == null) r = sheet.createRow(rowIdx);
        return r;
    }

    /**
     * Normalize an identifier so it's a valid Excel named-range key.
     * Named-range keys allow letters, digits, and underscore; hyphens
     * (allowed in client codes) get treated as arithmetic operators, so
     * we swap them for underscores. Same for periods.
     */
    private static String normalizeForName(String s) {
        if (s == null) return "";
        return s.replaceAll("[^A-Za-z0-9_]", "_");
    }

    /** Excel column letter for a 0-based index (0→A, 25→Z, 26→AA, ...). */
    private static String colLetter(int idx) {
        return CellReference.convertNumToColString(idx);
    }

    private static List<String> distinct(List<String> in) {
        Set<String> seen = new LinkedHashSet<>(in);
        return new ArrayList<>(seen);
    }
}
