package com.multiship.backend.service.shipment;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ManualShipmentRequest;
import com.multiship.backend.dto.MultiWarehouseLabelRequest;
import com.multiship.backend.dto.MultiWarehouseLabelRequest.LineItem;
import com.multiship.backend.dto.MultiWarehousePreviewResponse;
import com.multiship.backend.dto.MultiWarehousePreviewResponse.GroupPreview;
import com.multiship.backend.dto.MultiWarehousePreviewResponse.LinePreview;
import com.multiship.backend.dto.WarehouseSelectionResult;
import com.multiship.backend.service.warehouse.WarehouseSelector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sprint 47 PR4 — verifies the dry-run wiring: per-line assignment split
 * between EXPLICIT / AUTO / NONE, grouped rollup ordering, selector
 * call-count (once per request, not once per line), and validation.
 */
class MultiWarehousePreviewServiceImplTest {

    private WarehouseSelector selector;
    private MultiWarehousePreviewServiceImpl service;

    @BeforeEach
    void setUp() {
        selector = mock(WarehouseSelector.class);
        service = new MultiWarehousePreviewServiceImpl(selector);
    }

    // ===== Happy path — mixed EXPLICIT + AUTO =====

    @Test
    void mixedExplicitAndAutoLines() {
        // Two lines carry explicit WEST; two lines rely on the selector.
        MultiWarehouseLabelRequest req = baseRequest();
        req.setRecipient(recipient("US", "10001"));
        req.getLines().add(line("SKU-1", 1, "WEST"));
        req.getLines().add(line("SKU-2", 2, null));   // -> AUTO -> EAST
        req.getLines().add(line("SKU-3", 1, "WEST"));
        req.getLines().add(line("SKU-4", 3, null));   // -> AUTO -> EAST

        when(selector.selectNearest("ACME", "US", "10001"))
                .thenReturn(WarehouseSelectionResult.builder()
                        .matchReason("COUNTRY_AND_POSTAL")
                        .selectedWarehouseId(42L)
                        .selectedWarehouseCode("EAST")
                        .selectedWarehouseName("East DC")
                        .postalPrefixLength(4)
                        .candidates(List.of()).build());

        ApiResponse<MultiWarehousePreviewResponse> resp = service.preview(req);
        assertEquals("success", resp.getStatus());
        MultiWarehousePreviewResponse body = resp.getData();
        assertEquals(4, body.getTotalLines());
        assertEquals(2, body.getShipmentCount(), "EAST + WEST = 2 groups");
        assertEquals(0, body.getUnassignedLineCount());

        // Selector called ONCE regardless of how many AUTO lines share it.
        verify(selector, times(1)).selectNearest("ACME", "US", "10001");

        // Line-level trace: source + reasons + assigned warehouse.
        List<LinePreview> lines = body.getLines();
        assertEquals("EXPLICIT", lines.get(0).getSource());
        assertEquals("WEST", lines.get(0).getAssignedWarehouseCode());
        assertNull(lines.get(0).getMatchReason(), "EXPLICIT lines carry no matchReason");
        assertEquals("AUTO", lines.get(1).getSource());
        assertEquals("EAST", lines.get(1).getAssignedWarehouseCode());
        assertEquals("COUNTRY_AND_POSTAL", lines.get(1).getMatchReason());
        assertEquals(42L, lines.get(1).getSelectedWarehouseId());
        assertEquals("East DC", lines.get(1).getSelectedWarehouseName());

        // Rollup: sort by lineCount DESC, then warehouseCode ASC.
        // Both groups have 2 lines here, so tie-break by code -> EAST first.
        List<GroupPreview> groups = body.getGroups();
        assertEquals(2, groups.size());
        assertEquals("EAST", groups.get(0).getWarehouseCode());
        assertEquals(2, groups.get(0).getLineCount());
        assertEquals("WEST", groups.get(1).getWarehouseCode());
        assertEquals(2, groups.get(1).getLineCount());
    }

    @Test
    void allExplicit_skipsSelectorEntirely() {
        MultiWarehouseLabelRequest req = baseRequest();
        req.getLines().add(line("SKU-1", 1, "WEST"));
        req.getLines().add(line("SKU-2", 1, "EAST"));
        req.getLines().add(line("SKU-3", 1, "WEST"));

        ApiResponse<MultiWarehousePreviewResponse> resp = service.preview(req);
        assertEquals("success", resp.getStatus());
        // No line needed AUTO — selector should never be called (cheap fast-path).
        verify(selector, never()).selectNearest(anyString(), any(), any());

        MultiWarehousePreviewResponse body = resp.getData();
        // Rollup order: WEST has 2 lines, EAST has 1 -> WEST first.
        assertEquals("WEST", body.getGroups().get(0).getWarehouseCode());
        assertEquals(2, body.getGroups().get(0).getLineCount());
        assertEquals("EAST", body.getGroups().get(1).getWarehouseCode());
    }

    // ===== NONE bucket =====

    @Test
    void unassignedLinesWhenClientHasNoAttachedWarehouses() {
        MultiWarehouseLabelRequest req = baseRequest();
        req.setRecipient(recipient("US", "10001"));
        req.getLines().add(line("SKU-1", 1, null));
        req.getLines().add(line("SKU-2", 1, "WEST"));

        when(selector.selectNearest(eq("ACME"), any(), any()))
                .thenReturn(WarehouseSelectionResult.builder()
                        .matchReason("NONE")
                        .postalPrefixLength(0)
                        .candidates(List.of()).build());

        ApiResponse<MultiWarehousePreviewResponse> resp = service.preview(req);
        MultiWarehousePreviewResponse body = resp.getData();
        assertEquals(1, body.getUnassignedLineCount());
        // shipmentCount = 1 (WEST); NONE bucket is separate.
        assertEquals(1, body.getShipmentCount());
        // Groups: WEST then the null-coded unassigned bucket at the tail.
        assertEquals(2, body.getGroups().size());
        assertEquals("WEST", body.getGroups().get(0).getWarehouseCode());
        assertNull(body.getGroups().get(1).getWarehouseCode(),
                "unassigned bucket has no warehouseCode");
        assertEquals(1, body.getGroups().get(1).getLineCount());

        // The NONE line reports the selector's matchReason for traceability.
        assertEquals("NONE", body.getLines().get(0).getSource());
        assertEquals("NONE", body.getLines().get(0).getMatchReason());
        assertNull(body.getLines().get(0).getAssignedWarehouseCode());

        // Message calls out that lines were unassigned.
        assertNotNull(resp.getMessage());
        assertEquals(true, resp.getMessage().toLowerCase().contains("could not be auto-assigned"));
    }

    // ===== recipient absent =====

    @Test
    void missingRecipient_stillCallsSelectorWithNullDest() {
        // With no recipient country/postal the selector falls back to
        // "any attached" (matchReason ANY). This is a real case for
        // upstream systems that pre-classify by clientCode alone.
        MultiWarehouseLabelRequest req = baseRequest();
        req.setRecipient(null);
        req.getLines().add(line("SKU-1", 1, null));

        when(selector.selectNearest("ACME", null, null))
                .thenReturn(WarehouseSelectionResult.builder()
                        .matchReason("ANY")
                        .selectedWarehouseId(1L)
                        .selectedWarehouseCode("ONLY")
                        .postalPrefixLength(0)
                        .candidates(List.of()).build());

        ApiResponse<MultiWarehousePreviewResponse> resp = service.preview(req);
        assertEquals("success", resp.getStatus());
        assertEquals("ONLY", resp.getData().getLines().get(0).getAssignedWarehouseCode());
        assertEquals("ANY", resp.getData().getLines().get(0).getMatchReason());
    }

    // ===== validation =====

    @Test
    void rejectsNullRequest() {
        ApiResponse<MultiWarehousePreviewResponse> resp = service.preview(null);
        assertEquals("error", resp.getStatus());
        assertEquals(400, resp.getCode());
    }

    @Test
    void rejectsMissingClientCode() {
        MultiWarehouseLabelRequest req = baseRequest();
        req.setClientCode(null);
        req.getLines().add(line("SKU-1", 1, "EAST"));
        ApiResponse<MultiWarehousePreviewResponse> resp = service.preview(req);
        assertEquals("error", resp.getStatus());
    }

    @Test
    void rejectsEmptyLines() {
        ApiResponse<MultiWarehousePreviewResponse> resp = service.preview(baseRequest());
        assertEquals("error", resp.getStatus());
    }

    // ===== helpers =====

    private static MultiWarehouseLabelRequest baseRequest() {
        MultiWarehouseLabelRequest req = new MultiWarehouseLabelRequest();
        req.setClientCode("ACME");
        req.setOrderNo(1234);
        req.setLines(new ArrayList<>());
        return req;
    }

    private static LineItem line(String sku, int qty, String warehouseCode) {
        LineItem l = new LineItem();
        l.setItemNo(sku);
        l.setQuantity(qty);
        l.setWarehouseCode(warehouseCode);
        return l;
    }

    private static ManualShipmentRequest.Address recipient(String country, String postal) {
        ManualShipmentRequest.Address a = new ManualShipmentRequest.Address();
        a.setCountryCode(country);
        a.setPostalCode(postal);
        return a;
    }
}
