package com.multiship.backend.dto.external;

import lombok.Data;

/** Ask for the service options (and pricing when available) for a route. */
@Data
public class ExternalRateRequest {
    /** Ship-method code to resolve a specific service, or leave blank to list a carrier's services. */
    private String shipMethod;
    /** Optional explicit carrier to scope the options. */
    private String carrierCode;
    private ExternalAddress shipFrom;
    private ExternalAddress shipTo;
    private ExternalParcel parcel;
}
