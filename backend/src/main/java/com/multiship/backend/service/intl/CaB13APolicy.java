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
import java.util.Set;

/**
 * Canada — B13A export declaration required for exports to destinations
 * OUTSIDE the US at or above CAD $2,000. US-bound exports are exempt
 * (CBSA sees them as domestic-continental).
 *
 * <p>REGULATORY_REFERENCE — CBSA Memorandum D20-1-0 §5 (Reporting
 * Requirements). Compliance-officer review required before adjusting
 * threshold or destination-exempt list.
 */
@Component
public class CaB13APolicy implements ExportDeclarationPolicy {

    /** CAD $2,000 — per CBSA D20-1-0 §5. */
    static final BigDecimal THRESHOLD = new BigDecimal("2000");

    /** Destinations exempt from B13A reporting (continental exemption). */
    private static final Set<String> B13A_EXEMPT_DESTINATIONS = Set.of("US");

    @Override public String originIso() { return "CA"; }
    @Override public BigDecimal thresholdAmount() { return THRESHOLD; }
    @Override public String thresholdCurrency() { return "CAD"; }

    @Override
    public Optional<ValidationError> evaluate(ShipmentRequestDTO request, FxRateService fx) {
        IntlShipmentBlockDTO intl = request.getIntl();
        if (intl == null || !Boolean.TRUE.equals(intl.getInternational())) return Optional.empty();

        String dest = request.getRecipientCountryCode() == null
                ? "" : request.getRecipientCountryCode().trim().toUpperCase(Locale.ROOT);
        if (B13A_EXEMPT_DESTINATIONS.contains(dest)) return Optional.empty();
        if ("CA".equals(dest)) return Optional.empty();
        if (StringUtils.hasText(intl.getExportDeclarationReference())) return Optional.empty();

        return exceedsThreshold(intl, fx)
                .filter(Boolean::booleanValue)
                .map(over -> new ValidationError(
                        IntlShipmentValidator.CODE_CA_B13A_REQUIRED,
                        "Canadian exports valued at CAD $" + THRESHOLD.toPlainString()
                                + " or more to non-US destinations require a B13A export "
                                + "declaration reference. Provide the CBSA-issued B13A "
                                + "reference in the export declaration field."));
    }
}
