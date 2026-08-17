package com.multiship.backend.service;

import com.multiship.backend.config.AccessScopePolicy;
import com.multiship.backend.model.User;
import com.multiship.backend.model.UserAdminAudit;
import com.multiship.backend.repository.ClientRepository;
import com.multiship.backend.repository.UserAdminAuditRepository;
import com.multiship.backend.repository.UserRepository;
import com.multiship.backend.service.AdminUserService.ActionResult;
import com.multiship.backend.service.AdminUserService.MutationOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminUserServiceTest {

    private UserRepository userRepo;
    private UserAdminAuditRepository auditRepo;
    private ClientRepository clientRepo;
    private AdminUserService service;

    @BeforeEach
    void setUp() {
        userRepo = mock(UserRepository.class);
        auditRepo = mock(UserAdminAuditRepository.class);
        clientRepo = mock(ClientRepository.class);
        // Sprint 50 Tier 0.5 PR E - enforcer with flag OFF is a pass-through.
        // Sprint 51 T2 finding #5 — pass a mock TokenRevocationService.
        // Real one would need Redis + Caffeine; mock is transparent since
        // bumpTokenVersion is invoked via side-effect and the tests here
        // don't assert on tv (see TokenRevocationServiceTest for that).
        service = new AdminUserService(userRepo, auditRepo, clientRepo,
                new TenantScopeEnforcer(new AccessScopePolicy(false)),
                mock(com.multiship.backend.service.TokenRevocationService.class));
    }

    private User legacyUser() {
        return User.builder()
                .id(42L).username("acmeuser").email("ops@acme.com")
                .fullName("Ops One").role("USER").clientCode(null)
                .emailVerified(true).build();
    }

    @Test
    void assignClient_missingUser_returnsNotFound() {
        when(userRepo.findById(1L)).thenReturn(Optional.empty());
        MutationOutcome out = service.assignClient(1L, "ACME", "backfill", "admin");
        assertEquals(ActionResult.USER_NOT_FOUND, out.result());
        verify(auditRepo, never()).save(any());
    }

    /* -------- Sprint 50 Tier 0.5 PR E: tenant-scope -------- */

    @Test
    void scopedUserCannotAssignForeignTenant() {
        // A scoped USER (ACME) that somehow reaches this service (bypassing
        // the ADMIN-only controller gate) must be rejected before the DB
        // write. Real ADMIN callers are operators → pass-through.
        var authorities = List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER"));
        var principal = org.springframework.security.core.userdetails.User
                .withUsername("acmeuser").password("").authorities(authorities).build();
        var token = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                principal, null, authorities);
        token.setDetails(new com.multiship.backend.config.JwtAuthenticationFilter.AuthDetails("ACME"));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(token);
        try {
            AdminUserService scopedService = new AdminUserService(userRepo, auditRepo, clientRepo,
                    new TenantScopeEnforcer(new AccessScopePolicy(true)),
                    mock(com.multiship.backend.service.TokenRevocationService.class));
            // Prime findById so we reach the clamp (else USER_NOT_FOUND short-
            // circuits before tenantScope is consulted).
            when(userRepo.findById(42L)).thenReturn(Optional.of(legacyUser()));

            org.junit.jupiter.api.Assertions.assertThrows(
                    org.springframework.security.access.AccessDeniedException.class,
                    () -> scopedService.assignClient(42L, "OTHER", "leak-test", "acmeuser"));
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @Test
    void assignClient_missingTargetClient_returnsClientNotFound() {
        when(userRepo.findById(42L)).thenReturn(Optional.of(legacyUser()));
        when(clientRepo.existsByClientCodeIgnoreCase("GHOST")).thenReturn(false);

        MutationOutcome out = service.assignClient(42L, "GHOST", "typo", "admin");
        assertEquals(ActionResult.CLIENT_NOT_FOUND, out.result());
        verify(auditRepo, never()).save(any());
    }

    @Test
    void assignClient_success_writesAuditRow() {
        User u = legacyUser();
        when(userRepo.findById(42L)).thenReturn(Optional.of(u));
        when(clientRepo.existsByClientCodeIgnoreCase("ACME")).thenReturn(true);

        MutationOutcome out = service.assignClient(42L, "acme", "rollout", "admin");
        assertEquals(ActionResult.OK, out.result());
        assertEquals("ACME", u.getClientCode()); // normalised uppercase
        assertNotNull(out.user());

        ArgumentCaptor<UserAdminAudit> cap = ArgumentCaptor.forClass(UserAdminAudit.class);
        verify(auditRepo).save(cap.capture());
        UserAdminAudit row = cap.getValue();
        assertEquals("ASSIGN_CLIENT", row.getAction());
        assertEquals(null, row.getOldClientCode());
        assertEquals("ACME", row.getNewClientCode());
        assertEquals("admin", row.getActorUsername());
        assertEquals("rollout", row.getReason());
    }

    @Test
    void assignClient_clearsAssignment_whenBlank() {
        User u = legacyUser();
        u.setClientCode("ACME");
        when(userRepo.findById(42L)).thenReturn(Optional.of(u));

        MutationOutcome out = service.assignClient(42L, "  ", "revert", "admin");
        assertEquals(ActionResult.OK, out.result());
        assertNull(u.getClientCode());
    }

    @Test
    void assignClient_sameValue_isNoop() {
        User u = legacyUser();
        u.setClientCode("ACME");
        when(userRepo.findById(42L)).thenReturn(Optional.of(u));
        when(clientRepo.existsByClientCodeIgnoreCase("ACME")).thenReturn(true);

        MutationOutcome out = service.assignClient(42L, "ACME", null, "admin");
        assertEquals(ActionResult.ALREADY_IN_TARGET_STATE, out.result());
        verify(auditRepo, never()).save(any());
    }

    @Test
    void deactivate_setsTimestampAndAudits() {
        User u = legacyUser();
        when(userRepo.findById(42L)).thenReturn(Optional.of(u));

        MutationOutcome out = service.deactivate(42L, "no longer employed", "admin");
        assertEquals(ActionResult.OK, out.result());
        assertNotNull(u.getDeactivatedAt());
        assertEquals("admin", u.getDeactivatedBy());
        verify(auditRepo).save(any(UserAdminAudit.class));
    }

    /* -------- Sprint 55 audit #292: last-admin protection -------- */

    @Test
    void deactivate_lastActiveAdmin_isRejected_orgLockoutGuard() {
        User admin = User.builder().id(1L).username("only-admin")
                .email("a@a").fullName("Only Admin").role("ADMIN")
                .emailVerified(true).build();
        when(userRepo.findById(1L)).thenReturn(Optional.of(admin));
        // Only one active admin remains — deactivating them would lock the org out.
        when(userRepo.countByRoleIgnoreCaseAndDeactivatedAtIsNull("ADMIN")).thenReturn(1L);

        MutationOutcome out = service.deactivate(1L, "self", "only-admin");

        assertEquals(ActionResult.LAST_ADMIN_CANNOT_DEACTIVATE, out.result());
        // Row must NOT be mutated — timestamps stay null.
        assertNull(admin.getDeactivatedAt());
        assertNull(admin.getDeactivatedBy());
        // No audit row for a rejected op (mutation didn't happen).
        verify(auditRepo, never()).save(any());
    }

    @Test
    void deactivate_secondToLastAdmin_isAllowed_whenOtherActiveAdminExists() {
        User admin = User.builder().id(2L).username("admin-b")
                .email("b@a").fullName("Admin B").role("ADMIN")
                .emailVerified(true).build();
        when(userRepo.findById(2L)).thenReturn(Optional.of(admin));
        // Two active admins → deactivating one leaves one; safe.
        when(userRepo.countByRoleIgnoreCaseAndDeactivatedAtIsNull("ADMIN")).thenReturn(2L);

        MutationOutcome out = service.deactivate(2L, "no longer needed", "admin-a");

        assertEquals(ActionResult.OK, out.result());
        assertNotNull(admin.getDeactivatedAt());
    }

    @Test
    void deactivate_nonAdmin_skipsTheLastAdminCheck() {
        // A USER row should not query the admin quorum at all.
        User u = legacyUser();
        when(userRepo.findById(42L)).thenReturn(Optional.of(u));

        MutationOutcome out = service.deactivate(42L, "gone", "admin");

        assertEquals(ActionResult.OK, out.result());
        // The countByRole query MUST NOT be called for a non-ADMIN.
        verify(userRepo, never()).countByRoleIgnoreCaseAndDeactivatedAtIsNull(anyString());
    }

    @Test
    void deactivate_admin_isCaseInsensitiveOnRole() {
        // Some legacy rows may store 'admin' lowercase; the guard must still fire.
        User admin = User.builder().id(3L).username("mixed-case")
                .email("c@a").fullName("Mixed").role("admin")  // lowercase
                .emailVerified(true).build();
        when(userRepo.findById(3L)).thenReturn(Optional.of(admin));
        when(userRepo.countByRoleIgnoreCaseAndDeactivatedAtIsNull("ADMIN")).thenReturn(1L);

        MutationOutcome out = service.deactivate(3L, "self", "mixed-case");

        assertEquals(ActionResult.LAST_ADMIN_CANNOT_DEACTIVATE, out.result());
    }

    @Test
    void deactivate_alreadyDeactivated_isNoop() {
        User u = legacyUser();
        u.setDeactivatedAt(LocalDateTime.now());
        when(userRepo.findById(42L)).thenReturn(Optional.of(u));

        MutationOutcome out = service.deactivate(42L, null, "admin");
        assertEquals(ActionResult.ALREADY_IN_TARGET_STATE, out.result());
        verify(auditRepo, never()).save(any());
    }

    @Test
    void reactivate_clearsFieldsAndAudits() {
        User u = legacyUser();
        u.setDeactivatedAt(LocalDateTime.now());
        u.setDeactivatedBy("previousAdmin");
        when(userRepo.findById(42L)).thenReturn(Optional.of(u));

        MutationOutcome out = service.reactivate(42L, "rehired", "admin");
        assertEquals(ActionResult.OK, out.result());
        assertNull(u.getDeactivatedAt());
        assertNull(u.getDeactivatedBy());
        verify(auditRepo).save(any(UserAdminAudit.class));
    }

    @Test
    void list_filtersByRoleClientAndActiveOnly() {
        // Sprint 51 BP-L4 — filters push to the DB via a Specification +
        // PageRequest; the service no longer scans in-JVM. This test now
        // asserts the service *asks* the repo for a paged filtered slice,
        // and returns whatever the repo hands back. See
        // AdminUserServicePaginationTest for the size-clamp / paging
        // semantics coverage.
        User a = User.builder().id(1L).username("admin1").email("a@x").fullName("A")
                .role("ADMIN").build();
        User b = User.builder().id(2L).username("acmeuser").email("b@acme").fullName("B")
                .role("USER").clientCode("ACME").build();
        User c = User.builder().id(3L).username("betauser").email("c@beta").fullName("C")
                .role("USER").clientCode("BETA")
                .deactivatedAt(LocalDateTime.now()).build();
        when(userRepo.findAll(
                org.mockito.ArgumentMatchers.<org.springframework.data.jpa.domain.Specification<User>>any(),
                org.mockito.ArgumentMatchers.any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(a, b, c)));

        assertEquals(3, service.list("acme", null, null, null).size());
        assertEquals(3, service.list(null, "USER", null, null).size());
        assertEquals(3, service.list(null, "USER", "ACME", null).size());
        assertEquals(3, service.list(null, null, null, true).size());
    }

    @Test
    void list_clampsSizeToMax() {
        when(userRepo.findAll(
                org.mockito.ArgumentMatchers.<org.springframework.data.jpa.domain.Specification<User>>any(),
                org.mockito.ArgumentMatchers.any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of()));

        // Ask for a huge page — the service must clamp to MAX_PAGE_SIZE.
        service.list(null, null, null, null, 0, 10_000);

        ArgumentCaptor<org.springframework.data.domain.Pageable> cap =
                ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
        verify(userRepo).findAll(
                org.mockito.ArgumentMatchers.<org.springframework.data.jpa.domain.Specification<User>>any(),
                cap.capture());
        assertEquals(AdminUserService.MAX_PAGE_SIZE, cap.getValue().getPageSize());
    }
}
