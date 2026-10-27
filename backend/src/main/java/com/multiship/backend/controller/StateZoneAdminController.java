package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.StateZoneDTO;
import com.multiship.backend.model.StateZoneEntity;
import com.multiship.backend.service.carrier.StateZoneService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * G2 — admin REST surface over the state_zone lookup table that
 * drives DST-aware offsets for carrier timestamps (FedEx
 * readyDateTimestamp; future UPS / DHL slots). All endpoints
 * require ADMIN; a write triggers {@link StateZoneService#reload()}
 * so the hot-path cache picks up the change without a restart.
 */
@Tag(name = "State Zone", description = "Country + state → IANA zone mapping admin surface. ADMIN only.")
@RestController
@RequestMapping("/api/v1/admin/state-zones")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class StateZoneAdminController {

    private final StateZoneService service;
    /** BUG-157 — audit trail for writes that used to leave none. Optional so hand-built tests stay constructible. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.multiship.backend.service.AuditService audit;


    @Operation(summary = "List every (country, state) → zone row.")
    @GetMapping
    public ResponseEntity<ApiResponse<List<StateZoneDTO>>> list() {
        List<StateZoneDTO> body = service.list().stream().map(StateZoneAdminController::toDto).toList();
        return ResponseEntity.ok(ApiResponse.<List<StateZoneDTO>>builder()
                .status("SUCCESS").code(200).data(body).timestamp(LocalDateTime.now()).build());
    }

    @Operation(summary = "Insert-or-update one (country, state) row. ZoneId must be a valid IANA zone.")
    @PutMapping("/{countryCode}/{stateCode}")
    public ResponseEntity<ApiResponse<StateZoneDTO>> upsert(
            @PathVariable String countryCode,
            @PathVariable String stateCode,
            @Valid @RequestBody UpsertBody body,
            @AuthenticationPrincipal UserDetails caller) {
        try {
            boolean existed = service.exists(countryCode, stateCode);
            StateZoneEntity saved = service.upsert(countryCode, stateCode,
                    body.getZoneId(), body.getDisplayName(),
                    caller != null ? caller.getUsername() : null);
            if (audit != null) {
                audit.record(com.multiship.backend.service.AuditService.UPDATE,
                        com.multiship.backend.service.AuditService.STATE_ZONE, countryCode + "/" + stateCode,
                        countryCode + "/" + stateCode, toDto(saved), "State zone saved.");
            }
            return ResponseEntity.ok(ApiResponse.<StateZoneDTO>builder()
                    .status("SUCCESS").code(200).data(toDto(saved))
                    .message(existed
                            ? "Updated the existing zone for " + countryCode.toUpperCase() + "/" + stateCode.toUpperCase() + "."
                            : "State zone added.")
                    .timestamp(LocalDateTime.now()).build());
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(422).body(ApiResponse.<StateZoneDTO>builder()
                    .status("error").code(422).message(ex.getMessage())
                    .timestamp(LocalDateTime.now()).build());
        }
    }

    @Operation(summary = "Delete one (country, state) row. Idempotent.")
    @DeleteMapping("/{countryCode}/{stateCode}")
    public ResponseEntity<ApiResponse<Void>> delete(
            @PathVariable String countryCode,
            @PathVariable String stateCode) {
        service.delete(countryCode, stateCode);
        if (audit != null) {
            audit.record(com.multiship.backend.service.AuditService.DELETE,
                    com.multiship.backend.service.AuditService.STATE_ZONE, countryCode + "/" + stateCode,
                    countryCode + "/" + stateCode, null, "State zone deleted.");
        }
        return ResponseEntity.ok(ApiResponse.<Void>builder()
                .status("SUCCESS").code(200).timestamp(LocalDateTime.now()).build());
    }

    private static StateZoneDTO toDto(StateZoneEntity e) {
        return StateZoneDTO.builder()
                .countryCode(e.getId() != null ? e.getId().getCountryCode() : null)
                .stateCode(e.getId() != null ? e.getId().getStateCode() : null)
                .zoneId(e.getZoneId())
                .displayName(e.getDisplayName())
                .updatedAt(e.getUpdatedAt())
                .updatedBy(e.getUpdatedBy())
                .build();
    }

    @Data
    public static class UpsertBody {
        @NotBlank
        private String zoneId;
        private String displayName;
    }
}
