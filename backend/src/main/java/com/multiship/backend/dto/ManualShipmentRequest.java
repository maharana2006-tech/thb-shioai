package com.multiship.backend.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * A one-shot manually-entered shipment: the operator types the ship-from and
 * ship-to parties, the package + weight, and picks the carrier account / service
 * / packaging explicitly (nothing is auto-resolved). The label is purchased
 * immediately and the shipment is recorded as a manual order (label_batch.is_manual = 'Y').
 */
@Data
public class ManualShipmentRequest {

    /** Ship-from (sender). Falls back to the platform shipper defaults when blank. */
    private Address sender;

    /** Ship-to (recipient). Required. */
    private Address recipient;

    /** carrier_account_ref id to bill — determines the carrier + credentials. Required. */
    private Long accountId;

    /** shipping_service id (the chosen service level). Optional — falls back to the carrier default. */
    private Long serviceId;

    /** package_preset id (the chosen packaging). Optional when custom dimensions are supplied. */
    private Long packagePresetId;

    // Custom package dimensions (override the preset when provided).
    private BigDecimal length;
    private BigDecimal width;
    private BigDecimal height;
    private String dimUnit;

    /** Shipment weight — required, greater than zero. */
    private BigDecimal weight;
    private String weightUnit;

    /** Optional client/customer code to tag the shipment with. */
    private String clientCode;

    /** Declared/customs value. */
    private BigDecimal declaredValue;

    /** Goods description (prints on the label / commercial invoice). */
    private String goodsDescription;

    /** Free-text operator reference (defaults to the generated order number). */
    private String reference;

    // ── International only: commercial-invoice line items + header ──
    /** Commercial-invoice line items (required for cross-border shipments). */
    private List<Item> items;
    /** Incoterms — DAP / DDP (defaults from the client's importer profile). */
    private String incoterms;
    /** Reason for export — SALE / GIFT / SAMPLE / RETURN / REPAIR. */
    private String reasonForExport;
    /** 3-letter currency for the declared/unit values. */
    private String currency;

    @Data
    public static class Address {
        private String name;
        private String company;
        private String phone;
        private String email;
        private String addressLine1;
        private String addressLine2;
        private String city;
        private String state;
        private String postalCode;
        private String countryCode;
    }

    /** One commercial-invoice line. */
    @Data
    public static class Item {
        private String description;
        private String hsCode;
        private String countryOfOrigin;
        private Integer quantity;
        private BigDecimal unitValue;
        private BigDecimal weight;
        private String sku;
    }
}
