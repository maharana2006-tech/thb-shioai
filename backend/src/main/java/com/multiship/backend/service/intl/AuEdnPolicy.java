package com.multiship.backend.service.intl;

import com.multiship.backend.dto.IntlShipmentBlockDTO;
import com.multiship.backend.dto.ShipmentRequestDTO;
import com.multiship.backend.service.IntlShipmentValidator;
import com.multiship.backend.service.IntlShipmentValidator.ValidationError;
import com.multiship.backend.service.fx.FxRateService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Optional;

/**
 * Australia — Export Declaration Number (EDN) required for exports at
 * or above AUD $2,000 (FOB). Below the threshold, exports move on the
 * SAC (Self-Assessed Clearance) simplification.
 *
 * <p>REGULATORY_REFERENCE — Customs Act 1901 §113 + ABF Notice 2023-42
 * (SAC threshold). Compliance-officer review required.
 */
@Component
public class AuEdnPolicy implements ExportDeclarationPolicy {

    /** AUD $2,000 — Customs Act §113 SAC ceiling. */
    static final BigDecimal THRESHOLD = new BigDecimal("2000");

    @Override public String originIso() { return "AU"; }
    @Override public BigDecimal thresholdAmount() { return THRESHOLD; }
    @Override public String thresholdCurrency() { return "AUD"; }

    @Override
    public Optional<ValidationError> evaluate(ShipmentRequestDTO request, FxRateService fx) {
        IntlShipmentBlockDTO intl = request.getIntl();
        if (intl == null || !Boolean.TRUE.equals(intl.getInternational())) return Optional.empty();

        String dest = request.getRecipientCountryCode() == null
                ? "" : request.getRecipientCountryCode().trim().toUpperCase(Locale.ROOT);
        if ("AU".equals(dest)) return Optional.empty();
        if (StringUtils.hasText(intl.getExportDeclarationReference())) return Optional.empty();

        return exceedsThreshold(intl, fx)
                .filter(Boolean::booleanValue)
                .map(over -> new ValidationError(
                        IntlShipmentValidator.CODE_AU_EDN_REQUIRED,
                        "Australian exports valued at AUD $" + THRESHOLD.toPlainString()
                                + " (FOB) or more require an Export Declaration Number (EDN). "
                                + "Provide the ABF-issued EDN in the export declaration field "
                                + "before shipping."));
    }
}
