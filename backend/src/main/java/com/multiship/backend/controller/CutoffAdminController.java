package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.model.CutoffRule;
import com.multiship.backend.model.Holiday;
import com.multiship.backend.repository.CarrierAccountRefRepository;
import com.multiship.backend.repository.CutoffRuleRepository;
import com.multiship.backend.repository.HolidayRepository;
import com.multiship.backend.repository.WarehouseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * G7 admin — CRUD for cutoff_rule + holiday, plus a bulk seed action that
 * expands the tenant's connected (warehouse × carrier × source) combos
 * into per-combo rule rows. FE surface is /settings/cutoffs.
 */
@RestController
@RequestMapping("/api/v1/admin/cutoffs")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class CutoffAdminController {

    private static final List<String> SEED_SOURCES = List.of("MANUAL", "BULK", "API", "WMS", "DTC");
    private static final LocalTime DEFAULT_CUTOFF = LocalTime.of(20, 0);

    private final CutoffRuleRepository ruleRepo;
    private final HolidayRepository holidayRepo;
    private final WarehouseRepository warehouseRepo;
    private final CarrierAccountRefRepository carrierAccountRepo;

    // BUG-128 sprint 2: audit cutoff rule + holiday writes. Platform-wide
    // scope, CLIENT_POLICY bucket + "cutoff/" or "holiday/" entityKey prefix.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.multiship.backend.service.AuditService audit;
    private static final String CUTOFF_ENTITY = com.multiship.backend.service.AuditService.CLIENT_POLICY;

    // ── Rules ────────────────────────────────────────────────────────

    @GetMapping("/rules")
    public ResponseEntity<ApiResponse<List<CutoffRule>>> listRules() {
        return ok(ruleRepo.findAll());
    }

    @PostMapping("/rules")
    public ResponseEntity<ApiResponse<CutoffRule>> createRule(@RequestBody CutoffRule req, Authentication auth) {
        req.setId(null);
        req.setUpdatedBy(actor(auth));
        if (req.getCutoffTime() == null) req.setCutoffTime(DEFAULT_CUTOFF);
        // BUG-099 R6: pre-validate FK so the operator sees the actual
        // offending field name instead of the GlobalExceptionHandler's
        // generic "references something that doesn't exist". The FE dropdown
        // already blocks unknown ids via BUG-101, but a direct API call
        // can still send one.
        if (req.getWarehouseId() != null
                && !warehouseRepo.existsById(req.getWarehouseId())) {
            throw new IllegalArgumentException(
                    "warehouseId " + req.getWarehouseId() + " doesn't match any warehouse.");
        }
        CutoffRule saved = ruleRepo.save(req);
        if (audit != null) {
            audit.record(com.multiship.backend.service.AuditService.CREATE,
                    CUTOFF_ENTITY, saved.getId(),
                    "cutoff/" + saved.getSource() + "/" + saved.getCarrierCode() + "/" + saved.getWarehouseId(),
                    saved, "Cutoff rule created.");
        }
        return ok(saved);
    }

    @PutMapping("/rules/{id}")
    public ResponseEntity<ApiResponse<CutoffRule>> updateRule(@PathVariable Long id,
                                                               @RequestBody CutoffRule req,
                                                               Authentication auth) {
        CutoffRule existing = ruleRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Rule " + id + " not found."));
        if (req.getSource() != null) existing.setSource(req.getSource());
        if (req.getCarrierCode() != null) existing.setCarrierCode(req.getCarrierCode());
        if (req.getWarehouseId() != null) existing.setWarehouseId(req.getWarehouseId());
        if (req.getCutoffTime() != null) existing.setCutoffTime(req.getCutoffTime());
        if (req.getTimezone() != null) existing.setTimezone(req.getTimezone());
        if (req.getActive() != null) existing.setActive(req.getActive());
        existing.setUpdatedBy(actor(auth));
        CutoffRule saved = ruleRepo.save(existing);
        if (audit != null) {
            audit.record(com.multiship.backend.service.AuditService.UPDATE,
                    CUTOFF_ENTITY, saved.getId(),
                    "cutoff/" + saved.getSource() + "/" + saved.getCarrierCode() + "/" + saved.getWarehouseId(),
                    saved, "Cutoff rule updated.");
        }
        return ok(saved);
    }

    @DeleteMapping("/rules/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteRule(@PathVariable Long id) {
        ruleRepo.deleteById(id);
        if (audit != null) {
            audit.record(com.multiship.backend.service.AuditService.DELETE,
                    CUTOFF_ENTITY, id, "cutoff/" + id, null, "Cutoff rule deleted.");
        }
        return ok(null);
    }

    /**
     * Bulk-create rules for every (warehouse × active carrier account × source)
     * combination in the tenant. Existing rules for the same (source, carrier,
     * warehouse) triple are preserved (idempotent — no duplicates).
     */
    @PostMapping("/rules/seed")
    public ResponseEntity<ApiResponse<Map<String, Object>>> seedRules(Authentication auth) {
        Set<String> existingKeys = new HashSet<>();
        for (CutoffRule r : ruleRepo.findAll()) {
            existingKeys.add(ruleKey(r.getSource(), r.getCarrierCode(), r.getWarehouseId()));
        }
        Set<String> carriers = new HashSet<>();
        carrierAccountRepo.findAll().stream()
                .filter(a -> Boolean.TRUE.equals(a.getActive()))
                .map(a -> a.getCarrierCode() == null ? null : a.getCarrierCode().trim().toUpperCase())
                .filter(c -> c != null && !c.isBlank())
                .forEach(carriers::add);
        int created = 0, skipped = 0;
        String updatedBy = actor(auth);
        for (var wh : warehouseRepo.findAll()) {
            if (wh.getId() == null || !Boolean.TRUE.equals(wh.getActive())) continue;
            for (String carrier : carriers) {
                for (String source : SEED_SOURCES) {
                    String key = ruleKey(source, carrier, wh.getId());
                    if (existingKeys.contains(key)) { skipped++; continue; }
                    CutoffRule rule = new CutoffRule();
                    rule.setSource(source);
                    rule.setCarrierCode(carrier);
                    rule.setWarehouseId(wh.getId());
                    rule.setCutoffTime(DEFAULT_CUTOFF);
                    rule.setActive(true);
                    rule.setUpdatedBy(updatedBy);
                    ruleRepo.save(rule);
                    existingKeys.add(key);
                    created++;
                }
            }
        }
        return ok(Map.of(
                "created", created,
                "skipped", skipped,
                "warehouses", warehouseRepo.findAll().stream().filter(w -> Boolean.TRUE.equals(w.getActive())).count(),
                "carriers", carriers.size(),
                "sources", SEED_SOURCES));
    }

    // ── Holidays ─────────────────────────────────────────────────────

    @GetMapping("/holidays")
    public ResponseEntity<ApiResponse<List<Holiday>>> listHolidays() {
        return ok(holidayRepo.findAll());
    }

    @PostMapping("/holidays")
    public ResponseEntity<ApiResponse<Holiday>> createHoliday(@RequestBody Holiday req, Authentication auth) {
        if (req.getHolidayDate() == null || req.getName() == null || req.getName().isBlank()) {
            throw new IllegalArgumentException("holidayDate + name required.");
        }
        holidayRepo.findByHolidayDate(req.getHolidayDate()).ifPresent(existing -> {
            throw new IllegalArgumentException("Holiday for " + req.getHolidayDate() + " already exists.");
        });
        req.setId(null);
        req.setUpdatedBy(actor(auth));
        Holiday saved = holidayRepo.save(req);
        if (audit != null) {
            audit.record(com.multiship.backend.service.AuditService.CREATE,
                    CUTOFF_ENTITY, saved.getId(),
                    "holiday/" + saved.getHolidayDate(), saved,
                    "Holiday created: " + saved.getName() + ".");
        }
        return ok(saved);
    }

    @PutMapping("/holidays/{id}")
    public ResponseEntity<ApiResponse<Holiday>> updateHoliday(@PathVariable Long id,
                                                               @RequestBody Holiday req,
                                                               Authentication auth) {
        Holiday existing = holidayRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Holiday " + id + " not found."));
        if (req.getName() != null) existing.setName(req.getName());
        if (req.getHolidayDate() != null) existing.setHolidayDate(req.getHolidayDate());
        if (req.getActive() != null) existing.setActive(req.getActive());
        existing.setUpdatedBy(actor(auth));
        Holiday saved = holidayRepo.save(existing);
        if (audit != null) {
            audit.record(com.multiship.backend.service.AuditService.UPDATE,
                    CUTOFF_ENTITY, saved.getId(),
                    "holiday/" + saved.getHolidayDate(), saved,
                    "Holiday updated: " + saved.getName() + ".");
        }
        return ok(saved);
    }

    @DeleteMapping("/holidays/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteHoliday(@PathVariable Long id) {
        holidayRepo.deleteById(id);
        if (audit != null) {
            audit.record(com.multiship.backend.service.AuditService.DELETE,
                    CUTOFF_ENTITY, id, "holiday/" + id, null, "Holiday deleted.");
        }
        return ok(null);
    }

    // ── helpers ──────────────────────────────────────────────────────

    private static String ruleKey(String source, String carrier, Long warehouseId) {
        return (source == null ? "*" : source.toUpperCase()) + "|"
                + (carrier == null ? "*" : carrier.toUpperCase()) + "|"
                + (warehouseId == null ? "*" : warehouseId.toString());
    }

    private String actor(Authentication a) {
        return a == null ? "unknown" : a.getName();
    }

    private <T> ResponseEntity<ApiResponse<T>> ok(T body) {
        return ResponseEntity.ok(ApiResponse.<T>builder()
                .status("SUCCESS").code(200).timestamp(LocalDateTime.now())
                .data(body).build());
    }

    private LocalDate today() { return LocalDate.now(); }
}
