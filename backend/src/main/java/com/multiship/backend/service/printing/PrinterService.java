package com.multiship.backend.service.printing;

import com.multiship.backend.model.Printer;
import com.multiship.backend.model.PrinterAssignment;
import com.multiship.backend.repository.ClientRepository;
import com.multiship.backend.repository.OrderRepository;
import com.multiship.backend.repository.PrinterAssignmentRepository;
import com.multiship.backend.repository.PrinterRepository;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Printer registry, client assignments, and delivery to a printer.
 *
 * <p>Routing: a document for client C goes to C's assigned printer for that
 * document type, else the default printer (assignment with no client). Printers
 * are on the warehouse network, so private addresses are expected here (the
 * webhook host rules, which refuse them, do not apply).
 */
@Service
public class PrinterService {

    private static final Logger log = LoggerFactory.getLogger(PrinterService.class);

    public static final Set<String> CONNECTIONS = Set.of("RAW_9100", "IPP");
    public static final Set<String> FORMATS = Set.of("ZPL", "PDF");
    public static final Set<String> PAPERS = Set.of("LABEL_4X6", "A4", "LETTER");
    public static final Set<String> DOC_TYPES = Set.of("LABEL", "COMMERCIAL_INVOICE");
    private static final Pattern HOST = Pattern.compile("^[A-Za-z0-9](?:[A-Za-z0-9.\\-:]{0,251}[A-Za-z0-9])?$");
    private static final Pattern QUEUE_PATH = Pattern.compile("^[A-Za-z0-9._~\\-/]{1,160}$");

    /** Refused input: the message is shown to the operator as-is. */
    public static class PrinterValidationException extends RuntimeException {
        public PrinterValidationException(String message) { super(message); }
    }

    public record PrinterInput(String name, String location, String connection, String host, Integer port,
                               String queuePath, String format, String paper, Boolean active) {}

    public record AssignmentInput(String clientCode, String docType, Long printerId) {}

    private final PrinterRepository printers;
    private final PrinterAssignmentRepository assignments;
    private final ClientRepository clients;
    private final OrderRepository orders;
    /** PR-R9.5a — optional so pure-Mockito unit tests that construct
     *  PrinterService directly don't need to pass a 5th arg (widen-via-
     *  field pattern instead of constructor arity, per
     *  [[widen-via-dto-field-not-new-arg]] applied to service ctors). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.multiship.backend.repository.PrinterTestHistoryRepository testHistory;

    public PrinterService(PrinterRepository printers, PrinterAssignmentRepository assignments,
                          ClientRepository clients, OrderRepository orders) {
        this.printers = printers;
        this.assignments = assignments;
        this.clients = clients;
        this.orders = orders;
    }

    /* ------------------------------ printers ------------------------------ */

    public List<Printer> list() {
        return printers.findAllByOrderByNameAscIdAsc();
    }

    public Printer get(Long id) {
        return printers.findById(id).orElseThrow(() -> new NoSuchElementException("Printer " + id + " was not found."));
    }

    @Transactional
    public Printer create(PrinterInput input) {
        Printer p = new Printer();
        apply(p, input);
        p.setCreatedAt(LocalDateTime.now());
        p.setUpdatedAt(p.getCreatedAt());
        return printers.save(p);
    }

    @Transactional
    public Printer update(Long id, PrinterInput input) {
        Printer p = get(id);
        String oldFormat = p.getFormat();
        apply(p, input);
        if ("ZPL".equals(p.getFormat()) && !"ZPL".equals(oldFormat)
                && assignments.findAllByOrderByClientCodeAscDocTypeAsc().stream()
                        .anyMatch(a -> a.getPrinterId().equals(id) && "COMMERCIAL_INVOICE".equals(a.getDocType()))) {
            throw new PrinterValidationException("This printer receives commercial invoices, which need PDF. "
                    + "Move those assignments to a PDF printer before switching it to ZPL.");
        }
        p.setUpdatedAt(LocalDateTime.now());
        return printers.save(p);
    }

    @Transactional
    public void delete(Long id) {
        printers.delete(get(id));   // assignments go with it (ON DELETE CASCADE)
    }

    private void apply(Printer p, PrinterInput in) {
        if (in == null) throw new PrinterValidationException("Printer details are required.");
        String name = trim(in.name());
        if (name.isEmpty()) throw new PrinterValidationException("Give the printer a name.");
        if (name.length() > 120) throw new PrinterValidationException("Keep the name to 120 characters.");
        String connection = upper(in.connection());
        if (!CONNECTIONS.contains(connection)) {
            throw new PrinterValidationException("Connection must be RAW_9100 (network port) or IPP.");
        }
        String host = trim(in.host());
        if (host.startsWith("http://") || host.startsWith("https://") || host.startsWith("ipp://")) {
            throw new PrinterValidationException("Enter just the printer's IP address or hostname, without http:// or ipp://.");
        }
        if (!HOST.matcher(host).matches()) {
            throw new PrinterValidationException("Enter the printer's IP address or hostname, e.g. 192.168.1.50.");
        }
        int port = in.port() == null ? ("IPP".equals(connection) ? 631 : 9100) : in.port();
        if (port < 1 || port > 65535) throw new PrinterValidationException("Port must be between 1 and 65535.");
        // Never the server itself, the cloud metadata service, or a known
        // non-printer service port — see PrinterAddressGuard.
        var refused = PrinterAddressGuard.refusal(host, port);
        if (refused.isPresent()) throw new PrinterValidationException(refused.get());
        String format = upper(in.format());
        if (!FORMATS.contains(format)) throw new PrinterValidationException("Format must be ZPL or PDF.");
        if ("IPP".equals(connection) && "ZPL".equals(format)) {
            throw new PrinterValidationException("IPP printers take PDF. Use RAW_9100 for a ZPL label printer.");
        }
        String paper = upper(in.paper());
        if (paper.isEmpty()) paper = "ZPL".equals(format) ? "LABEL_4X6" : "LETTER";
        if (!PAPERS.contains(paper)) throw new PrinterValidationException("Paper must be LABEL_4X6, A4 or LETTER.");
        if ("ZPL".equals(format) && !"LABEL_4X6".equals(paper)) {
            throw new PrinterValidationException("A ZPL printer prints 4x6 labels; set paper to LABEL_4X6.");
        }
        String queuePath = null;
        if ("IPP".equals(connection)) {
            queuePath = PrinterTransport.normalisePath(in.queuePath());
            if (!QUEUE_PATH.matcher(queuePath).matches()) {
                throw new PrinterValidationException("Queue path may only use letters, digits and . _ ~ - /  (e.g. ipp/print).");
            }
        }
        String location = trim(in.location());
        p.setName(name);
        p.setLocation(location.isEmpty() ? null : location.substring(0, Math.min(160, location.length())));
        p.setConnection(connection);
        p.setHost(host);
        p.setPort(port);
        p.setQueuePath(queuePath);
        p.setFormat(format);
        p.setPaper(paper);
        p.setActive(in.active() == null || in.active());
    }

    /* ------------------------------ assignments ------------------------------ */

    public List<PrinterAssignment> listAssignments() {
        return assignments.findAllByOrderByClientCodeAscDocTypeAsc();
    }

    /** Create or replace the printer for (client, document type). Blank client = the default. */
    @Transactional
    public PrinterAssignment upsertAssignment(AssignmentInput in) {
        if (in == null) throw new PrinterValidationException("Assignment details are required.");
        String docType = upper(in.docType());
        if (!DOC_TYPES.contains(docType)) {
            throw new PrinterValidationException("Document type must be LABEL or COMMERCIAL_INVOICE.");
        }
        if (in.printerId() == null) throw new PrinterValidationException("Choose a printer.");
        Printer printer = printers.findById(in.printerId())
                .orElseThrow(() -> new PrinterValidationException("That printer no longer exists."));
        if ("COMMERCIAL_INVOICE".equals(docType) && "ZPL".equals(printer.getFormat())) {
            throw new PrinterValidationException("Commercial invoices are PDF pages; " + printer.getName()
                    + " is a ZPL label printer. Choose a PDF printer.");
        }
        String client = upper(in.clientCode());
        if (!client.isEmpty() && clients != null && !clients.existsByClientCodeIgnoreCase(client)) {
            throw new PrinterValidationException("Client " + client + " was not found.");
        }
        Optional<PrinterAssignment> existing = client.isEmpty()
                ? assignments.findFirstByClientCodeIsNullAndDocType(docType)
                : assignments.findFirstByClientCodeIgnoreCaseAndDocType(client, docType);
        PrinterAssignment a = existing.orElseGet(PrinterAssignment::new);
        LocalDateTime now = LocalDateTime.now();
        if (a.getId() == null) a.setCreatedAt(now);
        a.setClientCode(client.isEmpty() ? null : client);
        a.setDocType(docType);
        a.setPrinterId(printer.getId());
        a.setUpdatedAt(now);
        return assignments.save(a);
    }

    @Transactional
    public void deleteAssignment(Long id) {
        assignments.delete(assignments.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Assignment " + id + " was not found.")));
    }

    /** The client's own active printer for this document type, else the active default. */
    public Optional<Printer> resolve(String clientCode, String docType) {
        String client = upper(clientCode);
        if (!client.isEmpty()) {
            Optional<Printer> own = assignments.findFirstByClientCodeIgnoreCaseAndDocType(client, docType)
                    .flatMap(a -> printers.findById(a.getPrinterId())).filter(Printer::isActive);
            if (own.isPresent()) return own;
        }
        return assignments.findFirstByClientCodeIsNullAndDocType(docType)
                .flatMap(a -> printers.findById(a.getPrinterId())).filter(Printer::isActive);
    }

    /** The order's client: tenant, else customer number (same rule as the bulk label job). */
    public String clientCodeOf(Integer orderNo) {
        if (orders == null || orderNo == null) return null;
        return orders.findByOrderNo(orderNo)
                .map(o -> StringUtils.hasText(o.getTenantId()) ? o.getTenantId() : o.getCustNo())
                .map(c -> c.trim().toUpperCase(Locale.ROOT))
                .orElse(null);
    }

    /* ------------------------------ delivery ------------------------------ */

    /** PR-Printer-R11 — in-flight send count per printer_id. Single-node
     *  in-memory tracker; when we scale to multi-node this becomes a
     *  Redis counter (same pattern noted on the R2 SSE fan-out). */
    private final Map<Long, AtomicInteger> inFlightByPrinter = new ConcurrentHashMap<>();

    /** Deliver a document to the printer, in the format the printer was registered with. */
    public void send(Printer printer, byte[] payload, String jobName, String user) throws Exception {
        AtomicInteger counter = inFlightByPrinter.computeIfAbsent(printer.getId(), k -> new AtomicInteger());
        counter.incrementAndGet();
        try {
            if ("IPP".equals(printer.getConnection())) {
                PrinterTransport.sendIpp(printer.getHost(), printer.getPort(), printer.getQueuePath(), payload,
                        "application/pdf", jobName, user);
            } else {
                PrinterTransport.sendRaw(printer.getHost(), printer.getPort(), payload);
            }
        } finally {
            counter.decrementAndGet();
        }
    }

    /** PR-Printer-R11 — poll printer queue depth: our in-flight counter
     *  (portable, single-node) + the printer's own IPP Get-Jobs count
     *  (IPP printers only; RAW_9100 returns ippQueue=null with
     *  ippQueueError = "Not supported"). Never throws — errors are
     *  surfaced in the QueueDepth.ippQueueError field.
     */
    public QueueDepth queueDepth(Long id) {
        Printer p = get(id);
        int inFlight = inFlightByPrinter.getOrDefault(id, new AtomicInteger()).get();
        Integer ippQueue = null;
        String ippQueueError = null;
        if ("IPP".equals(p.getConnection())) {
            try {
                ippQueue = PrinterTransport.ippJobCount(p.getHost(), p.getPort(), p.getQueuePath());
            } catch (Exception ex) {
                if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
                ippQueueError = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            }
        } else {
            ippQueueError = "Not supported for RAW_9100 printers.";
        }
        return new QueueDepth(inFlight, ippQueue, ippQueueError);
    }

    public record QueueDepth(int inFlight, Integer ippQueue, String ippQueueError) {}

    /** Print a small test job and record the outcome on the printer. */
    @Transactional
    public Printer test(Long id, String user) {
        Printer p = get(id);
        try {
            byte[] payload = "ZPL".equals(p.getFormat()) ? testZpl(p) : testPdf(p);
            send(p, payload, "Multiship test page", user);
            p.setLastTestOk(true);
            p.setLastTestMessage("Test job sent (" + p.getFormat() + ", " + payload.length + " bytes).");
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            p.setLastTestOk(false);
            String reason = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            p.setLastTestMessage(truncate("Could not reach " + p.getHost() + ":" + p.getPort() + " — " + reason, 500));
            log.warn("Printer {} ({}:{}) test failed: {}", p.getName(), p.getHost(), p.getPort(), reason);
        }
        LocalDateTime now = LocalDateTime.now();
        p.setLastTestAt(now);
        // PR-R9.5a — also append to the rolling history for the FE
        // PrinterDetailsPanel. Optional (testHistory nullable in unit
        // tests) so an unwired repo doesn't break the test path.
        if (testHistory != null) {
            com.multiship.backend.model.PrinterTestHistory row =
                    new com.multiship.backend.model.PrinterTestHistory();
            row.setPrinterId(p.getId());
            row.setTestedAt(now);
            row.setOk(Boolean.TRUE.equals(p.getLastTestOk()));
            row.setMessage(p.getLastTestMessage());
            row.setTestedBy(user);
            testHistory.save(row);
        }
        return printers.save(p);
    }

    static byte[] testZpl(Printer p) {
        String when = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        String zpl = "^XA^CI28^PW812^LL1218"
                + "^FO60,80^A0N,60,60^FDMultiship test label^FS"
                + "^FO60,170^A0N,36,36^FDPrinter: " + zplSafe(p.getName()) + "^FS"
                + "^FO60,220^A0N,30,30^FD" + zplSafe(p.getHost()) + ":" + p.getPort() + "^FS"
                + "^FO60,270^A0N,30,30^FD" + when + "^FS"
                + "^FO60,340^GB690,4,4^FS^XZ";
        return zpl.getBytes(StandardCharsets.UTF_8);
    }

    static byte[] testPdf(Printer p) throws Exception {
        PDRectangle size = switch (p.getPaper()) {
            case "A4" -> PDRectangle.A4;
            case "LABEL_4X6" -> new PDRectangle(4 * 72f, 6 * 72f);
            default -> PDRectangle.LETTER;
        };
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(size);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                float y = size.getHeight() - 60;
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 18);
                cs.newLineAtOffset(36, y);
                cs.showText("Multiship test page");
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                cs.newLineAtOffset(0, -26);
                cs.showText("Printer: " + pdfSafe(p.getName()));
                cs.newLineAtOffset(0, -16);
                cs.showText(p.getConnection() + "  " + pdfSafe(p.getHost()) + ":" + p.getPort()
                        + "  " + p.getPaper());
                cs.newLineAtOffset(0, -16);
                cs.showText(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
                cs.endText();
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static String zplSafe(String v) {
        return v == null ? "" : v.replace("^", " ").replace("~", " ");
    }

    private static String pdfSafe(String v) {
        if (v == null) return "";
        StringBuilder b = new StringBuilder();
        for (char c : v.toCharArray()) b.append(c < 32 || c > 126 ? '?' : c);
        return b.toString();
    }

    private static String trim(String v) {
        return v == null ? "" : v.trim();
    }

    private static String upper(String v) {
        return trim(v).toUpperCase(Locale.ROOT);
    }

    private static String truncate(String v, int max) {
        return v.length() <= max ? v : v.substring(0, max);
    }
}
