package com.multiship.backend.service.wms;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.dto.OrderImportRowDTO;
import com.multiship.backend.dto.wms.WmsPendingOrderDTO;
import com.multiship.backend.dto.wms.WmsPendingOrderDTO.WmsAddress;
import com.multiship.backend.dto.wms.WmsPendingOrderDTO.WmsContainer;
import com.multiship.backend.dto.wms.WmsPullResultDTO;
import com.multiship.backend.model.ImportBatch;
import com.multiship.backend.model.ImportBatchRow;
import com.multiship.backend.repository.ImportBatchRepository;
import com.multiship.backend.repository.ImportBatchRowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for {@link WmsService} against the real WMS
 * {@code /api/v1/shipping-label/pending-orders} contract. Each fetch is recorded
 * as ONE editable import batch (source = WMS): unconfigured no-op, blank/null
 * shipmentNumber → failed, the row field-mapping (crammed-address cleanup,
 * container-weight sum, ship-via → carrier), and up-front validation that flags
 * the missing billing client so it shows in the grid.
 */
class WmsServiceTest {

    private WmsClient wmsClient;
    private ImportBatchRepository importBatchRepository;
    private ImportBatchRowRepository importBatchRowRepository;
    private final ObjectMapper mapper = new ObjectMapper();
    private WmsService service;

    @BeforeEach
    void setUp() {
        wmsClient = mock(WmsClient.class);
        importBatchRepository = mock(ImportBatchRepository.class);
        // PR-WMS trunk-fix — the `order data validation` refactor moved
        // row persistence off the batch's rows_json blob and onto a
        // dedicated import_batch_row table. Tests now wire the row-repo
        // mock and derive per-row assertions from what was passed to
        // importBatchRowRepository.save(...).
        importBatchRowRepository = mock(ImportBatchRowRepository.class);
        service = new WmsService(wmsClient);
        ReflectionTestUtils.setField(service, "importBatchRepository", importBatchRepository);
        ReflectionTestUtils.setField(service, "importBatchRowRepository", importBatchRowRepository);
        ReflectionTestUtils.setField(service, "importObjectMapper", mapper);
        when(importBatchRepository.save(any(ImportBatch.class))).thenAnswer(inv -> {
            ImportBatch b = inv.getArgument(0);
            b.setId(1L);
            return b;
        });
        // Post-refactor: findById reads the batch back to attach rows in
        // the row-persistence loop. Return the same instance the save
        // captured so the loop can proceed.
        when(importBatchRepository.findById(any())).thenAnswer(inv -> {
            ImportBatch b = new ImportBatch();
            b.setId((Long) inv.getArgument(0));
            return java.util.Optional.of(b);
        });
    }

    private WmsPendingOrderDTO sample(String shipmentNumber, String custNo) {
        WmsPendingOrderDTO dto = new WmsPendingOrderDTO();
        dto.setOrderNo("ORD-64596");
        dto.setPoNumber("PO-9876");
        dto.setShipmentNumber(shipmentNumber);
        dto.setCustomerReferenceId(custNo);
        dto.setShipVia("U11");
        WmsAddress to = new WmsAddress();
        to.setName("Jane Recipient");
        to.setAttn("Emily Contact");
        to.setPhone("2071234567");
        // WMS crams the street + city/state/zip into addr1 with long space runs.
        to.setAddr1("42 Overseas Ave           London, GB SW1A 1AA      ");
        to.setCity("London");
        to.setState("");
        to.setZip("SW1A 1AA");
        to.setCountry("GB");
        dto.setShipToAddress(to);
        WmsContainer c1 = new WmsContainer();
        c1.setWeight(1.5);
        WmsContainer c2 = new WmsContainer();
        c2.setWeight(1.0);
        dto.setContainers(List.of(c1, c2));
        return dto;
    }

    /**
     * Capture every ImportBatchRow the pull saved, convert back to
     * OrderImportRowDTO for the field-by-field assertions the tests
     * expect. Post-refactor equivalent of the pre-refactor
     * "parse rows_json off the batch" helper.
     */
    private List<OrderImportRowDTO> capturedRows() throws Exception {
        ArgumentCaptor<ImportBatchRow> cap = ArgumentCaptor.forClass(ImportBatchRow.class);
        verify(importBatchRowRepository, org.mockito.Mockito.atLeastOnce()).save(cap.capture());
        return cap.getAllValues().stream().map(WmsServiceTest::toDto)
                .collect(java.util.stream.Collectors.toList());
    }

    /** ImportBatchRow → OrderImportRowDTO conversion — inverse of
     *  WmsService.toImportBatchRow(). Only the fields the tests assert on. */
    private static OrderImportRowDTO toDto(ImportBatchRow row) {
        OrderImportRowDTO dto = new OrderImportRowDTO();
        dto.setRowNumber(row.getRowNumber());
        dto.setOrderRef(row.getOrderRef());
        dto.setReference(row.getReference());
        dto.setClientCode(row.getClientCode());
        dto.setWarehouseCode(row.getWarehouseCode());
        dto.setRecipientName(row.getRecipientName());
        dto.setRecipientCompany(row.getRecipientCompany());
        dto.setRecipientPhone(row.getRecipientPhone());
        dto.setRecipientEmail(row.getRecipientEmail());
        dto.setAddressLine1(row.getAddressLine1());
        dto.setAddressLine2(row.getAddressLine2());
        dto.setCity(row.getCity());
        dto.setState(row.getState());
        dto.setPostalCode(row.getPostalCode());
        dto.setCountryCode(row.getCountryCode());
        dto.setCarrierCode(row.getCarrierCode());
        dto.setServiceType(row.getServiceType());
        dto.setAccountNumber(row.getAccountNumber());
        dto.setPackageType(row.getPackageType());
        dto.setWeight(row.getWeight());
        dto.setWeightUnit(row.getWeightUnit());
        // Errors survived as JSON on the row; parse back for the assertion
        // that reads `errors.stream().anyMatch(...)` in blank-custNo test.
        if (row.getErrors() != null && !row.getErrors().isBlank()) {
            try {
                dto.setErrors(new ObjectMapper().readValue(row.getErrors(),
                        new TypeReference<List<String>>() {}));
            } catch (Exception ignored) {}
        }
        return dto;
    }

    // ===== isConfigured — pure delegation =====

    @Test
    void isConfigured_delegatesToClient_true() {
        when(wmsClient.isConfigured()).thenReturn(true);
        assertTrue(service.isConfigured());
    }

    @Test
    void isConfigured_delegatesToClient_false() {
        when(wmsClient.isConfigured()).thenReturn(false);
        assertFalse(service.isConfigured());
    }

    // ===== not-configured no-op =====

    @Test
    void pullShippable_notConfigured_returnsNoOp_withInstructionalMessage() {
        when(wmsClient.isConfigured()).thenReturn(false);

        WmsPullResultDTO result = service.pullShippable("alice");

        assertFalse(result.isConfigured());
        assertTrue(result.getMessages().get(0).contains("WMS_BASE_URL"));
        assertTrue(result.getImportedOrderNos().isEmpty());
        verify(wmsClient, never()).fetchShippable();
        verify(importBatchRepository, never()).save(any());
    }

    // ===== empty WMS list — no batch recorded =====

    @Test
    void pullShippable_emptyList_reportsZeroCounts_noBatch() {
        when(wmsClient.isConfigured()).thenReturn(true);
        when(wmsClient.fetchShippableBatch(anyInt())).thenReturn(List.of());

        WmsPullResultDTO result = service.pullShippable("alice");

        assertTrue(result.isConfigured());
        assertEquals(0, result.getFetched());
        assertEquals(0, result.getImported());
        assertNull(result.getImportBatchId());
        verify(importBatchRepository, never()).save(any());
    }

    // ===== bad-input branches =====

    @Test
    void pullShippable_blankShipmentNumber_countsAsFailed_noBatch() {
        when(wmsClient.isConfigured()).thenReturn(true);
        when(wmsClient.fetchShippableBatch(anyInt())).thenReturn(List.of(sample("   ", "ACME")));

        WmsPullResultDTO result = service.pullShippable("alice");

        assertEquals(1, result.getFetched());
        assertEquals(1, result.getFailed());
        assertEquals(0, result.getImported());
        assertTrue(result.getMessages().get(0).contains("no shipmentNumber"));
        verify(importBatchRepository, never()).save(any());
    }

    @Test
    void pullShippable_nullDto_countsAsFailed_withoutNullPointer() {
        when(wmsClient.isConfigured()).thenReturn(true);
        when(wmsClient.fetchShippableBatch(anyInt())).thenReturn(java.util.Arrays.asList((WmsPendingOrderDTO) null));

        WmsPullResultDTO result = service.pullShippable("alice");

        assertEquals(1, result.getFailed());
        verify(importBatchRepository, never()).save(any());
    }

    // ===== no dedup — each fetch is its own snapshot batch =====

    @Test
    void pullShippable_neverSkips_recordsEveryShipment() {
        when(wmsClient.isConfigured()).thenReturn(true);
        when(wmsClient.fetchShippableBatch(anyInt())).thenReturn(List.of(sample("SHP-1", "ACME")));

        WmsPullResultDTO result = service.pullShippable("alice");

        assertEquals(0, result.getSkipped());
        assertEquals(1, result.getImported());
        assertEquals(1L, result.getImportBatchId());
    }

    // ===== dedup: re-fetching the same set reuses the batch =====

    @Test
    void pullShippable_sameSetReFetched_reusesExistingBatch_noNewSave() {
        when(wmsClient.isConfigured()).thenReturn(true);
        when(wmsClient.fetchShippableBatch(anyInt())).thenReturn(List.of(sample("SHP-D1", "ACME")));
        ImportBatch existing = new ImportBatch();
        existing.setId(77L);
        existing.setSource("WMS");
        when(importBatchRepository.findFirstByContentHashAndDeletedAtIsNullOrderByIdDesc(any()))
                .thenReturn(java.util.Optional.of(existing));

        WmsPullResultDTO result = service.pullShippable("alice");

        assertEquals(0, result.getImported());
        assertEquals(1, result.getSkipped());
        assertEquals(77L, result.getImportBatchId());
        verify(importBatchRepository, never()).save(any());
        assertTrue(result.getMessages().stream().anyMatch(m -> m.contains("already fetched")));
    }

    // ===== successful import + row mapping =====

    @Test
    void pullShippable_recordsOneBatch_sourceWms_ready() {
        when(wmsClient.isConfigured()).thenReturn(true);
        when(wmsClient.fetchShippableBatch(anyInt())).thenReturn(List.of(sample("SHP-99", "ACME")));

        WmsPullResultDTO result = service.pullShippable("alice");

        assertEquals(1, result.getImported());
        ArgumentCaptor<ImportBatch> cap = ArgumentCaptor.forClass(ImportBatch.class);
        verify(importBatchRepository).save(cap.capture());
        ImportBatch b = cap.getValue();
        assertEquals("WMS", b.getSource());
        assertEquals(1, b.getTotalRows());
        assertEquals(0, b.getInvalidRows());
        // A complete, valid client → ready to generate.
        assertEquals("INITIATE", b.getStatus());
    }

    @Test
    void toImportRow_mapsFields_cleansAddress_sumsWeight_mapsCarrier() throws Exception {
        when(wmsClient.isConfigured()).thenReturn(true);
        when(wmsClient.fetchShippableBatch(anyInt())).thenReturn(List.of(sample("SHP-42", "ACME")));

        service.pullShippable("alice");

        OrderImportRowDTO r = capturedRows().get(0);
        assertEquals("ORD-64596", r.getOrderRef());
        assertEquals("ACME", r.getClientCode());
        assertEquals("Jane Recipient", r.getRecipientName());
        assertEquals("Emily Contact", r.getRecipientCompany());
        // Crammed addr1 reduced to just the street.
        assertEquals("42 Overseas Ave", r.getAddressLine1());
        assertEquals("London", r.getCity());
        assertEquals("SW1A 1AA", r.getPostalCode());
        assertEquals("GB", r.getCountryCode());
        // U11 → UPS heuristic.
        assertEquals("UPS", r.getCarrierCode());
        assertEquals("PO-9876", r.getReference());
        assertEquals("LB", r.getWeightUnit());
        // 1.5 + 1.0 containers → 2.5.
        assertEquals(0, r.getWeight().compareTo(new java.math.BigDecimal("2.5")));
        // Complete client + address → valid.
        assertTrue(r.getErrors() == null || r.getErrors().isEmpty());
    }

    @Test
    void toImportRow_blankCustNo_flagsClientRequired_batchIsDraft() throws Exception {
        when(wmsClient.isConfigured()).thenReturn(true);
        when(wmsClient.fetchShippableBatch(anyInt())).thenReturn(List.of(sample("SHP-C", "")));

        service.pullShippable("alice");

        OrderImportRowDTO r = capturedRows().get(0);
        assertNull(r.getClientCode());
        assertTrue(r.getErrors().stream().anyMatch(e -> e.contains("clientCode")));

        ArgumentCaptor<ImportBatch> cap = ArgumentCaptor.forClass(ImportBatch.class);
        verify(importBatchRepository).save(cap.capture());
        // A row needing fixes holds the batch in DRAFT (Generate gated off).
        assertEquals("DRAFT", cap.getValue().getStatus());
        assertEquals(1, cap.getValue().getInvalidRows());
    }

    // ===== ship-via → carrier heuristic =====

    @Test
    void mapCarrier_heuristics() {
        assertEquals("UPS", WmsService.mapCarrier("U11"));
        assertEquals("FEDEX", WmsService.mapCarrier("F03"));
        assertEquals("USPS", WmsService.mapCarrier("USPS-PM"));
        assertNull(WmsService.mapCarrier("  "));
    }

    // ===== mixed batch summary (blank shipmentNumber excluded) =====

    @Test
    void pullShippable_mixedBatch_reportsAccurateCounts() {
        when(wmsClient.isConfigured()).thenReturn(true);
        when(wmsClient.fetchShippableBatch(anyInt())).thenReturn(List.of(
                sample("SHP-N1", "ACME"), sample("", "ACME"), sample("SHP-N2", "ACME")));

        WmsPullResultDTO result = service.pullShippable("alice");

        assertEquals(3, result.getFetched());
        assertEquals(2, result.getImported());   // the two with a shipmentNumber
        assertEquals(1, result.getFailed());      // blank shipmentNumber
        assertEquals(0, result.getSkipped());
    }

    // ===== ship via mapping (same rules a CSV upload follows) =====

    private static com.multiship.backend.model.ShippingService svc(String carrier, String code, String name) {
        com.multiship.backend.model.ShippingService s = new com.multiship.backend.model.ShippingService();
        s.setCarrier(carrier);
        s.setServiceCode(code);
        s.setName(name);
        s.setEnabled(true);
        return s;
    }

    private String resolve(String clientCode, String carrier, String shipVia, com.multiship.backend.dto.OrderImportRowDTO row) {
        return ReflectionTestUtils.invokeMethod(service, "resolveService",
                clientCode, carrier, shipVia, null, "US", row);
    }

    /**
     * The mapping decides the carrier too: the WMS ship-via's first letter is
     * only a guess, and a client may route "U11" wherever they like.
     */
    @Test
    void theClientsRuleWinsAndSetsTheCarrier() {
        com.multiship.backend.service.ShippingConfigService cfg =
                mock(com.multiship.backend.service.ShippingConfigService.class);
        when(cfg.resolveRule(any(), any(), any(), any())).thenReturn(java.util.Optional.empty());
        when(cfg.resolveRule(org.mockito.ArgumentMatchers.eq("ACME"),
                org.mockito.ArgumentMatchers.eq("U11"), any(), any()))
                .thenReturn(java.util.Optional.of(svc("FEDEX", "FEDEX_GROUND", "FedEx Ground")));
        ReflectionTestUtils.setField(service, "shippingConfigService", cfg);

        com.multiship.backend.dto.OrderImportRowDTO row = new com.multiship.backend.dto.OrderImportRowDTO();
        row.setCarrierCode("UPS"); // the letter heuristic's guess
        assertEquals("FEDEX_GROUND", resolve("ACME", "UPS", "U11", row));
        assertEquals("FEDEX", row.getCarrierCode(), "the rule's carrier replaces the guess");
        assertEquals("U11", row.getShipViaCode());
        assertTrue(row.getShipViaNote().contains("FedEx Ground"), row.getShipViaNote());
    }

    /**
     * An unmapped code used to ship the parcel on the carrier's ground service
     * with only a warning. It now leaves the service blank so the row is flagged
     * and someone decides, exactly as a CSV upload does.
     */
    @Test
    void anUnmappedShipViaNoLongerDefaultsToGround() {
        com.multiship.backend.service.ShippingConfigService cfg =
                mock(com.multiship.backend.service.ShippingConfigService.class);
        when(cfg.resolveRule(any(), any(), any(), any())).thenReturn(java.util.Optional.empty());
        when(cfg.resolveServiceCode(any(), any(), any())).thenReturn(java.util.Optional.empty());
        ReflectionTestUtils.setField(service, "shippingConfigService", cfg);

        com.multiship.backend.dto.OrderImportRowDTO row = new com.multiship.backend.dto.OrderImportRowDTO();
        row.setCarrierCode("UPS");
        row.setClientCode("ACME");
        assertNull(resolve("ACME", "UPS", "ZZ9", row), "nothing is invented for an unmapped code");

        @SuppressWarnings("unchecked")
        java.util.List<String> errors = (java.util.List<String>) ReflectionTestUtils.invokeMethod(
                service, "preflight", sample("SHP-1", "ACME"), row);
        assertTrue(errors.stream().anyMatch(e -> e.contains("is not mapped for ACME")), String.valueOf(errors));
        assertTrue(errors.stream().anyMatch(e -> e.contains("Shipping Service Mapping")), String.valueOf(errors));
    }
}
