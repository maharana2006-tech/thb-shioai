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
@Table(name = "import_batch_row")
@Getter
@Setter
@NoArgsConstructor
public class ImportBatchRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "import_batch_id", nullable = false)
    private ImportBatch importBatch;

    // Row metadata
    @Column(name = "row_number")
    private Integer rowNumber;

    @Column(name = "order_ref", length = 100)
    private String orderRef;

    @Column(name = "bill_to", length = 100)
    private String billTo;

    @Column(name = "reference", length = 100)
    private String reference;

    @Column(name = "batch_id")
    private Integer batchId;

    // Client & Warehouse
    @Column(name = "client_code", length = 50)
    private String clientCode;

    @Column(name = "warehouse_code", length = 50)
    private String warehouseCode;

    // Recipient Information
    @Column(name = "recipient_name", length = 200)
    private String recipientName;

    @Column(name = "recipient_company", length = 200)
    private String recipientCompany;

    @Column(name = "recipient_phone", length = 20)
    private String recipientPhone;

    @Column(name = "recipient_email", length = 100)
    private String recipientEmail;

    // Address
    @Column(name = "address_line1", length = 200)
    private String addressLine1;

    @Column(name = "address_line2", length = 200)
    private String addressLine2;

    @Column(name = "city", length = 100)
    private String city;

    @Column(name = "state", length = 50)
    private String state;

    @Column(name = "postal_code", length = 20)
    private String postalCode;

    @Column(name = "country_code", length = 5)
    private String countryCode;

    // Shipping Details
    @Column(name = "carrier_code", length = 50)
    private String carrierCode;

    @Column(name = "service_type", length = 50)
    private String serviceType;

    @Column(name = "account_number", length = 100)
    private String accountNumber;

    @Column(name = "package_type", length = 50)
    private String packageType;

    // Dimensions & Weight
    @Column(name = "weight", precision = 10, scale = 2)
    private BigDecimal weight;

    @Column(name = "weight_unit", length = 10)
    private String weightUnit;

    @Column(name = "weight_inherited")
    private Boolean weightInherited;

    @Column(name = "length", precision = 10, scale = 2)
    private BigDecimal length;

    @Column(name = "width", precision = 10, scale = 2)
    private BigDecimal width;

    @Column(name = "height", precision = 10, scale = 2)
    private BigDecimal height;

    @Column(name = "dim_unit", length = 10)
    private String dimUnit;

    // International & Customs
    @Column(name = "currency", length = 5)
    private String currency;

    @Column(name = "incoterms", length = 50)
    private String incoterms;

    @Column(name = "hs_code", length = 50)
    private String hsCode;

    @Column(name = "country_of_origin", length = 5)
    private String countryOfOrigin;

    // Item Details
    @Column(name = "item_sku", length = 100)
    private String itemSku;

    @Column(name = "item_description", length = 500)
    private String itemDescription;

    @Column(name = "item_quantity")
    private Integer itemQuantity;

    @Column(name = "item_unit_value", precision = 10, scale = 2)
    private BigDecimal itemUnitValue;

    // Generated Results (after label generation)
    @Column(name = "generated_order_no", length = 100)
    private String generatedOrderNo;

    @Column(name = "generated_tracking_number", length = 100)
    private String generatedTrackingNumber;

    @Column(name = "generated_status", length = 50)
    private String generatedStatus;

    @Column(name = "generated_message", length = 500)
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
