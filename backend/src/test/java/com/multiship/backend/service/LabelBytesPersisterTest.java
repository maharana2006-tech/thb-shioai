package com.multiship.backend.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * PR #550 — locks in {@link LabelBytesPersister} contract. The service
 * pre-fetches URL labels at persistence time so {@code label_file_path}
 * carries base64 bytes that survive carrier-URL expiry (motivated by
 * order 900016 STATE_3 — 3 label_package rows with 146-char signed
 * FedEx URLs that had expired).
 *
 * <p>URL-fetch branch covered by an in-process JDK {@link HttpServer}
 * (no WireMock / no extra dep). Pure-string branches (null, ZPL, base64)
 * don't need networking.
 */
class LabelBytesPersisterTest {

    private HttpServer server;
    private int port;
    private LabelBytesPersister persister;
    /** Handler swap per test — atomic so the server thread sees the write. */
    private final AtomicReference<TestHandler> handler = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            TestHandler h = handler.get();
            if (h == null) {
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
                return;
            }
            int status = h.status;
            byte[] body = h.body;
            exchange.sendResponseHeaders(status, body == null || body.length == 0 ? -1 : body.length);
            if (body != null && body.length > 0) {
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        port = server.getAddress().getPort();
        persister = new LabelBytesPersister(1_000_000);
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    // ─── Passthrough branches ────────────────────────────────────────

    @Test
    void null_input_returns_null() {
        assertNull(persister.toPersistable(null));
    }

    @Test
    void blank_input_returns_input_verbatim() {
        assertEquals("", persister.toPersistable(""));
        assertEquals("   ", persister.toPersistable("   "));
    }

    @Test
    void raw_zpl_input_returns_input_verbatim() {
        String zpl = "^XA^FO50,50^A0N,40,40^FDTest Label^FS^XZ";
        assertEquals(zpl, persister.toPersistable(zpl));
    }

    @Test
    void base64_looking_input_returns_input_verbatim() {
        // Not a URL, doesn't start with ^XA → assumed to be already-base64
        // (FedEx encodedLabel / UPS GraphicImage / DHL labelImages).
        String base64 = Base64.getEncoder().encodeToString("^XA^XZ".getBytes());
        assertEquals(base64, persister.toPersistable(base64));
    }

    // ─── URL-fetch branch ────────────────────────────────────────────

    @Test
    void url_fetch_success_returns_base64_of_response_body() {
        byte[] pdfBytes = new byte[]{'%', 'P', 'D', 'F', '-', '1', '.', '4'};
        handler.set(new TestHandler(200, pdfBytes));

        String out = persister.toPersistable(url("/label/1.pdf"));

        assertEquals(Base64.getEncoder().encodeToString(pdfBytes), out);
    }

    @Test
    void url_fetch_https_scheme_recognised_but_fetch_fails_falls_back_to_url() {
        // Not actually hitting HTTPS (server is plain HTTP). Point: the
        // scheme check accepts both. Fetch fails (wrong scheme / no
        // listener), so fallback returns the URL unchanged.
        String url = "https://nowhere.invalid.example.com:1/label/x.pdf";
        assertEquals(url, persister.toPersistable(url),
                "https URL that can't be fetched falls back to storing the URL");
    }

    @Test
    void url_fetch_404_falls_back_to_storing_the_url() {
        handler.set(new TestHandler(404, null));
        String url = url("/gone.pdf");
        assertEquals(url, persister.toPersistable(url),
                "404 → fall back to storing URL unchanged (no worse than pre-#550)");
    }

    @Test
    void url_fetch_empty_body_falls_back_to_storing_the_url() {
        handler.set(new TestHandler(200, new byte[0]));
        String url = url("/empty.pdf");
        assertEquals(url, persister.toPersistable(url));
    }

    @Test
    void url_fetch_over_size_cap_falls_back_to_storing_the_url() {
        // Persister configured with 1 MB cap. Return 1.1 MB.
        byte[] oversized = new byte[1_100_000];
        java.util.Arrays.fill(oversized, (byte) 'X');
        handler.set(new TestHandler(200, oversized));
        String url = url("/huge.pdf");
        assertEquals(url, persister.toPersistable(url),
                "over-cap payload → fall back to URL rather than bloating the row");
    }

    @Test
    void url_within_cap_succeeds() {
        // Exactly at cap size — should succeed.
        byte[] atCap = new byte[1_000_000];
        java.util.Arrays.fill(atCap, (byte) 'X');
        handler.set(new TestHandler(200, atCap));
        String out = persister.toPersistable(url("/big.pdf"));
        // Base64-of-1MB is ~1.33 MB — verify it decodes back to the same
        // byte count. Don't compare the whole string (memory pressure).
        byte[] decoded = Base64.getDecoder().decode(out);
        assertEquals(atCap.length, decoded.length);
    }

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    /** Immutable pair for handler.set(...) — status + optional body. */
    private record TestHandler(int status, byte[] body) {}
}
