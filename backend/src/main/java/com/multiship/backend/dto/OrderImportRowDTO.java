package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Sprint 40 — one row from the CSV / XLSX import preview. Every field is
 * a String except the parsed weight; the frontend edits the preview and
 * POSTs the corrected rows back to the commit endpoint.
 *
 * <p>{@link #errors} is populated by the parser when required fields
 * are missing or malformed. An empty errors list means the row is
 * ready to commit.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderImportRowDTO {

    /** 1-based row number in the source file (excluding header). */
    private int rowNumber;

    // Recipient
    private String recipientName;
    private String recipientCompany;
    private String recipientPhone;
    private String recipientEmail;
    private String addressLine1;
    private String addressLine2;
    private String city;
    private String state;
    private String postalCode;
    private String countryCode;

    // Shipment
    private String carrierCode;
    private String serviceType;
    private String packageType;
    private BigDecimal weight;
    private String weightUnit;
    private BigDecimal declaredValue;
    private String currency;
    private String reference;
    private String goodsDescription;

    /** Per-row validation errors. Empty when the row is valid. */
    @Builder.Default
    private List<String> errors = List.of();
}
