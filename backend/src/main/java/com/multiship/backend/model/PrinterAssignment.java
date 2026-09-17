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
 * Which printer a client's documents go to (V65). {@code clientCode} null is the
 * default for that document type, used by clients without their own assignment.
 */
@Entity
@Table(name = "printer_assignment")
@Getter
@Setter
@NoArgsConstructor
public class PrinterAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Upper-cased client code; null = default for every other client. */
    @Column(name = "client_code", length = 60)
    private String clientCode;

    /** LABEL or COMMERCIAL_INVOICE. */
    @Column(name = "doc_type", length = 30, nullable = false)
    private String docType;

    @Column(name = "printer_id", nullable = false)
    private Long printerId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
