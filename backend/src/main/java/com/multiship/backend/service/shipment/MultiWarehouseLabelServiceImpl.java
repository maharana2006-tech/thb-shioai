package com.multiship.backend.service.shipment;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.LabelGenerationResponse;
import com.multiship.backend.dto.ManualShipmentRequest;
import com.multiship.backend.dto.MultiWarehouseLabelRequest;
import com.multiship.backend.dto.MultiWarehouseLabelRequest.LineItem;
import com.multiship.backend.dto.MultiWarehouseLabelResponse;
import com.multiship.backend.dto.MultiWarehouseLabelResponse.ChildShipment;
import com.multiship.backend.model.Shipment;
import com.multiship.backend.model.ShipmentGroup;
import com.multiship.backend.repository.ShipmentGroupRepository;
import com.multiship.backend.repository.ShipmentRepository;
import com.multiship.backend.service.CarrierService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class MultiWarehouseLabelServiceImpl implements MultiWarehouseLabelService {

    private final CarrierService carrierService;
    private final ShipmentGroupRepository groupRepository;
    private final ShipmentRepository shipmentRepository;

    @Override
    @Transactional
    public ApiResponse<MultiWarehouseLabelResponse> generate(
            MultiWarehouseLabelRequest request, UserDetails user) {

        // ===== Validation =====
        if (request == null) {
            return failure(HttpStatus.BAD_REQUEST, "Request is required.");
        }
        if (request.getClientCode() == null || request.getClientCode().isBlank()) {
            return failure(HttpStatus.BAD_REQUEST, "clientCode is required for a multi-warehouse split.");
        }
        if (request.getLines() == null || request.getLines().isEmpty()) {
            return failure(HttpStatus.BAD_REQUEST, "At least one line is required.");
        }
        // Every line must carry a warehouseCode — the split key.
        for (int i = 0; i < request.getLines().size(); i++) {
            LineItem line = request.getLines().get(i);
            if (line.getWarehouseCode() == null || line.getWarehouseCode().isBlank()) {
                return failure(HttpStatus.BAD_REQUEST,
                        "Line " + (i + 1) + " is missing warehouseCode — required for the split.");
            }
        }

        // ===== Group by warehouseCode (preserve insertion order for stable
        //       response layout). =====
        Map<String, List<LineItem>> byWarehouse = new LinkedHashMap<>();
        for (LineItem line : request.getLines()) {
            byWarehouse.computeIfAbsent(line.getWarehouseCode().trim(),
                    k -> new ArrayList<>()).add(line);
        }

        // ===== Generate one child label per group. =====
        //
        // Fail-all: any child that fails throws, which aborts the @Transactional
        // and rolls back everything. That's the invariant the operator relies
        // on — "either all labels bought or nothing."
        List<ChildShipment> children = new ArrayList<>(byWarehouse.size());
        List<Shipment> persistedRows = new ArrayList<>(byWarehouse.size());

        for (Map.Entry<String, List<LineItem>> entry : byWarehouse.entrySet()) {
            String warehouseCode = entry.getKey();
            List<LineItem> lines = entry.getValue();

            ManualShipmentRequest childReq = buildChildRequest(request, warehouseCode, lines);

            ApiResponse<LabelGenerationResponse> resp;
            try {
                resp = carrierService.generateManualLabel(childReq, user);
            } catch (RuntimeException e) {
                // The single-shipment generator throws only on unexpected
                // issues (its normal-fail path returns an error envelope).
                // Escalate so @Transactional rolls back.
                throw new IllegalStateException(
                        "Warehouse " + warehouseCode + " label generation failed: " + e.getMessage(), e);
            }

            if (resp == null || resp.getData() == null || !isSuccess(resp)) {
                // First failure aborts the whole batch. The response envelope
                // for the caller reflects the specific child that failed.
                String msg = resp == null || resp.getMessage() == null
                        ? "unknown error" : resp.getMessage();
                throw new SplitAbortException(
                        "Warehouse " + warehouseCode + " label failed: " + msg,
                        warehouseCode, msg);
            }

            LabelGenerationResponse label = resp.getData();
            Shipment s = Shipment.builder()
                    .orderNo(request.getOrderNo())
                    .clientCode(request.getClientCode())
                    .warehouseCode(warehouseCode)
                    .carrierCode(label.getCarrierCode())
                    // serviceCode isn't on LabelGenerationResponse — leave for a
                    // follow-up if needed. Downstream tracking works off carrier + tracking.
                    .trackingNumber(label.getTrackingNumber())
                    .trackingUrl(label.getTrackingUrl())
                    .labelUrl(label.getLabelUrl())
                    .labelPdf(label.getLabelPdf())
                    .carrierAmount(label.getCarrierAmount())
                    .billableAmount(label.getBillableAmount())
                    .currency(label.getMarkupCurrency())
                    .status("CREATED")
                    .build();
            persistedRows.add(s);

            children.add(ChildShipment.builder()
                    .warehouseCode(warehouseCode)
                    .carrierCode(label.getCarrierCode())
                    .trackingNumber(label.getTrackingNumber())
                    .trackingUrl(label.getTrackingUrl())
                    .labelUrl(label.getLabelUrl())
                    .labelPdf(label.getLabelPdf())
                    .carrierAmount(label.getCarrierAmount())
                    .billableAmount(label.getBillableAmount())
                    .currency(label.getMarkupCurrency())
                    .status("CREATED")
                    .message(label.getMessage())
                    .lineCount(lines.size())
                    .build());
        }

        // ===== Persist group + children. =====
        ShipmentGroup group = ShipmentGroup.builder()
                .clientCode(request.getClientCode())
                .orderNo(request.getOrderNo())
                .shipmentCount(children.size())
                .createdBy(user == null ? null : user.getUsername())
                .build();
        group = groupRepository.save(group);
        for (int i = 0; i < persistedRows.size(); i++) {
            persistedRows.get(i).setGroupId(group.getId());
            Shipment saved = shipmentRepository.save(persistedRows.get(i));
            children.get(i).setShipmentId(saved.getId());
        }

        log.info("Multi-warehouse split: group={} client={} shipments={} order={}",
                group.getId(), group.getClientCode(), children.size(), group.getOrderNo());

        return ApiResponse.<MultiWarehouseLabelResponse>builder()
                .status("success").code(200)
                .message("Generated " + children.size() + " label"
                        + (children.size() == 1 ? "" : "s")
                        + " across " + byWarehouse.size() + " warehouse"
                        + (byWarehouse.size() == 1 ? "" : "s") + ".")
                .data(MultiWarehouseLabelResponse.builder()
                        .groupId(group.getId())
                        .clientCode(group.getClientCode())
                        .orderNo(group.getOrderNo())
                        .shipmentCount(children.size())
                        .shipments(children)
                        .build())
                .build();
    }

    /**
     * Build the ManualShipmentRequest for one warehouse group by overlaying
     * the shared header on the group-specific lines.
     */
    private ManualShipmentRequest buildChildRequest(MultiWarehouseLabelRequest req,
                                                    String warehouseCode,
                                                    List<LineItem> lines) {
        ManualShipmentRequest out = new ManualShipmentRequest();
        out.setClientCode(req.getClientCode());
        out.setWarehouseCode(warehouseCode);
        out.setRecipient(req.getRecipient());
        out.setCarrierCode(req.getCarrierCode());
        out.setServiceId(req.getServiceId());
        out.setAccountId(req.getAccountId());
        out.setAccountNumber(req.getAccountNumber());
        out.setPackagePresetId(req.getPackagePresetId());
        out.setLength(req.getLength());
        out.setWidth(req.getWidth());
        out.setHeight(req.getHeight());
        out.setDimUnit(req.getDimUnit());
        // Per-child weight: sum of line weights when set, else fall back to
        // the shared per-shipment weight.
        BigDecimal childWeight = sumLineWeights(lines);
        out.setWeight(childWeight != null ? childWeight : req.getWeight());
        out.setWeightUnit(req.getWeightUnit());
        out.setDeclaredValue(req.getDeclaredValue());
        out.setCurrency(req.getCurrency());
        out.setGoodsDescription(req.getGoodsDescription());
        out.setReference(req.getReference());
        out.setSource(req.getSource());
        out.setIncoterms(req.getIncoterms());
        out.setReasonForExport(req.getReasonForExport());
        out.setImporter(req.getImporter());
        out.setBroker(req.getBroker());
        out.setDangerousGoods(req.getDangerousGoods());
        out.setSignatureOption(req.getSignatureOption());
        out.setInsuredValue(req.getInsuredValue());
        out.setInsuredValueCurrency(req.getInsuredValueCurrency());

        // Convert LineItem → ManualShipmentRequest.Item (commercial-invoice
        // line shape). Skip lines with no useful data so the connector doesn't
        // choke on empties.
        List<ManualShipmentRequest.Item> items = new ArrayList<>(lines.size());
        for (LineItem line : lines) {
            ManualShipmentRequest.Item it = new ManualShipmentRequest.Item();
            it.setSku(line.getItemNo());
            it.setDescription(line.getDescription());
            it.setHsCode(line.getHsCode());
            it.setCountryOfOrigin(line.getCountryOfOrigin());
            it.setQuantity(line.getQuantity() != null ? line.getQuantity() : 1);
            it.setUnitValue(line.getUnitValue());
            it.setWeight(line.getWeight());
            items.add(it);
        }
        out.setItems(items);
        return out;
    }

    private static BigDecimal sumLineWeights(List<LineItem> lines) {
        BigDecimal total = null;
        for (LineItem line : lines) {
            BigDecimal w = line.getWeight();
            if (w == null) continue;
            int qty = line.getQuantity() != null ? line.getQuantity() : 1;
            BigDecimal contribution = w.multiply(BigDecimal.valueOf(qty));
            total = total == null ? contribution : total.add(contribution);
        }
        return total;
    }

    private static boolean isSuccess(ApiResponse<?> resp) {
        return resp.getCode() >= 200 && resp.getCode() < 300
                && !"error".equalsIgnoreCase(resp.getStatus());
    }

    private static ApiResponse<MultiWarehouseLabelResponse> failure(HttpStatus status, String message) {
        return ApiResponse.<MultiWarehouseLabelResponse>builder()
                .status("error").code(status.value())
                .errorCode(ErrorCode.VALIDATION_ERROR.name())
                .message(message).build();
    }

    /**
     * Thrown when a child shipment fails, so @Transactional rolls back all
     * sibling children AND the group row. Carries the failing warehouse +
     * message so the caller's error envelope can be specific.
     */
    static class SplitAbortException extends RuntimeException {
        final String warehouseCode;
        final String detail;
        SplitAbortException(String message, String warehouseCode, String detail) {
            super(message);
            this.warehouseCode = warehouseCode;
            this.detail = detail;
        }
    }
}
