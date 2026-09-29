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
        return ok(ruleRepo.save(req));
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
        return ok(ruleRepo.save(existing));
    }

    @DeleteMapping("/rules/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteRule(@PathVariable Long id) {
        ruleRepo.deleteById(id);
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
        return ok(holidayRepo.save(req));
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
        return ok(holidayRepo.save(existing));
    }

    @DeleteMapping("/holidays/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteHoliday(@PathVariable Long id) {
        holidayRepo.deleteById(id);
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
