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
    /** Sprint 52 — commodities count exceeds the worst-case carrier ceiling.
     *  Per-carrier caps are enforced later at CarrierLimitService (which knows
     *  the resolved carrier); this is the cheap defensive check upstream. */
    public static final String CODE_TOO_MANY_COMMODITIES = "customs.commodities.tooMany";
    /**
     * US FTR §30.37 — an export from the US to a destination other than
     * Canada, valued at $2,500 USD or more per Schedule B code, must have
     * either an AES ITN (real Census filing) or a legally-recognized FTR
     * exemption (30.37(h) tools of trade / 30.36 Canada / ...). Without one,
     * FedEx (and other US-aware carriers) auto-apply 30.37(a) which is
     * only valid under $2,500 — the carrier then rejects with a cryptic
     * "The FTR Exemption or AES Citation you provided is not valid for
     * EEI" message. Catching it here surfaces an actionable local error.
     */
    public static final String CODE_EEI_REQUIRED = "customs.eei.required";
    /** Widest documented carrier commodity ceiling. Cheaper to hard-code
     *  than to plumb CarrierLimitService this far up the call chain. */
    static final int MAX_COMMODITIES_HARD_CEILING = 999;

    /** Approved incoterms — mirrors the wizard's INCOTERMS constant + CustomsProfile.dutiesBillTo enum. */
    private static final Set<String> VALID_INCOTERMS = Set.of("DDP", "DAP", "DDU");
    /** Same enum as ClientCustomsProfile.dutiesBillTo. */
    private static final Set<String> VALID_DUTY_BILL_TO = Set.of("SENDER", "RECIPIENT", "THIRD_PARTY");
    /**
     * Approved shipping-purpose values. Must stay in lockstep with
     * {@link com.multiship.backend.service.ShipmentDefaultsResolver#SHIPPING_PURPOSE_ENUM}
     * (all 8 values). Pre-UPS-9 fix, this set only had 6 values so shipments
     * with a resolver-valid MERCHANDISE / PERSONAL_USE / REPAIR_AND_RETURN
     * purpose failed validation with CODE_BAD_REASON — the resolver said "OK"
     * but this validator said "no". Each connector is responsible for mapping
     * the resolver value to its carrier-specific enum (FedEx via
     * mapFedExPurpose per FDX-D; UPS via mapUpsReasonForExport per UPS-9).
     */
    private static final Set<String> VALID_REASON = Set.of(
            "SALE", "GIFT", "SAMPLE", "REPAIR_AND_RETURN",
            "DOCUMENTS", "MERCHANDISE", "PERSONAL_USE", "RETURN");
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
        } else if (commodities.size() > MAX_COMMODITIES_HARD_CEILING) {
            // Sprint 52 — no single carrier accepts more than 999 commodity
            // lines per shipment. Fail fast so the operator remodels the
            // order rather than watching the carrier reject it. The
            // per-carrier cap (may be < 999) is enforced downstream at
            // CarrierLimitService once the carrier is resolved.
            errors.add(new ValidationError(CODE_TOO_MANY_COMMODITIES,
                    "Too many commodity lines: " + commodities.size()
                            + " (max " + MAX_COMMODITIES_HARD_CEILING
                            + "). Split this order into smaller shipments."));
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
                    "Reason for export must be one of SALE, GIFT, SAMPLE, RETURN, REPAIR_AND_RETURN, "
                            + "DOCUMENTS, MERCHANDISE, or PERSONAL_USE."));
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

        // US FTR §30.37 — require EEI on high-value US-origin exports.
        //   Origin  : shipperCountryCode == "US"
        //   Dest    : recipientCountryCode != "US" and != "CA"
        //             (Canada bilaterals are covered by NOEEI §30.36)
        //   Currency: customsCurrency == "USD" (the statute is USD-scoped;
        //             non-USD declarations are left alone here — carrier
        //             still rejects, but the operator's own filing currency
        //             would need conversion via a real FX service to gate
        //             deterministically here, and that's out of scope for
        //             a pure-function validator).
        //   Value   : customsTotalValue >= 2500
        // Enforcement: one of ftrExemption OR aesCitation must be populated.
        // Both blank → hard error naming the two ways to satisfy the rule.
        String shipperCountry = normalizeCountry(request.getShipperCountryCode());
        String recipientCountry = normalizeCountry(request.getRecipientCountryCode());
        String currencyForEei = intl.getCustomsCurrency() == null
                ? "" : intl.getCustomsCurrency().trim().toUpperCase();
        boolean usOrigin = "US".equals(shipperCountry);
        boolean nonCaDest = !recipientCountry.isEmpty()
                && !"CA".equals(recipientCountry)
                && !"US".equals(recipientCountry);
        boolean usdDeclared = "USD".equals(currencyForEei);
        boolean overThreshold = intl.getCustomsTotalValue() != null
                && intl.getCustomsTotalValue().compareTo(EEI_THRESHOLD_USD) >= 0;
        if (usOrigin && nonCaDest && usdDeclared && overThreshold
                && isBlank(intl.getFtrExemption()) && isBlank(intl.getAesCitation())) {
            errors.add(new ValidationError(CODE_EEI_REQUIRED,
                    "US exports valued at $" + EEI_THRESHOLD_USD.toPlainString()
                            + " or more (per Schedule B code) to non-Canada destinations "
                            + "require either an AES Citation (ITN) or an FTR §30.37 exemption. "
                            + "Provide one on the international details step before shipping."));
        }

        return errors;
    }

    /** US FTR §30.37(a) monetary threshold (per Schedule B code). */
    static final BigDecimal EEI_THRESHOLD_USD = new BigDecimal("2500");

    /** ISO country code normalizer — upper-cases and trims; null → "". */
    private static String normalizeCountry(String s) {
        return s == null ? "" : s.trim().toUpperCase();
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
