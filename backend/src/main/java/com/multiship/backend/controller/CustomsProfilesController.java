package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ClientCustomsProfileDTO;
import com.multiship.backend.service.ClientCustomsProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Master list of every client's importer/broker profiles for the management page. */
@Tag(name = "Customs", description = "Master list of importer/broker profiles across clients")
@RestController
@RequestMapping("/api/v1/customs-profiles")
@RequiredArgsConstructor
public class CustomsProfilesController {

    private final ClientCustomsProfileService service;

    @Operation(summary = "List all importer/broker profiles across clients")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping
    public ResponseEntity<ApiResponse<List<ClientCustomsProfileDTO>>> listAll() {
        ApiResponse<List<ClientCustomsProfileDTO>> r = service.listAll();
        return ResponseEntity.status(r.getCode()).body(r);
    }
}
