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
 * India — Shipping Bill (SB) required for ALL exports regardless of
 * value. No threshold exemption exists under the Customs Act 1962.
 * IEC (Importer Exporter Code) and GSTIN are also mandatory but tracked
 * on the customs profile, not here.
 *
 * <p>REGULATORY_REFERENCE — Customs Act 1962 §50 (mandatory Shipping
 * Bill) + Foreign Trade Policy 2023 §2.05 (IEC). Compliance-officer
 * review required.
 */
@Component
public class InShippingBillPolicy implements ExportDeclarationPolicy {

    /** No threshold — SB required on every export. Exposed for interface
     *  contract only; {@link #evaluate} short-circuits before the check. */
    static final BigDecimal THRESHOLD = BigDecimal.ZERO;

    @Override public String originIso() { return "IN"; }
    @Override public BigDecimal thresholdAmount() { return THRESHOLD; }
    @Override public String thresholdCurrency() { return "INR"; }

    @Override
    public Optional<ValidationError> evaluate(ShipmentRequestDTO request, FxRateService fx) {
        IntlShipmentBlockDTO intl = request.getIntl();
        if (intl == null || !Boolean.TRUE.equals(intl.getInternational())) return Optional.empty();

        String dest = request.getRecipientCountryCode() == null
                ? "" : request.getRecipientCountryCode().trim().toUpperCase(Locale.ROOT);
        if ("IN".equals(dest)) return Optional.empty();
        if (StringUtils.hasText(intl.getExportDeclarationReference())) return Optional.empty();

        // Every Indian export needs an SB — no value threshold.
        return Optional.of(new ValidationError(
                IntlShipmentValidator.CODE_IN_SB_REQUIRED,
                "Indian exports require a Shipping Bill (SB) number regardless of value. "
                        + "Provide the customs-issued SB number in the export declaration "
                        + "field before shipping."));
    }
}
