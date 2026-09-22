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

/** One order's document printed once — in the browser's print dialog or on a network printer. V80. */
@Entity
@Table(name = "document_print_event")
@Getter
@Setter
@NoArgsConstructor
public class DocumentPrintEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_no", nullable = false)
    private Integer orderNo;

    /** LABEL · COMMERCIAL_INVOICE */
    @Column(name = "doc_type", nullable = false, length = 32)
    private String docType;

    /** BROWSER (print dialog) · PRINTER (network printer) */
    @Column(name = "channel", nullable = false, length = 16)
    private String channel;

    @Column(name = "printer_name", length = 120)
    private String printerName;

    @Column(name = "printed_by", length = 120)
    private String printedBy;

    @Column(name = "printed_at", nullable = false)
    private LocalDateTime printedAt;
}
