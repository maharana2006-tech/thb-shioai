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

    @Override public String originIso() { return "US"; }

    @Override public BigDecimal thresholdAmount() { return THRESHOLD; }

    @Override public String thresholdCurrency() { return "USD"; }

    @Override
    public Optional<ValidationError> evaluate(ShipmentRequestDTO request, FxRateService fx) {
        IntlShipmentBlockDTO intl = request.getIntl();
        if (intl == null || !Boolean.TRUE.equals(intl.getInternational())) return Optional.empty();

        String recipientCountry = request.getRecipientCountryCode() == null
                ? "" : request.getRecipientCountryCode().trim().toUpperCase(Locale.ROOT);
        // §30.36 bilateral — never fires for US→CA.
        if (BILATERAL_EXEMPT.contains(recipientCountry)) return Optional.empty();
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
