package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Preview of which carrier account an order will use when a label is generated.
 * scenario: ORDER (full details on order), REFERENCE (order account matched the
 * reference book), DEFAULT (admin global default), NEEDS_DETAILS (partial details,
 * user must fill), NO_DEFAULT (nothing on order and no global default configured).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderAccountResolutionDTO {
    private Integer orderNo;
    private String scenario;
    private String carrierCode;
    private String accountNumber;
    private String accountName;
    private String environment;
    /** Fields still required before this order can generate (NEEDS_DETAILS only). */
    private List<String> missingFields;
    /** Pre-fillable client id captured on the order or reference (never the secret). */
    private String prefillClientId;
}
