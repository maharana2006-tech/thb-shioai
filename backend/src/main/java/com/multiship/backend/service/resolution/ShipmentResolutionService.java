package com.multiship.backend.service.resolution;

import com.multiship.backend.model.ClientShippingPolicy;
import com.multiship.backend.model.Warehouse;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Single choke point that applies the 3PL settings — client warehouses,
 * allowlists, destination rules, rate strategy, cutoff, markup — at label
 * time.
 *
 * <p>Callers use these helpers a la carte: the internal manual-shipment
 * path may only need {@link #assertWarehouse} + {@link #assertShipToAllowed}
 * + {@link #applyMarkup}, while the rate-shop path also uses
 * {@link #pickService}. Every "assert" variant throws
 * {@link ShipmentResolutionException} carrying a stable
 * {@link com.multiship.backend.dto.ErrorCode} so the two entry points
 * (internal controllers, external API) can map to their own response
 * envelopes without matching on message text.
 *
 * <p>Backward-compat rule: when a client has NOT yet been onboarded onto a
 * particular allowlist (empty list), we treat that as "unrestricted" so
 * shipping keeps working. Once product decides to flip to strict mode, the
 * "unrestricted-on-empty" branches become the throw path — no other
 * callsite has to change.
 */
public interface ShipmentResolutionService {

    // ===== Warehouse =====

    /**
     * Resolve the origin warehouse for a shipment.
     *
     * @param clientCode             the shipping client.
     * @param requestedWarehouseCode explicit override; null = use client's default.
     * @return the resolved warehouse, empty when the client has no
     *         attached warehouses and no code was supplied (caller may
     *         then fall back to legacy {@code Client.shipFrom}).
     * @throws ShipmentResolutionException with WAREHOUSE_NOT_FOUND when the
     *         requested code exists but isn't attached to the client, or
     *         WAREHOUSE_ATTACH_FORBIDDEN when it's a CLIENT-owned warehouse
     *         of a different client.
     */
    Optional<Warehouse> resolveWarehouse(String clientCode, String requestedWarehouseCode);

    /** {@link #resolveWarehouse} that throws NO_DEFAULT_WAREHOUSE on empty. */
    Warehouse assertWarehouse(String clientCode, String requestedWarehouseCode);

    // ===== Ship-to =====

    /** Does the client's destination-rule set permit shipping to this country? */
    boolean canShipTo(String clientCode, String destCountryCode);

    /** {@link #canShipTo} that throws SHIP_TO_DENIED when false. */
    void assertShipToAllowed(String clientCode, String destCountryCode);

    // ===== Service + package allowlists =====

    /**
     * Set of service ids the client may ship on. Empty return means
     * "no allowlist configured" — treat as unrestricted (see the class
     * javadoc's backward-compat note).
     */
    Set<Long> allowedServiceIds(String clientCode);

    /**
     * Set of service ids the client may ship on FROM the given warehouse.
     * A service is included when its warehouse-gate is empty (any warehouse
     * OK) or when the gate lists {@code warehouseId}. Passing {@code null}
     * for {@code warehouseId} skips the gate — equivalent to
     * {@link #allowedServiceIds(String)}.
     */
    Set<Long> allowedServiceIds(String clientCode, Long warehouseId);

    Set<Long> allowedPackageIds(String clientCode);

    /**
     * Assert the given service is on the client's allowlist and its
     * destination-gate (if any) permits {@code destCountry}. When the row
     * has no destination rows it's unrestricted; otherwise the target
     * country must be listed.
     *
     * @throws ShipmentResolutionException SERVICE_NOT_ALLOWED — the service
     *         isn't on the allowlist at all;
     *         SERVICE_NOT_ALLOWED_FOR_DEST — it's on the list but not for
     *         this destination.
     */
    void assertServiceAllowed(String clientCode, Long serviceId, String destCountry);

    /**
     * As {@link #assertServiceAllowed(String, Long, String)}, plus the
     * warehouse-gate check. When {@code warehouseId} is null the warehouse
     * gate is not consulted — same behaviour as the 3-arg overload.
     *
     * @throws ShipmentResolutionException SERVICE_NOT_ALLOWED_FOR_WAREHOUSE
     *         when the service is on the allowlist but restricted to
     *         warehouses that don't include {@code warehouseId}.
     */
    void assertServiceAllowed(String clientCode, Long serviceId, String destCountry, Long warehouseId);

    /**
     * Assert the given package is usable by the client. CLIENT-owned presets
     * auto-pass when the owner matches; PLATFORM presets must be on
     * {@link com.multiship.backend.model.ClientAllowedPackage}.
     *
     * @throws ShipmentResolutionException PACKAGE_NOT_ALLOWED.
     */
    void assertPackageAllowed(String clientCode, Long presetId);

    // ===== Rate strategy =====

    /** Stored policy for the client, empty when no PUT has ever run. */
    Optional<ClientShippingPolicy> policyFor(String clientCode);

    /**
     * Pick one option from a rate-shop result per the client's policy.
     *
     * <p>Strategy semantics:
     * <ul>
     *   <li>CHEAPEST — lowest {@code price}.</li>
     *   <li>FASTEST  — lowest {@code estimatedDeliveryDays}; options
     *                  missing an ETA are skipped and only fall back to
     *                  CHEAPEST when every candidate is missing one.</li>
     *   <li>FIXED    — the option whose {@code serviceId} matches
     *                  {@code policy.fixedServiceId}; empty when not in
     *                  the candidate set (caller decides whether to fall
     *                  back or fail).</li>
     * </ul>
     *
     * @return chosen option, empty when the strategy can't be satisfied.
     */
    Optional<RateOption> pickService(String clientCode, List<RateOption> candidates);

    // ===== Markup =====

    /**
     * Apply the client's markup to a carrier rate and produce a snapshot
     * suitable for stamping on a shipment row. Absent markup row = 0%
     * (billable = carrierRate). Snapshot fields are always populated so
     * historical rows carry the effective config even when the client's
     * markup is later cleared.
     *
     * @param clientCode        the shipping client.
     * @param carrierRate       the amount the carrier billed.
     * @param carrierCurrency   ISO-4217 the carrier priced in.
     * @throws ShipmentResolutionException MARKUP_INVALID when the client's
     *         markup currency doesn't match {@code carrierCurrency}.
     */
    MarkupApplied applyMarkup(String clientCode, BigDecimal carrierRate, String carrierCurrency);

    // ===== Cutoff (soft flag) =====

    /**
     * True when {@code now} is at or past the client's local cutoff. Absent
     * cutoff = always false. Returned as {@code dispatchNextBusinessDay} in
     * API responses; label creation is NOT blocked.
     */
    boolean isPastCutoff(String clientCode, Instant now);
}
