package com.multiship.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.math.BigDecimal;

/**
 * One product line on an order's customs declaration. These rows populate the
 * commercial invoice / CN22-CN23 sent to the carrier for an international label.
 */
@Entity
@Table(name = "order_customs_item")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderCustomsItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Owning declaration. Excluded from equals/hashCode/toString to avoid cycles. */
    @ManyToOne
    @JoinColumn(name = "order_customs_id", nullable = false)
    @ToString.Exclude
    @lombok.EqualsAndHashCode.Exclude
    private OrderCustoms orderCustoms;

    @Column(length = 200)
    private String description;

    /** Harmonized System / tariff code — an identifier, never a number. */
    @Column(name = "hs_code", length = 12)
    private String hsCode;

    /** Where the goods were made (ISO-3166 alpha-2). */
    @Column(name = "country_of_origin", length = 2)
    private String countryOfOrigin;

    @Column(nullable = false)
    private Integer quantity;

    /** Customs value per unit (never a float). */
    @Column(name = "unit_value", precision = 12, scale = 2)
    private BigDecimal unitValue;

    /** Per-unit weight, in the declaration's weightUnit. */
    @Column(precision = 10, scale = 3)
    private BigDecimal weight;

    @Column(length = 60)
    private String sku;

    /**
     * Sprint 48 B11 — 1-based package index within the shipment this
     * item belongs to. NULL = unassigned; when every item on the
     * shipment is null the connector treats all items as belonging to
     * one logical package (backward-compat with legacy single-box CI).
     * When any item has a value, the {@code DeclaredValueContextBuilder}
     * groups items by this column to derive per-box declared value.
     */
    @Column(name = "box_seq")
    private Integer boxSeq;
}
