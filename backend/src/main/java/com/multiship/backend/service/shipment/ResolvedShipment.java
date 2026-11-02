package com.multiship.backend.service.shipment;

import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.dto.ManualShipmentRequest;
import com.multiship.backend.dto.ShipmentRequestDTO;
import com.multiship.backend.dto.SplitStrategy;
import com.multiship.backend.model.CarrierAccountRef;
import com.multiship.backend.model.CarrierShippingLimit;
import com.multiship.backend.model.PackagePreset;
import com.multiship.backend.model.ShippingService;
import com.multiship.backend.service.carriers.CarrierConnector;
import com.multiship.backend.service.resolution.MarkupRule;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Manual Shipment redesign, Phase 1 — everything the purchase and the
 * persist steps need, decided before any money is spent. Produced by
 * {@code CarrierServiceImpl.resolveManualShipment}; immutable from then on.
 * If a value is not here, it cannot influence the carrier call or the rows.
 */
public record ResolvedShipment(
        ManualShipmentRequest.Address from,
        ManualShipmentRequest.Address to,
        String resolvedClient,
        boolean hasClient,
        String resolvedWarehouseCode,
        Long resolvedWarehouseId,
        String carrier,
        CarrierAccountRef account,
        String billToNumber,
        String typedNumber,
        CarrierConnector connector,
        ShippingService service,
        PackagePreset preset,
        String serviceType,
        String packageType,
        BigDecimal length,
        BigDecimal width,
        BigDecimal height,
        CarrierProperties.ShipperDefaults shipperDefaults,
        String fromCountry,
        int allocatedOrderNo,
        LocalDate shipDateOverride,
        ShipmentRequestDTO shipmentRequest,
        boolean isCrossBorder,
        CarrierShippingLimit limit,
        SplitStrategy splitStrategy,
        List<ShipmentRequestDTO> subRequests,
        BigDecimal totalWeightLb,
        MarkupRule markupRule
) {
    /** Mutable slot the resolver fills; the caller reads it once. */
    public static final class Holder {
        public ResolvedShipment value;
    }
}
