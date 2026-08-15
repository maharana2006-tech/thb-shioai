package com.multiship.backend.service.output;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 52 output-polish (follow-up #3) — verifies {@link TestPayloadFactory}
 * produces the right payload shape for each (destination, doc-type,
 * protocol) combination.
 */
class TestPayloadFactoryTest {

    private final TestPayloadFactory factory = new TestPayloadFactory();

    @Test
    void printerRawLabelProducesZplWithTimestamp() {
        TestPayloadFactory.Payload p = factory.build(
                DestinationType.PRINTER, DocType.LABEL, "RAW_9100", 42L);

        String body = new String(p.getBytes(), StandardCharsets.UTF_8);
        assertTrue(body.startsWith("^XA"), "ZPL must start with ^XA");
        assertTrue(body.contains("^XZ"),   "ZPL must contain ^XZ terminator");
        assertTrue(body.contains("ShipX test label"), "must include the human-readable title");
        assertTrue(body.contains("dest=42"), "must include the destination id");
        // Timestamp format: yyyy-MM-ddTHH:mm:ssZ — cheap check on the T + Z
        assertTrue(body.matches("(?s).*\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z.*"),
                "must include an ISO-8601 UTC timestamp for visual verification");
        assertEquals("application/zpl", p.getContentType());
        assertTrue(p.getFileNameHint().endsWith(".zpl"));
    }

    @Test
    void printerIppLabelProducesValidPdf() throws Exception {
        TestPayloadFactory.Payload p = factory.build(
                DestinationType.PRINTER, DocType.LABEL, "IPP", 7L);

        assertEquals("application/pdf", p.getContentType());
        assertTrue(p.getFileNameHint().endsWith(".pdf"));
        assertPdfIsParseable(p.getBytes());
    }

    @Test
    void localFsLabelProducesZpl() {
        TestPayloadFactory.Payload p = factory.build(
                DestinationType.LOCAL_FS, DocType.LABEL, null, 3L);

        assertTrue(new String(p.getBytes(), StandardCharsets.UTF_8).contains("^XA"));
        assertEquals("application/zpl", p.getContentType());
    }

    @Test
    void sftpLabelProducesZpl() {
        TestPayloadFactory.Payload p = factory.build(
                DestinationType.SFTP, DocType.LABEL, null, 5L);

        assertTrue(new String(p.getBytes(), StandardCharsets.UTF_8).contains("^XA"));
        assertEquals("application/zpl", p.getContentType());
    }

    @Test
    void commercialInvoiceAlwaysProducesPdfEvenForRawPrinter() throws Exception {
        // A COMMERCIAL_INVOICE is always PDF — even on a Zebra queue that
        // wouldn't know what to do with it. The factory shouldn't second-
        // guess the doc type just because the destination is unusual.
        TestPayloadFactory.Payload p = factory.build(
                DestinationType.PRINTER, DocType.COMMERCIAL_INVOICE, "RAW_9100", 88L);

        assertEquals("application/pdf", p.getContentType());
        assertPdfIsParseable(p.getBytes());
    }

    @Test
    void commercialInvoiceForSftpProducesPdf() throws Exception {
        TestPayloadFactory.Payload p = factory.build(
                DestinationType.SFTP, DocType.COMMERCIAL_INVOICE, null, 12L);

        assertEquals("application/pdf", p.getContentType());
        assertPdfIsParseable(p.getBytes());
    }

    /** PDF fixture is truly a PDF: %PDF- header + PDFBox can parse it back. */
    private static void assertPdfIsParseable(byte[] pdfBytes) throws Exception {
        assertNotNull(pdfBytes);
        assertTrue(pdfBytes.length > 100, "PDF should be non-trivial");
        // Header check.
        String head = new String(pdfBytes, 0, 5, StandardCharsets.US_ASCII);
        assertEquals("%PDF-", head, "PDF must start with %PDF- header");
        // Round-trip parse — proves the file is structurally valid.
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            assertEquals(1, doc.getNumberOfPages(), "test PDF is 1 page");
        }
    }
}
