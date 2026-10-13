package com.multiship.backend.controller;

import com.multiship.backend.model.RoleEntity;
import com.multiship.backend.repository.RoleRepository;
import com.multiship.backend.repository.UserRepository;
import com.multiship.backend.service.role.RolePlatformService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Admin surface over the role registry. Built-in roles (SUPERADMIN / ADMIN /
 * USER / TENANT) are read-only. Custom roles can be created, edited and
 * deleted: each has a base role (its backend security floor) and a set of
 * module keys that decide which pages show in the UI.
 */
@Tag(name = "Role registry", description = "Platform roles. Admin-only. Built-in roles are read-only.")
@RestController
@RequestMapping("/api/v1/admin/roles")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminRolesController {

    private static final Set<String> BUILTIN = Set.of("SUPERADMIN", "ADMIN", "USER", "TENANT");
    private static final Set<String> BASE_ROLES = Set.of("ADMIN", "USER", "TENANT");

    private final RoleRepository repo;
    private final UserRepository users;
    private final RolePlatformService rolePlatform;

    public record RoleView(String code, String name, boolean isInvitable, String baseRole,
                           boolean builtin, List<String> modules, LocalDateTime updatedAt) {}

    public record RoleUpsert(String code, @NotBlank String name, String baseRole,
                             Boolean invitable, List<String> modules) {}

    @Operation(summary = "List every role with its base role and modules.")
    @GetMapping
    public List<RoleView> list() {
        return repo.findAll().stream()
                .map(AdminRolesController::toView)
                .sorted(Comparator.comparing((RoleView v) -> !v.builtin()).thenComparing(RoleView::code))
                .toList();
    }

    @Operation(summary = "Create a custom role.")
    @PostMapping
    public ResponseEntity<RoleView> create(@Valid @RequestBody RoleUpsert req) {
        String code = normalizeCode(req.code());
        if (BUILTIN.contains(code)) throw conflict("That is a built-in role.");
        if (repo.existsById(code)) throw conflict("A role with that code already exists.");
        String base = requireBase(req.baseRole());

        RoleEntity role = new RoleEntity();
        role.setCode(code);
        role.setName(req.name().trim());
        role.setIsInvitable(req.invitable() == null || req.invitable());
        role.setBaseRole(base);
        role.setModules(cleanModules(req.modules()));
        role.setCreatedAt(LocalDateTime.now());
        role.setUpdatedAt(LocalDateTime.now());
        repo.save(role);
        rolePlatform.reload();
        return ResponseEntity.status(HttpStatus.CREATED).body(toView(role));
    }

    @Operation(summary = "Update a custom role.")
    @PutMapping("/{code}")
    public RoleView update(@PathVariable String code, @Valid @RequestBody RoleUpsert req) {
        RoleEntity role = customOrThrow(code);
        role.setName(req.name().trim());
        if (req.invitable() != null) role.setIsInvitable(req.invitable());
        if (req.baseRole() != null && !req.baseRole().isBlank()) role.setBaseRole(requireBase(req.baseRole()));
        role.setModules(cleanModules(req.modules()));
        role.setUpdatedAt(LocalDateTime.now());
        repo.save(role);
        rolePlatform.reload();
        return toView(role);
    }

    @Operation(summary = "Delete a custom role (only when no user has it).")
    @DeleteMapping("/{code}")
    public ResponseEntity<Void> delete(@PathVariable String code) {
        RoleEntity role = customOrThrow(code);
        if (users.countByRoleIgnoreCase(role.getCode()) > 0) {
            throw conflict("Some users still have this role. Reassign them before deleting it.");
        }
        repo.delete(role);
        rolePlatform.reload();
        return ResponseEntity.noContent().build();
    }

    // ---- helpers ----

    private RoleEntity customOrThrow(String rawCode) {
        String code = normalizeCode(rawCode);
        if (BUILTIN.contains(code)) throw conflict("Built-in roles can't be changed.");
        return repo.findById(code).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No role " + code + "."));
    }

    private static String normalizeCode(String code) {
        String c = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        if (!c.matches("^[A-Z][A-Z0-9_]{1,31}$")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Role code must be 2–32 chars: a letter then letters, digits or underscores.");
        }
        return c;
    }

    private static String requireBase(String baseRole) {
        String b = baseRole == null ? "" : baseRole.trim().toUpperCase(Locale.ROOT);
        if (!BASE_ROLES.contains(b)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Base role must be ADMIN, USER or TENANT.");
        }
        return b;
    }

    private static Set<String> cleanModules(List<String> modules) {
        if (modules == null) return Set.of();
        return modules.stream().filter(m -> m != null && !m.isBlank())
                .map(String::trim).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static ResponseStatusException conflict(String msg) {
        return new ResponseStatusException(HttpStatus.CONFLICT, msg);
    }

    private static RoleView toView(RoleEntity r) {
        List<String> mods = new ArrayList<>(r.getModules() == null ? Set.of() : r.getModules());
        mods.sort(Comparator.naturalOrder());
        return new RoleView(r.getCode(), r.getName(), Boolean.TRUE.equals(r.getIsInvitable()),
                r.getBaseRole(), BUILTIN.contains(r.getCode().toUpperCase(Locale.ROOT)), mods, r.getUpdatedAt());
    }
}
