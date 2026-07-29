package com.multiship.backend.service.hs;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One HS code entry in the curated dataset. Format is intentionally minimal —
 * a code, a human-readable description, and a category for grouping in the UI.
 * When we later swap to a full WCO / tariff dataset the schema will grow (unit
 * of measurement, chapter section, cross-carrier notes) but everything the
 * autocomplete needs is here.
 */
public record HsCodeEntry(String code, String description, String category) {
    @JsonCreator
    public HsCodeEntry(
            @JsonProperty("code") String code,
            @JsonProperty("description") String description,
            @JsonProperty("category") String category) {
        this.code = code;
        this.description = description;
        this.category = category;
    }
}
