package com.multiship.backend.service;

import com.multiship.backend.dto.CustomsCommodityDTO;
import com.multiship.backend.dto.IntlShipmentBlockDTO;
import com.multiship.backend.dto.PackageDetailDTO;
import com.multiship.backend.dto.ShipmentRequestDTO;
import com.multiship.backend.model.CarrierAccountRef;
import com.multiship.backend.service.ShipmentDefaultsResolver.ShipmentDefaultsException;
import com.multiship.backend.service.fx.FxRateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * F6-D — converts every money field on a {@link ShipmentRequestDTO} into the
 * carrier account's billing currency before the connector envelope is built.
 *
 * <p>Pre-F6-D, a client whose {@code defaultCurrency} was EUR would ship
 * against a USD UPS account with EUR values still on the wire — UPS then
 * priced insurance / duties as if the EUR figures were USD, over-charging by
 * ~10% depending on the day's rate. Same class of bug on every carrier where
 * account and client currency diverge.
 *
 * <p><b>Target currency</b> (highest first):
 * <ol>
 *   <li>{@link CarrierAccountRef#getCurrency()} — the per-account override
 *       set by F6-B2. Authoritative when set.</li>
 *   <li>Per-carrier home currency — USPS / UPS / FedEx → USD, DHL → EUR.
 *       Matches what each connector's legacy hardcode assumed.</li>
 * </ol>
 *
 * <p><b>Source currency</b> = {@code request.declaredValueCurrency}, which
 * the resolver populates from customs → Client → country_currency. When
 * source equals target we skip the whole pass — no-op, no FX read.
 *
 * <p><b>Fields converted</b> (in-place mutation on the request DTO):
 * <ul>
 *   <li>{@code declaredValue} + {@code declaredValueCurrency}</li>
 *   <li>{@code insuredValue} + {@code insuredValueCurrency}</li>
 *   <li>Each {@code packages[].declaredValue}</li>
 *   <li>Each {@code intl.commodities[].unitValue}</li>
 *   <li>{@code intl.customsCurrency} + {@code intl.customsTotalValue}
 *       (recomputed from the converted lines to stay consistent)</li>
 * </ul>
 * The intl block's {@code weightUnit} / {@code dimUnit} / any non-money
 * field is untouched.
 *
 * <p><b>Failure mode</b>: if {@link FxRateService#convert} returns empty for
 * any single field the whole conversion throws
 * {@link ShipmentDefaultsException}. Reason: shipping half-converted values
 * to the carrier would over- or under-report duties, which is worse than
 * blocking one label. Callers surface the error and the shipment goes to
 * manual review.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ShipmentCurrencyConverter {

    private final FxRateService fxRateService;

    /** Per-carrier home currency used when {@code CarrierAccountRef.currency}
     *  is null. Matches the hardcoded default each connector already assumed
     *  before F6-D — keeping the mapping here avoids re-scattering it. */
    private static final Map<String, String> CARRIER_HOME_CURRENCY = Map.of(
            "USPS",  "USD",
            "STAMPS", "USD",
            "UPS",   "USD",
            "FEDEX", "USD",
            "DHL",   "EUR");

    /**
     * Convert every money field on {@code request} into the target currency
     * derived from {@code account}. Mutates {@code request} in place and
     * returns it (fluent).
     *
     * @param request the shipment DTO fresh from the builder — must have a
     *                populated {@code declaredValueCurrency} (the resolver
     *                sets it; the domestic path where currency is USD by
     *                default also lands correctly).
     * @param account the carrier account chosen for this shipment; may be
     *                null (platform / bulk-shopping path — no account
     *                override so we use the per-carrier home currency).
     */
    public ShipmentRequestDTO apply(ShipmentRequestDTO request, CarrierAccountRef account) {
        if (request == null) return null;

        String source = trimUpper(request.getDeclaredValueCurrency());
        String target = resolveTarget(request.getCarrierCode(), account);
        if (source == null || target == null || source.equals(target)) {
            // Same currency — nothing to do; the resolver already set the
            // right code on the request, connectors take it from there.
            return request;
        }

        log.debug("F6-D converting {} → {} on carrier={} account={}",
                source, target, request.getCarrierCode(),
                account == null ? "<none>" : account.getAccountNumber());

        // Top-level money fields first — declared, then insured. Insured
        // has its own currency field but historically defaults to the
        // declared currency; if the caller set it explicitly we still
        // treat it as source since the resolver only ever writes one
        // currency onto the request.
        BigDecimal newDeclared = convertOrThrow(request.getDeclaredValue(), source, target, "declaredValue");
        request.setDeclaredValue(newDeclared);
        request.setDeclaredValueCurrency(target);

        BigDecimal newInsured = convertOrThrow(request.getInsuredValue(), source, target, "insuredValue");
        request.setInsuredValue(newInsured);
        // Only overwrite when the request had SOMETHING for insurance;
        // leaving null on both value + currency keeps the "no insurance
        // requested" signal intact for connectors that key off null.
        if (newInsured != null || request.getInsuredValueCurrency() != null) {
            request.setInsuredValueCurrency(target);
        }

        // Per-package declared values (multi-package shipments — the
        // top-level declaredValue above is either the sum or null when
        // packages[] carries the per-box values).
        if (request.getPackages() != null) {
            for (PackageDetailDTO pkg : request.getPackages()) {
                if (pkg == null) continue;
                pkg.setDeclaredValue(convertOrThrow(
                        pkg.getDeclaredValue(), source, target, "packages[].declaredValue"));
            }
        }

        // Customs — the intl block if the shipment has one. Convert each
        // commodity line, then rebuild customsTotalValue from the sums so
        // the two stay consistent (the block's convention per
        // IntlShipmentBlockDTO.customsTotalValue javadoc).
        IntlShipmentBlockDTO intl = request.getIntl();
        if (intl != null) {
            List<CustomsCommodityDTO> commodities = intl.getCommodities();
            if (commodities != null) {
                for (CustomsCommodityDTO line : commodities) {
                    if (line == null) continue;
                    line.setUnitValue(convertOrThrow(
                            line.getUnitValue(), source, target, "intl.commodities[].unitValue"));
                }
            }
            intl.setCustomsCurrency(target);
            intl.setCustomsTotalValue(recomputeCustomsTotal(commodities));
        }

        return request;
    }

    // ===== helpers =====

    private String resolveTarget(String carrierCode, CarrierAccountRef account) {
        if (account != null) {
            String override = trimUpper(account.getCurrency());
            if (override != null) return override;
        }
        String key = trimUpper(carrierCode);
        if (key == null) return null;
        return CARRIER_HOME_CURRENCY.getOrDefault(key, "USD");
    }

    private BigDecimal convertOrThrow(BigDecimal amount, String from, String to, String fieldName) {
        if (amount == null) return null;
        Optional<BigDecimal> converted = fxRateService.convert(amount, from, to);
        if (converted.isEmpty()) {
            throw new ShipmentDefaultsException(
                    "F6-D currency conversion failed for '" + fieldName + "': "
                            + from + "→" + to + " rate unavailable. Either the FX rate "
                            + "feed is down or one of the currencies isn't supported. "
                            + "Fix the account currency, the client's default currency, "
                            + "or wait for the FX feed to recover.");
        }
        return converted.get();
    }

    private static BigDecimal recomputeCustomsTotal(List<CustomsCommodityDTO> commodities) {
        if (commodities == null || commodities.isEmpty()) return null;
        BigDecimal total = BigDecimal.ZERO;
        boolean any = false;
        for (CustomsCommodityDTO line : commodities) {
            if (line == null) continue;
            BigDecimal lineTotal = line.lineTotalValue();
            if (lineTotal == null) continue;
            total = total.add(lineTotal);
            any = true;
        }
        return any ? total : null;
    }

    private static String trimUpper(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t.toUpperCase(Locale.ROOT);
    }
}
