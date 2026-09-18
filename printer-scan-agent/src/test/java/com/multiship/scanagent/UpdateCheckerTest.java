package com.multiship.scanagent;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PR-Printer-P4c — hourly update-check contract test.
 *
 * <p>Uses the built-in {@link HttpServer} instead of WireMock/MockWebServer
 * to keep test deps at zero. The exit hook is a boolean flag so a real
 * mismatch doesn't kill the test JVM.
 */
class UpdateCheckerTest {

    private HttpServer server;
    private AtomicReference<String> stubbedVersion;
    private String baseUrl;

    @BeforeEach
    void setUp() throws IOException {
        stubbedVersion = new AtomicReference<>("0.1.0");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/printer-scan-agents/latest-version", ex -> {
            String v = stubbedVersion.get();
            String body = v == null
                    ? "{\"status\":\"success\",\"code\":200,\"data\":{}}"
                    : "{\"status\":\"success\",\"code\":200,\"data\":{\"version\":\"" + v + "\"}}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    void checkOnce_versionMatches_doesNotExit() {
        AtomicBoolean exited = new AtomicBoolean();
        UpdateChecker checker = new UpdateChecker(baseUrl, "0.1.0", () -> exited.set(true));

        stubbedVersion.set("0.1.0");
        checker.checkOnce();

        assertFalse(exited.get(), "matched version must NOT trigger exit");
    }

    @Test
    void checkOnce_versionMismatch_callsExitHook() {
        AtomicBoolean exited = new AtomicBoolean();
        UpdateChecker checker = new UpdateChecker(baseUrl, "0.1.0", () -> exited.set(true));

        stubbedVersion.set("0.2.0");
        checker.checkOnce();

        assertTrue(exited.get(), "newer latest-version must trigger exit hook");
    }

    @Test
    void checkOnce_serverUnreachable_doesNotExit() {
        AtomicBoolean exited = new AtomicBoolean();
        // Bogus base URL: connection will be refused.
        UpdateChecker checker = new UpdateChecker("http://127.0.0.1:1", "0.1.0", () -> exited.set(true));

        checker.checkOnce();

        assertFalse(exited.get(), "transient network error must NOT crash the loop by exiting");
    }

    @Test
    void checkOnce_serverReturnsBlankVersion_doesNotExit() {
        AtomicBoolean exited = new AtomicBoolean();
        UpdateChecker checker = new UpdateChecker(baseUrl, "0.1.0", () -> exited.set(true));

        stubbedVersion.set(null);
        checker.checkOnce();

        assertFalse(exited.get(), "empty version response must NOT trigger exit — could be a rollout gap");
    }
}
