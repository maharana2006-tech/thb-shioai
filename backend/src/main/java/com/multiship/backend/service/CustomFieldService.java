package com.multiship.backend.service;

import com.multiship.backend.model.CustomFieldDefinition;

import java.util.List;
import java.util.Map;

/**
 * Sprint 43 — custom field definitions + per-order values. Every
 * write goes through this service so type validation lives in one
 * place.
 */
public interface CustomFieldService {

    /** All definitions for a tenant (active + inactive), for settings pages. */
    List<CustomFieldDefinition> listAllForTenant(String tenantId);

    /** Only fields that should appear on the order form for a tenant. */
    List<CustomFieldDefinition> listApplicable(String tenantId);

    /** Upsert a definition. Sets timestamps, normalises blank tenantId to null. */
    CustomFieldDefinition saveDefinition(CustomFieldDefinition definition);

    void deleteDefinition(Long id);

    /**
     * Load all values for an order as a fieldKey→string map. Missing
     * fields simply don't appear in the map.
     */
    Map<String, String> loadValues(Integer orderNo);

    /**
     * Upsert values on an order. Keys must map to definitions applicable
     * to the given tenant; values are type-validated against the
     * definition. Unknown or invalid keys throw {@link IllegalArgumentException}
     * so callers can surface a 400.
     */
    Map<String, String> upsertValues(Integer orderNo,
                                     String tenantId,
                                     Map<String, String> values);
}
