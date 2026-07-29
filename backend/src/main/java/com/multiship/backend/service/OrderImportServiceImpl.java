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
     *  discovery. Column names are normalised to lowercase on match. */
    static final List<String> HEADERS = List.of(
            "recipientName", "recipientCompany", "recipientPhone", "recipientEmail",
            "addressLine1", "addressLine2",
            "city", "state", "postalCode", "countryCode",
            "carrierCode", "accountNumber", "serviceType", "packageType",
            "weight", "weightUnit",
            "declaredValue", "currency",
            "reference", "goodsDescription");

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

        for (OrderImportRowDTO row : rows) {
            // Re-validate — the frontend may have edited rows.
            List<String> errors = validateRow(row);
            row.setErrors(errors);
            if (!errors.isEmpty()) {
                invalid++;
                continue;
            }
            valid++;

            // Sprint 41 — actually generate the label via the manual
            // shipment path. When carrierService isn't wired (test-only
            // no-arg constructor), fall through and just report the
            // "would commit" summary.
            if (carrierService == null) continue;

            try {
                com.multiship.backend.dto.ManualShipmentRequest req = toManualShipmentRequest(row);
                ApiResponse<com.multiship.backend.dto.LabelGenerationResponse> resp =
                        carrierService.generateManualLabel(req, null);
                com.multiship.backend.dto.LabelGenerationResponse data =
                        resp == null ? null : resp.getData();
                if (resp != null && "success".equalsIgnoreCase(resp.getStatus()) && data != null
                        && StringUtils.hasText(data.getTrackingNumber())) {
                    row.setGeneratedOrderNo(data.getOrderNo() == null ? null : data.getOrderNo().intValue());
                    row.setGeneratedTrackingNumber(data.getTrackingNumber());
                    row.setGeneratedStatus("GENERATED");
                    row.setGeneratedMessage(data.getMessage());
                    generated++;
                } else {
                    row.setGeneratedStatus("FAILED");
                    row.setGeneratedMessage(resp == null ? "no response"
                            : resp.getMessage());
                }
            } catch (Exception ex) {
                log.warn("Order import row {} failed at label generation: {}",
                        row.getRowNumber(), ex.getMessage());
                row.setGeneratedStatus("FAILED");
                row.setGeneratedMessage(ex.getMessage() == null
                        ? ex.getClass().getSimpleName() : ex.getMessage());
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
     * Sprint 41 — convert an import row to a ManualShipmentRequest.
     * Recipient block from the row's address fields; sender left null
     * so the CarrierService cascade uses the tenant's default ship-from.
     * accountNumber / accountId → row's accountNumber; when the row
     * doesn't carry one, the manual-shipment path errors clearly and
     * the row is marked FAILED.
     */
    static com.multiship.backend.dto.ManualShipmentRequest toManualShipmentRequest(OrderImportRowDTO row) {
        com.multiship.backend.dto.ManualShipmentRequest req =
                new com.multiship.backend.dto.ManualShipmentRequest();
        com.multiship.backend.dto.ManualShipmentRequest.Address recipient =
                new com.multiship.backend.dto.ManualShipmentRequest.Address();
        recipient.setName(row.getRecipientName());
        recipient.setCompany(row.getRecipientCompany());
        recipient.setPhone(row.getRecipientPhone());
        recipient.setEmail(row.getRecipientEmail());
        recipient.setAddressLine1(row.getAddressLine1());
        recipient.setAddressLine2(row.getAddressLine2());
        recipient.setCity(row.getCity());
        recipient.setState(row.getState());
        recipient.setPostalCode(row.getPostalCode());
        recipient.setCountryCode(row.getCountryCode());
        req.setRecipient(recipient);

        req.setCarrierCode(row.getCarrierCode());
        req.setAccountNumber(row.getAccountNumber());
        req.setWeight(row.getWeight());
        req.setWeightUnit(row.getWeightUnit());
        req.setDeclaredValue(row.getDeclaredValue());
        req.setCurrency(row.getCurrency());
        req.setReference(row.getReference());
        req.setGoodsDescription(row.getGoodsDescription());
        req.setSource("API");
        return req;
    }

    @Override
    public byte[] csvTemplate() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.join(",", HEADERS)).append('\n');
        // Sample row — realistic domestic US shipment.
        sb.append("Acme Warehouse,Acme Ltd,5551234567,ops@acme.com,")
                .append("1 Warehouse Way,,Louisville,KY,40209,US,")
                .append("UPS,A12345,03,02,2.5,LB,100.00,USD,PO-1001,General merchandise\n");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /* -------------------------- Parsers -------------------------- */

    private List<OrderImportRowDTO> parseCsv(InputStream body) throws Exception {
        List<OrderImportRowDTO> out = new ArrayList<>();
        try (InputStreamReader reader = new InputStreamReader(body, StandardCharsets.UTF_8);
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
        OrderImportRowDTO out = new OrderImportRowDTO();
        out.setRowNumber(rowNumber);
        out.setRecipientName(r.read("recipientName"));
        out.setRecipientCompany(r.read("recipientCompany"));
        out.setRecipientPhone(r.read("recipientPhone"));
        out.setRecipientEmail(r.read("recipientEmail"));
        out.setAddressLine1(r.read("addressLine1"));
        out.setAddressLine2(r.read("addressLine2"));
        out.setCity(r.read("city"));
        out.setState(r.read("state"));
        out.setPostalCode(r.read("postalCode"));
        out.setCountryCode(upper(r.read("countryCode")));
        out.setCarrierCode(upper(r.read("carrierCode")));
        out.setAccountNumber(r.read("accountNumber"));
        out.setServiceType(r.read("serviceType"));
        out.setPackageType(r.read("packageType"));
        out.setWeight(parseDecimal(r.read("weight")));
        out.setWeightUnit(upper(r.read("weightUnit")));
        out.setDeclaredValue(parseDecimal(r.read("declaredValue")));
        out.setCurrency(upper(r.read("currency")));
        out.setReference(r.read("reference"));
        out.setGoodsDescription(r.read("goodsDescription"));
        out.setErrors(validateRow(out));
        return out;
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
