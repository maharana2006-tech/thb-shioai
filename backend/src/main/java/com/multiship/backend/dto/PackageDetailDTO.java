package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * One package inside a multi-package shipment. Sprint 28 threads
 * multi-package support through every connector — an order with three
 * boxes lands as one shipment request with a {@code packages[]} of
 * length 3 instead of three separate shipments.
 *
 * <p>Every field mirrors the equivalent top-level field on
 * {@link ShipmentRequestDTO} so a single-package shipment can be
 * expressed either way. When both are populated on the parent DTO the
 * connectors use {@code packages[]} (see
 * {@link ShipmentRequestDTO#effectivePackages()}); the top-level fields
 * are the backwards-compat fallback for callers that predate multi-
 * package support.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PackageDetailDTO {

    /**
     * 1-based sequence number within the shipment. Populated for
     * carrier wire formats that require it (FedEx sequenceNumber, UPS
     * doesn't need it explicitly). Optional — connectors synthesise
     * when absent.
     */
    private Integer sequenceNumber;

    /** Carrier packaging code — same values as
     *  {@link ShipmentRequestDTO#getPackageType()}. */
    private String packageType;

    /** Weight of THIS package. Required. */
    private BigDecimal weight;

    /** LB | KG. Null = "assume LB" (legacy default). */
    private String weightUnit;

    /** Optional dimensions — connectors that ignore dims (SWSIM
     *  outside oversize) skip these safely. */
    private BigDecimal length;
    private BigDecimal width;
    private BigDecimal height;

    /** IN | CM. Null = "assume IN". */
    private String dimUnit;

    /**
     * Per-package declared value. When set, carriers that support
     * per-package customs values (FedEx, DHL) use it; when null we fall
     * back to the shipment-level {@link ShipmentRequestDTO#getDeclaredValue}
     * split proportionally OR emitted only on package 1 (carrier-
     * specific).
     */
    private BigDecimal declaredValue;

    /** Free-text description printed on the label / manifest. */
    private String description;

    /**
     * Free-text customer reference — some carriers print this on the
     * label so operators can bind box → RMA / order line. Optional.
     */
    private String reference;
}
