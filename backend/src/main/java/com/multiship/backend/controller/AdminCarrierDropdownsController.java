package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.model.CarrierClearanceOptionEntity;
import com.multiship.backend.model.CarrierLabelFormatEntity;
import com.multiship.backend.model.CarrierPickupTypeEntity;
import com.multiship.backend.repository.CarrierClearanceOptionRepository;
import com.multiship.backend.repository.CarrierLabelFormatRepository;
import com.multiship.backend.repository.CarrierPickupTypeRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** V120 — three read-only per-carrier dropdown admin endpoints.
 *  Mirrors the refdata controller pattern. Each returns rows sorted by
 *  carrier then sort_order so the FE can group with no extra work. */
@Tag(name = "Carrier dropdowns",
        description = "Per-carrier label-format / pickup-type / clearance-option vocabularies. Admin-only, read-only.")
@RestController
@RequestMapping("/api/v1/admin/carrier-dropdowns")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminCarrierDropdownsController {

    private final CarrierLabelFormatRepository labelFormats;
    private final CarrierPickupTypeRepository pickupTypes;
    private final CarrierClearanceOptionRepository clearanceOptions;

    @Operation(summary = "List every carrier_label_format row, sorted by carrier + sort_order.")
    @GetMapping("/label-formats")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listLabelFormats() {
        List<Map<String, Object>> body = labelFormats.findAll().stream()
                .sorted(Comparator.comparing(CarrierLabelFormatEntity::getCarrier)
                        .thenComparing(r -> r.getSortOrder() == null ? 0 : r.getSortOrder()))
                .map(r -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("carrier", r.getCarrier());
                    m.put("code", r.getCode());
                    m.put("label", r.getLabel());
                    m.put("isStockType", Boolean.TRUE.equals(r.getIsStockType()));
                    m.put("sortOrder", r.getSortOrder());
                    return m;
                }).toList();
        return ok(body);
    }

    @Operation(summary = "List every carrier_pickup_type row.")
    @GetMapping("/pickup-types")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listPickupTypes() {
        List<Map<String, Object>> body = pickupTypes.findAll().stream()
                .sorted(Comparator.comparing(CarrierPickupTypeEntity::getCarrier)
                        .thenComparing(r -> r.getSortOrder() == null ? 0 : r.getSortOrder()))
                .map(r -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("carrier", r.getCarrier());
                    m.put("code", r.getCode());
                    m.put("label", r.getLabel());
                    m.put("sortOrder", r.getSortOrder());
                    return m;
                }).toList();
        return ok(body);
    }

    @Operation(summary = "List every carrier_clearance_option row.")
    @GetMapping("/clearance-options")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listClearanceOptions() {
        List<Map<String, Object>> body = clearanceOptions.findAll().stream()
                .sorted(Comparator.comparing(CarrierClearanceOptionEntity::getCarrier)
                        .thenComparing(r -> r.getSortOrder() == null ? 0 : r.getSortOrder()))
                .map(r -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("carrier", r.getCarrier());
                    m.put("code", r.getCode());
                    m.put("label", r.getLabel());
                    m.put("sortOrder", r.getSortOrder());
                    return m;
                }).toList();
        return ok(body);
    }

    private static <T> ResponseEntity<ApiResponse<T>> ok(T body) {
        return ResponseEntity.ok(ApiResponse.<T>builder()
                .status("SUCCESS").code(200).data(body)
                .timestamp(LocalDateTime.now()).build());
    }
}
