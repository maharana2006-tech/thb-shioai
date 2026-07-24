package com.multiship.backend.dto.external;

import lombok.Data;

/** Ask for the service options (and pricing when available) for a route. */
@Data
public class ExternalRateRequest {
    /** The client to rate for — required for a platform-wide (WMS) key; must match a client-bound key if sent. */
    private String clientCode;
    /** Ship-method code to resolve a specific service, or leave blank to list a carrier's services. */
    private String shipMethod;
    /** Optional explicit carrier to scope the options. */
    private String carrierCode;
    /**
     * Explicit ship-from warehouse code. When set, its country becomes the
     * origin for domestic/international scope; also used to reject shipping
     * from a warehouse not attached to the client (403).
     */
    private String warehouseCode;
    private ExternalAddress shipFrom;
    private ExternalAddress shipTo;
    private ExternalParcel parcel;
}
