package com.multiship.backend.service.printing;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Puts bytes on a network printer.
 *
 * <ul>
 *   <li><b>RAW_9100</b> — open the printer's raw port and write the job. What
 *       Zebra-style thermal printers take for ZPL, and what PDF-capable office
 *       printers accept on port 9100.</li>
 *   <li><b>IPP</b> — a real RFC 8011 Print-Job request: the binary IPP header
 *       (charset, language, printer-uri, user, job name, document-format)
 *       followed by the document, POSTed as {@code application/ipp}. The
 *       printer's own IPP status code decides success, not just HTTP 200.</li>
 * </ul>
 */
public final class PrinterTransport {

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final Duration IPP_REQUEST_TIMEOUT = Duration.ofSeconds(120);
    private static final AtomicInteger REQUEST_IDS = new AtomicInteger(1);
    /** RFC 8010 §3.5.1 — the delimiter that opens one job's attribute group. */
    static final int JOB_ATTRIBUTES_TAG = 0x02;
    static final int END_OF_ATTRIBUTES_TAG = 0x03;

    private PrinterTransport() {}

    /** Write the job to the printer's raw port. */
    public static void sendRaw(String host, int port, byte[] payload) throws IOException {
        PrinterAddressGuard.check(host, port);
        try (Socket socket = new Socket()) {
            socket.setSoTimeout(CONNECT_TIMEOUT_MS);
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            try (OutputStream out = socket.getOutputStream()) {
                out.write(payload);
                out.flush();
            }
        }
    }

    /** Send one IPP Print-Job. Throws when the connection fails or the printer refuses the job. */
    public static void sendIpp(String host, int port, String queuePath, byte[] document, String documentFormat,
                               String jobName, String user) throws IOException, InterruptedException {
        PrinterAddressGuard.check(host, port);
        String path = normalisePath(queuePath);
        String authority = uriHost(host) + ":" + port;
        byte[] header = printJobHeader("ipp://" + authority + "/" + path, user, jobName, documentFormat,
                REQUEST_IDS.getAndIncrement());
        byte[] body = new byte[header.length + document.length];
        System.arraycopy(header, 0, body, 0, header.length);
        System.arraycopy(document, 0, body, header.length, document.length);

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://" + authority + "/" + path))
                .timeout(IPP_REQUEST_TIMEOUT)
                .header("Content-Type", "application/ipp")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("the printer answered HTTP " + response.statusCode() + " to the IPP request");
        }
        int status = ippStatus(response.body());
        // 0x0000-0x00FF are successful-ok variants; anything above is a refusal.
        if (status >= 0x0100) {
            throw new IOException("the printer refused the job (IPP status 0x" + String.format("%04x", status) + ")");
        }
    }

    /**
     * PR-Printer-R11 — send an IPP Get-Jobs request and return the number of
     * pending (not-completed) jobs at the printer. Same wire format + auth
     * assumptions as {@link #sendIpp}; only the operation code + which-jobs
     * attribute differ. Callers should use a short-ish timeout (this is a
     * status poll, not a print).
     *
     * @return count of pending jobs, or throw on I/O / auth / IPP-status errors.
     */
    public static int ippJobCount(String host, int port, String queuePath) throws IOException, InterruptedException {
        PrinterAddressGuard.check(host, port);
        String path = normalisePath(queuePath);
        String authority = uriHost(host) + ":" + port;
        byte[] header = getJobsHeader("ipp://" + authority + "/" + path,
                REQUEST_IDS.getAndIncrement());

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://" + authority + "/" + path))
                // Poll — not a full print. Short timeout so a hung printer
                // doesn't stall the UI refresh loop.
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/ipp")
                .POST(HttpRequest.BodyPublishers.ofByteArray(header))
                .build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("the printer answered HTTP " + response.statusCode() + " to Get-Jobs");
        }
        int status = ippStatus(response.body());
        if (status >= 0x0100) {
            throw new IOException("the printer refused Get-Jobs (IPP status 0x" + String.format("%04x", status) + ")");
        }
        return countJobGroups(response.body());
    }

    /** What a printer says about itself: its model and the formats it can print. */
    public record PrinterAttributes(String makeAndModel, java.util.List<String> formats, String queuePath) { }

    /**
     * IPP Get-Printer-Attributes — read-only, prints nothing. Asks for the
     * model and document-format-supported, which is what tells an office laser
     * (PCL only) apart from one that takes PDF, before anything is sent to it.
     */
    public static PrinterAttributes printerAttributes(String host, int port, String queuePath)
            throws IOException, InterruptedException {
        PrinterAddressGuard.check(host, port);
        String path = normalisePath(queuePath);
        String printerUri = "ipp://" + host + ":" + port + "/" + path;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(1);
        out.writeByte(1);
        out.writeShort(0x000B);                                // Get-Printer-Attributes
        out.writeInt(REQUEST_IDS.getAndIncrement());
        out.writeByte(0x01);                                   // operation-attributes
        attribute(out, 0x47, "attributes-charset", "utf-8");
        attribute(out, 0x48, "attributes-natural-language", "en");
        attribute(out, 0x45, "printer-uri", printerUri);
        attribute(out, 0x44, "requested-attributes", "printer-make-and-model");
        attribute(out, 0x44, "", "document-format-supported");  // additional value, same attribute
        out.writeByte(END_OF_ATTRIBUTES_TAG);
        out.flush();

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://" + host + ":" + port + "/" + path))
                .timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/ipp")
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes.toByteArray()))
                .build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("the printer answered HTTP " + response.statusCode());
        }
        int status = ippStatus(response.body());
        if (status >= 0x0100) {
            throw new IOException("the printer refused the query (IPP status 0x" + String.format("%04x", status) + ")");
        }
        return parseAttributes(response.body(), path);
    }

    /** Walks a Get-Printer-Attributes response for the two attributes we asked for. */
    static PrinterAttributes parseAttributes(byte[] body, String path) {
        String model = null;
        java.util.List<String> formats = new java.util.ArrayList<>();
        String last = null;
        int i = 8;
        while (i < body.length) {
            int tag = body[i] & 0xff;
            if (tag == END_OF_ATTRIBUTES_TAG) break;
            if (tag < 0x10) { i++; continue; }
            if (i + 3 > body.length) break;
            int nameLen = ((body[i + 1] & 0xff) << 8) | (body[i + 2] & 0xff);
            int afterName = i + 3 + nameLen;
            if (afterName + 2 > body.length) break;
            String name = new String(body, i + 3, nameLen, java.nio.charset.StandardCharsets.UTF_8);
            int valueLen = ((body[afterName] & 0xff) << 8) | (body[afterName + 1] & 0xff);
            int valueStart = afterName + 2;
            if (valueStart + valueLen > body.length) break;
            String value = new String(body, valueStart, valueLen, java.nio.charset.StandardCharsets.UTF_8);
            String attr = name.isEmpty() ? last : name;          // empty name = another value of the same attribute
            if ("printer-make-and-model".equals(attr) && model == null) model = value;
            if ("document-format-supported".equals(attr)) formats.add(value);
            last = attr;
            i = valueStart + valueLen;
        }
        return new PrinterAttributes(model, java.util.List.copyOf(formats), path);
    }

    /** IPP Get-Jobs operation header — like Print-Job but with a different
     *  op code and a which-jobs = "not-completed" attribute. No body. */
    static byte[] getJobsHeader(String printerUri, int requestId) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(1);                 // version 1.1
            out.writeByte(1);
            out.writeShort(0x000A);           // Get-Jobs
            out.writeInt(requestId);
            out.writeByte(0x01);              // operation-attributes-tag
            attribute(out, 0x47, "attributes-charset", "utf-8");
            attribute(out, 0x48, "attributes-natural-language", "en");
            attribute(out, 0x45, "printer-uri", printerUri);
            attribute(out, 0x42, "requesting-user-name", "multiship");
            // keyword — default is "not-completed" per RFC 8011 §4.2.6.
            attribute(out, 0x44, "which-jobs", "not-completed");
            out.writeByte(0x03);              // end-of-attributes-tag
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * Count the job groups in an IPP Get-Jobs response: one per pending job.
     *
     * <p>RFC 8010 §3.5.1 delimiter tags: 0x01 operation-attributes, 0x02
     * job-attributes, 0x03 end-of-attributes, 0x04 printer-attributes, 0x05
     * unsupported-attributes; 0x06–0x0F are reserved delimiters. This used to
     * count 0x04 — the printer's own attribute group — so the queue always read
     * 0 (or 1 from a printer that echoes its attributes), never the real depth.
     */
    static int countJobGroups(byte[] body) {
        if (body == null || body.length <= 8) return 0;
        int count = 0;
        // Skip the 8-byte IPP header (version + status + request-id), then
        // walk the attribute stream tag by tag.
        int i = 8;
        while (i < body.length) {
            int tag = body[i] & 0xff;
            if (tag == JOB_ATTRIBUTES_TAG) count++;
            if (tag == END_OF_ATTRIBUTES_TAG) break;
            if (tag < 0x10) {
                // A delimiter (group start): no name or value bytes follow.
                i++;
                continue;
            }
            // value tag: [tag:1][name-len:2][name:N][value-len:2][value:M]
            if (i + 3 >= body.length) break;
            int nameLen = ((body[i + 1] & 0xff) << 8) | (body[i + 2] & 0xff);
            int afterName = i + 3 + nameLen;
            if (afterName + 2 > body.length) break;
            int valueLen = ((body[afterName] & 0xff) << 8) | (body[afterName + 1] & 0xff);
            i = afterName + 2 + valueLen;
        }
        return count;
    }

    /** The IPP status code from a response body (bytes 2-3). */
    static int ippStatus(byte[] responseBody) throws IOException {
        if (responseBody == null || responseBody.length < 4) {
            throw new IOException("the printer sent an empty IPP response");
        }
        return ((responseBody[2] & 0xff) << 8) | (responseBody[3] & 0xff);
    }

    /** IPP/1.1 Print-Job operation header, ending with end-of-attributes. */
    static byte[] printJobHeader(String printerUri, String user, String jobName, String documentFormat, int requestId) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(1);                 // version 1.1
            out.writeByte(1);
            out.writeShort(0x0002);           // Print-Job
            out.writeInt(requestId);
            out.writeByte(0x01);              // operation-attributes-tag
            attribute(out, 0x47, "attributes-charset", "utf-8");
            attribute(out, 0x48, "attributes-natural-language", "en");
            attribute(out, 0x45, "printer-uri", printerUri);
            attribute(out, 0x42, "requesting-user-name", user == null || user.isBlank() ? "multiship" : user);
            attribute(out, 0x42, "job-name", jobName == null || jobName.isBlank() ? "Multiship" : jobName);
            attribute(out, 0x49, "document-format", documentFormat);
            out.writeByte(0x03);              // end-of-attributes-tag
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void attribute(DataOutputStream out, int valueTag, String name, String value) throws IOException {
        byte[] n = name.getBytes(StandardCharsets.US_ASCII);
        byte[] v = value.getBytes(StandardCharsets.UTF_8);
        out.writeByte(valueTag);
        out.writeShort(n.length);
        out.write(n);
        out.writeShort(v.length);
        out.write(v);
    }

    static String normalisePath(String queuePath) {
        String p = queuePath == null || queuePath.isBlank() ? "ipp/print" : queuePath.trim();
        while (p.startsWith("/")) p = p.substring(1);
        return p;
    }

    private static String uriHost(String host) {
        return host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
    }
}
