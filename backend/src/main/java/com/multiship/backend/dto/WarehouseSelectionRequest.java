package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * G3 — request body for {@code POST /clients/{code}/warehouses/select-nearest}.
 * Both fields optional; the selector falls back to any attached warehouse
 * when neither can be used.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WarehouseSelectionRequest {
    /** ISO-3166 alpha-2. Case-insensitive. */
    private String destCountry;
    /** Destination postal / ZIP code. Case-insensitive. */
    private String destPostal;
}
