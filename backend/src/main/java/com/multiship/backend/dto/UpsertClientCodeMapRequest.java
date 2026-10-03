package com.multiship.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Body for POST / PUT of any of the four per-client alias tables.
 * Exactly one of {@code targetId} / {@code iso2} is expected depending on the
 * URL path (SHIPVIA/SERVICE/PACKAGE → targetId; DEST_COUNTRY → iso2).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpsertClientCodeMapRequest {

    @NotBlank
    @Size(max = 40)
    private String erpCode;

    /** For SHIPVIA / SERVICE (shipping_service.id) or PACKAGE (package_preset.id). */
    private Long targetId;

    /** For DEST_COUNTRY — ISO-3166 alpha-2, upper-case. */
    @Size(min = 2, max = 2)
    private String iso2;

    /**
     * Destination scope for SHIPVIA / SERVICE / PACKAGE aliases; ignored
     * for DEST_COUNTRY. Either or both may be null (= "any"). If both
     * set, country wins for lookup / resolution.
     */
    @Size(max = 2)
    private String destCountry;
    @Size(max = 40)
    private String destRegion;

    /** V126 — SHIPVIA only. Null = any origin (default). FK to
     *  {@code warehouse.id}. Ignored for non-SHIPVIA kinds. */
    private Long warehouseId;

    /** V127 — SHIPVIA only. Null or empty = unrestricted; a non-empty list
     *  replaces the sidecar rows. Ignored for non-SHIPVIA kinds. */
    private List<Long> allowedPresetIds;
}
