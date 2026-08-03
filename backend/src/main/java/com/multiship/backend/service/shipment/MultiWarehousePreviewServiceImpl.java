package com.multiship.backend.service.shipment;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.MultiWarehouseLabelRequest;
import com.multiship.backend.dto.MultiWarehouseLabelRequest.LineItem;
import com.multiship.backend.dto.MultiWarehousePreviewResponse;
import com.multiship.backend.dto.MultiWarehousePreviewResponse.GroupPreview;
import com.multiship.backend.dto.MultiWarehousePreviewResponse.LinePreview;
import com.multiship.backend.dto.WarehouseSelectionResult;
import com.multiship.backend.service.warehouse.WarehouseSelector;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class MultiWarehousePreviewServiceImpl implements MultiWarehousePreviewService {

    private final WarehouseSelector warehouseSelector;

    @Override
    public ApiResponse<MultiWarehousePreviewResponse> preview(MultiWarehouseLabelRequest request) {
        if (request == null) {
            return failure(HttpStatus.BAD_REQUEST, "Request is required.");
        }
        if (request.getClientCode() == null || request.getClientCode().isBlank()) {
            return failure(HttpStatus.BAD_REQUEST, "clientCode is required.");
        }
        if (request.getLines() == null || request.getLines().isEmpty()) {
            return failure(HttpStatus.BAD_REQUEST, "At least one line is required.");
        }

        // Resolve the destination once — same recipient for every line.
        // AUTO lines can only be assigned when the recipient carries at
        // least a country; postal is a bonus (bumps the score inside the
        // selector but not required).
        String destCountry = request.getRecipient() != null ? request.getRecipient().getCountryCode() : null;
        String destPostal = request.getRecipient() != null ? request.getRecipient().getPostalCode() : null;

        // Cache: the selector only depends on (clientCode, country, postal)
        // and those are constant across lines in one request. One call =
        // one DB roundtrip regardless of line count.
        WarehouseSelectionResult selection = null;
        boolean autoNeeded = request.getLines().stream()
                .anyMatch(l -> l.getWarehouseCode() == null || l.getWarehouseCode().isBlank());
        if (autoNeeded) {
            selection = warehouseSelector.selectNearest(request.getClientCode(), destCountry, destPostal);
        }

        List<LinePreview> linePreviews = new ArrayList<>(request.getLines().size());
        Map<String, Integer> lineCountByWarehouse = new LinkedHashMap<>();
        Map<String, String> nameByWarehouse = new LinkedHashMap<>();
        int unassigned = 0;

        for (int i = 0; i < request.getLines().size(); i++) {
            LineItem line = request.getLines().get(i);
            String explicit = line.getWarehouseCode();
            LinePreview preview;

            if (explicit != null && !explicit.isBlank()) {
                String wh = explicit.trim();
                preview = LinePreview.builder()
                        .lineIndex(i).itemNo(line.getItemNo()).quantity(line.getQuantity())
                        .assignedWarehouseCode(wh).source("EXPLICIT")
                        .build();
                lineCountByWarehouse.merge(wh, 1, Integer::sum);
                // Explicit lines carry no selector detail — we don't know
                // the human name unless the caller supplied it separately.
                nameByWarehouse.putIfAbsent(wh, null);
            } else if (selection != null && selection.getSelectedWarehouseCode() != null) {
                String wh = selection.getSelectedWarehouseCode();
                preview = LinePreview.builder()
                        .lineIndex(i).itemNo(line.getItemNo()).quantity(line.getQuantity())
                        .assignedWarehouseCode(wh).source("AUTO")
                        .matchReason(selection.getMatchReason())
                        .selectedWarehouseId(selection.getSelectedWarehouseId())
                        .selectedWarehouseName(selection.getSelectedWarehouseName())
                        .build();
                lineCountByWarehouse.merge(wh, 1, Integer::sum);
                nameByWarehouse.putIfAbsent(wh, selection.getSelectedWarehouseName());
            } else {
                // No explicit code AND the selector couldn't pick — client
                // has no attached warehouses. This line will block the
                // write endpoint until the operator fills it in.
                preview = LinePreview.builder()
                        .lineIndex(i).itemNo(line.getItemNo()).quantity(line.getQuantity())
                        .source("NONE")
                        .matchReason(selection != null ? selection.getMatchReason() : "NONE")
                        .build();
                unassigned++;
            }
            linePreviews.add(preview);
        }

        // Rollup: lineCount DESC, then warehouseCode ASC. Unassigned bucket
        // (null code) always at the tail so a UI can visually separate it.
        List<GroupPreview> groups = new ArrayList<>();
        lineCountByWarehouse.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed()
                        .thenComparing(Map.Entry::getKey))
                .forEach(e -> groups.add(GroupPreview.builder()
                        .warehouseCode(e.getKey())
                        .warehouseName(nameByWarehouse.get(e.getKey()))
                        .lineCount(e.getValue())
                        .build()));
        if (unassigned > 0) {
            groups.add(GroupPreview.builder()
                    .warehouseCode(null).warehouseName(null)
                    .lineCount(unassigned).build());
        }

        String message = unassigned == 0
                ? "Split would generate " + lineCountByWarehouse.size() + " shipment"
                        + (lineCountByWarehouse.size() == 1 ? "" : "s") + "."
                : unassigned + " line" + (unassigned == 1 ? "" : "s")
                        + " could not be auto-assigned — client has no attached warehouses.";

        return ApiResponse.<MultiWarehousePreviewResponse>builder()
                .status("success").code(200).message(message)
                .data(MultiWarehousePreviewResponse.builder()
                        .clientCode(request.getClientCode())
                        .orderNo(request.getOrderNo())
                        .totalLines(request.getLines().size())
                        .shipmentCount(lineCountByWarehouse.size())
                        .unassignedLineCount(unassigned)
                        .groups(groups)
                        .lines(linePreviews)
                        .build())
                .build();
    }

    private static ApiResponse<MultiWarehousePreviewResponse> failure(HttpStatus status, String message) {
        return ApiResponse.<MultiWarehousePreviewResponse>builder()
                .status("error").code(status.value())
                .errorCode(ErrorCode.VALIDATION_ERROR.name())
                .message(message).build();
    }
}
