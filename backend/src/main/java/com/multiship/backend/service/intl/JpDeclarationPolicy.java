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
 * Japan — Export declaration (輸出申告) required for exports at or above
 * ¥200,000. Below the threshold, exports move on the simplified customs
 * clearance for small parcels.
 *
 * <p>REGULATORY_REFERENCE — Customs Law Article 67 + JCS Notice 68-3-2
 * (small parcel exemption). Compliance-officer review required.
 */
@Component
public class JpDeclarationPolicy implements ExportDeclarationPolicy {

    /** ¥200,000 — Customs Law Article 67 small-parcel ceiling. */
    static final BigDecimal THRESHOLD = new BigDecimal("200000");

    @Override public String originIso() { return "JP"; }
    @Override public BigDecimal thresholdAmount() { return THRESHOLD; }
    @Override public String thresholdCurrency() { return "JPY"; }

    @Override
    public Optional<ValidationError> evaluate(ShipmentRequestDTO request, FxRateService fx) {
        IntlShipmentBlockDTO intl = request.getIntl();
        if (intl == null || !Boolean.TRUE.equals(intl.getInternational())) return Optional.empty();

        String dest = request.getRecipientCountryCode() == null
                ? "" : request.getRecipientCountryCode().trim().toUpperCase(Locale.ROOT);
        if ("JP".equals(dest)) return Optional.empty();
        if (StringUtils.hasText(intl.getExportDeclarationReference())) return Optional.empty();

        return exceedsThreshold(intl, fx)
                .filter(Boolean::booleanValue)
                .map(over -> new ValidationError(
                        IntlShipmentValidator.CODE_JP_DECLARATION_REQUIRED,
                        "Japanese exports valued at ¥" + THRESHOLD.toPlainString()
                                + " or more require an export declaration (輸出申告). "
                                + "Provide the JCS-issued declaration ID in the export "
                                + "declaration field before shipping."));
    }
}
