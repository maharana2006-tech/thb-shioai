package com.multiship.backend.service;

import com.multiship.backend.model.LabelTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;

/**
 * Sprint 42 — CRUD for tenant-branded label templates. Each tenant
 * gets one template per {@code templateType}; upsert-style save.
 */
public interface LabelTemplateService {

    /** Fetch a template by primary key. */
    Optional<LabelTemplate> findById(Long id);

    /**
     * Resolve the effective template: tenant-scoped, then platform default.
     * {@code templateType} accepts any of the {@link LabelTemplate} type
     * discriminators — {@code PACKING_SLIP}, {@code COMMERCIAL_INVOICE},
     * {@code RETURN_COVER}. Returns empty when neither the tenant nor
     * the platform has one configured; callers fall back to hardcoded
     * defaults.
     */
    Optional<LabelTemplate> resolve(String tenantId, String templateType);

    /** Fetch the tenant-scoped template (does NOT fall back). */
    Optional<LabelTemplate> findForTenant(String tenantId, String templateType);

    /** Upsert. Sets timestamps. */
    LabelTemplate save(LabelTemplate template);

    /** Delete by id. */
    void delete(Long id);

    /**
     * List all templates for the operator settings screen. Empty
     * strings mean "no filter on this axis" (nulls are normalised).
     * {@code hasLogo}: null / '' = any, 'Y' = with logo, 'N' = without.
     */
    Page<LabelTemplate> list(String search, String templateType, String hasLogo, Pageable pageable);
}
