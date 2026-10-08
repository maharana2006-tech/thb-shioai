package com.multiship.backend.service;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.EodEventDTO;
import com.multiship.backend.dto.ManifestRequestDTO;
import com.multiship.backend.dto.ManifestResponseDTO;

import java.time.LocalDate;
import java.util.List;

/**
 * Sprint 34 — close out the day's shipments at a carrier and return the
 * manifest identifier + optional PDF. Routes to UPS End of Day, FedEx
 * CloseShipment, or SWSIM CreateScanForm based on {@code carrierCode}.
 * DHL is NOT_SUPPORTED (its manifests are implicit via pickup).
 */
public interface ManifestService {

    ApiResponse<ManifestResponseDTO> closeOut(ManifestRequestDTO request);

    /**
     * V135 — close out a whole day's open labels for one carrier without the
     * caller listing tracking numbers: gathers that day's GENERATED, non-voided
     * labels for the carrier (optionally scoped to a client / warehouse) and
     * manifests them. {@code source} is MANUAL (Settings page) or SCHEDULED
     * (CARRIER_CLOSEOUT job) — recorded on the audit log.
     */
    ApiResponse<ManifestResponseDTO> closeOutForDay(String carrierCode, String customerNo,
                                                    LocalDate closeDate, String warehouseCode,
                                                    String source);

    /** V135 — recent pickup/close events for the Settings activity table. */
    List<EodEventDTO> recentEvents(int limit);
}
