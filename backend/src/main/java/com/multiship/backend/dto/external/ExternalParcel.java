package com.multiship.backend.dto.external;

import lombok.Data;

import java.math.BigDecimal;

/** Parcel weight, optional dimensions, and optional packaging code. */
@Data
public class ExternalParcel {
    /** Required, greater than zero. */
    private BigDecimal weight;
    /** lb | kg (defaults to lb). */
    private String weightUnit;
    private BigDecimal length;
    private BigDecimal width;
    private BigDecimal height;
    /** in | cm (defaults to in). */
    private String dimUnit;
    /** package_preset code, if the caller wants a specific packaging. */
    private String packagingCode;
}
