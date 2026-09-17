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

/** A registered printer (V65). Assigned to clients per document type via {@link PrinterAssignment}. */
@Entity
@Table(name = "printer")
@Getter
@Setter
@NoArgsConstructor
public class Printer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", length = 120, nullable = false)
    private String name;

    @Column(name = "location", length = 160)
    private String location;

    /** RAW_9100 or IPP. */
    @Column(name = "connection", length = 20, nullable = false)
    private String connection;

    @Column(name = "host", length = 255, nullable = false)
    private String host;

    @Column(name = "port", nullable = false)
    private int port;

    /** IPP resource path, e.g. {@code ipp/print} or {@code printers/Office}. */
    @Column(name = "queue_path", length = 160)
    private String queuePath;

    /** ZPL or PDF — what the printer understands. */
    @Column(name = "format", length = 10, nullable = false)
    private String format;

    /** LABEL_4X6, A4 or LETTER. */
    @Column(name = "paper", length = 20, nullable = false)
    private String paper;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "last_test_at")
    private LocalDateTime lastTestAt;

    @Column(name = "last_test_ok")
    private Boolean lastTestOk;

    @Column(name = "last_test_message", length = 500)
    private String lastTestMessage;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
