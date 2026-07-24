package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Uniform read shape for the four per-client ERP alias tables.
 * {@link #kind} tells the caller which flavour it is; the target field
 * varies by kind:
 * <ul>
 *   <li>SHIPVIA / SERVICE / PACKAGE — {@code targetId} points at a
 *       {@code shipping_service} or {@code package_preset} row; {@code iso2}
 *       is null.</li>
 *   <li>DEST_COUNTRY — {@code iso2} is the canonical ISO-2; {@code targetId}
 *       is null.</li>
 * </ul>
 * {@code targetLabel} is a display convenience (e.g. "UPS Ground · 03"),
 * populated by the service for the list view.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClientCodeMapDTO {

    public enum Kind { SHIPVIA, SERVICE, DEST_COUNTRY, PACKAGE }

    private Long id;
    private Kind kind;
    private String clientCode;
    private String erpCode;
    /** Populated for SHIPVIA / SERVICE / PACKAGE. */
    private Long targetId;
    /** Populated for DEST_COUNTRY. */
    private String iso2;
    /** Human-readable summary of the target row (for list rendering). */
    private String targetLabel;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
