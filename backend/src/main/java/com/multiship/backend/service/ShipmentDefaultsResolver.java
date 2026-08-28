package com.multiship.backend.service;

import com.multiship.backend.model.CarrierAccountRef;
import com.multiship.backend.model.Client;
import com.multiship.backend.model.CountryCurrency;
import com.multiship.backend.model.OrderCustoms;
import com.multiship.backend.repository.CountryCurrencyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * F6-B3 — resolves per-shipment defaults from a documented precedence
 * chain so every carrier connector sees a consistent, filled-in
 * {@code ShipmentRequestDTO}.
 *
 * <p>Six fields are resolved:
 * <table>
 *   <tr><th>Field</th><th>Precedence (highest first)</th><th>On-blank behavior</th></tr>
 *   <tr><td>currency</td><td>request → customs → Client.defaultCurrency → country_currency(client.shipFrom.country)</td><td>Domestic → "USD"; International → throw</td></tr>
 *   <tr><td>weightUnit</td><td>request → customs → Client.defaultWeightUnit</td><td>"LB"</td></tr>
 *   <tr><td>dimUnit</td><td>request → customs.dimUnit → Client.defaultDimUnit</td><td>"IN"</td></tr>
 *   <tr><td>timezone</td><td>request → Client.timezone</td><td>null (F6-E consumers no-op)</td></tr>
 *   <tr><td>shippingPurpose</td><td>request (customs.reasonForExport) → CarrierAccountRef.shippingPurpose</td><td>"SALE"; VALIDATED against the 8-value enum, throws on unknown</td></tr>
 *   <tr><td>clearanceOption</td><td>request → CarrierAccountRef.clearanceOption</td><td>null (connector applies its own carrier default)</td></tr>
 * </table>
 *
 * <p>Design choices locked with the user in F6-A:
 * <ul>
 *   <li>Currency-wide fields (currency, weightUnit, dimUnit, timezone) fall
 *       through to the client-level default; per-account overrides don't
 *       apply. Rationale: these change rarely per lane.</li>
 *   <li>Per-lane fields (shippingPurpose, clearanceOption) fall through to
 *       the carrier-account default, NOT the client. Rationale: a return
 *       account might use REPAIR_AND_RETURN; a bulk-outbound account uses
 *       MERCHANDISE — different accounts on the same client differ.</li>
 *   <li>{@link #resolve} is a pure function of its inputs — no side effects,
 *       no writes, no caching. Callers that need the same result twice can
 *       call twice for free (aside from the country_currency read).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ShipmentDefaultsResolver {

    private final CountryCurrencyRepository countryCurrencyRepository;

    /** The 8 valid {@code shippingPurpose} values — must stay in sync with
     *  {@code multiship-react/src/utils/customsOptions.ts SHIPPING_PURPOSES}.
     *  Uppercase for case-insensitive comparison. */
    static final Set<String> SHIPPING_PURPOSE_ENUM = Set.of(
            "SALE", "GIFT", "SAMPLE", "REPAIR_AND_RETURN",
            "DOCUMENTS", "MERCHANDISE", "PERSONAL_USE", "RETURN");

    /** Domestic-safe currency default. Carrier connectors bill USD for US
     *  domestic labels regardless of client / country. */
    static final String DEFAULT_CURRENCY_DOMESTIC = "USD";
    static final String DEFAULT_WEIGHT_UNIT = "LB";
    static final String DEFAULT_DIM_UNIT = "IN";
    /** Domestic-safe purpose fallback — matches what every connector's
     *  legacy hardcode picks when the customs record is missing. */
    static final String DEFAULT_SHIPPING_PURPOSE = "SALE";
    /** FDX-H2 — pre-fix FedEx hardcode. FedEx accepts this even for accounts
     *  without a standing pickup (they'll reject at label-buy time with an
     *  actionable error); every other carrier ignores the field. */
    static final String DEFAULT_PICKUP_TYPE = "USE_SCHEDULED_PICKUP";
    /** UPS-4b — pre-fix UPS hardcode. GIF is the smallest wire size and
     *  UPS's own default; every other carrier ignores the field. */
    static final String DEFAULT_LABEL_IMAGE_FORMAT = "GIF";
    /** FDX-H3 — pre-fix FedEx labelSpecification hardcode. PDF/PAPER_4X6
     *  matches the thermal 4x6 format the other carriers default to; every
     *  other carrier ignores these fields. */
    static final String DEFAULT_LABEL_IMAGE_TYPE = "PDF";
    static final String DEFAULT_LABEL_STOCK_TYPE = "PAPER_4X6";

    /**
     * Resolves defaults for a single shipment. Every {@code inputs} field is
     * optional; the resolver walks the precedence chain and returns whatever
     * survives.
     *
     * @throws ShipmentDefaultsException when a required field can't be resolved:
     *   currency on an international shipment with no fallback source, or
     *   shippingPurpose set to a value outside {@link #SHIPPING_PURPOSE_ENUM}.
     */
    @Transactional(readOnly = true)
    public ResolvedDefaults resolve(ResolveInputs inputs) {
        if (inputs == null) {
            throw new ShipmentDefaultsException(
                    "ShipmentDefaultsResolver.resolve requires non-null inputs.");
        }
        return new ResolvedDefaults(
                resolveCurrency(inputs),
                resolveWeightUnit(inputs),
                resolveDimUnit(inputs),
                resolveTimezone(inputs),
                resolveShippingPurpose(inputs),
                resolveClearanceOption(inputs),
                resolvePickupType(inputs),
                resolveLabelImageFormat(inputs),
                resolveLabelImageType(inputs),
                resolveLabelStockType(inputs));
    }

    // ===== per-field resolvers =====

    private String resolveCurrency(ResolveInputs inputs) {
        // Request (typically ShipmentRequestDTO.declaredValueCurrency).
        String candidate = trimUpper(inputs.requestCurrency());
        if (candidate != null) return candidate;
        // Customs (per-shipment override on IntlShipmentBlockDTO.customsCurrency).
        if (inputs.customs() != null) {
            candidate = trimUpper(inputs.customs().getCurrency());
            if (candidate != null) return candidate;
        }
        // Client (per-tenant default).
        if (inputs.client() != null) {
            candidate = trimUpper(inputs.client().getDefaultCurrency());
            if (candidate != null) return candidate;
        }
        // country_currency (by client's ship-from country).
        String shipFromCountry = null;
        if (inputs.client() != null && inputs.client().getShipFrom() != null) {
            shipFromCountry = trimUpper(inputs.client().getShipFrom().getCountry());
        }
        if (shipFromCountry != null) {
            Optional<CountryCurrency> mapped = countryCurrencyRepository
                    .findByCountryCodeIgnoreCase(shipFromCountry);
            if (mapped.isPresent()) {
                return mapped.get().getCurrencyCode();
            }
        }
        // Last-resort split — domestic gets USD, international gets a throw.
        if (inputs.international()) {
            throw new ShipmentDefaultsException(
                    "No currency configured for international shipment: request.customs, "
                            + "Client.defaultCurrency, and country_currency lookup all empty. "
                            + "Set Client.defaultCurrency or ensure the client's ship-from "
                            + "country is populated + present in country_currency.");
        }
        return DEFAULT_CURRENCY_DOMESTIC;
    }

    private String resolveWeightUnit(ResolveInputs inputs) {
        String candidate = trimUpper(inputs.requestWeightUnit());
        if (candidate != null) return candidate;
        if (inputs.customs() != null) {
            candidate = trimUpper(inputs.customs().getWeightUnit());
            if (candidate != null) return candidate;
        }
        if (inputs.client() != null) {
            candidate = trimUpper(inputs.client().getDefaultWeightUnit());
            if (candidate != null) return candidate;
        }
        return DEFAULT_WEIGHT_UNIT;
    }

    private String resolveDimUnit(ResolveInputs inputs) {
        String candidate = trimUpper(inputs.requestDimUnit());
        if (candidate != null) return candidate;
        if (inputs.customs() != null) {
            candidate = trimUpper(inputs.customs().getDimUnit());
            if (candidate != null) return candidate;
        }
        if (inputs.client() != null) {
            candidate = trimUpper(inputs.client().getDefaultDimUnit());
            if (candidate != null) return candidate;
        }
        return DEFAULT_DIM_UNIT;
    }

    private String resolveTimezone(ResolveInputs inputs) {
        // Timezone preserves case (IANA IDs like "America/New_York" are case-sensitive).
        String candidate = trim(inputs.requestTimezone());
        if (candidate != null) return candidate;
        if (inputs.client() != null) {
            candidate = trim(inputs.client().getTimezone());
            if (candidate != null) return candidate;
        }
        return null;
    }

    private String resolveShippingPurpose(ResolveInputs inputs) {
        String candidate = trimUpper(inputs.requestShippingPurpose());
        if (candidate == null && inputs.account() != null) {
            candidate = trimUpper(inputs.account().getShippingPurpose());
        }
        if (candidate == null) {
            candidate = DEFAULT_SHIPPING_PURPOSE;
        }
        if (!SHIPPING_PURPOSE_ENUM.contains(candidate)) {
            throw new ShipmentDefaultsException(
                    "shippingPurpose '" + candidate + "' isn't a recognised value; "
                            + "expected one of: " + SHIPPING_PURPOSE_ENUM);
        }
        return candidate;
    }

    private String resolveClearanceOption(ResolveInputs inputs) {
        // Per-carrier vocabulary passed through verbatim (F6-A locked
        // decision: no normalization layer, each connector maps its own).
        String candidate = trimUpper(inputs.requestClearanceOption());
        if (candidate != null) return candidate;
        if (inputs.account() != null) {
            candidate = trimUpper(inputs.account().getClearanceOption());
            if (candidate != null) return candidate;
        }
        return null;
    }

    /**
     * FDX-H2 — pickupType precedence: request → account → hardcoded
     * USE_SCHEDULED_PICKUP fallback. Only FedEx consumes this; other
     * connectors accept the value but no-op on it. Return labels
     * bypass this in the connector (force-set to
     * CONTACT_FEDEX_TO_SCHEDULE) so the resolver doesn't need a
     * return-vs-outbound branch here.
     */
    private String resolvePickupType(ResolveInputs inputs) {
        String candidate = trimUpper(inputs.requestPickupType());
        if (candidate != null) return candidate;
        if (inputs.account() != null) {
            candidate = trimUpper(inputs.account().getPickupType());
            if (candidate != null) return candidate;
        }
        return DEFAULT_PICKUP_TYPE;
    }

    /**
     * UPS-4b — labelImageFormat precedence: request → account → hardcoded
     * GIF fallback. Only UPS consumes this; other connectors ignore.
     */
    private String resolveLabelImageFormat(ResolveInputs inputs) {
        String candidate = trimUpper(inputs.requestLabelImageFormat());
        if (candidate != null) return candidate;
        if (inputs.account() != null) {
            candidate = trimUpper(inputs.account().getLabelImageFormat());
            if (candidate != null) return candidate;
        }
        return DEFAULT_LABEL_IMAGE_FORMAT;
    }

    /**
     * FDX-H3 — labelImageType precedence: request → account → hardcoded PDF
     * fallback. Only FedEx consumes this; other connectors ignore.
     */
    private String resolveLabelImageType(ResolveInputs inputs) {
        String candidate = trimUpper(inputs.requestLabelImageType());
        if (candidate != null) return candidate;
        if (inputs.account() != null) {
            candidate = trimUpper(inputs.account().getFedexLabelImageType());
            if (candidate != null) return candidate;
        }
        return DEFAULT_LABEL_IMAGE_TYPE;
    }

    /**
     * FDX-H3 — labelStockType precedence: request → account → hardcoded
     * PAPER_4X6 fallback. Only FedEx consumes this; other connectors ignore.
     */
    private String resolveLabelStockType(ResolveInputs inputs) {
        String candidate = trimUpper(inputs.requestLabelStockType());
        if (candidate != null) return candidate;
        if (inputs.account() != null) {
            candidate = trimUpper(inputs.account().getFedexLabelStockType());
            if (candidate != null) return candidate;
        }
        return DEFAULT_LABEL_STOCK_TYPE;
    }

    // ===== helpers =====

    private static String trim(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String trimUpper(String s) {
        String t = trim(s);
        return t == null ? null : t.toUpperCase(Locale.ROOT);
    }

    // ===== input + output types =====

    /**
     * Bundle of everything the resolver reads. All fields nullable so callers
     * can build partial inputs without gymnastics; the resolver copes with
     * missing sources by walking further down the precedence chain.
     */
    public record ResolveInputs(
            String requestCurrency,
            String requestWeightUnit,
            String requestDimUnit,
            String requestTimezone,
            String requestShippingPurpose,
            String requestClearanceOption,
            String requestPickupType,
            String requestLabelImageFormat,
            String requestLabelImageType,
            String requestLabelStockType,
            OrderCustoms customs,
            Client client,
            CarrierAccountRef account,
            boolean international) {}

    /** Immutable snapshot of resolved values. Consumers read straight into
     *  the ShipmentRequestDTO builder. */
    public record ResolvedDefaults(
            String currency,
            String weightUnit,
            String dimUnit,
            String timezone,
            String shippingPurpose,
            String clearanceOption,
            String pickupType,
            String labelImageFormat,
            String labelImageType,
            String labelStockType) {}

    /** Thrown when a required field can't be resolved OR shippingPurpose
     *  is set to a value outside the enum. */
    public static class ShipmentDefaultsException extends RuntimeException {
        public ShipmentDefaultsException(String message) { super(message); }
    }
}
