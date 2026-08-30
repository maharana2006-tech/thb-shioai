package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Sprint 52 — response for {@code POST /api/v1/shipments/validate}.
 * Combines server-side pre-flight results (IntlShipmentValidator,
 * PackagingCompatibilityGuard, markup check, resolver allowlist gates,
 * DG validator, carrier caps) with an optional carrier-side address
 * validation call (existing per-carrier validateAddress). The frontend
 * renders one aggregated banner: local errors first (fail fast — the
 * label call would fail with the same codes), then the address result
 * as a secondary detail.
 *
 * <p>{@link #overall} is the top-level UX signal:
 * <ul>
 *   <li>{@code PASS}  — no local errors + address either passed or was
 *                       not called (carrier without validateAddress).</li>
 *   <li>{@code WARN}  — no local errors but the carrier flagged the
 *                       address (CORRECTED / AMBIGUOUS) or warnings
 *                       are present.</li>
 *   <li>{@code FAIL}  — at least one local error, OR carrier returned
 *                       NOT_FOUND / ERROR on the address.</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShipmentValidationResult {

    /** Top-level verdict — see class javadoc. */
    private String overall;

    /**
     * Human-readable one-liner for the banner header. Aggregated from the
     * local + address details; the FE always shows this unmodified.
     */
    private String message;

    /**
     * Server-side pre-flight errors (each blocks label generation).
     * Empty when local checks all passed. Each carries a stable
     * {@link ErrorCode} name so the FE can style / group / link.
     */
    private List<ValidationIssue> localErrors;

    /**
     * Non-blocking warnings the operator should see but that don't stop
     * label generation. E.g., no billing markup saved but this is an
     * ad-hoc shipment (blank clientCode).
     */
    private List<ValidationIssue> localWarnings;

    /**
     * Which server-side checks were skipped and why. Populated so the
     * FE can tell "not applicable" apart from "no issues found". E.g.,
     * IntlShipmentValidator is SKIPPED on domestic shipments;
     * PackagingCompatibilityGuard is SKIPPED when serviceId is null.
     */
    private List<ValidationCheckStatus> skipped;

    /**
     * Result of the per-carrier validateAddress call, when the carrier
     * supports it and local checks passed. Null when local checks
     * failed (skipped the carrier hop) or when the carrier's
     * validateAddress returned NOT_SUPPORTED.
     *
     * @deprecated Sprint 52 PR δ — superseded by {@link #carrier} which
     * carries the richer ValidateShipmentResult shape. Kept as-is (null
     * on new responses) for pre-PR-δ FE bundle back-compat.
     */
    @Deprecated
    private AddressValidationResponseDTO address;

    /**
     * Sprint 52 PR δ — carrier-native shipment validation result. When
     * local pre-flight passes AND the picked carrier is resolvable
     * (credentials available), the connector's validateShipment is
     * called and its result lands here. Null when local failed (skipped
     * carrier hop), when the carrier couldn't be resolved (no
     * credentials on file), or when the connector returned NOT_SUPPORTED.
     *
     * <p>{@code matchLevel} drives the UI:
     * EXACT (green), CORRECTED / AMBIGUOUS (amber), NOT_FOUND / ERROR
     * (red), NOT_SUPPORTED (grey — no call made).
     *
     * <p>{@code kind} tells the operator whether the carrier ran a
     * shipment-level check (SHIPMENT) or the connector fell back to its
     * address-only validator (ADDRESS_ONLY). MVP: all 4 connectors are
     * ADDRESS_ONLY. Follow-up PR δ.1 upgrades FedEx + UPS to SHIPMENT.
     */
    private CarrierValidationSubResult carrier;

    /**
     * True when the shipment was classified as international by the
     * server (sender vs recipient country + sameTerritory rules). The
     * FE mirrors its own isInternational for form UX; this echoes the
     * server's view for consistency.
     */
    private boolean international;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CarrierValidationSubResult {
        /** Which carrier ran the check (FEDEX / UPS / DHL / USPS). */
        private String carrierCode;
        /** True when the carrier confirmed the shipment / address is deliverable. */
        private boolean valid;
        /** EXACT | CORRECTED | AMBIGUOUS | NOT_FOUND | NOT_SUPPORTED | ERROR. */
        private String matchLevel;
        /** SHIPMENT (native validate) | ADDRESS_ONLY (delegated to validateAddress). */
        private String kind;
        /** Carrier-flagged warnings (unusual state format, missing suite, etc.). */
        private List<String> warnings;
        /** Carrier-flagged errors (invalid ZIP, service not available on lane, etc.). */
        private List<String> errors;
        /** Operator-facing summary — one sentence. */
        private String message;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ValidationIssue {
        /** Stable code — matches {@link ErrorCode} enum name where
         *  a matching enum exists, else a snake_case string. */
        private String code;
        /** Operator-facing message; already-formatted for display. */
        private String message;
        /** Optional field hint — 'recipient.postalCode', 'items[2].hsCode', etc. */
        private String field;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ValidationCheckStatus {
        /** Short check identifier — 'customs', 'packaging_compatibility', 'markup', ... */
        private String name;
        /** Why skipped — 'domestic shipment', 'no service picked', etc. */
        private String reason;
    }
}
