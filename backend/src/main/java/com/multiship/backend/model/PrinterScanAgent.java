package com.multiship.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Per-tenant enrollment of a {@code multiship-lan-scanner} Docker agent
 * running inside the customer's warehouse LAN. See V67 + design doc
 * {@code docs/printer-auto-detect-design.md}.
 *
 * <p>{@link #apiKeyHash} is SHA-256 of the raw key returned once at
 * generate time (mirrors the ApiKeyService pattern). The raw key
 * lives only in the operator's copy of the Docker env; if lost, the
 * admin must rotate — no recovery from the DB.
 *
 * <p>{@link #scanRequestedAt} is the poll nudge: an admin's "Scan now"
 * click sets it; the agent's next long-poll picks it up and clears
 * via PUT. Latency budget: one poll interval (default 5s).
 */
@Entity
@Table(name = "printer_scan_agent")
@Getter
@Setter
@NoArgsConstructor
public class PrinterScanAgent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_code", length = 50, nullable = false)
    private String tenantCode;

    /** Free-form agent identifier chosen at enrollment (e.g. "warehouse-north").
     *  Unique per tenant so a customer with N warehouses can run N agents. */
    @Column(name = "agent_id", length = 100, nullable = false)
    private String agentId;

    /** Docker host / container hostname reported by the agent at enrollment.
     *  Nullable — some ops teams deploy behind reverse proxies. */
    @Column(name = "hostname", length = 255)
    private String hostname;

    /** SHA-256 hex of the raw agent key. Raw key returned ONCE at enrollment. */
    @Column(name = "api_key_hash", length = 64, nullable = false)
    private String apiKeyHash;

    @Column(name = "enrolled_at", nullable = false)
    private LocalDateTime enrolledAt;

    @Column(name = "enrolled_by", length = 120)
    private String enrolledBy;

    /** Refreshed on every long-poll or discovered-POST. NULL until first
     *  contact — used for the P4-deferred Grafana staleness alert. */
    @Column(name = "last_seen_at")
    private LocalDateTime lastSeenAt;

    /** Non-null = admin requested a fresh scan since the agent last polled. */
    @Column(name = "scan_requested_at")
    private LocalDateTime scanRequestedAt;

    @Column(name = "active", nullable = false)
    private Boolean active = Boolean.TRUE;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;
}
