package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Sprint 34 wire response for {@code POST /api/v1/manifests}. Mirrors
 * {@link com.multiship.backend.service.carriers.CarrierConnector.CloseOutResult}
 * with wire-friendly fields.
 *
 * <p>FDX-G2 — response gained two optional fields to carry per-fleet
 * manifests + trackings we couldn't classify:
 * <ul>
 *   <li>{@link #manifests} — populated when a real close-out ran with more
 *       than one fleet group (typically FedEx: Ground + Express labels
 *       mixed in one submission). Callers should read this list
 *       preferentially; the flat top-level fields aggregate for single-
 *       fleet submissions (back-compat) and remain null when a split
 *       actually occurred.</li>
 *   <li>{@link #failedToClassify} — trackings whose fleet couldn't be
 *       determined via the shipping-service-mapping chain (missing
 *       OrderTracking row, no ClientShipviaCodeMap alias, unresolved
 *       ShippingService). These get EXCLUDED from the manifest so the
 *       carrier doesn't reject the whole batch; the operator re-runs
 *       after fixing the mapping.</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ManifestResponseDTO {

    private String carrierCode;
    /** Carrier's manifest identifier (UPS BOL, FedEx groupID, SWSIM SubmissionID).
     *  Null when the split path produced multiple manifests — read
     *  {@link #manifests} in that case. */
    private String manifestId;
    /** Direct URL to the manifest PDF when the carrier gives one.
     *  Null when the split path produced multiple manifests. */
    private String manifestPdfUrl;
    /** Base64-encoded manifest PDF when the carrier returns it inline.
     *  Null when the split path produced multiple manifests. */
    private String manifestPdfBase64;
    private int trackingCount;
    /** MANIFESTED | PARTIAL | ERROR | NOT_SUPPORTED. FDX-G2 added PARTIAL
     *  for the case where at least one but not every per-fleet manifest
     *  succeeded (or trackings landed in {@link #failedToClassify}). */
    private String status;
    private String message;

    /**
     * FDX-G2 — per-fleet manifest breakdown. Present when the classifier
     * produced more than one fleet group (typically FedEx: Ground +
     * Express mixed). Null when a single fleet covered the whole batch
     * (single-fleet carriers like DHL / USPS, or all-Ground / all-Express
     * FedEx submissions).
     */
    private List<ManifestEntryDTO> manifests;

    /**
     * FDX-G2 — trackings excluded from the manifest because their fleet
     * couldn't be classified. Populated only when the classifier missed
     * at least one tracking; null otherwise. Each entry is the raw
     * tracking number the caller submitted.
     */
    private List<String> failedToClassify;

    /**
     * FDX-G2 — one per-fleet manifest inside a split response. Shape
     * mirrors the top-level flat fields so callers can render N tables
     * uniformly.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ManifestEntryDTO {
        /** GROUND | EXPRESS. */
        private String fleet;
        private String manifestId;
        private String manifestPdfUrl;
        private String manifestPdfBase64;
        private int trackingCount;
        /** MANIFESTED | ERROR | NOT_SUPPORTED. */
        private String status;
        private String message;
        /** Trackings this manifest covers. Useful for the FE to render
         *  which labels landed in which fleet. */
        private List<String> trackingNumbers;
    }
}
