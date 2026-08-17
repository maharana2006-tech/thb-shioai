package com.multiship.backend.service.output;

import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Sprint 52 output-polish (follow-up #3) — synthesises a realistic
 * payload for the admin "test dispatch" button. Previously the endpoint
 * shipped {@code text/plain "hello world"} regardless of destination
 * type, which meant a Zebra printer got garbage bytes and a laser IPP
 * queue rejected the non-PDF body — neither outcome told the operator
 * whether their credentials + connectivity were actually good.
 *
 * <p>Rules:
 * <ul>
 *   <li>{@link DocType#LABEL}:
 *       <ul>
 *         <li>PRINTER + {@code RAW_9100} → minimal valid ZPL sample.</li>
 *         <li>PRINTER + {@code IPP} → 1-page PDF.</li>
 *         <li>LOCAL_FS / SFTP → ZPL fixture (the file-drop endpoints see
 *             both PDF and ZPL depending on the carrier; ZPL is
 *             cheaper to generate and just as valid a smoke test).</li>
 *       </ul>
 *   </li>
 *   <li>{@link DocType#COMMERCIAL_INVOICE} → PDF regardless of
 *       destination type. Commercial invoices are always PDFs in the
 *       real dispatch path.</li>
 * </ul>
 *
 * <p>Every payload embeds the current UTC timestamp so an operator can
 * visually confirm that the label / PDF they see was produced by the
 * button they just clicked (not a stale test artefact).
 */
@Slf4j
@Component
public class TestPayloadFactory {

    static final DateTimeFormatter TS_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    /**
     * The payload + its content-type + a filename hint the file-drop
     * drivers use to pick an extension.
     */
    @Value
    public static class Payload {
        byte[] bytes;
        String contentType;
        String fileNameHint;
    }

    /**
     * Build a payload appropriate for the (destinationType, docType,
     * protocol) combination. {@code protocolOrNull} is only meaningful
     * for {@link DestinationType#PRINTER}; pass {@code null} otherwise.
     */
    public Payload build(DestinationType destinationType,
                         DocType docType,
                         String protocolOrNull,
                         Long destinationId) {
        String ts = TS_FORMAT.format(Instant.now());
        // COMMERCIAL_INVOICE is always PDF — same across every destination.
        if (docType == DocType.COMMERCIAL_INVOICE) {
            byte[] pdf = generatePdf("ShipX Test — Commercial Invoice",
                    "Destination #" + destinationId, ts);
            return new Payload(pdf, "application/pdf",
                    "test_" + destinationId + "_ci_" + ts.replace(':', '-') + ".pdf");
        }
        // LABEL — split by destination + protocol.
        switch (destinationType) {
            case PRINTER:
                if (PrinterDriver.PROTO_IPP.equalsIgnoreCase(protocolOrNull)) {
                    byte[] pdf = generatePdf("ShipX Test Label",
                            "Destination #" + destinationId, ts);
                    return new Payload(pdf, "application/pdf",
                            "test_" + destinationId + "_label_" + ts.replace(':', '-') + ".pdf");
                }
                // RAW_9100 or unknown → ZPL (safer default; a RAW printer
                // will just discard bytes it doesn't understand).
                byte[] zpl = generateZpl(destinationId, ts);
                return new Payload(zpl, "application/zpl",
                        "test_" + destinationId + "_label_" + ts.replace(':', '-') + ".zpl");
            case LOCAL_FS:
            case SFTP:
            default:
                byte[] fileZpl = generateZpl(destinationId, ts);
                return new Payload(fileZpl, "application/zpl",
                        "test_" + destinationId + "_label_" + ts.replace(':', '-') + ".zpl");
        }
    }

    /**
     * Minimal valid Zebra ZPL that prints "ShipX test label" + the
     * timestamp. Kept short so a small label stock (2x1") won't overflow.
     * Two text fields is enough to prove the printer accepted the job
     * (single-field ZPL sometimes gets silently discarded by badly-
     * configured queues).
     */
    static byte[] generateZpl(Long destinationId, String ts) {
        String zpl = "^XA\n"
                + "^FO50,50^A0N,40,40^FDShipX test label^FS\n"
                + "^FO50,120^A0N,20,20^FDdest=" + destinationId + "^FS\n"
                + "^FO50,160^A0N,20,20^FD" + ts + "^FS\n"
                + "^XZ\n";
        return zpl.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Generate a 1-page PDF with the two title lines centred at the
     * top and the timestamp underneath. Uses PDFBox (already in the
     * classpath for packing slips) so no new dependency.
     */
    static byte[] generatePdf(String title, String subtitle, String ts) {
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            PDType1Font bold    = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                writeLine(cs, bold,    24f, 72f, 700f, title);
                writeLine(cs, regular, 14f, 72f, 670f, subtitle);
                writeLine(cs, regular, 12f, 72f, 640f, "Generated at " + ts);
                writeLine(cs, regular, 10f, 72f, 100f,
                        "This is a synthetic payload from the admin Test button.");
            }
            doc.save(out);
            return out.toByteArray();
        } catch (Exception ex) {
            // PDF generation failing is a bug — surface it loudly rather
            // than silently sending an empty payload.
            throw new IllegalStateException("Test PDF generation failed: " + ex.getMessage(), ex);
        }
    }

    private static void writeLine(PDPageContentStream cs, PDType1Font font,
                                  float size, float x, float y, String text) throws java.io.IOException {
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, y);
        cs.showText(text);
        cs.endText();
    }
}
