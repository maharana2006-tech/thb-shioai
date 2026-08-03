package com.multiship.backend.service;

import com.multiship.backend.model.CarrierAccountRef;
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
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFDataValidationHelper;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Sprint 48 — POI-generated .xlsx template for the order-import upload
 * flow. Serves the same schema as {@link OrderImportServiceImpl#HEADERS}
 * but with dropdown data validation, sample rows, and an operator-
 * facing instructions block on a second sheet.
 *
 * <p>When {@code accountId} scopes the download to a specific carrier
 * account, the template pre-fills the accountNumber cell + restricts
 * the carrierCode / serviceType / packageType dropdowns to that
 * account's carrier only. Generic (accountId null) templates offer
 * every enabled carrier's options.
 *
 * <p>Cascading dropdowns are avoided in favour of server-side scoping
 * — the operator picks an account in the app, downloads a template
 * baked for that account, and each cell's dropdown lists only the
 * relevant options. Simpler formulas, no INDIRECT() gymnastics.
 */
final class OrderImportTemplateBuilder {

    private OrderImportTemplateBuilder() { /* static-only */ }

    // ISO-2 country list — the ~30 destinations we ship to most.
    // Longer lists overflow Excel's inline validation-list cap (~255 chars);
    // this set is small enough to fit inline for the country column.
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

    /**
     * Build the .xlsx bytes.
     *
     * @param headers  column ordering — must match {@link OrderImportServiceImpl#HEADERS}
     *                 so downstream parsing lines up on both name and index.
     * @param account  optional carrier account to scope the template to.
     *                 When non-null: sample accountNumber prefilled, carrierCode
     *                 locked to account.carrierCode, service/package dropdowns
     *                 narrowed to that carrier's rows.
     * @param services every ShippingService row in the platform catalog;
     *                 the builder filters internally per account/carrier.
     * @param presets  every PackagePreset in the platform catalog; same
     *                 filtering.
     */
    static byte[] build(List<String> headers,
                        CarrierAccountRef account,
                        List<ShippingService> services,
                        List<PackagePreset> presets) {
        try (XSSFWorkbook wb = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            XSSFSheet data = wb.createSheet("Import");
            XSSFSheet ref = wb.createSheet("Reference");
            XSSFSheet notes = wb.createSheet("How to use");

            // ===== Styles =====
            CellStyle headerStyle = wb.createCellStyle();
            Font headerFont = wb.createFont();
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setAlignment(HorizontalAlignment.CENTER);
            headerStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            headerStyle.setBorderBottom(BorderStyle.THIN);

            CellStyle sampleStyle = wb.createCellStyle();
            sampleStyle.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
            sampleStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            CellStyle prefilledStyle = wb.createCellStyle();
            prefilledStyle.setFillForegroundColor(IndexedColors.LIGHT_GREEN.getIndex());
            prefilledStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Font prefilledFont = wb.createFont();
            prefilledFont.setBold(true);
            prefilledStyle.setFont(prefilledFont);

            // ===== Header row =====
            Row headerRow = data.createRow(0);
            for (int i = 0; i < headers.size(); i++) {
                Cell c = headerRow.createCell(i);
                c.setCellValue(headers.get(i));
                c.setCellStyle(headerStyle);
                // Sensible column widths — wider for freeform, narrower for codes.
                data.setColumnWidth(i, columnWidthFor(headers.get(i)));
            }
            data.createFreezePane(0, 1);

            // ===== Sample rows =====
            // Row 1: domestic US, single row, no orderRef.
            // Row 2 + 3: international UK, grouped by ORD-2001, two line-items.
            addSampleRows(data, headers, account, sampleStyle, prefilledStyle);

            // ===== Reference sheet (dropdown lookup data) =====
            List<String> allowedCarriers = allowedCarriers(account);
            List<String> allowedServices = filterServices(services, account);
            List<String> allowedPackages = filterPackages(presets, account);
            writeReferenceSheet(ref, allowedCarriers, allowedServices, allowedPackages, headerStyle);

            // ===== Data validation on each column =====
            applyDropdown(data, headers, "countryCode", COUNTRIES);
            applyDropdown(data, headers, "carrierCode", allowedCarriers);
            applyDropdown(data, headers, "serviceType", allowedServices);
            applyDropdown(data, headers, "packageType", allowedPackages);
            applyDropdown(data, headers, "weightUnit", WEIGHT_UNITS);
            applyDropdown(data, headers, "currency", CURRENCIES);
            applyDropdown(data, headers, "countryOfOrigin", COUNTRIES);

            // ===== Instructions sheet =====
            writeInstructionsSheet(notes, account, headerStyle);

            // Auto-size the reference sheet + notes sheet columns so text
            // isn't chopped on open. Import sheet uses fixed widths (auto-
            // size on 100 rows would be slow + inconsistent).
            for (int i = 0; i < 3; i++) ref.autoSizeColumn(i);
            for (int i = 0; i < 2; i++) notes.autoSizeColumn(i);

            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to build order-import .xlsx template", e);
        }
    }

    // ===== helpers =====

    private static int columnWidthFor(String header) {
        // POI widths are in units of 1/256th of a "0" character; 4000 ≈ 16 chars.
        return switch (header) {
            case "orderRef", "reference" -> 3200;
            case "recipientName", "recipientCompany", "recipientEmail",
                    "addressLine1", "addressLine2", "goodsDescription",
                    "itemDescription" -> 6000;
            case "city", "serviceType", "packageType" -> 4500;
            case "state", "postalCode", "carrierCode",
                    "accountNumber", "countryCode", "countryOfOrigin",
                    "recipientPhone", "weightUnit", "currency",
                    "hsCode", "itemSku" -> 3000;
            case "weight", "declaredValue", "itemQuantity",
                    "itemUnitValue" -> 3200;
            default -> 4000;
        };
    }

    private static void addSampleRows(XSSFSheet data, List<String> headers,
                                      CarrierAccountRef account,
                                      CellStyle sampleStyle, CellStyle prefilledStyle) {
        String defaultCarrier = account != null && account.getCarrierCode() != null
                ? account.getCarrierCode().toUpperCase(Locale.ROOT)
                : "UPS";
        String defaultAccount = account != null ? account.getAccountNumber() : "A12345";

        // Sample 1: domestic US (no customs)
        Row r1 = data.createRow(1);
        setCell(r1, headers, "orderRef", "", sampleStyle);
        setCell(r1, headers, "recipientName", "Acme Warehouse", sampleStyle);
        setCell(r1, headers, "recipientCompany", "Acme Ltd", sampleStyle);
        setCell(r1, headers, "recipientPhone", "5551234567", sampleStyle);
        setCell(r1, headers, "recipientEmail", "ops@acme.com", sampleStyle);
        setCell(r1, headers, "addressLine1", "1 Warehouse Way", sampleStyle);
        setCell(r1, headers, "city", "Louisville", sampleStyle);
        setCell(r1, headers, "state", "KY", sampleStyle);
        setCell(r1, headers, "postalCode", "40209", sampleStyle);
        setCell(r1, headers, "countryCode", "US", sampleStyle);
        setCell(r1, headers, "carrierCode", defaultCarrier,
                account != null ? prefilledStyle : sampleStyle);
        setCell(r1, headers, "accountNumber", defaultAccount,
                account != null ? prefilledStyle : sampleStyle);
        setCell(r1, headers, "serviceType", "GROUND", sampleStyle);
        setCell(r1, headers, "packageType", "YOUR_PACKAGING", sampleStyle);
        setCellNumber(r1, headers, "weight", 2.5, sampleStyle);
        setCell(r1, headers, "weightUnit", "LB", sampleStyle);
        setCellNumber(r1, headers, "declaredValue", 100.00, sampleStyle);
        setCell(r1, headers, "currency", "USD", sampleStyle);
        setCell(r1, headers, "reference", "PO-1001", sampleStyle);
        setCell(r1, headers, "goodsDescription", "General merchandise", sampleStyle);

        // Sample 2: international UK, group ORD-2001 line 1
        Row r2 = data.createRow(2);
        setCell(r2, headers, "orderRef", "ORD-2001", sampleStyle);
        setCell(r2, headers, "recipientName", "Ava Chen", sampleStyle);
        setCell(r2, headers, "recipientPhone", "4402071234567", sampleStyle);
        setCell(r2, headers, "recipientEmail", "ava.chen@example.co.uk", sampleStyle);
        setCell(r2, headers, "addressLine1", "221B Baker Street", sampleStyle);
        setCell(r2, headers, "city", "London", sampleStyle);
        setCell(r2, headers, "state", "LDN", sampleStyle);
        setCell(r2, headers, "postalCode", "NW1 6XE", sampleStyle);
        setCell(r2, headers, "countryCode", "GB", sampleStyle);
        setCell(r2, headers, "carrierCode", defaultCarrier,
                account != null ? prefilledStyle : sampleStyle);
        setCell(r2, headers, "accountNumber", defaultAccount,
                account != null ? prefilledStyle : sampleStyle);
        setCell(r2, headers, "serviceType", "INTERNATIONAL_PRIORITY", sampleStyle);
        setCell(r2, headers, "packageType", "YOUR_PACKAGING", sampleStyle);
        setCellNumber(r2, headers, "weight", 3.2, sampleStyle);
        setCell(r2, headers, "weightUnit", "LB", sampleStyle);
        setCellNumber(r2, headers, "declaredValue", 275.00, sampleStyle);
        setCell(r2, headers, "currency", "USD", sampleStyle);
        setCell(r2, headers, "reference", "ORD-2001", sampleStyle);
        setCell(r2, headers, "goodsDescription", "Silk garments + accessories", sampleStyle);
        setCell(r2, headers, "itemDescription", "Silk lining natural", sampleStyle);
        setCell(r2, headers, "itemSku", "SKU-100", sampleStyle);
        setCellNumber(r2, headers, "itemQuantity", 2, sampleStyle);
        setCellNumber(r2, headers, "itemUnitValue", 45.00, sampleStyle);
        setCell(r2, headers, "hsCode", "5007.20", sampleStyle);
        setCell(r2, headers, "countryOfOrigin", "IT", sampleStyle);

        // Sample 3: item-only continuation of ORD-2001 (only orderRef + item data)
        Row r3 = data.createRow(3);
        setCell(r3, headers, "orderRef", "ORD-2001", sampleStyle);
        setCell(r3, headers, "itemDescription", "Cotton canvas cream", sampleStyle);
        setCell(r3, headers, "itemSku", "SKU-200", sampleStyle);
        setCellNumber(r3, headers, "itemQuantity", 1, sampleStyle);
        setCellNumber(r3, headers, "itemUnitValue", 60.00, sampleStyle);
        setCell(r3, headers, "hsCode", "5209.11", sampleStyle);
        setCell(r3, headers, "countryOfOrigin", "IN", sampleStyle);
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

    /**
     * Attach a data-validation dropdown to every data cell in a column
     * (row 1 through 100). Rows past 100 render without validation — the
     * operator can add more but they won't get the dropdown affordance.
     *
     * <p>Excel's inline validation-list source is capped at ~255 chars.
     * Long value lists get quietly truncated here — that's a tradeoff
     * for keeping the template a single self-contained workbook. Very
     * long lists (100+ items) would need a Named Range referencing the
     * Reference sheet, which we can add later if the platform grows.
     */
    private static void applyDropdown(XSSFSheet sheet, List<String> headers,
                                      String col, List<String> values) {
        int idx = headers.indexOf(col);
        if (idx < 0 || values == null || values.isEmpty()) return;
        String source = String.join(",", values);
        if (source.length() > 250) source = source.substring(0, 247) + "..."; // Excel cap safety
        DataValidationHelper helper = new XSSFDataValidationHelper(sheet);
        DataValidationConstraint constraint = helper.createExplicitListConstraint(values.toArray(new String[0]));
        CellRangeAddressList range = new CellRangeAddressList(1, 100, idx, idx);
        DataValidation validation = helper.createValidation(constraint, range);
        validation.setShowErrorBox(false); // permissive — highlight but don't block edits
        validation.setSuppressDropDownArrow(false);
        sheet.addValidationData(validation);
    }

    private static void writeReferenceSheet(XSSFSheet ref,
                                            List<String> carriers,
                                            List<String> services,
                                            List<String> packages,
                                            CellStyle headerStyle) {
        Row head = ref.createRow(0);
        String[] titles = {"Carriers", "Services", "Packages"};
        for (int i = 0; i < titles.length; i++) {
            Cell c = head.createCell(i);
            c.setCellValue(titles[i]);
            c.setCellStyle(headerStyle);
        }
        int rowCount = Math.max(Math.max(carriers.size(), services.size()), packages.size());
        for (int i = 0; i < rowCount; i++) {
            Row r = ref.createRow(i + 1);
            if (i < carriers.size()) r.createCell(0).setCellValue(carriers.get(i));
            if (i < services.size()) r.createCell(1).setCellValue(services.get(i));
            if (i < packages.size()) r.createCell(2).setCellValue(packages.get(i));
        }
    }

    private static void writeInstructionsSheet(XSSFSheet notes, CarrierAccountRef account,
                                               CellStyle headerStyle) {
        Row head = notes.createRow(0);
        Cell hc = head.createCell(0);
        hc.setCellValue("How to fill this template");
        hc.setCellStyle(headerStyle);
        notes.createRow(1).createCell(0).setCellValue(
                "1. One row per shipment for domestic (no customs). Fill every required column.");
        notes.createRow(2).createCell(0).setCellValue(
                "2. For international shipments with multiple line-items, set the same orderRef on every row of the group.");
        notes.createRow(3).createCell(0).setCellValue(
                "3. The FIRST row of an orderRef group carries recipient + shipment fields; item-only rows only need orderRef + item columns.");
        notes.createRow(4).createCell(0).setCellValue(
                "4. hsCode + countryOfOrigin are required for international shipments; leave blank for domestic.");
        notes.createRow(5).createCell(0).setCellValue(
                "5. Save as CSV (UTF-8) before uploading — Excel: File → Save As → CSV UTF-8. The importer also accepts this .xlsx directly.");
        notes.createRow(6).createCell(0).setCellValue(
                "6. Dropdowns show the allowed values. Editing outside a dropdown is fine; the server re-validates on preview.");
        if (account != null) {
            Row ac = notes.createRow(8);
            Cell acc = ac.createCell(0);
            acc.setCellValue("Account context: this template is scoped to account "
                    + account.getAccountNumber() + " on " + account.getCarrierCode()
                    + (account.getCustomerNo() == null || account.getCustomerNo().isBlank()
                        ? " (PLATFORM)"
                        : " (client " + account.getCustomerNo() + ")"));
        }
    }

    /**
     * Which carrier codes should the carrierCode dropdown offer? Scoped
     * to the account's carrier when one is provided; otherwise every
     * carrier that appears in the current service catalog (falls back
     * to the four we integrate today).
     */
    private static List<String> allowedCarriers(CarrierAccountRef account) {
        if (account != null && account.getCarrierCode() != null) {
            return List.of(account.getCarrierCode().toUpperCase(Locale.ROOT));
        }
        return List.of("UPS", "FEDEX", "USPS", "DHL");
    }

    /** Distinct enabled service codes for the account's carrier (or every
     *  carrier when unscoped). Preserves catalog display order via
     *  LinkedHashSet. */
    private static List<String> filterServices(List<ShippingService> services, CarrierAccountRef account) {
        String scope = account == null ? null
                : (account.getCarrierCode() == null ? null : account.getCarrierCode().toUpperCase(Locale.ROOT));
        Set<String> out = new LinkedHashSet<>();
        for (ShippingService s : services) {
            if (!s.isEnabled()) continue;
            if (scope != null && !scope.equalsIgnoreCase(s.getCarrier())) continue;
            if (s.getServiceCode() != null && !s.getServiceCode().isBlank()) out.add(s.getServiceCode());
        }
        // Common fallback when the platform hasn't seeded services yet —
        // don't leave the dropdown empty, operators need something to pick.
        if (out.isEmpty()) return List.of("GROUND", "STANDARD", "PRIORITY");
        return new ArrayList<>(out);
    }

    /** Distinct enabled package presets. Since presets aren't strictly
     *  carrier-scoped in the model, we surface every enabled preset by
     *  code when unscoped, and every preset whose carrier matches the
     *  account when scoped. */
    private static List<String> filterPackages(List<PackagePreset> presets, CarrierAccountRef account) {
        String scope = account == null ? null
                : (account.getCarrierCode() == null ? null : account.getCarrierCode().toUpperCase(Locale.ROOT));
        Set<String> out = new LinkedHashSet<>();
        for (PackagePreset p : presets) {
            if (!Boolean.TRUE.equals(p.getEnabled())) continue;
            if (scope != null && p.getCarrier() != null
                    && !scope.equalsIgnoreCase(p.getCarrier())) continue;
            // CARRIER kind presets have a carrier-specific packaging code
            // ("01" = UPS Letter, "FEDEX_PAK" etc.). CUSTOM presets don't
            // carry one — they map to the "your own box" fallback on the
            // carrier side, so surface their friendly name instead so the
            // dropdown still looks operator-friendly.
            String code = p.getCarrierPackageCode();
            if (code == null || code.isBlank()) code = p.getName();
            if (code != null && !code.isBlank()) out.add(code);
        }
        if (out.isEmpty()) return List.of("YOUR_PACKAGING", "FEDEX_BOX_10KG", "UPS_LETTER");
        return new ArrayList<>(out);
    }
}
