package com.multiship.backend.model;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Sprint 45 — persisted rate-shop run. One row per fan-out completion so
 * the reporting API can render a "saved-vs-shopped" comparison. Best-
 * effort: RateShopServiceImpl writes at the tail of {@code rateShop()}
 * with try/catch — a persistence failure never fails the shop itself.
 */
@Entity
@Table(name = "rate_shop_history",
        indexes = {
                @Index(name = "idx_rate_shop_history_customer", columnList = "customer_no"),
                @Index(name = "idx_rate_shop_history_created", columnList = "created_at"),
        })
@Data
public class RateShopHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Client the shop ran for. Nullable for ad-hoc calls. */
    @Column(name = "customer_no", length = 50)
    private String customerNo;

    @Column(name = "dest_country", length = 2)
    private String destCountry;

    @Column(name = "weight", precision = 10, scale = 3)
    private BigDecimal weight;

    @Column(name = "weight_unit", length = 5)
    private String weightUnit;

    /** How many carriers were queried in this shop. */
    @Column(name = "carriers_queried")
    private Integer carriersQueried;

    /** How many carriers returned at least one option. */
    @Column(name = "carriers_returned")
    private Integer carriersReturned;

    /** Cheapest option's carrier / service / amount / currency (FX-normalised). */
    @Column(name = "cheapest_carrier", length = 20)
    private String cheapestCarrier;
    @Column(name = "cheapest_service", length = 40)
    private String cheapestService;
    @Column(name = "cheapest_amount", precision = 12, scale = 2)
    private BigDecimal cheapestAmount;
    @Column(name = "cheapest_currency", length = 3)
    private String cheapestCurrency;

    /** Populated when a caller later commits a pick — savings = picked - cheapest. */
    @Column(name = "picked_carrier", length = 20)
    private String pickedCarrier;
    @Column(name = "picked_service", length = 40)
    private String pickedService;
    @Column(name = "picked_amount", precision = 12, scale = 2)
    private BigDecimal pickedAmount;
    @Column(name = "savings_amount", precision = 12, scale = 2)
    private BigDecimal savingsAmount;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
