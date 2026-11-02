package com.multiship.backend.service.carrier;

import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.service.resolution.ShipmentResolutionException;

import java.util.List;

/**
 * Strict shipper mode refused a tenant with no {@code shipper.*} override.
 * A resolution failure (422 with the field named), not a server error:
 * the operator fixes it under Settings → System.
 */
public class ShipperDefaultsMissingException extends ShipmentResolutionException {

    private final String tenantCode;
    private final List<String> missingKeys;

    public ShipperDefaultsMissingException(String tenantCode, List<String> missingKeys) {
        super(ErrorCode.SHIPPER_DEFAULTS_MISSING,
                "Tenant " + tenantCode + " has no shipper address configured ("
                        + String.join(", ", missingKeys) + "). Set it under Settings → System → Shipper defaults "
                        + "before buying labels for this tenant.");
        this.tenantCode = tenantCode;
        this.missingKeys = List.copyOf(missingKeys);
    }

    public String getTenantCode() { return tenantCode; }
    public List<String> getMissingKeys() { return missingKeys; }
}
