package com.multiship.backend.model;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Builder.Default;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * The customs declaration attached to an INTERNATIONAL order. Keyed by order_no
 * (the same linkage OrderCarrierDetails uses). Holds the importer of record —
 * the party customs treats as the receiver — plus shipment-level customs meta
 * and the declared line items. Domestic orders never get a row here.
 */
@Entity
@Table(name = "order_customs")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderCustoms {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Links to the order (label_batch.order_no). One declaration per order. */
    @Column(name = "order_no", nullable = false, unique = true, length = 40)
    private String orderNo;

    /** Importer of record — legal receiver for customs (defaults from Ship-To). */
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "name", column = @Column(name = "importer_name", length = 255)),
            @AttributeOverride(name = "line1", column = @Column(name = "importer_line1", length = 255)),
            @AttributeOverride(name = "line2", column = @Column(name = "importer_line2", length = 255)),
            @AttributeOverride(name = "city", column = @Column(name = "importer_city", length = 100)),
            @AttributeOverride(name = "state", column = @Column(name = "importer_state", length = 50)),
            @AttributeOverride(name = "zip", column = @Column(name = "importer_zip", length = 20)),
            @AttributeOverride(name = "country", column = @Column(name = "importer_country", length = 10)),
            @AttributeOverride(name = "phone", column = @Column(name = "importer_phone", length = 50)),
    })
    private Address importerAddress;

    @Column(name = "importer_company", length = 255)
    private String importerCompany;

    @Column(name = "importer_tax_id", length = 50)
    private String importerTaxId;

    @Column(name = "importer_vat", length = 50)
    private String importerVat;

    @Column(name = "importer_eori", length = 50)
    private String importerEori;

    /** Delivery terms — DDP (seller pays duty) / DAP / DDU. */
    @Column(length = 10)
    private String incoterms;

    /** SALE | GIFT | SAMPLE | RETURN | REPAIR. */
    @Column(name = "reason_for_export", length = 20)
    private String reasonForExport;

    /** ISO-4217 currency of the declared values. */
    @Column(length = 3)
    private String currency;

    /** KG | LB — the unit item weights are expressed in. */
    @Column(name = "weight_unit", length = 3)
    private String weightUnit;

    @Column(length = 500)
    private String notes;

    @OneToMany(mappedBy = "orderCustoms", cascade = CascadeType.ALL, orphanRemoval = true)
    @Default
    private List<OrderCustomsItem> items = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
