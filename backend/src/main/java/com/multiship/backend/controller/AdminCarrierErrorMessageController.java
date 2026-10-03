package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.repository.CarrierErrorMessageRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** V118 — read-only admin surface over carrier_error_message rows. */
@Tag(name = "Carrier error messages",
        description = "Pattern → humanized sentence rules. Admin-only, read-only in V118.")
@RestController
@RequestMapping("/api/v1/admin/carrier-error-messages")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminCarrierErrorMessageController {

    private final CarrierErrorMessageRepository repo;

    @Operation(summary = "List every rule, sorted by sort_order.")
    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list() {
        List<Map<String, Object>> body = repo.findAll().stream()
                .sorted((a, b) -> Integer.compare(
                        a.getSortOrder() == null ? 0 : a.getSortOrder(),
                        b.getSortOrder() == null ? 0 : b.getSortOrder()))
                .map(r -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", r.getId());
                    m.put("carrier", r.getCarrier());
                    m.put("matchAnyOf", r.getMatchAnyOf());
                    m.put("humanized", r.getHumanized());
                    m.put("sortOrder", r.getSortOrder());
                    m.put("updatedAt", r.getUpdatedAt());
                    return m;
                }).toList();
        return ResponseEntity.ok(ApiResponse.<List<Map<String, Object>>>builder()
                .status("SUCCESS").code(200).data(body).timestamp(LocalDateTime.now()).build());
    }
}
