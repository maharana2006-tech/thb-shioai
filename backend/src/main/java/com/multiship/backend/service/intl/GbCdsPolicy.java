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
 * United Kingdom — HMRC Customs Declaration Service (CDS) declaration
 * required for exports at or above £873 to non-EU destinations. Below
 * the threshold, a Low Value Bulking of Exports (LVBE) simplification
 * covers most postal / express shipments.
 *
 * <p>REGULATORY_REFERENCE — HMRC Public Notice 703 §2, and the CDS
 * transition guidance. Compliance-officer review required.
 */
@Component
public class GbCdsPolicy implements ExportDeclarationPolicy {

    /** £873 — Low Value Bulking of Exports (LVBE) ceiling. */
    static final BigDecimal THRESHOLD = new BigDecimal("873");

    @Override public String originIso() { return "GB"; }
    @Override public BigDecimal thresholdAmount() { return THRESHOLD; }
    @Override public String thresholdCurrency() { return "GBP"; }

    @Override
    public Optional<ValidationError> evaluate(ShipmentRequestDTO request, FxRateService fx) {
        IntlShipmentBlockDTO intl = request.getIntl();
        if (intl == null || !Boolean.TRUE.equals(intl.getInternational())) return Optional.empty();

        String dest = request.getRecipientCountryCode() == null
                ? "" : request.getRecipientCountryCode().trim().toUpperCase(Locale.ROOT);
        if ("GB".equals(dest)) return Optional.empty();
        if (StringUtils.hasText(intl.getExportDeclarationReference())) return Optional.empty();

        return exceedsThreshold(intl, fx)
                .filter(Boolean::booleanValue)
                .map(over -> new ValidationError(
                        IntlShipmentValidator.CODE_GB_CDS_REQUIRED,
                        "UK exports valued at £" + THRESHOLD.toPlainString()
                                + " or more require an HMRC CDS declaration reference. "
                                + "Provide the CDS declaration MRN in the export declaration "
                                + "field before shipping."));
    }
}
