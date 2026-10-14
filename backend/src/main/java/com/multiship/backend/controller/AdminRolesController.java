package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.repository.RoleRepository;
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

/** V115 — read-only admin surface over the role registry. */
@Tag(name = "Role registry", description = "Platform-wide role list. Admin-only, read-only in V115.")
@RestController
@RequestMapping("/api/v1/admin/roles")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminRolesController {

    private final RoleRepository repo;

    @Operation(summary = "List every role in the registry.")
    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list() {
        List<Map<String, Object>> body = repo.findAll().stream().map(r -> {
            Map<String, Object> m = new HashMap<>();
            m.put("code", r.getCode());
            m.put("name", r.getName());
            m.put("isInvitable", Boolean.TRUE.equals(r.getIsInvitable()));
            m.put("updatedAt", r.getUpdatedAt());
            return m;
        }).toList();
        return ResponseEntity.ok(ApiResponse.<List<Map<String, Object>>>builder()
                .status("SUCCESS").code(200).data(body).timestamp(LocalDateTime.now()).build());
    }
}
