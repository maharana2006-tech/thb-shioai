package com.multiship.backend.service;

import com.multiship.backend.model.LabelTemplate;
import com.multiship.backend.repository.LabelTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Sprint 42 — label template service coverage: resolution cascade,
 * upsert timestamps, blank-tenant normalisation.
 */
class LabelTemplateServiceTest {

    private LabelTemplateRepository repo;
    private LabelTemplateServiceImpl service;

    @BeforeEach
    void setUp() {
        repo = mock(LabelTemplateRepository.class);
        service = new LabelTemplateServiceImpl(repo);
    }

    @Test
    void resolve_prefersTenantScopedOverPlatform() {
        LabelTemplate tenant = new LabelTemplate();
        tenant.setTenantId("ARHDEV");
        LabelTemplate platform = new LabelTemplate();
        when(repo.findByTenantAndType("ARHDEV", "PACKING_SLIP"))
                .thenReturn(Optional.of(tenant));

        Optional<LabelTemplate> result = service.resolve("ARHDEV", "PACKING_SLIP");

        assertTrue(result.isPresent());
        assertEquals("ARHDEV", result.get().getTenantId());
        verify(repo, never()).findByTenantAndType(isNull(), any());
    }

    @Test
    void resolve_fallsBackToPlatformDefault_whenTenantHasNone() {
        LabelTemplate platform = new LabelTemplate();
        when(repo.findByTenantAndType("ARHDEV", "PACKING_SLIP"))
                .thenReturn(Optional.empty());
        when(repo.findByTenantAndType(null, "PACKING_SLIP"))
                .thenReturn(Optional.of(platform));

        Optional<LabelTemplate> result = service.resolve("ARHDEV", "PACKING_SLIP");

        assertTrue(result.isPresent());
        assertSame(platform, result.get());
    }

    @Test
    void resolve_nullTenant_looksUpPlatformDefaultDirectly() {
        service.resolve(null, "PACKING_SLIP");
        verify(repo).findByTenantAndType(isNull(), eq("PACKING_SLIP"));
        verify(repo, times(1)).findByTenantAndType(any(), any());
    }

    @Test
    void findForTenant_normalisesBlankToNull() {
        service.findForTenant("   ", "PACKING_SLIP");
        verify(repo).findByTenantAndType(isNull(), eq("PACKING_SLIP"));
    }

    @Test
    void save_newTemplate_setsBothTimestampsAndDefaultsType() {
        LabelTemplate t = new LabelTemplate();
        t.setTenantId("ARHDEV");
        t.setTemplateType(null);  // service should default it

        ArgumentCaptor<LabelTemplate> cap = ArgumentCaptor.forClass(LabelTemplate.class);
        when(repo.save(cap.capture())).thenAnswer(inv -> inv.getArgument(0));

        service.save(t);

        LabelTemplate saved = cap.getValue();
        assertEquals("PACKING_SLIP", saved.getTemplateType());
        assertNotNull(saved.getCreatedAt());
        assertNotNull(saved.getUpdatedAt());
    }

    @Test
    void save_updateOfExistingTemplate_preservesCreatedAt() {
        LabelTemplate t = new LabelTemplate();
        t.setId(42L);
        t.setTenantId("ARHDEV");
        // simulate existing createdAt on the passed entity
        java.time.LocalDateTime originalCreated = java.time.LocalDateTime.of(2026, 1, 1, 0, 0);
        t.setCreatedAt(originalCreated);

        ArgumentCaptor<LabelTemplate> cap = ArgumentCaptor.forClass(LabelTemplate.class);
        when(repo.save(cap.capture())).thenAnswer(inv -> inv.getArgument(0));

        service.save(t);

        assertEquals(originalCreated, cap.getValue().getCreatedAt(),
                "Update should not overwrite createdAt");
        assertNotNull(cap.getValue().getUpdatedAt());
    }

    @Test
    void save_blankTenantId_isStoredAsNull() {
        LabelTemplate t = new LabelTemplate();
        t.setTenantId("");
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.save(t);

        ArgumentCaptor<LabelTemplate> cap = ArgumentCaptor.forClass(LabelTemplate.class);
        verify(repo).save(cap.capture());
        assertNull(cap.getValue().getTenantId(),
                "Blank tenantId should normalise to null (platform default row)");
    }
}
