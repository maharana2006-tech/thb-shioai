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

    private PrinterTransport() {}

    /** Write the job to the printer's raw port. */
    public static void sendRaw(String host, int port, byte[] payload) throws IOException {
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

    /** Count the number of job-attributes-tag (0x04) group markers in an
     *  IPP response body. Each pending job produces one such group. */
    static int countJobGroups(byte[] body) {
        if (body == null || body.length <= 8) return 0;
        int count = 0;
        // Skip the 8-byte IPP header (version + status + request-id).
        // Then walk through remaining tags; 0x04 (job-attributes-tag)
        // begins a job group.
        int i = 8;
        while (i < body.length) {
            int tag = body[i] & 0xff;
            if (tag == 0x04) count++;
            if (tag == 0x03) break; // end-of-attributes
            if (tag >= 0x00 && tag <= 0x05) {
                // begin-attribute-group tag: no name / value bytes.
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
