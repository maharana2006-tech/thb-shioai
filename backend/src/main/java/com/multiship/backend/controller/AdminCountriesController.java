package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.repository.CountryRepository;
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

/** V111 — read-only admin surface over the country table. */
@Tag(name = "Country", description = "Platform-wide country list + is_us_territory flag. Admin-only, read-only.")
@RestController
@RequestMapping("/api/v1/admin/countries")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminCountriesController {

    private final CountryRepository repo;

    @Operation(summary = "List every seeded country row.")
    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list() {
        List<Map<String, Object>> body = repo.findAll().stream().map(r -> {
            Map<String, Object> m = new HashMap<>();
            m.put("countryCode", r.getCountryCode());
            m.put("name", r.getName());
            m.put("isUsTerritory", Boolean.TRUE.equals(r.getIsUsTerritory()));
            m.put("updatedAt", r.getUpdatedAt());
            return m;
        }).toList();
        return ResponseEntity.ok(ApiResponse.<List<Map<String, Object>>>builder()
                .status("SUCCESS").code(200).data(body).timestamp(LocalDateTime.now()).build());
    }
}
