package com.multiship.backend.service.carriers.usps;

import com.multiship.backend.dto.IntlShipmentBlockDTO;
import com.multiship.backend.dto.IntlShipmentBlockDTO.CustomsSplitStrategy;
import com.multiship.backend.dto.ShipmentRequestDTO;
import com.multiship.backend.dto.SplitStrategy;
import com.multiship.backend.model.CarrierShippingLimit;
import com.multiship.backend.service.ShipmentSplitter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * PR-F3 — customs-form line-item splitter for USPS_DIRECT international
 * shipments. Dispatches on the operator's chosen
 * {@link CustomsSplitStrategy} to handle the case where a shipment's
 * commodity count exceeds the USPS printed customs form's physical line
 * ceiling (currently 30 lines on CN23/PS-2976-A —
 * see {@link com.multiship.backend.service.carriers.UspsDirectConnector#CUSTOMS_FORM_SOFT_MAX_LINES}).
 *
 * <p>Two strategies per USPS convention:
 * <ul>
 *   <li>{@link CustomsSplitStrategy#SPLIT} — chunk the shipment into
 *       ceil(N/30) sub-parcels via
 *       {@link ShipmentSplitter#splitByCommodityStrategy}. Each sub-parcel
 *       becomes its own USPS label + customs form + tracking number.
 *       Every commodity is accounted for on paper. Default under
 *       USPS_DIRECT.</li>
 *   <li>{@link CustomsSplitStrategy#INVOICE_REFERENCE} — keep as ONE
 *       shipment. The customs form is transformed at request-build time
 *       by {@link UspsCustomsFormBuilder} into a single summary line
 *       plus an invoice reference. Operator physically attaches the
 *       full itemized commercial invoice.</li>
 * </ul>
 *
 * <p>Under SPLIT the splitter itself does NOT enqueue the sub-requests
 * through the MPS queue — it just returns them. The caller (currently
 * {@link com.multiship.backend.service.carriers.UspsDirectConnector}) is
 * responsible for iterating and dispatching each sub-request. PR-F2.5's
 * runtime dispatch will let the connector queue each sub-request as a
 * separate MPS piece; until then the connector calls
 * {@code createShipment} once per sub-request synchronously.
 *
 * <p><b>REGULATORY_REFERENCE.</b> USPS Publication 52 §12.4 (Hazardous,
 * Restricted, and Perishable Mail) + the USPS eVS integrator guide
 * jointly document both strategies. WCO Kyoto Convention Specific Annex
 * J Chapter 2 sanctions the "commercial invoice as supporting document"
 * pattern behind INVOICE_REFERENCE. Adjusting either strategy's line-limit
 * assumption requires compliance-officer sign-off.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UspsCustomsLineItemSplitter {

    /**
     * Physical line limit on USPS PS-2976-A (long-form customs
     * declaration used for high-value MPS commercial merchandise). Kept
     * here as a package-visible copy of
     * {@link com.multiship.backend.service.carriers.UspsDirectConnector#CUSTOMS_FORM_SOFT_MAX_LINES}
     * so the splitter can decide "over cap?" without a circular dep on
     * the connector.
     */
    public static final int USPS_CUSTOMS_FORM_LINE_CAP = 30;

    /**
     * Synthetic carrier code stamped on the {@link CarrierShippingLimit}
     * this splitter passes to {@link ShipmentSplitter#splitByCommodityStrategy}.
     * The value only surfaces in split-strategy debug logs — it's not
     * looked up in the {@code carrier_shipping_limit} table.
     */
    private static final String SYNTHETIC_LIMIT_CARRIER = "USPS_DIRECT";

    private final ShipmentSplitter shipmentSplitter;
    private final UspsCustomsFormBuilder customsFormBuilder;

    /**
     * Dispatch on the strategy. See {@link CustomsSplitStrategy} for
     * per-strategy semantics.
     *
     * <p><b>SPLIT</b> — invokes
     * {@link ShipmentSplitter#splitByCommodityStrategy} with a synthetic
     * {@link CarrierShippingLimit} carrying {@link #USPS_CUSTOMS_FORM_LINE_CAP}
     * as {@code maxCommodities} and {@link SplitStrategy#SAME_PACKAGES}
     * as the package-distribution strategy (safest choice — every
     * sub-shipment carries the full physical package set, so each
     * customs form declares complete-shipment dimensions rather than
     * per-slice fractional weights that don't match the operator's
     * physical parcel).
     *
     * <p><b>INVOICE_REFERENCE</b> — returns the request unchanged in a
     * single-element list. The connector's downstream call to
     * {@link UspsCustomsFormBuilder#build(ShipmentRequestDTO, CustomsSplitStrategy)}
     * (via the strategy hint threaded through
     * {@code buildIntlLabelRequestBody}) transforms the customs form
     * into a summary line + invoice reference at wire-build time.
     *
     * <p><b>null strategy</b> — treated as SPLIT (the safer default).
     * Callers should have already invoked the connector's boundary
     * guard, which throws when the request is over-cap and the strategy
     * is null; this fallback protects against tests / non-USPS callers
     * that might reach the splitter without going through the guard.
     */
    public List<ShipmentRequestDTO> applyStrategy(ShipmentRequestDTO request,
                                                   CustomsSplitStrategy strategy) {
        if (request == null || request.getIntl() == null) {
            return List.of(request);
        }
        IntlShipmentBlockDTO intl = request.getIntl();
        int lineCount = intl.getCommodities() == null ? 0 : intl.getCommodities().size();
        if (lineCount <= USPS_CUSTOMS_FORM_LINE_CAP) {
            // Below cap — no split needed regardless of strategy. Keeps
            // the caller from paying multi-label postage when the
            // shipment already fits on one form.
            return List.of(request);
        }
        CustomsSplitStrategy effective = strategy != null ? strategy : CustomsSplitStrategy.SPLIT;
        switch (effective) {
            case INVOICE_REFERENCE:
                // Single label call — connector consults the strategy at
                // request-build time and swaps the commodities list for a
                // summary line + invoice ref. Splitter passes the request
                // through unchanged.
                log.info("USPS customs INVOICE_REFERENCE: order {} — {} commodities collapsed to one summary line.",
                        request.getReferenceNumber(), lineCount);
                return List.of(request);

            case SPLIT:
            default:
                CarrierShippingLimit syntheticLimit = CarrierShippingLimit.builder()
                        .carrierCode(SYNTHETIC_LIMIT_CARRIER)
                        .maxCommodities(USPS_CUSTOMS_FORM_LINE_CAP)
                        .scope("INTERNATIONAL")
                        .build();
                List<ShipmentRequestDTO> subs = shipmentSplitter.splitByCommodityStrategy(
                        request, syntheticLimit, SplitStrategy.SAME_PACKAGES);
                log.info("USPS customs SPLIT: order {} — {} commodities → {} sub-shipments (cap={}).",
                        request.getReferenceNumber(), lineCount, subs.size(),
                        USPS_CUSTOMS_FORM_LINE_CAP);
                return subs;
        }
    }

    /**
     * True iff the request is intl AND its commodity count exceeds the
     * USPS printed-form ceiling. Callers use this to decide whether to
     * invoke {@link #applyStrategy} or short-circuit straight to the
     * single-label createShipment path.
     */
    public boolean requiresSplit(ShipmentRequestDTO request) {
        if (request == null || request.getIntl() == null) return false;
        List<?> commodities = request.getIntl().getCommodities();
        if (commodities == null || commodities.isEmpty()) return false;
        return commodities.size() > USPS_CUSTOMS_FORM_LINE_CAP;
    }

    /**
     * Test / connector seam — expose the builder so callers that already
     * hold a splitter reference can build the summary-line customs form
     * without a second injection. Purely convenience; the connector uses
     * its own {@link UspsCustomsFormBuilder} injection.
     */
    UspsCustomsFormBuilder customsFormBuilder() {
        return customsFormBuilder;
    }
}
