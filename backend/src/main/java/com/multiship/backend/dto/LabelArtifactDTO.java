package com.multiship.backend.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Phase 1 (Shipment &amp; Label re-architecture) — what the label screen needs
 * to render itself. Every field is read from stored columns; nothing here is
 * inferred from the label bytes. The frontend's format badge comes from
 * {@code source}, not from inspecting a 248 KB string.
 */
public record LabelArtifactDTO(
        Integer orderNo,
        /** GENERATED when at least one package artifact is stored, else PENDING. */
        String state,
        String carrier,
        /** SANDBOX | PRODUCTION, from the account the order shipped under. */
        String environment,
        LocalDateTime generatedAt,
        List<PackageArtifact> packages) {

    public record PackageArtifact(
            Integer seq,
            /** ZPL | PDF | GIF | PNG | UNKNOWN — the label_package.format column. */
            String format,
            /** CARRIER_NATIVE | CARRIER_RASTER | UNKNOWN — the label_package.source column. */
            String source,
            String tracking,
            /** The carrier's tracking page for this package, when it returned one. */
            String trackingUrl,
            LocalDateTime generatedAt) {}
}
