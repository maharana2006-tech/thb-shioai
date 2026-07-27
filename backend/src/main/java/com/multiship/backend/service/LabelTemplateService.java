package com.multiship.backend.service;

import com.multiship.backend.model.LabelTemplate;

import java.util.Optional;

/**
 * Sprint 42 — CRUD for tenant-branded label templates. Each tenant
 * gets one template per {@code templateType}; upsert-style save.
 */
public interface LabelTemplateService {

    /** Resolve the effective template: tenant-scoped, then platform default. */
    Optional<LabelTemplate> resolve(String tenantId, String templateType);

    /** Fetch the tenant-scoped template (does NOT fall back). */
    Optional<LabelTemplate> findForTenant(String tenantId, String templateType);

    /** Upsert. Sets timestamps. */
    LabelTemplate save(LabelTemplate template);

    /** Delete by id. */
    void delete(Long id);
}
