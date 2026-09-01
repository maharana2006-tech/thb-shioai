package com.multiship.backend.service.wms;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.dto.OrderImportRowDTO;
import com.multiship.backend.dto.wms.WmsPendingOrderDTO;
import com.multiship.backend.dto.wms.WmsPendingOrderDTO.WmsAddress;
import com.multiship.backend.dto.wms.WmsPendingOrderDTO.WmsContainer;
import com.multiship.backend.dto.wms.WmsPullResultDTO;
import com.multiship.backend.model.ImportBatch;
import com.multiship.backend.repository.ImportBatchRepository;
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
    private final ObjectMapper mapper = new ObjectMapper();
    private WmsService service;

    @BeforeEach
    void setUp() {
        wmsClient = mock(WmsClient.class);
        importBatchRepository = mock(ImportBatchRepository.class);
        service = new WmsService(wmsClient);
        ReflectionTestUtils.setField(service, "importBatchRepository", importBatchRepository);
        ReflectionTestUtils.setField(service, "importObjectMapper", mapper);
        when(importBatchRepository.save(any(ImportBatch.class))).thenAnswer(inv -> {
            ImportBatch b = inv.getArgument(0);
            b.setId(1L);
            return b;
        });
    }

    private WmsPendingOrderDTO sample(String shipmentNumber, String custNo) {
        WmsPendingOrderDTO dto = new WmsPendingOrderDTO();
        dto.setOrderNo("ORD-64596");
        dto.setPoNumber("PO-9876");
        dto.setShipmentNumber(shipmentNumber);
        dto.setCustNo(custNo);
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

    /** Parse the rows_json off the single ImportBatch the pull saved. */
    private List<OrderImportRowDTO> capturedRows() throws Exception {
        ArgumentCaptor<ImportBatch> cap = ArgumentCaptor.forClass(ImportBatch.class);
        verify(importBatchRepository).save(cap.capture());
        return mapper.readValue(cap.getValue().getRowsJson(),
                new TypeReference<List<OrderImportRowDTO>>() {});
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
        when(wmsClient.fetchShippable()).thenReturn(List.of());

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
        when(wmsClient.fetchShippable()).thenReturn(List.of(sample("   ", "ACME")));

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
        when(wmsClient.fetchShippable()).thenReturn(java.util.Arrays.asList((WmsPendingOrderDTO) null));

        WmsPullResultDTO result = service.pullShippable("alice");

        assertEquals(1, result.getFailed());
        verify(importBatchRepository, never()).save(any());
    }

    // ===== no dedup — each fetch is its own snapshot batch =====

    @Test
    void pullShippable_neverSkips_recordsEveryShipment() {
        when(wmsClient.isConfigured()).thenReturn(true);
        when(wmsClient.fetchShippable()).thenReturn(List.of(sample("SHP-1", "ACME")));

        WmsPullResultDTO result = service.pullShippable("alice");

        assertEquals(0, result.getSkipped());
        assertEquals(1, result.getImported());
        assertEquals(1L, result.getImportBatchId());
    }

    // ===== dedup: re-fetching the same set reuses the batch =====

    @Test
    void pullShippable_sameSetReFetched_reusesExistingBatch_noNewSave() {
        when(wmsClient.isConfigured()).thenReturn(true);
        when(wmsClient.fetchShippable()).thenReturn(List.of(sample("SHP-D1", "ACME")));
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
        when(wmsClient.fetchShippable()).thenReturn(List.of(sample("SHP-99", "ACME")));

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
        when(wmsClient.fetchShippable()).thenReturn(List.of(sample("SHP-42", "ACME")));

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
        when(wmsClient.fetchShippable()).thenReturn(List.of(sample("SHP-C", "")));

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
        when(wmsClient.fetchShippable()).thenReturn(List.of(
                sample("SHP-N1", "ACME"), sample("", "ACME"), sample("SHP-N2", "ACME")));

        WmsPullResultDTO result = service.pullShippable("alice");

        assertEquals(3, result.getFetched());
        assertEquals(2, result.getImported());   // the two with a shipmentNumber
        assertEquals(1, result.getFailed());      // blank shipmentNumber
        assertEquals(0, result.getSkipped());
    }
}
