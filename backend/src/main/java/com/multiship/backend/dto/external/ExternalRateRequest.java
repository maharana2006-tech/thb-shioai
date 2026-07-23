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
    private ExternalAddress shipFrom;
    private ExternalAddress shipTo;
    private ExternalParcel parcel;
}
