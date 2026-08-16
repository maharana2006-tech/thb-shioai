package com.multiship.backend.integration;

import com.multiship.backend.model.LabelTemplate;
import com.multiship.backend.repository.LabelTemplateRepository;
import com.multiship.backend.service.LabelTemplateService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 53 templates-be-integration — full label-template lifecycle
 * exercised against real Postgres. Covers `/settings/templates` write +
 * read + resolve paths end-to-end.
 *
 * <p>Anti-fallback: reuses {@link MockCarrierConnectorsTestConfig} +
 * {@link ForbidOutboundHttpTestConfig} so no carrier IO is possible.
 * Label-template CRUD is pure DB (no HTTP path) — belt-and-suspenders
 * for future refactors.
 *
 * <p>Rows namespaced with the {@link #TENANT_PREFIX} so re-runs stay
 * clean.
 *
 * <p>Guarded by {@code INTEGRATION_TESTS=1}.
 */
@Import({ForbidOutboundHttpTestConfig.class, MockCarrierConnectorsTestConfig.class})
@TestPropertySource(properties = "spring.main.allow-bean-definition-overriding=true")
class LabelTemplateLifecycleIntegrationTest extends AbstractIntegrationTest {

    private static final String TENANT_PREFIX = "LTIT_";

    @Autowired
    private LabelTemplateService service;
    @Autowired
    private LabelTemplateRepository repo;

    @BeforeEach
    void setUp() {
        // Wipe this class' rows so re-runs start clean.
        repo.findAll().stream()
                .filter(t -> t.getTenantId() != null && t.getTenantId().startsWith(TENANT_PREFIX))
                .forEach(t -> repo.deleteById(t.getId()));

        // Also wipe any platform-scoped rows from prior runs (identify by header prefix).
        repo.findAll().stream()
                .filter(t -> t.getTenantId() == null
                        && t.getHeaderText() != null && t.getHeaderText().startsWith(TENANT_PREFIX))
                .forEach(t -> repo.deleteById(t.getId()));

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin-it", "",
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ================ helpers ================

    private LabelTemplate template(String tenantId, String type, String header) {
        LabelTemplate t = new LabelTemplate();
        t.setTenantId(tenantId);
        t.setTemplateType(type);
        t.setHeaderText(header);
        t.setFooterText("Footer");
        return t;
    }

    // ================ 1. CREATE + LIST ================

    @Test
    void create_persistsRow_andListIncludesIt() {
        LabelTemplate saved = service.save(template(TENANT_PREFIX + "A", "PACKING_SLIP", TENANT_PREFIX + "hdr"));

        assertNotNull(saved.getId());
        assertNotNull(saved.getCreatedAt());
        assertNotNull(saved.getUpdatedAt());
        assertEquals(TENANT_PREFIX + "A", saved.getTenantId());

        Page<LabelTemplate> page = service.list(null, null, null,
                PageRequest.of(0, 100, Sort.by(Sort.Direction.DESC, "updatedAt")));
        assertTrue(page.getContent().stream().anyMatch(t -> saved.getId().equals(t.getId())));
    }

    // ================ 2. UPDATE preserves createdAt ================

    @Test
    void update_preservesCreatedAt_advancesUpdatedAt() throws Exception {
        LabelTemplate saved = service.save(template(TENANT_PREFIX + "UPD", "PACKING_SLIP", TENANT_PREFIX + "orig"));
        var originalCreated = saved.getCreatedAt();
        var originalUpdated = saved.getUpdatedAt();

        // 1ms guarantee for the LocalDateTime comparison.
        Thread.sleep(2);
        saved.setHeaderText(TENANT_PREFIX + "edited");
        LabelTemplate updated = service.save(saved);

        assertEquals(saved.getId(), updated.getId());
        assertEquals(originalCreated, updated.getCreatedAt(),
                "createdAt must survive across updates.");
        assertTrue(updated.getUpdatedAt().isAfter(originalUpdated),
                "updatedAt must advance on update.");
        assertEquals(TENANT_PREFIX + "edited", updated.getHeaderText());
    }

    // ================ 3. RESOLVE cascade ================

    @Test
    void resolve_prefersTenantScopedOverPlatform() {
        // Seed BOTH a platform default AND a tenant-specific — tenant wins.
        service.save(template(null, "PACKING_SLIP", TENANT_PREFIX + "platform"));
        LabelTemplate tenantTmpl = service.save(
                template(TENANT_PREFIX + "R", "PACKING_SLIP", TENANT_PREFIX + "tenant"));

        Optional<LabelTemplate> resolved = service.resolve(TENANT_PREFIX + "R", "PACKING_SLIP");

        assertTrue(resolved.isPresent());
        assertEquals(tenantTmpl.getId(), resolved.get().getId(),
                "Tenant-scoped template must beat platform default at resolve time.");
    }

    @Test
    void resolve_fallsBackToPlatformDefault_whenTenantHasNoTemplate() {
        LabelTemplate platform = service.save(template(null, "PACKING_SLIP", TENANT_PREFIX + "platform"));

        Optional<LabelTemplate> resolved = service.resolve(TENANT_PREFIX + "NEW", "PACKING_SLIP");

        assertTrue(resolved.isPresent());
        assertEquals(platform.getId(), resolved.get().getId(),
                "Missing tenant template must fall back to the platform default (tenantId=null).");
    }

    @Test
    void resolve_nullTenant_returnsPlatformDirectly() {
        LabelTemplate platform = service.save(template(null, "PACKING_SLIP", TENANT_PREFIX + "platform"));

        Optional<LabelTemplate> resolved = service.resolve(null, "PACKING_SLIP");

        assertTrue(resolved.isPresent());
        assertEquals(platform.getId(), resolved.get().getId());
    }

    // ================ 4. FIND FOR TENANT ================

    @Test
    void findForTenant_returnsTenantsOwnRowNotPlatformFallback() {
        service.save(template(null, "PACKING_SLIP", TENANT_PREFIX + "platform"));
        LabelTemplate own = service.save(template(TENANT_PREFIX + "OWN", "PACKING_SLIP", TENANT_PREFIX + "own"));

        Optional<LabelTemplate> found = service.findForTenant(TENANT_PREFIX + "OWN", "PACKING_SLIP");

        assertTrue(found.isPresent());
        assertEquals(own.getId(), found.get().getId());
    }

    @Test
    void findForTenant_returnsEmptyWhenTenantHasNoRow_evenIfPlatformExists() {
        service.save(template(null, "PACKING_SLIP", TENANT_PREFIX + "platform"));

        Optional<LabelTemplate> found = service.findForTenant(TENANT_PREFIX + "NEW", "PACKING_SLIP");

        // Unlike resolve(), findForTenant does NOT fall back — the tenant
        // must have its own row or the result is empty.
        assertTrue(found.isEmpty(),
                "findForTenant must not fall back to the platform default (that's resolve's job).");
    }

    // ================ 5. DELETE ================

    @Test
    void delete_removesRow() {
        LabelTemplate saved = service.save(template(TENANT_PREFIX + "DEL", "PACKING_SLIP", TENANT_PREFIX + "del"));

        service.delete(saved.getId());

        assertFalse(repo.existsById(saved.getId()), "Row must be hard-deleted.");
    }

    // ================ 6. LIST — filter + paginate ================

    @Test
    void list_filtersByTemplateType() {
        service.save(template(TENANT_PREFIX + "F1", "PACKING_SLIP", TENANT_PREFIX + "pk"));
        service.save(template(TENANT_PREFIX + "F2", "RETURN_COVER", TENANT_PREFIX + "rc"));

        Page<LabelTemplate> page = service.list(null, "RETURN_COVER", null,
                PageRequest.of(0, 100, Sort.by(Sort.Direction.DESC, "updatedAt")));

        List<LabelTemplate> ours = page.getContent().stream()
                .filter(t -> t.getTenantId() != null && t.getTenantId().startsWith(TENANT_PREFIX))
                .toList();
        assertEquals(1, ours.size());
        assertEquals("RETURN_COVER", ours.get(0).getTemplateType());
    }

    // ================ 7. BLANK TENANT → PLATFORM ROW ================

    @Test
    void save_blankTenant_isNormalizedToNull_thusPlatformRow() {
        LabelTemplate t = template("", "PACKING_SLIP", TENANT_PREFIX + "blank");
        LabelTemplate saved = service.save(t);

        assertEquals(null, saved.getTenantId(),
                "Blank tenantId must normalize to null on save (platform default row).");
        // resolve with any tenant now falls back to this platform row.
        Optional<LabelTemplate> resolved = service.resolve(TENANT_PREFIX + "ANY", "PACKING_SLIP");
        assertTrue(resolved.isPresent());
        assertSame(saved.getId(), resolved.get().getId());
    }
}
