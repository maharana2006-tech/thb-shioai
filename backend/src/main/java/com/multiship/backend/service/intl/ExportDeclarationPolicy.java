package com.multiship.backend.service.intl;

import com.multiship.backend.dto.IntlShipmentBlockDTO;
import com.multiship.backend.dto.ShipmentRequestDTO;
import com.multiship.backend.service.IntlShipmentValidator.ValidationError;
import com.multiship.backend.service.fx.FxRateService;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * A single origin country's export-declaration regime — the compliance
 * requirement that governs whether a shipment leaving that origin needs
 * a specific reference (AES ITN, B13A, CDS, EDN, ...) at a specific
 * value threshold. One implementation per regulated origin.
 *
 * <p>PR 3 of the "handle >$2,500 for all countries" track. Replaces the
 * single US-scoped rule that lived inline in
 * {@link com.multiship.backend.service.IntlShipmentValidator} with a
 * pluggable per-origin policy. Registry lookup by ISO alpha-2 code, so
 * adding a new corridor is: new impl + one line in
 * {@link ExportDeclarationPolicyRegistry}.
 *
 * <p>REGULATORY NOTE — each policy embeds real-world statutory numbers
 * (thresholds, currencies, exemption codes). Those numbers change with
 * legislative revisions and international agreements. Every impl in
 * this package carries a {@code REGULATORY_REFERENCE} constant citing
 * the source-of-truth statute; a compliance-officer review is required
 * before adjusting or adding a corridor. This is code-shape, not legal
 * advice.
 */
public interface ExportDeclarationPolicy {

    /**
     * ISO 3166-1 alpha-2 code of the origin country this policy governs
     * (e.g. {@code "US"}, {@code "CA"}, {@code "GB"}). The registry
     * indexes policies by this key.
     */
    String originIso();

    /**
     * Evaluate the intl block against the corridor's rule and return an
     * error when the shipment crosses the threshold without an
     * acceptable declaration reference. Returns {@link Optional#empty()}
     * when the rule doesn't apply or is satisfied.
     *
     * <p>Contract:
     * <ul>
     *   <li>Called only when {@code request.shipperCountryCode} matches
     *       {@link #originIso()} (registry-enforced).</li>
     *   <li>Must fall through as empty (not error) on FX outage — safer
     *       than false-blocking on a broken rate feed.</li>
     *   <li>Must fall through as empty when the destination is
     *       explicitly exempted for this corridor (e.g. US→CA §30.36).</li>
     * </ul>
     */
    Optional<ValidationError> evaluate(ShipmentRequestDTO request, FxRateService fx);

    /**
     * True when the intl block's dutiable value exceeds this corridor's
     * threshold — helper for policy impls, exposed here so tests can
     * cross-check the FX-normalized amount. Returns empty on missing
     * value or unrecoverable FX.
     */
    default Optional<Boolean> exceedsThreshold(IntlShipmentBlockDTO intl, FxRateService fx) {
        BigDecimal amount = intl.getCustomsTotalValue();
        if (amount == null) return Optional.empty();
        String customsCurrency = intl.getCustomsCurrency() == null
                ? "USD" : intl.getCustomsCurrency().trim().toUpperCase();
        BigDecimal thresholdAmount = thresholdAmount();
        String thresholdCurrency = thresholdCurrency();
        if (customsCurrency.equals(thresholdCurrency)) {
            return Optional.of(amount.compareTo(thresholdAmount) >= 0);
        }
        if (fx == null) return Optional.empty();
        return fx.convert(amount, customsCurrency, thresholdCurrency)
                .map(converted -> converted.compareTo(thresholdAmount) >= 0);
    }

    /** Statutory threshold amount in {@link #thresholdCurrency()}. */
    BigDecimal thresholdAmount();

    /** ISO 4217 currency of {@link #thresholdAmount()} — usually the origin's own. */
    String thresholdCurrency();
}
