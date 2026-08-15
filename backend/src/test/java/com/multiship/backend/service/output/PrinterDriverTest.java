package com.multiship.backend.service.output;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.model.ClientOutputDestination;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sprint 52 — {@link PrinterDriver} unit tests. RAW_9100 is covered by
 * opening a real localhost {@link ServerSocket} the driver connects
 * to; IPP is covered by config validation + protocol-selector paths
 * (the HTTP round-trip is a network integration concern documented in
 * the PR body).
 */
class PrinterDriverTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final PrinterDriver driver = new PrinterDriver(mapper);

    private ServerSocket serverSocket;
    private CompletableFuture<byte[]> received;

    @BeforeEach
    void openRawServer() throws Exception {
        serverSocket = new ServerSocket(0); // ephemeral port
        received = new CompletableFuture<>();
        new Thread(() -> {
            try (Socket client = serverSocket.accept();
                 InputStream in = client.getInputStream();
                 ByteArrayOutputStream buf = new ByteArrayOutputStream()) {
                in.transferTo(buf);
                received.complete(buf.toByteArray());
            } catch (Exception ex) {
                received.completeExceptionally(ex);
            }
        }, "raw-test-server").start();
    }

    @AfterEach
    void closeRawServer() throws Exception {
        if (serverSocket != null && !serverSocket.isClosed()) serverSocket.close();
    }

    @Test
    void supportsReturnsPrinter() {
        assertEquals(DestinationType.PRINTER, driver.supports());
    }

    @Test
    void rawDispatchWritesBytesToSocket() throws Exception {
        int port = serverSocket.getLocalPort();
        ClientOutputDestination dest = ClientOutputDestination.builder()
                .id(101L).clientCode("ACME").docType(DocType.LABEL)
                .destinationType(DestinationType.PRINTER)
                .config("{\"host\":\"127.0.0.1\",\"port\":" + port + ",\"protocol\":\"RAW_9100\"}")
                .active(true).build();
        DispatchContext ctx = new DispatchContext(1L, 1, "ACME", null, null);
        byte[] payload = "^XA^FDhello^FS^XZ".getBytes();

        driver.dispatch(dest, DocType.LABEL, payload, ctx);

        byte[] got = received.get(5, TimeUnit.SECONDS);
        assertArrayEquals(payload, got);
    }

    @Test
    void dispatchFailsWhenHostMissing() {
        ClientOutputDestination dest = ClientOutputDestination.builder()
                .id(102L).clientCode("ACME").docType(DocType.LABEL)
                .destinationType(DestinationType.PRINTER)
                .config("{\"protocol\":\"RAW_9100\"}")
                .active(true).build();
        DispatchContext ctx = new DispatchContext(1L, 1, "ACME", null, null);

        OutputDeliveryException ex = assertThrows(OutputDeliveryException.class, () ->
                driver.dispatch(dest, DocType.LABEL, "x".getBytes(), ctx));
        assertTrue(ex.getMessage().contains("host"));
    }

    @Test
    void dispatchFailsForUnknownProtocol() {
        ClientOutputDestination dest = ClientOutputDestination.builder()
                .id(103L).clientCode("ACME").docType(DocType.LABEL)
                .destinationType(DestinationType.PRINTER)
                .config("{\"host\":\"127.0.0.1\",\"protocol\":\"WEIRD\"}")
                .active(true).build();
        DispatchContext ctx = new DispatchContext(1L, 1, "ACME", null, null);

        OutputDeliveryException ex = assertThrows(OutputDeliveryException.class, () ->
                driver.dispatch(dest, DocType.LABEL, "x".getBytes(), ctx));
        assertTrue(ex.getMessage().contains("RAW_9100 or IPP"));
    }

    @Test
    void rawDispatchTimesOutOnUnreachableHost() {
        // Non-routable IP + very short timeout — should fail with an OutputDeliveryException.
        ClientOutputDestination dest = ClientOutputDestination.builder()
                .id(104L).clientCode("ACME").docType(DocType.LABEL)
                .destinationType(DestinationType.PRINTER)
                .config("{\"host\":\"10.255.255.1\",\"port\":9100,\"protocol\":\"RAW_9100\"}")
                .active(true).build();
        DispatchContext ctx = new DispatchContext(1L, 1, "ACME", null, null);

        // Bound in the driver at 10s — enough to be robust in CI without being flaky.
        assertThrows(OutputDeliveryException.class, () ->
                driver.dispatch(dest, DocType.LABEL, "x".getBytes(), ctx));
    }
}
