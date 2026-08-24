package com.multiship.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * F1 — one entry in the polymorphic identifier list on
 * {@link BulkLabelRequestDTO#getIdentifiers()}. Each entry names how the
 * server should resolve {@code value} into an internal {@code Order.orderNo}:
 *
 * <ul>
 *   <li>{@code type = "orderNo"} — {@code value} is the numeric internal id
 *       ({@link com.multiship.backend.model.Order#getOrderNo}), passed as a
 *       string so the polymorphic list stays uniform. Parsed to {@code Long}
 *       server-side; a non-numeric value fails resolution with a per-entry
 *       error.</li>
 *   <li>{@code type = "orderRef"} — {@code value} matches
 *       {@link com.multiship.backend.model.Order#getWmsExternalId}
 *       case-insensitively. Only WMS-imported orders carry a wmsExternalId
 *       (CSV / API-created orders have {@code null}), so this path is
 *       WMS-only by design. F1 storage decision: reuse the existing column,
 *       don't add a new one.</li>
 * </ul>
 *
 * <p>Both list modes ({@link BulkLabelRequestDTO#getOrderNumbers()} and this
 * polymorphic list) are supported; the caller picks exactly one per request.
 * A single {@code identifiers} list may mix both types — the server resolves
 * each entry independently and enqueues the union.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BulkLabelIdentifierDTO {

    /**
     * How to resolve {@link #value}. Constrained to the two known kinds via
     * regex so an unknown value fails at validation instead of leaking into
     * the switch in {@code BulkLabelServiceImpl.resolveIdentifiers}. Kept as
     * a String (not enum) so the request/response wire format is easy for
     * non-Java callers to hand-build.
     */
    @NotBlank(message = "identifiers[].type is required (\"orderNo\" or \"orderRef\").")
    @Pattern(regexp = "orderNo|orderRef",
            message = "identifiers[].type must be \"orderNo\" or \"orderRef\".")
    private String type;

    /**
     * Payload to resolve. For {@code type=orderNo} — a numeric internal id;
     * for {@code type=orderRef} — the WMS external id (case-insensitive match
     * on {@code label_batch.wms_external_id}).
     */
    @NotBlank(message = "identifiers[].value is required.")
    private String value;
}
