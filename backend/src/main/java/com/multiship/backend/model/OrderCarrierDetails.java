package com.multiship.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Builder.Default;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Carrier details supplied on an individual order by the client.
 * Completeness drives the label-generation scenario:
 * complete -> use directly, partial -> prompt to fill, absent -> global default.
 */
@Entity
@Table(name = "order_carrier_details",
        uniqueConstraints = @UniqueConstraint(name = "uk_order_carrier_details_order", columnNames = {"order_no"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderCarrierDetails {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_no", nullable = false)
    private Integer orderNo;

    @Column(name = "order_suffix")
    @Default
    private Integer orderSuffix = 0;

    @Column(name = "carrier_code", length = 50)
    private String carrierCode;

    @Column(name = "account_number", length = 100)
    private String accountNumber;

    @Column(name = "client_id", length = 255)
    private String clientId;

    @Column(name = "client_secret", length = 255)
    private String clientSecret;

    @Column(name = "environment", length = 20)
    private String environment;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /** Scenario 1: every credential needed to ship is present on the order. */
    public boolean isComplete() {
        return hasText(accountNumber) && hasText(clientId) && hasText(clientSecret);
    }

    /** Scenario 2 trigger: something is present but not everything. */
    public boolean hasAnyDetail() {
        return hasText(accountNumber) || hasText(clientId) || hasText(clientSecret);
    }
}
