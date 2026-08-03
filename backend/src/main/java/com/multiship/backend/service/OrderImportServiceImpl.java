package com.multiship.backend.service;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.OrderImportPreviewDTO;
import com.multiship.backend.dto.OrderImportRowDTO;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Sprint 40 impl. Format detection by filename extension:
 * {@code .csv} → Apache Commons CSV; {@code .xlsx} → Apache POI XSSF.
 * Both formats normalise into the same {@link OrderImportRowDTO} shape.
 *
 * <p>Schema (columns in order — case-insensitive header row required):
 * <ul>
 *   <li>recipientName (required)</li>
 *   <li>recipientCompany, recipientPhone, recipientEmail</li>
 *   <li>addressLine1 (required), addressLine2</li>
 *   <li>city (required), state, postalCode (required), countryCode (required)</li>
 *   <li>carrierCode, serviceType, packageType</li>
 *   <li>weight (required, numeric > 0), weightUnit</li>
 *   <li>declaredValue, currency, reference, goodsDescription</li>
 * </ul>
 *
 * <p>Missing required fields, weight ≤ 0 or unparseable, or
 * declaredValue unparseable each produce a row-level error message.
 * The commit endpoint refuses to persist rows with errors.
 */
@Slf4j
@Service
public class OrderImportServiceImpl implements OrderImportService {

    private final CarrierService carrierService;

    // Constructor with @Autowired handles the CarrierService injection.
    // Second no-arg constructor kept for the Sprint 40 test suite that
    // exercises parsing / validation in isolation.
    @org.springframework.beans.factory.annotation.Autowired
    public OrderImportServiceImpl(CarrierService carrierService) {
        this.carrierService = carrierService;
    }

    public OrderImportServiceImpl() {
        this.carrierService = null;
    }

    /** Canonical header ordering used for the template + parser column
     *  discovery. Column names are normalised to lowercase on match.
     *
     *  <p>Sprint 48 adds:
     *  <ul>
     *    <li>{@code orderRef} — order-group key. Rows sharing a non-blank
     *        orderRef fold into a single shipment; the first row supplies
     *        recipient / carrier / service, subsequent rows carry additional
     *        customs line-items.</li>
     *    <li>{@code itemDescription}, {@code itemSku}, {@code itemQuantity},
     *        {@code itemUnitValue}, {@code hsCode}, {@code countryOfOrigin}
     *        — per-line-item customs data. Optional; blank rows just skip
     *        the customs commodity block (domestic-only shipments).</li>
     *  </ul>
     */
    static final List<String> HEADERS = List.of(
            "orderRef",
            "recipientName", "recipientCompany", "recipientPhone", "recipientEmail",
            "addressLine1", "addressLine2",
            "city", "state", "postalCode", "countryCode",
            "carrierCode", "accountNumber", "serviceType", "packageType",
            "weight", "weightUnit",
            "declaredValue", "currency",
            "reference", "goodsDescription",
            "itemDescription", "itemSku", "itemQuantity", "itemUnitValue",
            "hsCode", "countryOfOrigin");

    /** Column names required for a valid row. */
    static final List<String> REQUIRED_COLUMNS = List.of(
            "recipientName", "addressLine1", "city", "postalCode",
            "countryCode", "weight");

    @Override
    public ApiResponse<OrderImportPreviewDTO> preview(String filename, InputStream body) {
        String ext = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        try {
            List<OrderImportRowDTO> rows;
            if (ext.endsWith(".xlsx")) {
                rows = parseXlsx(body);
            } else if (ext.endsWith(".csv") || ext.endsWith(".txt")) {
                rows = parseCsv(body);
            } else {
                return failure(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                        "Only .csv, .txt, and .xlsx files are supported.");
            }
            return success(buildPreview(rows), rows.size() + " row(s) parsed.");
        } catch (Exception ex) {
            log.warn("Order import parse failed for {}: {}", filename, ex.getMessage());
            return failure(HttpStatus.BAD_REQUEST,
                    "Failed to parse " + filename + ": " + ex.getMessage());
        }
    }

    @Override
    public ApiResponse<OrderImportPreviewDTO> commit(List<OrderImportRowDTO> rows, String requestedBy) {
        if (rows == null || rows.isEmpty()) {
            return failure(HttpStatus.BAD_REQUEST, "No rows to commit.");
        }

        int valid = 0;
        int invalid = 0;
        int generated = 0;

        // Sprint 48 — group rows by orderRef so multi-row orders (one
        // shipment, N line-items) fold into a single label call. Rows
        // WITHOUT orderRef stay standalone (pre-Sprint-48 behaviour).
        // Ordering is preserved so preview and commit rows line up 1:1.
        // The first row of each group is the "leader" — its recipient +
        // shipment fields drive the request; subsequent rows contribute
        // only customs line-items.
        Map<String, List<OrderImportRowDTO>> groups = new LinkedHashMap<>();
        for (OrderImportRowDTO row : rows) {
            String key = StringUtils.hasText(row.getOrderRef())
                    ? row.getOrderRef().trim()
                    : "__row_" + row.getRowNumber(); // unique standalone key
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }

        for (Map.Entry<String, List<OrderImportRowDTO>> entry : groups.entrySet()) {
            List<OrderImportRowDTO> group = entry.getValue();
            OrderImportRowDTO leader = group.get(0);
            // Re-validate the leader (has recipient + shipment fields);
            // item-only rows don't need those, so skip their re-validate.
            List<String> errors = validateRow(leader);
            leader.setErrors(errors);
            if (!errors.isEmpty()) {
                // Whole group is invalid; count each row so the summary
                // stays accurate.
                invalid += group.size();
                for (int i = 1; i < group.size(); i++) {
                    group.get(i).setErrors(List.of("orderRef leader failed validation"));
                }
                continue;
            }
            valid += group.size();
            if (carrierService == null) continue;

            try {
                com.multiship.backend.dto.ManualShipmentRequest req = toManualShipmentRequest(group);
                ApiResponse<com.multiship.backend.dto.LabelGenerationResponse> resp =
                        carrierService.generateManualLabel(req, null);
                com.multiship.backend.dto.LabelGenerationResponse data =
                        resp == null ? null : resp.getData();
                if (resp != null && "success".equalsIgnoreCase(resp.getStatus()) && data != null
                        && StringUtils.hasText(data.getTrackingNumber())) {
                    // Stamp the whole group so operators see the label
                    // outcome on every line-item row, not just the leader.
                    Integer orderNo = data.getOrderNo() == null ? null : data.getOrderNo().intValue();
                    for (OrderImportRowDTO gr : group) {
                        gr.setGeneratedOrderNo(orderNo);
                        gr.setGeneratedTrackingNumber(data.getTrackingNumber());
                        gr.setGeneratedStatus("GENERATED");
                        gr.setGeneratedMessage(data.getMessage());
                    }
                    generated++;
                } else {
                    String msg = resp == null ? "no response" : resp.getMessage();
                    for (OrderImportRowDTO gr : group) {
                        gr.setGeneratedStatus("FAILED");
                        gr.setGeneratedMessage(msg);
                    }
                }
            } catch (Exception ex) {
                log.warn("Order import group (leader row {}) failed at label generation: {}",
                        leader.getRowNumber(), ex.getMessage());
                String msg = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                for (OrderImportRowDTO gr : group) {
                    gr.setGeneratedStatus("FAILED");
                    gr.setGeneratedMessage(msg);
                }
            }
        }

        log.info("Order import commit ({}): {} valid, {} invalid, {} labels generated",
                requestedBy, valid, invalid, generated);
        return success(OrderImportPreviewDTO.builder()
                .totalRows(rows.size())
                .validRows(valid)
                .invalidRows(invalid)
                .rows(rows)
                .build(),
                generated > 0
                        ? generated + " label(s) generated"
                                + (invalid > 0 ? " · " + invalid + " row(s) skipped" : "")
                        : (invalid > 0
                                ? invalid + " row(s) skipped — none committed"
                                : "0 label(s) generated"));
    }

    /**
     * Sprint 41 — convert a single import row to a ManualShipmentRequest.
     * Kept for tests / legacy call paths; the commit loop uses the
     * {@link #toManualShipmentRequest(List)} group overload so multi-row
     * orders fold into one shipment with a customs items[] array.
     */
    static com.multiship.backend.dto.ManualShipmentRequest toManualShipmentRequest(OrderImportRowDTO row) {
        return toManualShipmentRequest(List.of(row));
    }

    /**
     * Sprint 48 — convert an orderRef group (leader + item rows) into one
     * ManualShipmentRequest. Recipient / carrier / weight / service come
     * from the group's leader (row 0). Every row in the group contributes
     * a customs {@code Item} when it carries any item-level data
     * (description, HS code, SKU, quantity, or unit value).
     *
     * <p>Domestic-only groups (no item-level data on any row) send the
     * legacy single-goods block — no customs items array — so we don't
     * force customs data on shipments that don't need it.
     */
    static com.multiship.backend.dto.ManualShipmentRequest toManualShipmentRequest(List<OrderImportRowDTO> group) {
        OrderImportRowDTO leader = group.get(0);
        com.multiship.backend.dto.ManualShipmentRequest req =
                new com.multiship.backend.dto.ManualShipmentRequest();
        com.multiship.backend.dto.ManualShipmentRequest.Address recipient =
                new com.multiship.backend.dto.ManualShipmentRequest.Address();
        recipient.setName(leader.getRecipientName());
        recipient.setCompany(leader.getRecipientCompany());
        recipient.setPhone(leader.getRecipientPhone());
        recipient.setEmail(leader.getRecipientEmail());
        recipient.setAddressLine1(leader.getAddressLine1());
        recipient.setAddressLine2(leader.getAddressLine2());
        recipient.setCity(leader.getCity());
        recipient.setState(leader.getState());
        recipient.setPostalCode(leader.getPostalCode());
        recipient.setCountryCode(leader.getCountryCode());
        req.setRecipient(recipient);

        req.setCarrierCode(leader.getCarrierCode());
        req.setAccountNumber(leader.getAccountNumber());
        req.setWeight(leader.getWeight());
        req.setWeightUnit(leader.getWeightUnit());
        req.setDeclaredValue(leader.getDeclaredValue());
        req.setCurrency(leader.getCurrency());
        req.setReference(leader.getReference());
        req.setGoodsDescription(leader.getGoodsDescription());
        req.setSource("API");

        // Customs items: any row (leader OR item rows) that carries
        // item-level data becomes one Item on the invoice. Skip rows
        // that are purely shipment-level (domestic-only leader with no
        // customs data).
        List<com.multiship.backend.dto.ManualShipmentRequest.Item> items = new ArrayList<>();
        for (OrderImportRowDTO row : group) {
            if (!rowHasItemData(row)) continue;
            com.multiship.backend.dto.ManualShipmentRequest.Item it =
                    new com.multiship.backend.dto.ManualShipmentRequest.Item();
            it.setDescription(row.getItemDescription() != null
                    ? row.getItemDescription()
                    : row.getGoodsDescription());
            it.setHsCode(row.getHsCode());
            it.setCountryOfOrigin(row.getCountryOfOrigin());
            it.setQuantity(row.getItemQuantity() != null ? row.getItemQuantity() : 1);
            it.setUnitValue(row.getItemUnitValue());
            it.setSku(row.getItemSku());
            items.add(it);
        }
        if (!items.isEmpty()) req.setItems(items);
        return req;
    }

    /** True when the row carries any per-item data. Blank-rows shouldn't
     *  become empty customs entries. */
    private static boolean rowHasItemData(OrderImportRowDTO row) {
        return StringUtils.hasText(row.getItemDescription())
                || StringUtils.hasText(row.getItemSku())
                || StringUtils.hasText(row.getHsCode())
                || StringUtils.hasText(row.getCountryOfOrigin())
                || row.getItemQuantity() != null
                || row.getItemUnitValue() != null;
    }

    @Override
    public byte[] csvTemplate() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.join(",", HEADERS)).append('\n');
        // Sample 1 — domestic US shipment, no customs, single row (no orderRef).
        // Uses empty orderRef so the row commits standalone (pre-Sprint-48 behaviour).
        sb.append(",Acme Warehouse,Acme Ltd,5551234567,ops@acme.com,")
                .append("1 Warehouse Way,,Louisville,KY,40209,US,")
                .append("UPS,A12345,03,02,2.5,LB,100.00,USD,PO-1001,General merchandise,")
                // itemDescription / itemSku / itemQuantity / itemUnitValue / hsCode / countryOfOrigin
                .append(",,,,,\n");
        // Sample 2 — international UK shipment, 2 line-items, grouped by orderRef "ORD-2001".
        // Row A carries recipient + shipment + item 1; row B carries only orderRef + item 2.
        sb.append("ORD-2001,Ava Chen,,4402071234567,ava.chen@example.co.uk,")
                .append("221B Baker Street,,London,LDN,NW1 6XE,GB,")
                .append("FEDEX,F98765,INTERNATIONAL_PRIORITY,YOUR_PACKAGING,3.2,LB,275.00,USD,ORD-2001,Silk garments + accessories,")
                .append("Silk lining natural,SKU-100,2,45.00,5007.20,IT\n");
        sb.append("ORD-2001,,,,,,,,,,,,,,,,,,,")
                .append("Cotton canvas cream,SKU-200,1,60.00,5209.11,IN\n");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /* -------------------------- Parsers -------------------------- */

    private List<OrderImportRowDTO> parseCsv(InputStream body) throws Exception {
        List<OrderImportRowDTO> out = new ArrayList<>();
        // Wrap in a PushbackInputStream so we can peek + swallow a UTF-8
        // BOM (0xEF 0xBB 0xBF) — Excel writes one when Save As CSV, and
        // without stripping it the first header column reads as "﻿orderRef"
        // instead of "orderRef" and every value on that column comes back null.
        java.io.PushbackInputStream pb = new java.io.PushbackInputStream(body, 3);
        int b1 = pb.read();
        if (b1 != -1) {
            int b2 = pb.read();
            int b3 = pb.read();
            if (b1 != 0xEF || b2 != 0xBB || b3 != 0xBF) {
                if (b3 != -1) pb.unread(b3);
                if (b2 != -1) pb.unread(b2);
                pb.unread(b1);
            }
        }
        try (InputStreamReader reader = new InputStreamReader(pb, StandardCharsets.UTF_8);
             CSVParser parser = CSVFormat.DEFAULT.builder()
                     .setHeader().setSkipHeaderRecord(true)
                     .setIgnoreEmptyLines(true).setTrim(true)
                     .build().parse(reader)) {
            Map<String, Integer> headerMap = lowerCasedHeaderMap(parser.getHeaderMap());
            int rowNo = 0;
            for (CSVRecord rec : parser) {
                rowNo++;
                if (isBlank(rec)) continue;
                out.add(buildRow(rowNo, name -> get(rec, headerMap, name)));
            }
        }
        return out;
    }

    private List<OrderImportRowDTO> parseXlsx(InputStream body) throws Exception {
        List<OrderImportRowDTO> out = new ArrayList<>();
        DataFormatter fmt = new DataFormatter();
        try (Workbook workbook = new XSSFWorkbook(body)) {
            Sheet sheet = workbook.getSheetAt(0);
            if (sheet == null || sheet.getPhysicalNumberOfRows() == 0) return out;

            Row header = sheet.getRow(sheet.getFirstRowNum());
            Map<String, Integer> headerMap = new LinkedHashMap<>();
            if (header != null) {
                for (Cell cell : header) {
                    String label = fmt.formatCellValue(cell).trim();
                    if (StringUtils.hasText(label)) {
                        headerMap.put(label.toLowerCase(Locale.ROOT), cell.getColumnIndex());
                    }
                }
            }

            int rowNo = 0;
            for (int i = sheet.getFirstRowNum() + 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;
                rowNo++;
                boolean anyValue = false;
                for (Cell cell : row) {
                    if (StringUtils.hasText(fmt.formatCellValue(cell))) { anyValue = true; break; }
                }
                if (!anyValue) continue;

                Map<String, Integer> capturedHeader = headerMap;
                int finalI = i;
                out.add(buildRow(rowNo, name -> readCell(sheet, finalI, capturedHeader, name, fmt)));
            }
        }
        return out;
    }

    private static boolean isBlank(CSVRecord rec) {
        for (int i = 0; i < rec.size(); i++) {
            if (StringUtils.hasText(rec.get(i))) return false;
        }
        return true;
    }

    private static Map<String, Integer> lowerCasedHeaderMap(Map<String, Integer> raw) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (var e : raw.entrySet()) {
            if (e.getKey() != null) out.put(e.getKey().toLowerCase(Locale.ROOT).trim(), e.getValue());
        }
        return out;
    }

    private static String get(CSVRecord rec, Map<String, Integer> headerMap, String columnName) {
        Integer idx = headerMap.get(columnName.toLowerCase(Locale.ROOT));
        if (idx == null || idx >= rec.size()) return null;
        String value = rec.get(idx);
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static String readCell(Sheet sheet, int rowIndex, Map<String, Integer> headerMap,
                                    String columnName, DataFormatter fmt) {
        Integer idx = headerMap.get(columnName.toLowerCase(Locale.ROOT));
        if (idx == null) return null;
        Row row = sheet.getRow(rowIndex);
        if (row == null) return null;
        Cell cell = row.getCell(idx);
        if (cell == null) return null;
        String value = fmt.formatCellValue(cell).trim();
        return StringUtils.hasText(value) ? value : null;
    }

    /* -------------------------- Row build + validate -------------------------- */

    @FunctionalInterface
    private interface ColumnReader {
        String read(String columnName);
    }

    OrderImportRowDTO buildRow(int rowNumber, ColumnReader r) {
        // Wrap every read in sanitise() so downstream validators + carrier
        // connectors see clean strings — no control chars, no carrier-
        // forbidden characters. sanitise() returns null for whitespace-only
        // input, preserving today's "blank = null" semantics.
        ColumnReader s = name -> sanitise(r.read(name));
        OrderImportRowDTO out = new OrderImportRowDTO();
        out.setRowNumber(rowNumber);
        out.setOrderRef(s.read("orderRef"));
        out.setRecipientName(s.read("recipientName"));
        out.setRecipientCompany(s.read("recipientCompany"));
        out.setRecipientPhone(s.read("recipientPhone"));
        out.setRecipientEmail(s.read("recipientEmail"));
        out.setAddressLine1(s.read("addressLine1"));
        out.setAddressLine2(s.read("addressLine2"));
        out.setCity(s.read("city"));
        out.setState(s.read("state"));
        out.setPostalCode(s.read("postalCode"));
        out.setCountryCode(upper(s.read("countryCode")));
        out.setCarrierCode(upper(s.read("carrierCode")));
        out.setAccountNumber(s.read("accountNumber"));
        out.setServiceType(s.read("serviceType"));
        out.setPackageType(s.read("packageType"));
        out.setWeight(parseDecimal(s.read("weight")));
        out.setWeightUnit(upper(s.read("weightUnit")));
        out.setDeclaredValue(parseDecimal(s.read("declaredValue")));
        out.setCurrency(upper(s.read("currency")));
        out.setReference(s.read("reference"));
        out.setGoodsDescription(s.read("goodsDescription"));
        // Sprint 48 — per-item customs data.
        out.setItemDescription(s.read("itemDescription"));
        out.setItemSku(s.read("itemSku"));
        out.setItemQuantity(parseInt(s.read("itemQuantity")));
        out.setItemUnitValue(parseDecimal(s.read("itemUnitValue")));
        out.setHsCode(s.read("hsCode"));
        out.setCountryOfOrigin(upper(s.read("countryOfOrigin")));
        out.setErrors(validateRow(out));
        return out;
    }

    /**
     * Strip control characters (0x00–0x1F, 0x7F) and carrier-forbidden
     * chars ({@code < > \ | `}) from a user-supplied string. Returns null
     * when the result is blank so downstream code can treat "just noise"
     * the same as "not supplied at all".
     *
     * <p>Sprint 48 — needed because CSV uploads and Excel paste-in flows
     * routinely carry stray control characters (BOMs, non-breaking
     * spaces sneak in as 0x00 in some exports), and UPS + FedEx reject
     * name / address fields containing any of the punctuation set.
     */
    static String sanitise(String s) {
        if (s == null) return null;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c <= 0x1F || c == 0x7F) continue;
            if (c == '<' || c == '>' || c == '\\' || c == '|' || c == '`') continue;
            sb.append(c);
        }
        String out = sb.toString().trim();
        return out.isEmpty() ? null : out;
    }

    private static Integer parseInt(String s) {
        if (!StringUtils.hasText(s)) return null;
        try { return Integer.parseInt(s.trim().replace(",", "")); }
        catch (NumberFormatException ex) { return null; }
    }

    static List<String> validateRow(OrderImportRowDTO row) {
        List<String> errors = new ArrayList<>();
        if (!StringUtils.hasText(row.getRecipientName())) errors.add("recipientName is required");
        if (!StringUtils.hasText(row.getAddressLine1())) errors.add("addressLine1 is required");
        if (!StringUtils.hasText(row.getCity())) errors.add("city is required");
        if (!StringUtils.hasText(row.getPostalCode())) errors.add("postalCode is required");
        if (!StringUtils.hasText(row.getCountryCode())) errors.add("countryCode is required");
        if (row.getWeight() == null || row.getWeight().signum() <= 0) {
            errors.add("weight must be > 0");
        }
        return errors;
    }

    private static String upper(String s) {
        return s == null ? null : s.trim().toUpperCase(Locale.ROOT);
    }

    private static BigDecimal parseDecimal(String s) {
        if (!StringUtils.hasText(s)) return null;
        try { return new BigDecimal(s.trim().replace(",", "")); }
        catch (NumberFormatException ex) { return null; }
    }

    private static OrderImportPreviewDTO buildPreview(List<OrderImportRowDTO> rows) {
        int invalid = (int) rows.stream()
                .filter(r -> r.getErrors() != null && !r.getErrors().isEmpty())
                .count();
        return OrderImportPreviewDTO.builder()
                .totalRows(rows.size())
                .validRows(rows.size() - invalid)
                .invalidRows(invalid)
                .rows(rows)
                .build();
    }

    private static ApiResponse<OrderImportPreviewDTO> success(OrderImportPreviewDTO data, String message) {
        return ApiResponse.<OrderImportPreviewDTO>builder()
                .status("success").code(200).message(message).data(data).build();
    }

    private static ApiResponse<OrderImportPreviewDTO> failure(HttpStatus status, String message) {
        return ApiResponse.<OrderImportPreviewDTO>builder()
                .status("error").code(status.value())
                .errorCode(ErrorCode.VALIDATION_ERROR.name())
                .message(message).data(null).build();
    }

    /** Used only in tests to keep IntelliJ happy about unused imports. */
    @SuppressWarnings("unused")
    private static ByteArrayOutputStream noop() {
        return new ByteArrayOutputStream();
    }
}
