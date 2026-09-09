package com.multiship.backend.service.intl;

import com.multiship.backend.dto.IntlShipmentBlockDTO;
import com.multiship.backend.dto.ShipmentRequestDTO;
import com.multiship.backend.service.IntlShipmentValidator.ValidationError;
import com.multiship.backend.service.fx.FxRateService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * US Foreign Trade Regulations §30.37 — every export from the US must
 * carry an AES filing (ITN) or a legally-recognised §30.37 exemption
 * once the Schedule B commodity value crosses $2,500 USD. §30.36 is the
 * bilateral Canada exemption, applied automatically.
 *
 * <p>REGULATORY_REFERENCE — 15 CFR Part 30 Subpart B §30.37(a). Verify
 * before adjusting: <a href="https://www.ecfr.gov/current/title-15/subtitle-B/chapter-I/subchapter-C/part-30">
 * ecfr.gov/title-15/part-30</a>.
 */
@Component
public class UsFtr30_37Policy implements ExportDeclarationPolicy {

    /** Statutory threshold — 15 CFR §30.37(a). */
    static final BigDecimal THRESHOLD = new BigDecimal("2500");

    /** Destinations that get a bilateral exemption (§30.36) — no filing needed. */
    private static final Set<String> BILATERAL_EXEMPT = Set.of("CA");

    /**
     * REGULATORY_REFERENCE — 15 CFR Part 30 Subpart B §30.37(a) +
     * §30.1(c). Empty by operator direction 2026-09-09: PR and VI DO
     * require EEI filing above the $2,500 Schedule-B threshold same as
     * every other non-Canada destination. Prior reading (PR #626,
     * 2026-09-08) treated PR/VI as "within the US customs territory
     * per §30.1(c)" and skipped the gate — that interpretation is
     * defensible on the letter of the reg but doesn't match how FedEx
     * and UPS actually surface the FTR box in their own tools, and the
     * operator's compliance stance is the safer position: file when
     * over $2,500 regardless of customs-territory status.
     *
     * <p>Compliance officer sign-off required before reverting to a
     * non-empty exempt set. Cite the §30.1(c) reading + carrier tool
     * behaviour in any change-request. See
     * <a href="https://www.ecfr.gov/current/title-15/subtitle-B/chapter-I/subchapter-C/part-30/subpart-B/section-30.37">
     * §30.37(a)</a> +
     * <a href="https://www.ecfr.gov/current/title-15/subtitle-B/chapter-I/subchapter-C/part-30/subpart-A/section-30.1">
     * §30.1(c)</a>.
     */
    private static final Set<String> CUSTOMS_TERRITORY_EXEMPT = Set.of();

    @Override public String originIso() { return "US"; }

    @Override public BigDecimal thresholdAmount() { return THRESHOLD; }

    @Override public String thresholdCurrency() { return "USD"; }

    @Override
    public Optional<ValidationError> evaluate(ShipmentRequestDTO request, FxRateService fx) {
        IntlShipmentBlockDTO intl = request.getIntl();
        if (intl == null || !Boolean.TRUE.equals(intl.getInternational())) return Optional.empty();

        // Normalize US-territory state → country so US+state=GU maps to
        // country=GU (fires the export gate) while US+state=PR/VI maps
        // to PR/VI (hits CUSTOMS_TERRITORY_EXEMPT below). Mirrors the
        // connector-side wire rewrite from UsTerritoryNormalizer.
        String recipientCountry = com.multiship.backend.util.UsTerritoryNormalizer
                .normalizeCountryCode(request.getRecipientCountryCode(), request.getRecipientState());
        recipientCountry = recipientCountry == null
                ? "" : recipientCountry.trim().toUpperCase(Locale.ROOT);
        // §30.36 bilateral — never fires for US→CA.
        if (BILATERAL_EXEMPT.contains(recipientCountry)) return Optional.empty();
        // §30.1(c) — PR + VI are within US customs territory, not exports.
        if (CUSTOMS_TERRITORY_EXEMPT.contains(recipientCountry)) return Optional.empty();
        // Domestic (US→US) — not an export.
        if ("US".equals(recipientCountry)) return Optional.empty();
        // Already satisfied — operator supplied an FTR exemption or AES ITN.
        if (StringUtils.hasText(intl.getFtrExemption())
                || StringUtils.hasText(intl.getAesCitation())) return Optional.empty();

        return exceedsThreshold(intl, fx)
                .filter(Boolean::booleanValue)
                .map(over -> new ValidationError(
                        com.multiship.backend.service.IntlShipmentValidator.CODE_EEI_REQUIRED,
                        "US exports valued at $" + THRESHOLD.toPlainString()
                                + " or more (per Schedule B code) to non-Canada destinations "
                                + "require either an AES Citation (ITN) or an FTR §30.37 exemption. "
                                + "Provide one on the international details step before shipping."));
    }
}
