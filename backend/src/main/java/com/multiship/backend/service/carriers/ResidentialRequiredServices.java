package com.multiship.backend.service.carriers;

import java.util.Locale;
import java.util.Set;

/**
 * Single source of truth for "which carrier service codes REQUIRE the
 * shipment to be marked as residential". FedEx Home Delivery is the
 * canonical case — the carrier flat-out refuses to accept a
 * commercial address on this service and returns an obscure error
 * that operators have to decode from the carrier's raw response.
 *
 * <p>Every layer that could send a shipment (manual UI, DTO validator,
 * import row validator, bulk-labels boundary guard) consults this
 * class so behaviour stays consistent — a change here propagates to
 * every entry point without hunting through call sites.
 *
 * <p>UPS and USPS don't have a service that requires residential:
 * <ul>
 *   <li>UPS's residential surcharge is billed automatically at
 *       delivery if the address turns out to be a residence; no
 *       carrier rejection happens.</li>
 *   <li>USPS delivers to residential + commercial via the same
 *       services (Priority Mail, etc.) — the flag is informational.</li>
 * </ul>
 * DHL Express has similar informational-only residential handling.
 * So only FedEx belongs in this list today.
 *
 * <p>Extending: add a new service code (or a new carrier's equivalent)
 * to {@link #RESIDENTIAL_REQUIRED_SERVICE_CODES}. Codes are
 * case-normalised so operators typing lowercase in an import
 * spreadsheet still match.
 */
public final class ResidentialRequiredServices {

    /**
     * Uppercase carrier service codes that the carrier's own rules
     * REQUIRE be shipped to a residential address. Any other service
     * either accepts both or bills a surcharge silently.
     *
     * <p>FedEx {@code GROUND_HOME_DELIVERY} — literal "FedEx Home
     * Delivery" — refuses commercial destinations with a
     * {@code CUSTOMER.DESTINATION.INVALID} response that operators
     * historically had to decode by reading the carrier's raw error.
     */
    public static final Set<String> RESIDENTIAL_REQUIRED_SERVICE_CODES = Set.of(
            "GROUND_HOME_DELIVERY"
    );

    private ResidentialRequiredServices() {}

    /**
     * True when the given service code requires a residential address.
     * Null / blank input returns false — no service picked yet is not
     * a validation failure. Case-insensitive so an import row typed
     * as {@code "ground_home_delivery"} matches.
     */
    public static boolean requiresResidential(String serviceCode) {
        if (serviceCode == null) return false;
        String trimmed = serviceCode.trim();
        if (trimmed.isEmpty()) return false;
        return RESIDENTIAL_REQUIRED_SERVICE_CODES.contains(trimmed.toUpperCase(Locale.ROOT));
    }

    /**
     * True when the given (service, residential) pair is mutually
     * inconsistent — service requires residential but the flag is
     * false or null. Callers use this to short-circuit an obviously-
     * broken shipment before hitting the carrier's OAuth + rate quote.
     *
     * <p>A residential flag of {@code null} counts as inconsistent
     * because the wire connectors default null to
     * "unknown-treated-as-commercial" and the carrier would then
     * reject.
     */
    public static boolean isInconsistent(String serviceCode, Boolean residential) {
        if (!requiresResidential(serviceCode)) return false;
        return !Boolean.TRUE.equals(residential);
    }

    /**
     * Operator-facing error message for validators + connectors.
     * Names the exact service so the operator knows which of their
     * choices to change (either the service picker or the residential
     * checkbox).
     */
    public static String inconsistentMessage(String serviceCode) {
        String code = serviceCode == null ? "the selected service" : serviceCode.trim();
        return "Service " + code + " requires the recipient address to be marked as residential. "
                + "Either tick 'Residential address' on the recipient block, or pick a different "
                + "service that supports commercial delivery.";
    }
}
