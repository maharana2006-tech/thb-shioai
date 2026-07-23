package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ClientCustomsProfileDTO;
import com.multiship.backend.dto.CustomsProfileFilters;
import com.multiship.backend.dto.PageResponseDTO;
import com.multiship.backend.service.ClientCustomsProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Master list of every client's importer/broker profiles for the management page. */
@Tag(name = "Customs", description = "Master list of importer/broker profiles across clients")
@RestController
@RequestMapping("/api/v1/customs-profiles")
@RequiredArgsConstructor
public class CustomsProfilesController {

    private final ClientCustomsProfileService service;

    @Operation(summary = "List importer/broker profiles",
            description = "Paginated + filtered + sorted; region filtering is done client-side by expanding a region to its ISO-2 country codes.")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponseDTO<ClientCustomsProfileDTO>>> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String clientCode,
            @RequestParam(required = false) String carrier,
            @RequestParam(required = false) String broker,
            @RequestParam(required = false) List<String> countries,
            @RequestParam(defaultValue = "client") String sortBy,
            @RequestParam(defaultValue = "ASC") String sortDirection,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        CustomsProfileFilters filters = CustomsProfileFilters.builder()
                .search(search).clientCode(clientCode).carrier(carrier).broker(broker)
                .countries(countries)
                .sortBy(sortBy).sortDirection(sortDirection)
                .page(page).size(size)
                .build();
        ApiResponse<PageResponseDTO<ClientCustomsProfileDTO>> r = service.listPaginated(filters);
        return ResponseEntity.status(r.getCode()).body(r);
    }

    @Operation(summary = "Aggregate counts for the Importer/Broker page's health strip",
            description = "Returns: profiles, destinationsCovered, clientsConfigured.")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<Map<String, Long>>> stats() {
        ApiResponse<Map<String, Long>> r = service.getStats();
        return ResponseEntity.status(r.getCode()).body(r);
    }

    @Operation(summary = "Export every filtered profile as CSV",
            description = "Same filters as GET /customs-profiles; ignores page/size and streams the full result.")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping(value = "/export.csv", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<byte[]> exportCsv(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String clientCode,
            @RequestParam(required = false) String carrier,
            @RequestParam(required = false) String broker,
            @RequestParam(required = false) List<String> countries,
            @RequestParam(defaultValue = "client") String sortBy,
            @RequestParam(defaultValue = "ASC") String sortDirection) {
        CustomsProfileFilters filters = CustomsProfileFilters.builder()
                .search(search).clientCode(clientCode).carrier(carrier).broker(broker)
                .countries(countries)
                .sortBy(sortBy).sortDirection(sortDirection)
                .page(0).size(1)
                .build();
        String csv = service.exportProfilesCsv(filters);
        byte[] body = ("﻿" + csv).getBytes(StandardCharsets.UTF_8);
        String filename = "customs-profiles-" + LocalDate.now() + ".csv";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(body);
    }
}
