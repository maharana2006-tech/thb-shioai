package com.multiship.backend.service;

import com.multiship.backend.dto.OrderImportRowDTO;
import com.multiship.backend.model.Client;
import com.multiship.backend.model.ShippingService;
import com.multiship.backend.repository.ClientRepository;
import com.multiship.backend.repository.ShippingServiceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Bulk upload's real job: the file carries the client's OWN ship via code
 * (U11, P10…), and the client's mapping decides which carrier service that is.
 * A code the mapping doesn't cover has to fail at upload, where the operator
 * can still fix it — not at the carrier, with orders already minted.
 */
class ShipViaImportValidationTest {

    private OrderImportServiceImpl service;
    private ShippingConfigService shippingConfig;

    private static ShippingService svc(String carrier, String code, String name) {
        ShippingService s = new ShippingService();
        s.setCarrier(carrier);
        s.setServiceCode(code);
        s.setName(name);
        s.setEnabled(true);
        return s;
    }

    @BeforeEach
    void setUp() {
        service = new OrderImportServiceImpl(mock(CarrierService.class));

        ClientRepository clients = mock(ClientRepository.class);
        Client acme = new Client();
        acme.setClientCode("DES875");
        acme.setStatus(Client.STATUS_ACTIVE);
        when(clients.findByClientCodeInIgnoreCase(any())).thenReturn(List.of(acme));
        ReflectionTestUtils.setField(service, "clientRepository", clients);

        ShippingServiceRepository catalog = mock(ShippingServiceRepository.class);
        when(catalog.findAllByOrderByCarrierAscSortOrderAsc()).thenReturn(List.of(
                svc("UPS", "03", "UPS Ground"),
                svc("UPS", "02", "UPS 2nd Day Air"),
                svc("FEDEX", "FEDEX_GROUND", "FedEx Ground")));
        ReflectionTestUtils.setField(service, "shippingServiceRepository", catalog);

        shippingConfig = mock(ShippingConfigService.class);
        // DES875 maps U11 -> UPS Ground; nothing else resolves.
        when(shippingConfig.resolveRule(anyString(), anyString(), any(), isNull()))
                .thenReturn(Optional.empty());
        when(shippingConfig.resolveRule(eq("DES875"), eq("U11"), any(), isNull()))
                .thenReturn(Optional.of(svc("UPS", "03", "UPS Ground")));
        when(shippingConfig.shipViaCodeExists(anyString())).thenReturn(false);
        ReflectionTestUtils.setField(service, "shippingConfigService", shippingConfig);
    }

    private static OrderImportRowDTO row(String shipVia, String carrier) {
        return OrderImportRowDTO.builder().rowNumber(1).orderRef("A").clientCode("DES875")
                .recipientName("Jane").recipientPhone("2125550100").addressLine1("1 Broadway")
                .city("New York").state("NY").postalCode("10001").countryCode("US")
                .carrierCode(carrier).serviceType(shipVia)
                .weight(new BigDecimal("2")).weightUnit("LB").build();
    }

    /** Runs the same two steps the preview/edit paths run, in the same order. */
    private OrderImportRowDTO validate(OrderImportRowDTO row) {
        List<OrderImportRowDTO> rows = new java.util.ArrayList<>(List.of(row));
        ReflectionTestUtils.invokeMethod(service, "resolveNamesToCodes", rows);
        service.validateReferences(rows);
        return rows.get(0);
    }

    @Test
    void theClientsOwnCodeBecomesTheCarrierAndServiceTheMappingNames() {
        OrderImportRowDTO r = validate(row("U11", null));
        assertEquals(List.of(), r.getErrors());
        assertEquals("UPS", r.getCarrierCode(), "carrier comes from the mapping, not the file");
        assertEquals("03", r.getServiceType());
        assertEquals("U11", r.getShipViaCode(), "the file's code is kept for the operator");
        assertTrue(r.getShipViaNote().contains("UPS Ground"), r.getShipViaNote());
    }

    @Test
    void aCodeNoRuleCoversFailsAtUploadNamingTheMappingScreen() {
        OrderImportRowDTO r = validate(row("U99", "UPS"));
        assertEquals(1, r.getErrors().size(), r.getErrors().toString());
        String err = r.getErrors().get(0);
        assertTrue(err.contains("'U99'") && err.contains("DES875"), err);
        assertTrue(err.contains("Shipping Service Mapping"), err);
        assertNull(r.getShipViaCode());
    }

    @Test
    void aCodeMappedForSomeoneElseSaysSoInsteadOfNeverHeardOfIt() {
        when(shippingConfig.shipViaCodeExists("U43")).thenReturn(true);
        String err = validate(row("U43", "UPS")).getErrors().get(0);
        assertTrue(err.contains("is mapped, but not for DES875"), err);
        assertTrue(err.contains("switched off") && err.contains("Shipping Service Mapping"), err);
    }

    @Test
    void aRealCarrierServiceCodeStillPassesForClientsWithNoMapping() {
        OrderImportRowDTO r = validate(row("02", "UPS"));
        assertEquals(List.of(), r.getErrors());
        assertEquals("02", r.getServiceType());
        assertNull(r.getShipViaCode(), "no rule fired — nothing was translated");
    }

    @Test
    void aBlankShipViaIsAnErrorNotASilentDefault() {
        String err = validate(row(null, "UPS")).getErrors().get(0);
        assertTrue(err.contains("serviceType is required"), err);
        assertTrue(err.contains("ship via code"), err);
    }

    @Test
    void whenTheFilesCarrierContradictsTheRuleTheRuleWinsAndTheRowSaysSo() {
        OrderImportRowDTO r = validate(row("U11", "FEDEX"));
        assertEquals(List.of(), r.getErrors());
        assertEquals("UPS", r.getCarrierCode());
        assertEquals("03", r.getServiceType());
        assertTrue(r.getWarnings().stream().anyMatch(w -> w.contains("not FEDEX as the file says")),
                r.getWarnings().toString());
    }

    /**
     * Our own template's first tab is a blank pad holding the macro buttons,
     * and operators keep notes or a read-me in front of their data. Reading
     * sheet 0 blindly answered "The file has no order rows."
     */
    @Test
    void theOrderSheetIsFoundBehindABlankFirstTab() throws Exception {
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            wb.createSheet("Sheet1"); // blank pad, as the macro template has
            org.apache.poi.ss.usermodel.Sheet imp = wb.createSheet("Import");
            org.apache.poi.ss.usermodel.Row head = imp.createRow(0);
            head.createCell(0).setCellValue("orderRef");
            head.createCell(1).setCellValue("clientCode");
            org.apache.poi.ss.usermodel.Row body = imp.createRow(1);
            body.createCell(0).setCellValue("A-1");
            body.createCell(1).setCellValue("DES875");

            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            wb.write(out);
            List<OrderImportRowDTO> rows = ReflectionTestUtils.invokeMethod(
                    service, "parseXlsx", new java.io.ByteArrayInputStream(out.toByteArray()));
            assertEquals(1, rows == null ? 0 : rows.size(), "the Import sheet's row is read, not the blank tab");
            assertEquals("A-1", rows.get(0).getOrderRef());
        }
    }
}
