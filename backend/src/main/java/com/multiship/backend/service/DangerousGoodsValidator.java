package com.multiship.backend.service;

import com.multiship.backend.dto.DangerousCommodityDTO;
import com.multiship.backend.dto.DangerousGoodsBlockDTO;
import com.multiship.backend.dto.ShipmentRequestDTO;
import com.multiship.backend.service.IntlShipmentValidator.ValidationError;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Guards dangerous goods shipments against carrier-side rejection by
 * checking the DG block BEFORE the payload leaves our server. Carriers
 * (UPS ShipmentServiceOptions.HazMat, FedEx dangerousGoodsDetail, DHL
 * dangerousGoods) all fail fast on missing UN numbers, blank shipping
 * names, or malformed hazard classes — with terse messages that
 * dead-end the operator. Catching those failures here surfaces a
 * clean, actionable error before the carrier is even called.
 *
 * <p>Pure function. Never throws. Callers get an ordered list of
 * {@link ValidationError} — empty list == valid. Mirrors
 * {@link IntlShipmentValidator}'s return contract so a single toast /
 * 422 body handles both.
 */
public final class DangerousGoodsValidator {

    /** Documented codes so the UI can localise messages predictably. */
    public static final String CODE_MISSING_BLOCK = "dg.missing";
    public static final String CODE_NO_COMMODITIES = "dg.commodities.empty";
    public static final String CODE_BAD_REGULATION = "dg.regulation.invalid";
    public static final String CODE_MISSING_CONTACT = "dg.contact.missing";
    public static final String CODE_MISSING_SIGNATORY = "dg.signatory.missing";
    public static final String CODE_BAD_UN_NUMBER = "dg.commodity.un.invalid";
    public static final String CODE_MISSING_SHIPPING_NAME = "dg.commodity.shippingname.missing";
    public static final String CODE_BAD_HAZARD_CLASS = "dg.commodity.class.invalid";
    public static final String CODE_BAD_PACKING_GROUP = "dg.commodity.packinggroup.invalid";
    public static final String CODE_ZERO_QUANTITY = "dg.commodity.quantity.zero";

    /** Regulation sets we accept — one per transport mode. */
    private static final Set<String> VALID_REGULATION = Set.of("IATA", "ADR", "DOT");

    /** Accessibility values — matches FedEx enum. Case-insensitive. */
    private static final Set<String> VALID_ACCESSIBILITY = Set.of("ACCESSIBLE", "INACCESSIBLE");

    /**
     * UN number format: {@code UN} prefix + exactly 4 digits. UN numbers
     * are assigned by the UN Committee of Experts on the Transport of
     * Dangerous Goods; they run from UN0001 through UN3549+.
     */
    private static final Pattern UN_NUMBER_PATTERN = Pattern.compile("^UN\\d{4}$");

    /**
     * Hazard class: 1 digit, optionally followed by {@code .N} for
     * subclass. Classes 1-9 exist; subclasses use up to 3 digits after
     * the dot (e.g. "4.1", "5.2").
     */
    private static final Pattern HAZARD_CLASS_PATTERN = Pattern.compile("^[1-9](\\.\\d{1,3})?$");

    /** Packing group values: Roman numerals I | II | III. */
    private static final Set<String> VALID_PACKING_GROUP = Set.of("I", "II", "III");

    /** Common quantity units. Case-insensitive on the wire. */
    private static final Set<String> VALID_QUANTITY_UNIT = Set.of("KG", "L", "PCS", "G", "ML");

    private DangerousGoodsValidator() {}

    /**
     * Validate the DG block on a shipment. Domestic non-hazmat shipments
     * pass through with an empty error list; only shipments where the
     * DG block is non-null run through the checks. Callers should use
     * {@link DangerousGoodsBlockDTO#isReadyForCarrier} for the wire-emit
     * gate — this method is for user-facing feedback.
     */
    public static List<ValidationError> validate(ShipmentRequestDTO request) {
        DangerousGoodsBlockDTO dg = request == null ? null : request.getDangerousGoods();
        if (dg == null) return List.of();

        List<ValidationError> errors = new ArrayList<>();

        String regulation = dg.getRegulationSet();
        if (regulation == null || regulation.isBlank()) {
            errors.add(new ValidationError(CODE_BAD_REGULATION,
                    "Dangerous goods regulation set is required (IATA | ADR | DOT)."));
        } else if (!VALID_REGULATION.contains(regulation.trim().toUpperCase())) {
            errors.add(new ValidationError(CODE_BAD_REGULATION,
                    "Regulation set '" + regulation + "' is not one of IATA, ADR, DOT."));
        }

        if (dg.getAccessibility() != null && !dg.getAccessibility().isBlank()
                && !VALID_ACCESSIBILITY.contains(dg.getAccessibility().trim().toUpperCase())) {
            errors.add(new ValidationError(CODE_BAD_REGULATION,
                    "Accessibility '" + dg.getAccessibility() + "' must be ACCESSIBLE or INACCESSIBLE."));
        }

        if (isBlank(dg.getEmergencyContactName()) || isBlank(dg.getEmergencyContactPhone())) {
            errors.add(new ValidationError(CODE_MISSING_CONTACT,
                    "24/7 emergency contact name and phone are required for DG shipments."));
        }
        if (isBlank(dg.getSignatoryName())) {
            errors.add(new ValidationError(CODE_MISSING_SIGNATORY,
                    "Legal signatory name is required for DG shipments."));
        }

        List<DangerousCommodityDTO> commodities = dg.getCommodities();
        if (commodities == null || commodities.isEmpty()) {
            errors.add(new ValidationError(CODE_NO_COMMODITIES,
                    "Dangerous goods block must list at least one commodity."));
        } else {
            for (int i = 0; i < commodities.size(); i++) {
                validateCommodity(commodities.get(i), i, errors);
            }
        }

        return errors;
    }

    private static void validateCommodity(DangerousCommodityDTO commodity, int index,
                                            List<ValidationError> errors) {
        String position = "commodity #" + (index + 1);
        if (commodity == null) {
            errors.add(new ValidationError(CODE_NO_COMMODITIES,
                    position + " is null."));
            return;
        }

        String un = commodity.getUnNumber();
        if (isBlank(un)) {
            errors.add(new ValidationError(CODE_BAD_UN_NUMBER,
                    position + ": UN number is required."));
        } else if (!UN_NUMBER_PATTERN.matcher(un.trim().toUpperCase()).matches()) {
            errors.add(new ValidationError(CODE_BAD_UN_NUMBER,
                    position + ": UN number '" + un + "' must match the format UN\\d{4} (e.g. UN3480)."));
        }

        if (isBlank(commodity.getProperShippingName())) {
            errors.add(new ValidationError(CODE_MISSING_SHIPPING_NAME,
                    position + ": proper shipping name is required."));
        }

        String hazardClass = commodity.getHazardClass();
        if (isBlank(hazardClass)) {
            errors.add(new ValidationError(CODE_BAD_HAZARD_CLASS,
                    position + ": hazard class is required."));
        } else if (!HAZARD_CLASS_PATTERN.matcher(hazardClass.trim()).matches()) {
            errors.add(new ValidationError(CODE_BAD_HAZARD_CLASS,
                    position + ": hazard class '" + hazardClass + "' must be a digit 1-9, optionally with a subclass like 4.1."));
        }

        // Packing group is optional (Class 1 / Class 7 don't have one).
        String pg = commodity.getPackingGroup();
        if (pg != null && !pg.isBlank() && !VALID_PACKING_GROUP.contains(pg.trim().toUpperCase())) {
            errors.add(new ValidationError(CODE_BAD_PACKING_GROUP,
                    position + ": packing group '" + pg + "' must be I, II, or III."));
        }

        if (commodity.getQuantity() == null
                || commodity.getQuantity().signum() <= 0) {
            errors.add(new ValidationError(CODE_ZERO_QUANTITY,
                    position + ": quantity must be > 0."));
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
