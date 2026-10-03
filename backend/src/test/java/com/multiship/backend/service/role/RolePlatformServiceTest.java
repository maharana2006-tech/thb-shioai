package com.multiship.backend.service.role;

import com.multiship.backend.model.RoleEntity;
import com.multiship.backend.repository.RoleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** V115 — bootstrap defaults vs DB-driven swap behaviour. */
class RolePlatformServiceTest {

    private RoleRepository repo;
    private RolePlatformService service;

    @BeforeEach
    void setUp() {
        repo = mock(RoleRepository.class);
        service = new RolePlatformService(repo);
    }

    @Test
    void bootstrapDefaultsMatchPreV115Behaviour() {
        // No reload called — the volatile fields hold their initializer values.
        assertTrue(service.isValidRole("USER"));
        assertTrue(service.isValidRole("TENANT"));
        assertTrue(service.isValidRole("ADMIN"));
        assertFalse(service.isValidRole("REPORTING"));

        assertTrue(service.isInvitableRole("USER"));
        assertTrue(service.isInvitableRole("TENANT"));
        assertFalse(service.isInvitableRole("ADMIN"), "ADMIN must stay non-invitable");
    }

    @Test
    void reloadSwapsFromDbRows() {
        when(repo.findAll()).thenReturn(List.of(
                RoleEntity.builder().code("USER").isInvitable(true).build(),
                RoleEntity.builder().code("ADMIN").isInvitable(false).build(),
                // DB-added role the Java default doesn't know about.
                RoleEntity.builder().code("REPORTING").isInvitable(true).build()));
        service.reload();
        assertTrue(service.isValidRole("REPORTING"));
        assertTrue(service.isInvitableRole("reporting"));  // case-insensitive
        assertFalse(service.isValidRole("TENANT"),
                "TENANT dropped from the DB → no longer valid after reload");
    }

    @Test
    void reloadWithEmptyRowsKeepsBootstrap() {
        when(repo.findAll()).thenReturn(List.of());
        service.reload();
        assertTrue(service.isValidRole("USER"));
        assertTrue(service.isValidRole("TENANT"));
    }

    @Test
    void reloadSwallowsRepoFailure() {
        when(repo.findAll()).thenThrow(new RuntimeException("DB down"));
        service.reload();  // must not throw
        assertTrue(service.isValidRole("USER"));
    }

    @Test
    void isValidRoleIsCaseInsensitive() {
        assertTrue(service.isValidRole("user"));
        assertTrue(service.isValidRole("Admin"));
        assertTrue(service.isValidRole(" tenant "));
    }
}
