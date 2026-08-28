package com.multiship.backend.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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

    /** Ship-from (sender). For a RETURN this is the customer; for a shipment it's your warehouse. */
    private Address sender;

    /** Ship-to (recipient). For a RETURN this is your return address; for a shipment it's the customer. */
    private Address recipient;

    /** True = reverse/return label (customer ships back to you); false/null = normal outbound shipment. */
    private Boolean isReturn;

    /** Return label delivery type — PRINT | EMAIL (informational for now; finalised against the carrier sandbox). */
    private String returnType;

    /** carrier_account_ref id to bill — optional credential hint. */
    private Long accountId;

    /** Carrier code (UPS/FEDEX/USPS) — used to resolve credentials when the bill-to account is typed manually. */
    private String carrierCode;

    /** Bill-to account number (may be typed manually). Credentials resolve from the matching
     *  carrier account, or the carrier's platform account when the number isn't on file. */
    private String accountNumber;

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

    /**
     * Multi-package: box 1..N with individual weight + dims. Frontend
     * sends this when the operator adds extra boxes via the "Add another
     * box" button (NewShipmentPage.extraPackages). When null / empty the
     * connector falls back to the top-level weight + dims (single box).
     */
    private java.util.List<PackageDetailDTO> packages;

    /** Optional client/customer code to tag the shipment with. */
    private String clientCode;

    /** Where the order originated: MANUAL (default) | WMS | API. Recorded on the order. */
    private String source;

    /**
     * Optional ship-from warehouse code (must be attached to
     * {@link #clientCode}). When set, its address wins over the {@link #sender}
     * block. Ignored for ad-hoc shipments with no client.
     */
    private String warehouseCode;

    /** Declared/customs value. */
    private BigDecimal declaredValue;

    /** Goods description (prints on the label / commercial invoice). */
    private String goodsDescription;

    /** Free-text operator reference (defaults to the generated order number). */
    private String reference;

    // ── International only: commercial-invoice line items + header ──
    /** Commercial-invoice line items (required for cross-border shipments). */
    // Sprint 52 — DTO-level worst-case cap (see IntlShipmentBlockDTO for
    // the same defence rationale). Per-carrier cap still enforced later.
    @Size(max = 999, message = "items: at most 999 commercial-invoice lines per shipment")
    private List<Item> items;
    /** Incoterms — DAP / DDP (defaults from the client's importer profile). */
    private String incoterms;
    /** Reason for export — SALE / GIFT / SAMPLE / RETURN / REPAIR. */
    private String reasonForExport;
    /** 3-letter currency for the declared/unit values. */
    private String currency;

    /** Per-shipment importer override (label-document keys) — overrides the client profile for THIS label only. */
    private java.util.Map<String, Object> importer;
    /** Per-shipment customs-broker override — overrides the client profile for THIS label only. */
    private java.util.Map<String, Object> broker;

    /**
     * Sprint 27 — dangerous goods declaration. When present + ready,
     * connectors emit their carrier-specific hazmat wire format (UPS
     * HazMatPackageInformation, FedEx dangerousGoodsDetail, DHL
     * content.dangerousGoods, SWSIM HazardousMaterials).
     */
    private DangerousGoodsBlockDTO dangerousGoods;

    /**
     * Sprint 35 — signature at delivery. NONE | INDIRECT | DIRECT | ADULT.
     * Null = carrier default. Every connector normalises + emits the
     * carrier-specific wire format.
     */
    private String signatureOption;

    /**
     * Sprint 35 — insured value beyond the free tier ($100 UPS/FedEx/USPS
     * Priority Ground). Null / 0 = no explicit insurance requested; the
     * carrier's free tier still applies.
     */
    private BigDecimal insuredValue;

    /**
     * ISO-4217 for {@link #insuredValue}. Null defaults to
     * {@link #currency} on the wire, then to USD at the connector.
     */
    private String insuredValueCurrency;

    /**
     * Per-shipment override of the account's default label file format.
     * Blank/null = use the account default (which itself falls back to
     * each carrier's hardcoded default). Meaning is carrier-specific:
     * <ul>
     *   <li>UPS — LabelImageFormat: GIF | PDF | PNG | ZPL | EPL. Default GIF.</li>
     *   <li>DHL Express — labelSpecification encoding: PDF | ZPL. Default PDF.</li>
     *   <li>USPS/Stamps — SWSIM ImageType: PNG | PDF | GIF | JPG. Default PNG
     *       (SWSIM's own default when unset).</li>
     *   <li>FedEx — ignored; use {@link #labelImageType} instead (FedEx has
     *       a second axis, {@link #labelStockType}, that these other
     *       carriers don't).</li>
     * </ul>
     */
    @Pattern(regexp = "GIF|PDF|PNG|ZPL|EPL|JPG|",
            message = "labelImageFormat must be one of GIF / PDF / PNG / ZPL / EPL / JPG")
    private String labelImageFormat;

    /**
     * FDX-H3 — per-shipment override of the FedEx account's default
     * labelSpecification.imageType. Blank/null = use the account default
     * (which itself falls back to PDF when unset). Only FedEx maps this;
     * other carriers ignore the field.
     */
    @Pattern(regexp = "PDF|PNG|ZPLII|EPL2|DPL|",
            message = "labelImageType must be one of PDF / PNG / ZPLII / EPL2 / DPL")
    private String labelImageType;

    /**
     * FDX-H3 — per-shipment override of the FedEx account's default
     * labelSpecification.labelStockType. Blank/null = use the account
     * default (which itself falls back to PAPER_4X6 when unset). Only
     * FedEx maps this; other carriers ignore the field.
     */
    @Pattern(regexp = "PAPER_4X6|PAPER_4X6\\.75|PAPER_4X8|PAPER_4X9|PAPER_7X4\\.75|PAPER_LETTER|"
            + "STOCK_4X6|STOCK_4X6\\.75|STOCK_4X8|STOCK_4X9_LEADING_DOC_TAB|",
            message = "labelStockType must be a supported FedEx label stock type")
    private String labelStockType;

    @Data
    public static class Address {
        private String name;
        private String company;
        private String phone;
        private String email;
        private String addressLine1;
        private String addressLine2;
        /** Third street line — JP/CN/IN often need this. */
        private String addressLine3;
        private String city;
        private String state;
        private String postalCode;
        private String countryCode;
        /** True when this is a residence — flips carrier residential rating. */
        private Boolean residential;
        /** ISO dial code (no plus) — "1", "44", "91". Prepended to phone at carrier time. */
        private String phoneCountryCode;
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
        /** Sprint 48 B11 — 1-based package this item belongs to; null = unassigned. */
        private Integer boxSeq;
    }
}
