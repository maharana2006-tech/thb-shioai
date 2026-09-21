package com.multiship.backend.service.printing;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real sockets on localhost: what a printer on port 9100 / an IPP printer actually receives. */
class PrinterTransportTest {

    // These tests stand up real sockets on 127.0.0.1, which the address
    // guard refuses in production. Allow loopback for this class only.
    @org.junit.jupiter.api.BeforeEach
    void allowLoopback() { PrinterAddressGuard.setAllowLoopback(true); }

    @org.junit.jupiter.api.AfterEach
    void restoreGuard() { PrinterAddressGuard.setAllowLoopback(false); }


    @Test
    void raw9100WritesTheJobBytesUnchanged() throws Exception {
        byte[] zpl = "^XA^FO50,50^A0N,40,40^FDHello^FS^XZ".getBytes(StandardCharsets.UTF_8);
        try (ServerSocket printer = new ServerSocket(0)) {
            CompletableFuture<byte[]> received = CompletableFuture.supplyAsync(() -> {
                try (Socket s = printer.accept(); InputStream in = s.getInputStream()) {
                    return in.readAllBytes();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            PrinterTransport.sendRaw("127.0.0.1", printer.getLocalPort(), zpl);
            assertArrayEquals(zpl, received.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void ippSendsAPrintJobOperationFollowedByTheDocument() throws Exception {
        AtomicReference<byte[]> body = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        HttpServer printer = ippPrinter(0x0000, body, contentType, path);
        try {
            int port = printer.getAddress().getPort();
            byte[] pdf = "%PDF-1.7 test document".getBytes(StandardCharsets.US_ASCII);
            PrinterTransport.sendIpp("127.0.0.1", port, "/printers/Office", pdf, "application/pdf", "Multiship 2 labels", "alice");

            byte[] b = body.get();
            assertEquals("application/ipp", contentType.get());
            assertEquals("/printers/Office", path.get());
            assertEquals(1, b[0]);                                   // IPP 1.1
            assertEquals(1, b[1]);
            assertEquals(0x0002, ((b[2] & 0xff) << 8) | (b[3] & 0xff)); // Print-Job
            assertEquals(0x01, b[8]);                                // operation-attributes-tag
            String text = new String(b, StandardCharsets.ISO_8859_1);
            assertTrue(text.contains("printer-uri") && text.contains("ipp://127.0.0.1:" + port + "/printers/Office"), text);
            assertTrue(text.contains("requesting-user-name") && text.contains("alice"), text);
            assertTrue(text.contains("document-format") && text.contains("application/pdf"), text);
            // end-of-attributes (0x03) immediately followed by the document
            byte[] tail = Arrays.copyOfRange(b, b.length - pdf.length, b.length);
            assertArrayEquals(pdf, tail);
            assertEquals(0x03, b[b.length - pdf.length - 1]);
        } finally {
            printer.stop(0);
        }
    }

    @Test
    void ippRefusalByThePrinterIsAnError_evenOverHttp200() throws Exception {
        HttpServer printer = ippPrinter(0x0400, new AtomicReference<>(), new AtomicReference<>(), new AtomicReference<>());
        try {
            IOException e = assertThrows(IOException.class, () -> PrinterTransport.sendIpp("127.0.0.1",
                    printer.getAddress().getPort(), "ipp/print", new byte[]{1, 2, 3}, "application/pdf", "job", "bob"));
            assertTrue(e.getMessage().contains("refused") && e.getMessage().contains("0400"), e.getMessage());
        } finally {
            printer.stop(0);
        }
    }

    @Test
    void unreachablePrinterFailsFast() {
        assertThrows(IOException.class, () -> PrinterTransport.sendRaw("127.0.0.1", freePort(), new byte[]{1}));
    }

    private static HttpServer ippPrinter(int ippStatus, AtomicReference<byte[]> body, AtomicReference<String> contentType,
                                         AtomicReference<String> path) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (InputStream in = exchange.getRequestBody()) {
                body.set(in.readAllBytes());
            }
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            path.set(exchange.getRequestURI().getPath());
            ByteArrayOutputStream resp = new ByteArrayOutputStream();
            resp.write(new byte[]{1, 1, (byte) (ippStatus >> 8), (byte) ippStatus, 0, 0, 0, 1, 0x03});
            byte[] r = resp.toByteArray();
            exchange.getResponseHeaders().set("Content-Type", "application/ipp");
            exchange.sendResponseHeaders(200, r.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(r);
            }
        });
        server.start();
        return server;
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    // ===== queue depth: counting job groups in a Get-Jobs response =====

    /** One attribute in IPP wire form: tag, name, value. */
    private static void attr(java.io.DataOutputStream out, int tag, String name, byte[] value) throws IOException {
        out.writeByte(tag);
        out.writeShort(name.length());
        out.writeBytes(name);
        out.writeShort(value.length);
        out.write(value);
    }

    private static byte[] getJobsResponse(int jobs, boolean withPrinterGroup) throws IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream out = new java.io.DataOutputStream(bytes);
        out.writeByte(1); out.writeByte(1);    // IPP 1.1
        out.writeShort(0x0000);                // successful-ok
        out.writeInt(7);                        // request id
        out.writeByte(0x01);                    // operation-attributes
        attr(out, 0x47, "attributes-charset", "utf-8".getBytes());
        if (withPrinterGroup) {
            // A printer-attributes group (0x04) — what the old code counted.
            out.writeByte(0x04);
            attr(out, 0x21, "queued-job-count", new byte[]{0, 0, 0, 9});
        }
        for (int j = 0; j < jobs; j++) {
            out.writeByte(0x02);                // job-attributes: one per job
            // Value bytes deliberately include 0x02 / 0x04 so a byte scan
            // would miscount; the parser has to skip values by length.
            attr(out, 0x21, "job-id", new byte[]{0, 0, 0x02, 0x04});
            attr(out, 0x23, "job-state", new byte[]{0, 0, 0, 0x04});
        }
        out.writeByte(0x03);                    // end-of-attributes
        return bytes.toByteArray();
    }

    @Test
    void queueDepthCountsJobGroupsNotThePrintersOwnAttributes() throws IOException {
        assertEquals(3, PrinterTransport.countJobGroups(getJobsResponse(3, true)),
                "three pending jobs, and the printer-attributes group is not one of them");
        assertEquals(0, PrinterTransport.countJobGroups(getJobsResponse(0, true)),
                "an idle printer that echoes its own attributes still has an empty queue");
        assertEquals(2, PrinterTransport.countJobGroups(getJobsResponse(2, false)));
    }

    // ===== the address guard =====

    @Test
    void theGuardRefusesTheServerItselfTheMetadataServiceAndServicePorts() throws IOException {
        PrinterAddressGuard.setAllowLoopback(false);
        assertTrue(PrinterAddressGuard.refusal("127.0.0.1", 9100).isPresent(), "loopback");
        assertTrue(PrinterAddressGuard.refusal("localhost", 9100).isPresent(), "localhost");
        assertTrue(PrinterAddressGuard.refusal("169.254.169.254", 80).get().contains("metadata"), "cloud metadata");
        assertTrue(PrinterAddressGuard.refusal("0.0.0.0", 9100).isPresent(), "unspecified");
        assertTrue(PrinterAddressGuard.refusal("192.168.1.50", 5432).get().contains("PostgreSQL"), "database port");
        assertTrue(PrinterAddressGuard.refusal("10.0.0.9", 6379).get().contains("Redis"), "cache port");

        // Real warehouse printers stay allowed: private LAN addresses, printer ports.
        assertTrue(PrinterAddressGuard.refusal("192.168.1.50", 9100).isEmpty(), "LAN label printer");
        assertTrue(PrinterAddressGuard.refusal("10.20.30.40", 631).isEmpty(), "LAN IPP printer");
        assertTrue(PrinterAddressGuard.refusal("172.16.5.5", 9101).isEmpty(), "second JetDirect port");

        // And a refused address is refused at connect time too, not just on save.
        IOException e = assertThrows(IOException.class, () -> PrinterTransport.sendRaw("127.0.0.1", 9100, new byte[]{1}));
        assertTrue(e.getMessage().contains("this server itself"), e.getMessage());
    }
}
