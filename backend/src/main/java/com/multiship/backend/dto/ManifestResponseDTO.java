package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Sprint 34 wire response for {@code POST /api/v1/manifests}. Mirrors
 * {@link com.multiship.backend.service.carriers.CarrierConnector.CloseOutResult}
 * with wire-friendly fields.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ManifestResponseDTO {

    private String carrierCode;
    /** Carrier's manifest identifier (UPS BOL, FedEx groupID, SWSIM SubmissionID). */
    private String manifestId;
    /** Direct URL to the manifest PDF when the carrier gives one. */
    private String manifestPdfUrl;
    /** Base64-encoded manifest PDF when the carrier returns it inline. */
    private String manifestPdfBase64;
    private int trackingCount;
    /** MANIFESTED | ERROR | NOT_SUPPORTED. */
    private String status;
    private String message;
}
