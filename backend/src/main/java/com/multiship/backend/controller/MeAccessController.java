package com.multiship.backend.controller;

import com.multiship.backend.model.RoleEntity;
import com.multiship.backend.model.User;
import com.multiship.backend.repository.RoleRepository;
import com.multiship.backend.repository.UserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The current user's effective access, for the SPA to gate its nav and routes.
 * For a built-in role baseRole is null and modules is empty (the UI uses its
 * built-in gating); for a custom role, baseRole is the inherited built-in role
 * and modules is the curated list of pages it may see.
 */
@Tag(name = "Me", description = "The signed-in user's own access.")
@RestController
@RequestMapping("/api/v1/me")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class MeAccessController {

    private final UserRepository users;
    private final RoleRepository roles;

    public record AccessView(String role, String baseRole, List<String> modules) {}

    @Operation(summary = "The current user's role, base role and visible modules.")
    @GetMapping("/access")
    public AccessView access() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String username = auth == null ? null : auth.getName();
        String role = username == null ? null
                : users.findByUsername(username).map(User::getRole).orElse(null);
        if (role == null && auth != null) {
            role = auth.getAuthorities().stream().findFirst()
                    .map(a -> a.getAuthority().replaceFirst("^ROLE_", "")).orElse(null);
        }
        RoleEntity re = role == null ? null : roles.findById(role.toUpperCase(Locale.ROOT)).orElse(null);
        String baseRole = re == null ? null : re.getBaseRole();
        List<String> modules = (re == null || re.getModules() == null)
                ? List.of() : new ArrayList<>(re.getModules());
        return new AccessView(role, baseRole, modules);
    }
}
