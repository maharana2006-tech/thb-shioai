package com.multiship.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "import_batch_row", indexes = {
        @jakarta.persistence.Index(name = "ix_import_batch_row_batch_row", columnList = "import_batch_id, row_number")})
@Getter
@Setter
@NoArgsConstructor
public class ImportBatchRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "import_batch_id", nullable = false)
    @org.hibernate.annotations.OnDelete(action = org.hibernate.annotations.OnDeleteAction.CASCADE)
    private ImportBatch importBatch;

    /** The client's own ship via code, when a Shipping Service Mapping rule resolved it. */
    @Column(name = "ship_via_code", columnDefinition = "TEXT")
    private String shipViaCode;

    /** What the mapping made of it ("U11 maps to UPS Ground (UPS 03)"). */
    @Column(name = "ship_via_note", columnDefinition = "TEXT")
    private String shipViaNote;

    /** The carrier's tracking page for this row's label. */
    @Column(name = "tracking_url", columnDefinition = "TEXT")
    private String trackingUrl;

    // Row metadata
    @Column(name = "row_number")
    private Integer rowNumber;

    @Column(name = "order_ref", columnDefinition = "TEXT")
    private String orderRef;

    @Column(name = "bill_to", columnDefinition = "TEXT")
    private String billTo;

    @Column(name = "reference", columnDefinition = "TEXT")
    private String reference;

    @Column(name = "batch_id")
    private Integer batchId;

    // Client & Warehouse
    @Column(name = "client_code", columnDefinition = "TEXT")
    private String clientCode;

    @Column(name = "warehouse_code", columnDefinition = "TEXT")
    private String warehouseCode;

    // Recipient Information
    @Column(name = "recipient_name", columnDefinition = "TEXT")
    private String recipientName;

    @Column(name = "recipient_company", columnDefinition = "TEXT")
    private String recipientCompany;

    @Column(name = "recipient_phone", columnDefinition = "TEXT")
    private String recipientPhone;

    @Column(name = "recipient_email", columnDefinition = "TEXT")
    private String recipientEmail;

    // Address
    @Column(name = "address_line1", columnDefinition = "TEXT")
    private String addressLine1;

    @Column(name = "address_line2", columnDefinition = "TEXT")
    private String addressLine2;

    @Column(name = "city", columnDefinition = "TEXT")
    private String city;

    @Column(name = "state", columnDefinition = "TEXT")
    private String state;

    @Column(name = "postal_code", columnDefinition = "TEXT")
    private String postalCode;

    @Column(name = "country_code", columnDefinition = "TEXT")
    private String countryCode;

    // Shipping Details
    @Column(name = "carrier_code", columnDefinition = "TEXT")
    private String carrierCode;

    @Column(name = "service_type", columnDefinition = "TEXT")
    private String serviceType;

    @Column(name = "account_number", columnDefinition = "TEXT")
    private String accountNumber;

    @Column(name = "package_type", columnDefinition = "TEXT")
    private String packageType;

    // Dimensions & Weight
    @Column(name = "weight", columnDefinition = "NUMERIC")
    private BigDecimal weight;

    @Column(name = "weight_unit", columnDefinition = "TEXT")
    private String weightUnit;

    @Column(name = "weight_inherited")
    private Boolean weightInherited;

    @Column(name = "length", columnDefinition = "NUMERIC")
    private BigDecimal length;

    @Column(name = "width", columnDefinition = "NUMERIC")
    private BigDecimal width;

    @Column(name = "height", columnDefinition = "NUMERIC")
    private BigDecimal height;

    @Column(name = "dim_unit", columnDefinition = "TEXT")
    private String dimUnit;

    // International & Customs
    @Column(name = "currency", columnDefinition = "TEXT")
    private String currency;

    @Column(name = "incoterms", columnDefinition = "TEXT")
    private String incoterms;

    @Column(name = "hs_code", columnDefinition = "TEXT")
    private String hsCode;

    @Column(name = "country_of_origin", columnDefinition = "TEXT")
    private String countryOfOrigin;

    // Item Details
    @Column(name = "item_sku", columnDefinition = "TEXT")
    private String itemSku;

    @Column(name = "item_description", columnDefinition = "TEXT")
    private String itemDescription;

    @Column(name = "item_quantity")
    private Integer itemQuantity;

    @Column(name = "item_unit_value", columnDefinition = "NUMERIC")
    private BigDecimal itemUnitValue;

    // Generated Results (after label generation)
    @Column(name = "generated_order_no", columnDefinition = "TEXT")
    private String generatedOrderNo;

    @Column(name = "generated_tracking_number", columnDefinition = "TEXT")
    private String generatedTrackingNumber;

    @Column(name = "generated_status", columnDefinition = "TEXT")
    private String generatedStatus;

    @Column(name = "generated_message", columnDefinition = "TEXT")
    private String generatedMessage;

    // Custom Fields
    @Column(name = "custom_fields", columnDefinition = "TEXT")
    private String customFields;

    // Validation
    @Column(name = "errors", columnDefinition = "TEXT")
    private String errors;

    @Column(name = "warnings", columnDefinition = "TEXT")
    private String warnings;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
