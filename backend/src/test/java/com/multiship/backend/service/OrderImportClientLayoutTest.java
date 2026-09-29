package com.multiship.backend.service;

import com.multiship.backend.dto.OrderImportPreviewDTO;
import com.multiship.backend.dto.OrderImportRowDTO;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A file in the client layout (POB250's WSH2609F.csv) imports like one in ours. */
class OrderImportClientLayoutTest {

    private static final String FILE = String.join("\n",
            "CLIENT_ID,ATTENTION,COMPANY_NAME,PHONE,EMAIL,ADDRESS1,ADDRESS2,CITY,STATE_CODE,ZIP,COUNTRY_CODE,WEIGHT,ONE_RATE,SHIPVIA_CD,THIRD_PARTY_ACC,GROUP_ID,ITEM_NUMBERS,QUANTITY,SHIP_DATE,HTSCode,THP_ADDRESS,THP_CITY,THP_STATE,THP_POSTAL,THP_COUNTRY",
            "POB250,3,WS Columbus Circle,6167723513,,10 Columbus Circle,Suite 114,New York,NY,10019,US,7,,U11,,A-WSH2609F,,,,,,,,,",
            "POB250,712,WS Sherway Grdns CAN,6167723513,,25 The West Mall,Space #1394,Toronto,ON,M9C1B8,CA,1,,U54,,B-WSH2609F,MARKETING MATERIAL,1,,4911.1,,,,,",
            "POB250,B2B Team ,Sales,6167723513,,77 Grandview Drive ,,Ridgefield,CT,06877,US,1,,U11,A12345,D-WSH2609F,,,2026-10-01,,,,,,");

    private static OrderImportPreviewDTO preview(String csv) {
        return new OrderImportServiceImpl().preview("WSH2609F.csv",
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), null).getData();
    }

    @Test
    void everyClientColumnLandsInItsField() {
        OrderImportPreviewDTO p = preview(FILE);
        List<OrderImportRowDTO> rows = p.getRows();
        assertEquals(3, rows.size());

        OrderImportRowDTO store = rows.get(0);
        assertEquals("POB250", store.getClientCode());
        assertEquals("3", store.getRecipientName());
        assertEquals("WS Columbus Circle", store.getRecipientCompany());
        assertEquals("6167723513", store.getRecipientPhone());
        assertEquals("10 Columbus Circle", store.getAddressLine1());
        assertEquals("Suite 114", store.getAddressLine2());
        assertEquals("NY", store.getState());
        assertEquals("10019", store.getPostalCode());
        assertEquals(0, new BigDecimal("7").compareTo(store.getWeight()));
        assertEquals("LB", store.getWeightUnit(), "WEIGHT alone means pounds");
        assertEquals("U11", store.getServiceType());
        assertEquals("A-WSH2609F", store.getReference(), "GROUP_ID is a reference, not one shipment");

        OrderImportRowDTO canada = rows.get(1);
        assertEquals("MARKETING MATERIAL", canada.getItemDescription());
        assertEquals(1, canada.getItemQuantity());
        assertEquals("4911.1", canada.getHsCode());

        OrderImportRowDTO thirdParty = rows.get(2);
        assertEquals("A12345", thirdParty.getAccountNumber());
        assertEquals("THIRD_PARTY", thirdParty.getBillTo());

        assertEquals("CLIENT_ID", p.getFileColumns().get(0));
        assertEquals(25, p.getFileColumns().size());
    }

    @Test
    void knownClientColumnsAreNotCustomFields_butAFilledUnusedOneIsKept() {
        OrderImportRowDTO thirdParty = preview(FILE).getRows().get(2);
        Map<String, String> extras = thirdParty.getCustomFields();
        assertEquals(Map.of("ship_date", "2026-10-01"), extras);
        assertTrue(ImportColumnAliases.unusedWarning("ship_date").startsWith("SHIP_DATE isn't used yet"));
    }

    @Test
    void theClientLayoutTemplatesUseTheClientsColumnNamesInItsOrder() throws Exception {
        String csv = new String(new OrderImportServiceImpl().clientLayoutCsvTemplate(), StandardCharsets.UTF_8);
        assertEquals(String.join(",", ImportColumnAliases.CLIENT_LAYOUT), csv.lines().findFirst().orElse(""));

        byte[] xlsx = OrderImportTemplateBuilder.build(ImportColumnAliases.clientLayoutKeys(),
                ImportColumnAliases.clientHeaderNames(), List.of(), List.of(), Map.of(), List.of(), List.of(), Map.of());
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            var header = wb.getSheet("Import").getRow(0);
            List<String> names = new ArrayList<>();
            header.forEach(c -> names.add(c.getStringCellValue()));
            assertEquals(ImportColumnAliases.CLIENT_LAYOUT, names);
        }
    }

    @Test
    void aStandardLayoutFileStillImportsAsBefore() {
        String std = "orderRef,clientCode,recipientName,addressLine1,city,state,postalCode,countryCode,weight,weightUnit,serviceType\n"
                + "R-1,ACME,Jane,1 Broadway,New York,NY,10004,US,2,KG,U11\n";
        OrderImportRowDTO r = preview(std).getRows().get(0);
        assertEquals("ACME", r.getClientCode());
        assertEquals("KG", r.getWeightUnit(), "a file with its own weightUnit keeps it");
        assertEquals(null, r.getBillTo());
    }
}
