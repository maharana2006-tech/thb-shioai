package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.model.DtcSchedulerRun;
import com.multiship.backend.service.dtc.scheduler.DtcSchedulerAdminService;
import com.multiship.backend.service.dtc.scheduler.DtcSchedulerAdminService.JobDto;
import com.multiship.backend.service.dtc.scheduler.DtcSchedulerAdminService.JobUpdate;
import com.multiship.backend.service.dtc.scheduler.DtcSchedulerAdminService.OverviewDto;
import com.multiship.backend.service.dtc.scheduler.DtcSchedulerAdminService.SettingsDto;
import com.multiship.backend.service.dtc.scheduler.DtcSchedulerAdminService.SettingsUpdate;
import com.multiship.backend.service.dtc.scheduler.DtcSchedulerEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * V132 admin — the DB-driven DTC scheduler. FE surface is
 * /settings/dtc-scheduler (Settings → Integrations → DTC Scheduler).
 */
@RestController
@RequestMapping("/api/v1/admin/dtc-scheduler")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class DtcSchedulerAdminController {

    private final DtcSchedulerAdminService service;
    private final DtcSchedulerEngine engine;

    @GetMapping
    public ResponseEntity<ApiResponse<OverviewDto>> overview() {
        return ok(service.overview());
    }

    @PutMapping("/settings")
    public ResponseEntity<ApiResponse<SettingsDto>> updateSettings(@RequestBody SettingsUpdate req, Authentication auth) {
        return ok(service.updateSettings(req, actor(auth)));
    }

    @PutMapping("/jobs/{jobKey}")
    public ResponseEntity<ApiResponse<JobDto>> updateJob(@PathVariable String jobKey,
                                                         @RequestBody JobUpdate req,
                                                         Authentication auth) {
        return ok(service.updateJob(jobKey, req, actor(auth)));
    }

    @PostMapping("/jobs/{jobKey}/run-now")
    public ResponseEntity<ApiResponse<Void>> runNow(@PathVariable String jobKey, Authentication auth) {
        engine.runNow(jobKey, actor(auth));
        return ok(null);
    }

    @GetMapping("/jobs/{jobKey}/runs")
    public ResponseEntity<ApiResponse<List<DtcSchedulerRun>>> runs(@PathVariable String jobKey,
                                                                   @RequestParam(defaultValue = "50") int limit) {
        return ok(service.runs(jobKey, limit));
    }

    @PostMapping("/reset-defaults")
    public ResponseEntity<ApiResponse<OverviewDto>> resetDefaults(Authentication auth) {
        return ok(service.resetDefaults(actor(auth)));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> bad(IllegalArgumentException ex) {
        return error(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ApiResponse<Void>> missing(NoSuchElementException ex) {
        return error(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    private String actor(Authentication a) {
        return a == null ? "unknown" : a.getName();
    }

    private <T> ResponseEntity<ApiResponse<T>> ok(T body) {
        return ResponseEntity.ok(ApiResponse.<T>builder()
                .status("SUCCESS").code(200).timestamp(LocalDateTime.now())
                .data(body).build());
    }

    private static ResponseEntity<ApiResponse<Void>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(ApiResponse.<Void>builder().status("ERROR").code(status.value())
                .timestamp(LocalDateTime.now()).errorCode(ErrorCode.VALIDATION_ERROR.name()).message(message).build());
    }
}
