package com.multiship.backend.service;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.OrderImportPreviewDTO;
import com.multiship.backend.dto.OrderImportRowDTO;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link OrderImportServiceImpl}. Builds in-memory CSV
 * strings + XLSX byte arrays so no test data files are required.
 */
class OrderImportServiceImplTest {

    private OrderImportServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new OrderImportServiceImpl();
    }

    /* -------------------------- CSV template -------------------------- */

    @Test
    void csvTemplateStartsWithCanonicalHeaders() {
        String template = new String(service.csvTemplate(), StandardCharsets.UTF_8);
        assertTrue(template.startsWith("recipientName,recipientCompany,"),
                "Template should start with the canonical header");
        assertTrue(template.contains("weight"));
        assertTrue(template.contains("countryCode"));
        // At least one sample row after the header line.
        assertTrue(template.split("\n").length >= 2);
    }

    /* -------------------------- Format detection -------------------------- */

    @Test
    void previewRejectsUnsupportedExtension() {
        ApiResponse<OrderImportPreviewDTO> resp = service.preview("orders.txt.bak",
                new ByteArrayInputStream(new byte[0]));
        assertEquals("error", resp.getStatus());
        assertEquals(415, resp.getCode());
    }

    @Test
    void previewAcceptsCsvExtension() {
        String csv = "recipientName,addressLine1,city,postalCode,countryCode,weight\n"
                + "Jane,42 Broadway,NYC,10001,US,2.5\n";
        ApiResponse<OrderImportPreviewDTO> resp = service.preview("orders.csv",
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
        assertEquals("success", resp.getStatus());
        assertEquals(1, resp.getData().getTotalRows());
    }

    /* -------------------------- CSV parsing -------------------------- */

    @Test
    void csvParserPopulatesRequiredFields() {
        String csv = "recipientName,recipientPhone,addressLine1,city,state,postalCode,countryCode,weight,weightUnit,carrierCode\n"
                + "Jane Doe,5559876543,42 Broadway,New York,NY,10001,US,2.5,LB,UPS\n";
        OrderImportPreviewDTO data = service.preview("test.csv",
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8))).getData();
        assertEquals(1, data.getTotalRows());
        assertEquals(1, data.getValidRows());
        assertEquals(0, data.getInvalidRows());

        OrderImportRowDTO row = data.getRows().get(0);
        assertEquals("Jane Doe", row.getRecipientName());
        assertEquals("42 Broadway", row.getAddressLine1());
        assertEquals("New York", row.getCity());
        assertEquals("US", row.getCountryCode(), "countryCode uppercased");
        assertEquals("UPS", row.getCarrierCode());
        assertEquals(0, new BigDecimal("2.5").compareTo(row.getWeight()));
        assertEquals("LB", row.getWeightUnit());
        assertTrue(row.getErrors().isEmpty());
    }

    @Test
    void csvParserPreservesHeaderCaseInsensitive() {
        String csv = "RecipientName,ADDRESSLINE1,City,POSTALCODE,countrycode,Weight\n"
                + "Jane,42 Broadway,NYC,10001,US,2\n";
        OrderImportPreviewDTO data = service.preview("test.csv",
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8))).getData();
        assertEquals("Jane", data.getRows().get(0).getRecipientName());
        assertEquals("42 Broadway", data.getRows().get(0).getAddressLine1());
        assertEquals(0, data.getRows().get(0).getErrors().size());
    }

    @Test
    void csvParserSkipsEmptyLines() {
        String csv = "recipientName,addressLine1,city,postalCode,countryCode,weight\n"
                + "Jane,42 Broadway,NYC,10001,US,2\n"
                + "\n"
                + "John,10 Downing St,London,SW1A 2AA,GB,3\n";
        OrderImportPreviewDTO data = service.preview("test.csv",
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8))).getData();
        assertEquals(2, data.getTotalRows(), "Empty line should be silently skipped");
    }

    @Test
    void csvParserFlagsMissingRequiredFields() {
        String csv = "recipientName,addressLine1,city,postalCode,countryCode,weight\n"
                + ",,,,,\n"          // Every required field blank
                + "Jane,42 Broadway,,10001,US,2\n";  // Missing city
        OrderImportPreviewDTO data = service.preview("test.csv",
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8))).getData();
        // Row 1 = empty line → skipped by isBlank. Only row 2 (Jane) counted.
        assertEquals(1, data.getTotalRows());
        assertEquals(0, data.getValidRows());
        assertEquals(1, data.getInvalidRows());
        assertTrue(data.getRows().get(0).getErrors().stream()
                .anyMatch(e -> e.contains("city")));
    }

    @Test
    void csvParserFlagsInvalidWeight() {
        String csv = "recipientName,addressLine1,city,postalCode,countryCode,weight\n"
                + "Jane,42 Broadway,NYC,10001,US,not-a-number\n"
                + "John,10 High St,Boston,02101,US,-1\n"
                + "Alice,5 Way,Chicago,60601,US,0\n";
        OrderImportPreviewDTO data = service.preview("test.csv",
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8))).getData();
        assertEquals(0, data.getValidRows());
        assertEquals(3, data.getInvalidRows());
        for (OrderImportRowDTO row : data.getRows()) {
            assertTrue(row.getErrors().stream().anyMatch(e -> e.contains("weight")));
        }
    }

    @Test
    void csvParserToleratesCommasInDecimals() {
        String csv = "recipientName,addressLine1,city,postalCode,countryCode,weight,declaredValue\n"
                + "Jane,42 Broadway,NYC,10001,US,2.5,\"1,000.00\"\n";
        OrderImportPreviewDTO data = service.preview("test.csv",
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8))).getData();
        assertEquals(1, data.getValidRows());
        assertEquals(0, new BigDecimal("1000.00").compareTo(data.getRows().get(0).getDeclaredValue()));
    }

    /* -------------------------- XLSX parsing -------------------------- */

    private byte[] buildXlsx(List<List<String>> rows) throws Exception {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("orders");
            for (int i = 0; i < rows.size(); i++) {
                Row row = sheet.createRow(i);
                for (int c = 0; c < rows.get(i).size(); c++) {
                    Cell cell = row.createCell(c);
                    String val = rows.get(i).get(c);
                    if (val != null && val.matches("-?\\d+(\\.\\d+)?")) {
                        try { cell.setCellValue(Double.parseDouble(val)); }
                        catch (NumberFormatException ignored) { cell.setCellValue(val); }
                    } else {
                        cell.setCellValue(val == null ? "" : val);
                    }
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        }
    }

    @Test
    void xlsxParserPopulatesRequiredFields() throws Exception {
        byte[] xlsx = buildXlsx(List.of(
                List.of("recipientName", "addressLine1", "city", "postalCode",
                        "countryCode", "weight", "carrierCode"),
                List.of("Jane Doe", "42 Broadway", "New York", "10001", "US",
                        "2.5", "UPS")));

        OrderImportPreviewDTO data = service.preview("orders.xlsx",
                new ByteArrayInputStream(xlsx)).getData();
        assertEquals(1, data.getTotalRows());
        assertEquals(1, data.getValidRows());
        OrderImportRowDTO row = data.getRows().get(0);
        assertEquals("Jane Doe", row.getRecipientName());
        assertEquals("UPS", row.getCarrierCode());
        assertNotNull(row.getWeight());
        assertTrue(row.getWeight().signum() > 0);
    }

    @Test
    void xlsxParserSkipsBlankRows() throws Exception {
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("recipientName", "addressLine1", "city", "postalCode",
                "countryCode", "weight"));
        rows.add(List.of("Jane", "42 Broadway", "NYC", "10001", "US", "2"));
        rows.add(List.of("", "", "", "", "", ""));
        rows.add(List.of("John", "10 High", "Boston", "02101", "US", "3"));
        byte[] xlsx = buildXlsx(rows);
        OrderImportPreviewDTO data = service.preview("orders.xlsx",
                new ByteArrayInputStream(xlsx)).getData();
        assertEquals(2, data.getTotalRows());
    }

    @Test
    void xlsxParserFlagsMissingWeight() throws Exception {
        byte[] xlsx = buildXlsx(List.of(
                List.of("recipientName", "addressLine1", "city", "postalCode", "countryCode", "weight"),
                List.of("Jane", "42 Broadway", "NYC", "10001", "US", "")));
        OrderImportPreviewDTO data = service.preview("orders.xlsx",
                new ByteArrayInputStream(xlsx)).getData();
        assertEquals(0, data.getValidRows());
        assertTrue(data.getRows().get(0).getErrors().stream()
                .anyMatch(e -> e.contains("weight")));
    }

    /* -------------------------- Commit -------------------------- */

    @Test
    void commitRejectsEmptyList() {
        ApiResponse<OrderImportPreviewDTO> resp = service.commit(List.of(), "alice");
        assertEquals("error", resp.getStatus());
        assertEquals(400, resp.getCode());
    }

    @Test
    void commitRejectsNull() {
        ApiResponse<OrderImportPreviewDTO> resp = service.commit(null, "alice");
        assertEquals(400, resp.getCode());
    }

    @Test
    void commitCountsValidAndInvalidRows() {
        OrderImportRowDTO good = OrderImportRowDTO.builder()
                .rowNumber(1).recipientName("Jane").addressLine1("42 Broadway")
                .city("NYC").postalCode("10001").countryCode("US")
                .weight(new BigDecimal("2")).build();
        OrderImportRowDTO bad = OrderImportRowDTO.builder()
                .rowNumber(2).recipientName("John").build();  // Missing everything else

        ApiResponse<OrderImportPreviewDTO> resp = service.commit(List.of(good, bad), "alice");
        assertEquals("success", resp.getStatus());
        assertEquals(2, resp.getData().getTotalRows());
        assertEquals(1, resp.getData().getValidRows());
        assertEquals(1, resp.getData().getInvalidRows());
    }

    @Test
    void commitRevalidatesRowsIgnoringClientErrorField() {
        // Client sent a row claiming it's valid (errors=[]) but a required
        // field is actually blank — server re-validates and rejects.
        OrderImportRowDTO liesAboutValidity = OrderImportRowDTO.builder()
                .rowNumber(1).recipientName("Jane")
                .addressLine1(null)  // ← Missing
                .city("NYC").postalCode("10001").countryCode("US")
                .weight(new BigDecimal("2"))
                .errors(List.of())  // ← Client claims no errors
                .build();

        ApiResponse<OrderImportPreviewDTO> resp = service.commit(
                List.of(liesAboutValidity), "alice");
        assertEquals(1, resp.getData().getInvalidRows(),
                "Server must re-validate; client-claimed emptiness isn't trusted");
    }

    /* -------------------------- validateRow -------------------------- */

    @Test
    void validateRowReturnsEmptyForGoodRow() {
        OrderImportRowDTO row = OrderImportRowDTO.builder()
                .recipientName("Jane").addressLine1("42 Broadway")
                .city("NYC").postalCode("10001").countryCode("US")
                .weight(new BigDecimal("2")).build();
        assertTrue(OrderImportServiceImpl.validateRow(row).isEmpty());
    }

    @Test
    void validateRowFlagsAllRequiredFieldsIndividually() {
        OrderImportRowDTO row = OrderImportRowDTO.builder().build();
        List<String> errors = OrderImportServiceImpl.validateRow(row);
        assertEquals(6, errors.size(),
                "6 required fields = 6 error lines: name, address, city, postal, country, weight");
    }
}
