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
 * A printer discovered on a tenant's LAN by the
 * {@code multiship-lan-scanner} agent. See V67 + design doc
 * {@code docs/printer-auto-detect-design.md}.
 *
 * <p>One row per {@code (tenant_code, host, port)} tuple — UPSERTED
 * on every agent scan. {@link #scanSeq} bumps per scan so the FE
 * picker can filter to the most recent snapshot only (older rows
 * hang around for the P4-deferred drift-detection use case).
 *
 * <p>All {@code *_guess} fields are heuristics the agent derived from
 * port probes + mDNS TXT records. The admin confirms them via the
 * FE picker before they become a real {@link Printer} row via
 * {@code PrinterService.apply()} (the same validation choke point
 * as manual entry).
 */
@Entity
@Table(name = "printer_discovered")
@Getter
@Setter
@NoArgsConstructor
public class PrinterDiscovered {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_code", length = 50, nullable = false)
    private String tenantCode;

    /** Matches {@link PrinterScanAgent#getAgentId()} — which warehouse
     *  the scan came from when a tenant runs multiple agents. */
    @Column(name = "agent_id", length = 100, nullable = false)
    private String agentId;

    @Column(name = "host", length = 255, nullable = false)
    private String host;

    @Column(name = "port", nullable = false)
    private Integer port;

    @Column(name = "name", length = 120)
    private String name;

    @Column(name = "location", length = 160)
    private String location;

    /** RAW_9100 | IPP — port-probe heuristic (9100 open → RAW_9100, 631 → IPP). */
    @Column(name = "connection_guess", length = 20)
    private String connectionGuess;

    /** ZPL | PDF — heuristic from IPP {@code pdl} TXT record or default. */
    @Column(name = "format_guess", length = 10)
    private String formatGuess;

    /** LABEL_4X6 | A4 | LETTER — heuristic from IPP {@code media-default}. */
    @Column(name = "paper_guess", length = 20)
    private String paperGuess;

    @Column(name = "queue_path", length = 160)
    private String queuePath;

    /** Pipe-joined mDNS TXT dump for debugging when the heuristic guesses
     *  wrong (e.g. IPP printer announcing on port 9100). Also useful for
     *  new printer families we haven't seen before. */
    @Column(name = "raw_txt", columnDefinition = "TEXT")
    private String rawTxt;

    @Column(name = "discovered_at", nullable = false)
    private LocalDateTime discoveredAt;

    /** Per-scan sequence number. Bumps on every scan run so the FE picker
     *  filters "most recent scan only" cleanly. Bigger = newer. */
    @Column(name = "scan_seq", nullable = false)
    private Long scanSeq;
}
