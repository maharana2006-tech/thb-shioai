package com.multiship.backend.integration;

import com.multiship.backend.config.JwtAuthenticationFilter;
import com.multiship.backend.model.AuditLog;
import com.multiship.backend.repository.AuditLogRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 51 follow-up BS-M3 — end-to-end proof that the audit-log scope
 * filter (persisted client_code column + repository-layer predicate)
 * correctly partitions rows per tenant:
 *
 * <ul>
 *   <li>{@code findByClientCode} returns only rows for that tenant.</li>
 *   <li>{@code search} for a tenant-scoped USER returns only their tenant's
 *       rows — never another tenant's, never system-event NULL rows.</li>
 *   <li>{@code search} for an ADMIN returns every row including NULL.</li>
 * </ul>
 *
 * <p>Requires the tenant-scope flag on so USER is treated as tenant-scoped
 * rather than a legacy platform operator. Guarded by {@code INTEGRATION_TESTS=1}
 * via {@link AbstractIntegrationTest}.
 */
@TestPropertySource(properties = "access.scope-user-by-client=true")
class AuditLogScopeIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private AuditLogRepository repo;

    @BeforeEach
    void seed() {
        // Delete only rows this test created — other integration tests may
        // seed their own via AuditService. Match on the audit action prefix
        // we use below.
        repo.findAll().stream()
                .filter(r -> r.getEntityKey() != null && r.getEntityKey().startsWith("BSM3_"))
                .forEach(r -> repo.deleteById(r.getId()));

        repo.save(row("acmeuser",  "ACME",  "BSM3_ACME_1"));
        repo.save(row("acmeuser",  "ACME",  "BSM3_ACME_2"));
        repo.save(row("otheruser", "OTHER", "BSM3_OTHER_1"));
        repo.save(row(null,        null,    "BSM3_SYSTEM_1"));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /** Direct derived query — sanity check that the column is stored + queryable. */
    @Test
    void findByClientCode_returnsOnlyTenantRows() {
        Page<AuditLog> acme = repo.findByClientCode("ACME",
                PageRequest.of(0, 50, Sort.by(Sort.Direction.DESC, "createdAt")));

        assertEquals(2, acme.getTotalElements(),
                "Only the two ACME rows should match, not OTHER or the NULL system row.");
        assertTrue(acme.getContent().stream().allMatch(r -> "ACME".equals(r.getClientCode())));
    }

    /** Scoped USER search: strict tenant partition — no OTHER, no NULL system row. */
    @Test
    void search_scopedUser_seesOnlyOwnTenant() {
        putScopedUserInContext("acmeuser", "ACME");

        Page<AuditLog> visible = repo.search("", "", "", "",
                LocalDateTime.of(1970, 1, 1, 0, 0),
                LocalDateTime.of(9999, 12, 31, 23, 59),
                PageRequest.of(0, 50, Sort.by(Sort.Direction.DESC, "createdAt")));

        // Filter to just the rows this test seeded so other integration
        // tests' fixtures don't skew the count.
        List<AuditLog> ours = visible.getContent().stream()
                .filter(r -> r.getEntityKey() != null && r.getEntityKey().startsWith("BSM3_"))
                .toList();
        assertEquals(2, ours.size(),
                "ACME-scoped USER must see exactly the two ACME rows we seeded, "
                        + "never OTHER's row or the NULL system row.");
        assertTrue(ours.stream().allMatch(r -> "ACME".equals(r.getClientCode())));
    }

    /** ADMIN search: no scope predicate — every row is visible, including NULL system rows. */
    @Test
    void search_admin_seesEveryRowIncludingSystemNulls() {
        putAdminInContext("root");

        Page<AuditLog> visible = repo.search("", "", "", "",
                LocalDateTime.of(1970, 1, 1, 0, 0),
                LocalDateTime.of(9999, 12, 31, 23, 59),
                PageRequest.of(0, 50, Sort.by(Sort.Direction.DESC, "createdAt")));

        List<AuditLog> ours = visible.getContent().stream()
                .filter(r -> r.getEntityKey() != null && r.getEntityKey().startsWith("BSM3_"))
                .toList();
        assertEquals(4, ours.size(),
                "ADMIN must see every row: two ACME, one OTHER, one NULL system.");
        assertTrue(ours.stream().anyMatch(r -> r.getClientCode() == null),
                "ADMIN must see NULL-clientCode system rows too.");
    }

    private static AuditLog row(String actor, String clientCode, String entityKey) {
        return AuditLog.builder()
                .actor(actor)
                .action("CREATE")
                .entityType("CLIENT")
                .entityKey(entityKey)
                .clientCode(clientCode)
                .build();
    }

    private static void putScopedUserInContext(String username, String clientCode) {
        Authentication auth = new UsernamePasswordAuthenticationToken(
                username, "",
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        ((UsernamePasswordAuthenticationToken) auth)
                .setDetails(new JwtAuthenticationFilter.AuthDetails(clientCode));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private static void putAdminInContext(String username) {
        Authentication auth = new UsernamePasswordAuthenticationToken(
                username, "",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
