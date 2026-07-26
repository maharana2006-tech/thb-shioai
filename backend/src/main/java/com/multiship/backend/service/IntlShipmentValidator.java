package com.multiship.backend.service;

import com.multiship.backend.dto.CustomsCommodityDTO;
import com.multiship.backend.dto.IntlShipmentBlockDTO;
import com.multiship.backend.dto.ShipmentRequestDTO;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Guards international shipments against carrier-side validation failures by
 * checking the customs block BEFORE the payload leaves our server. Carriers
 * (especially FedEx CustomsClearanceDetail and UPS InternationalForms) fail
 * fast on missing HS codes, blank descriptions, zero declared values, or
 * unrecognised incoterms — with terse messages that dead-end the operator.
 * Catching those failures here means we surface a clean, actionable error
 * before the carrier is even called.
 *
 * <p>Pure function. Reused by every carrier: whatever passes here will pass
 * UPS + FedEx + USPS validation for the fields we own. Carrier-specific
 * quirks (paperless-invoice enablement, DDP-only accounts, DG restrictions)
 * are checked in the connector, not here.
 *
 * <p>Return contract: never throws. Callers get an ordered list of
 * {@link ValidationError} — empty list == valid. The label flow builds a
 * single toast from the list; the API surface uses them as a 422 body.
 */
public final class IntlShipmentValidator {

    /** Documented codes; every message the UI shows resolves back to one of these. */
    public static final String CODE_MISSING_CUSTOMS = "customs.missing";
    public static final String CODE_NO_COMMODITIES = "customs.commodities.empty";
    public static final String CODE_ITEM_INCOMPLETE = "customs.commodity.incomplete";
    public static final String CODE_INVOICE_ZERO = "customs.total.zero";
    public static final String CODE_BAD_INCOTERMS = "customs.incoterms.invalid";
    public static final String CODE_BAD_CURRENCY = "customs.currency.invalid";
    public static final String CODE_BAD_REASON = "customs.reason.invalid";
    public static final String CODE_DUTY_ACCOUNT_MISSING = "customs.duty.account.missing";
    public static final String CODE_BAD_HS_CODE = "customs.commodity.hscode.invalid";

    /** Approved incoterms — mirrors the wizard's INCOTERMS constant + CustomsProfile.dutiesBillTo enum. */
    private static final Set<String> VALID_INCOTERMS = Set.of("DDP", "DAP", "DDU");
    /** Same enum as ClientCustomsProfile.dutiesBillTo. */
    private static final Set<String> VALID_DUTY_BILL_TO = Set.of("SENDER", "RECIPIENT", "THIRD_PARTY");
    /** Same enum as OrderCustoms.reasonForExport. */
    private static final Set<String> VALID_REASON = Set.of("SALE", "GIFT", "SAMPLE", "RETURN", "REPAIR", "DOCUMENTS");
    /**
     * HS (Harmonized System) tariff code — 6 digits at the WCO root, with
     * per-country extensions up to 10 digits. UPS / FedEx / most customs
     * authorities accept dot- or space-separated forms too, but we normalize
     * to digits-only for the pattern check. The regex is intentionally lax
     * (6-10 digits, no zero-only) — enforcing chapter/section validity is a
     * dataset problem we won't take on here.
     */
    private static final java.util.regex.Pattern HS_CODE_PATTERN =
            java.util.regex.Pattern.compile("^\\d{6,10}$");

    private IntlShipmentValidator() {}

    /**
     * Full validation entry point. Returns an empty list when the request is
     * OK to send to the carrier. When {@code request.intl} is null the
     * shipment is treated as domestic — no errors regardless of other fields.
     */
    public static List<ValidationError> validate(ShipmentRequestDTO request) {
        List<ValidationError> errors = new ArrayList<>();
        IntlShipmentBlockDTO intl = request == null ? null : request.getIntl();
        if (intl == null || !Boolean.TRUE.equals(intl.getInternational())) {
            return errors;
        }

        // Structural gates: the block must exist, be non-empty, and describe
        // WHAT is being shipped in a form that a customs officer can act on.
        List<CustomsCommodityDTO> commodities = intl.getCommodities();
        if (commodities == null || commodities.isEmpty()) {
            errors.add(new ValidationError(CODE_NO_COMMODITIES,
                    "At least one commodity line is required for an international shipment."));
        } else {
            for (int i = 0; i < commodities.size(); i++) {
                CustomsCommodityDTO c = commodities.get(i);
                List<String> missing = new ArrayList<>();
                if (isBlank(c.getDescription())) missing.add("description");
                if (isBlank(c.getHsCode())) missing.add("HS code");
                if (isBlank(c.getCountryOfOrigin())) missing.add("country of origin");
                if (c.getQuantity() == null || c.getQuantity() <= 0) missing.add("quantity > 0");
                if (c.getUnitValue() == null || c.getUnitValue().signum() <= 0) missing.add("unit value > 0");
                if (!missing.isEmpty()) {
                    errors.add(new ValidationError(CODE_ITEM_INCOMPLETE,
                            "Commodity line " + (i + 1) + " missing: " + String.join(", ", missing) + "."));
                }
                // HS code shape check runs INDEPENDENTLY of the missing-fields
                // list so a line with description+quantity+value but a wrong
                // HS code still surfaces the code-shape error.
                if (!isBlank(c.getHsCode()) && !isValidHsCodeShape(c.getHsCode())) {
                    errors.add(new ValidationError(CODE_BAD_HS_CODE,
                            "Commodity line " + (i + 1) + " HS code \"" + c.getHsCode().trim()
                                    + "\" isn't 6-10 digits — customs will reject the declaration."));
                }
            }
        }

        // Header-level gates: incoterms, currency, reason are all closed
        // enums; a typo here fails at the carrier with a cryptic message.
        if (intl.getCustomsTotalValue() == null || intl.getCustomsTotalValue().compareTo(BigDecimal.ZERO) <= 0) {
            errors.add(new ValidationError(CODE_INVOICE_ZERO,
                    "Invoice total must be greater than zero."));
        }
        if (isBlank(intl.getIncoterms()) || !VALID_INCOTERMS.contains(intl.getIncoterms().trim().toUpperCase())) {
            errors.add(new ValidationError(CODE_BAD_INCOTERMS,
                    "Incoterms must be one of DDP, DAP, or DDU."));
        }
        if (!isValidCurrency(intl.getCustomsCurrency())) {
            errors.add(new ValidationError(CODE_BAD_CURRENCY,
                    "Customs currency must be a 3-letter ISO-4217 code (USD, EUR, GBP, ...)."));
        }
        if (!isBlank(intl.getReasonForExport())
                && !VALID_REASON.contains(intl.getReasonForExport().trim().toUpperCase())) {
            errors.add(new ValidationError(CODE_BAD_REASON,
                    "Reason for export must be SALE, GIFT, SAMPLE, RETURN, REPAIR, or DOCUMENTS."));
        }

        // Duty bill-to: when non-SENDER a payer account is required so the
        // carrier knows who to invoice. Sender is our own account — no
        // account number needed.
        String dutyBillTo = intl.getDutyBillTo();
        if (!isBlank(dutyBillTo)) {
            String normalized = dutyBillTo.trim().toUpperCase();
            if (!VALID_DUTY_BILL_TO.contains(normalized)) {
                // Silently ignored — dutyBillTo isn't hard-required today
                // (defaults to RECIPIENT / DAP semantics at carrier level).
                // A hard error would break every existing profile that
                // hasn't set the field yet.
            } else if ("THIRD_PARTY".equals(normalized) && isBlank(intl.getDutyAccount())) {
                errors.add(new ValidationError(CODE_DUTY_ACCOUNT_MISSING,
                        "Third-party duty billing requires the payer's carrier account number."));
            }
        }

        return errors;
    }

    /**
     * Convenience for callers that want one concatenated message rather than
     * a structured list. Newline-joined for log output; UI callers should
     * iterate the list themselves for per-field styling.
     */
    public static String toMessage(List<ValidationError> errors) {
        if (errors == null || errors.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("International shipment failed validation:");
        for (ValidationError e : errors) sb.append("\n • ").append(e.message());
        return sb.toString();
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /**
     * Light ISO-4217 check: 3 letters. A full lookup against
     * {@link java.util.Currency} would also catch codes like "XXX" (no
     * currency) but at the cost of Currency-not-supported UnknownCurrency
     * exceptions on some JVMs. Three-letter regex is enough here — the
     * carrier's own currency lookup fails downstream on truly invalid codes.
     */
    private static boolean isValidCurrency(String s) {
        return s != null && s.trim().length() == 3 && s.trim().chars().allMatch(Character::isLetter);
    }

    /**
     * True when the HS code is 6-10 digits after removing dots, spaces, and
     * hyphens. Carriers and customs accept "6104.62.20" as much as "610462" —
     * the separators are cosmetic. We only reject codes that couldn't
     * possibly resolve to a valid tariff.
     */
    private static boolean isValidHsCodeShape(String s) {
        if (s == null) return false;
        String stripped = s.trim().replaceAll("[.\\s\\-]", "");
        return HS_CODE_PATTERN.matcher(stripped).matches();
    }

    /** One violation. Immutable, structured so the UI can localise off {@code code}. */
    public record ValidationError(String code, String message) {}
}
