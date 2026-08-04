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

    /**
     * Sprint 48 — order-group key. Multiple rows sharing a non-blank
     * {@code orderRef} fold into a single shipment: the first row supplies
     * recipient / carrier / service, subsequent rows contribute additional
     * customs line-items. Blank {@code orderRef} = standalone single-item
     * shipment (same as pre-Sprint-48 behaviour).
     */
    private String orderRef;

    /**
     * Sprint 48 — client the row ships for. Universal template model:
     * one workbook covers multiple clients, each row picks its own client
     * and the carrier / account / warehouse dropdowns cascade off it.
     * Blank = fall back to the pre-Sprint-48 "any client" resolution.
     */
    private String clientCode;

    /**
     * Sprint 48 — bill-to party for the shipment. SENDER / RECIPIENT /
     * THIRD_PARTY. When THIRD_PARTY, {@link #accountNumber} may be a
     * third-party account (free-text, not from the client's own book).
     * Backend defaults to SENDER when blank (existing behaviour).
     */
    private String billTo;

    /**
     * Sprint 48 — warehouse code the shipment ships from. Optional; blank
     * lets the label-generation cascade pick the client's default. When
     * populated, must match an active warehouse code (PLATFORM or client-
     * owned) attached to {@link #clientCode}.
     */
    private String warehouseCode;

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
    /** Sprint 41 — bill-to carrier account number (matches the credential
     *  book row that will pay for the label). Optional at import time;
     *  when absent, commit needs {@code accountId} or the default cascade. */
    private String accountNumber;
    private String serviceType;
    private String packageType;
    private BigDecimal weight;
    private String weightUnit;
    private BigDecimal declaredValue;
    private String currency;
    private String reference;
    private String goodsDescription;

    // ===== Sprint 48 — per-item customs fields =====
    /** Line-item description (e.g. "Silk lining, natural"). When {@link
     *  #orderRef} groups multiple rows into one shipment, each row's
     *  itemDescription becomes one CustomsCommodityDTO. Blank on a
     *  domestic-only row is fine — customs block is skipped. */
    private String itemDescription;
    /** Optional SKU / part number on the line item. */
    private String itemSku;
    /** Line-item quantity. Defaults to 1 when blank + the row carries any
     *  item-level data. Skipped entirely when no item fields present. */
    private Integer itemQuantity;
    /** Per-unit value in the shipment's {@link #currency}. */
    private BigDecimal itemUnitValue;
    /** Harmonised System code (up to 12 chars); required by every
     *  international carrier connector. Blank OK for domestic. */
    private String hsCode;
    /** ISO-2 country of origin / manufacture. Blank OK for domestic. */
    private String countryOfOrigin;

    /** Per-row validation errors. Empty when the row is valid. */
    @Builder.Default
    private List<String> errors = List.of();

    /**
     * Sprint 48 — non-fatal warnings surfaced on the preview UI (e.g.
     * "template account = ACC-A but row uses ACC-B"). Empty by default;
     * committing rows with warnings is allowed unless {@link #errors}
     * also fires.
     */
    @Builder.Default
    private List<String> warnings = List.of();

    // ===== Sprint 41 — populated on successful commit only. Null on
    //       preview and on rows that failed to generate a label. =====

    /** Order number the label was generated against. */
    private Integer generatedOrderNo;
    /** Carrier tracking number on the generated label. */
    private String generatedTrackingNumber;
    /** GENERATED | FAILED. Present only after a commit call. */
    private String generatedStatus;
    /** Carrier's failure message when {@code generatedStatus=FAILED}. */
    private String generatedMessage;
}
