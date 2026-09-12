package com.multiship.backend.service.carriers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.service.fx.FxRateService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Batch #9 post-mortem test (2026-09-12) — the TCP-retry helper added
 * to {@link FedExConnector} must:
 *   1. Return normally on the first attempt when the POST succeeds.
 *   2. Retry once on {@link ResourceAccessException} (TCP-level failure
 *      like {@code Connection reset}) and return the retry's response.
 *   3. Give up after the retry if the failure persists, propagating the
 *      original TCP error.
 *
 * <p>Uses a hand-crafted {@link RestClient} mock rather than
 * Mockito's fluent-builder pattern (which is verbose against
 * RestClient's chained API) — the helper method's contract is small
 * enough to exercise directly via reflection.
 */
class FedExConnectorTcpRetryTest {

    private static FxRateService noFx() {
        return new FxRateService() {
            @Override public Optional<BigDecimal> rate(String f, String t) { return Optional.empty(); }
            @Override public Optional<BigDecimal> convert(BigDecimal a, String f, String t) { return Optional.empty(); }
            @Override public boolean supports(String c) { return false; }
        };
    }

    private String invokeHelper(RestClient client) throws Exception {
        FedExConnector conn = new FedExConnector(new CarrierProperties(), new ObjectMapper(), noFx());
        Method m = FedExConnector.class.getDeclaredMethod(
                "postShipmentWithTcpRetry", RestClient.class, String.class, Map.class, String.class);
        m.setAccessible(true);
        try {
            return (String) m.invoke(conn, client, "tok", Map.of("k", "v"), "PO-1");
        } catch (java.lang.reflect.InvocationTargetException ite) {
            if (ite.getCause() instanceof RuntimeException re) throw re;
            throw new RuntimeException(ite.getCause());
        }
    }

    /**
     * Build a {@link RestClient} that returns pre-scripted responses (or
     * throws pre-scripted exceptions) on successive calls. Uses the real
     * RestClient builder + a Spring {@code ClientHttpRequestFactory} stub
     * so the entire builder chain executes as it would in production;
     * only the actual HTTP send is intercepted.
     */
    private RestClient scriptedClient(Object... responses) {
        AtomicInteger idx = new AtomicInteger();
        org.springframework.http.client.ClientHttpRequestFactory factory =
                (uri, method) -> new org.springframework.http.client.ClientHttpRequest() {
            private final org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            private final java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
            @Override public java.io.OutputStream getBody() { return body; }
            @Override public org.springframework.http.HttpMethod getMethod() { return method; }
            @Override public java.net.URI getURI() { return uri; }
            @Override public org.springframework.http.HttpHeaders getHeaders() { return headers; }
            @Override public java.util.Map<String, Object> getAttributes() { return new java.util.HashMap<>(); }
            @Override public org.springframework.http.client.ClientHttpResponse execute() throws IOException {
                Object next = responses[idx.getAndIncrement()];
                if (next instanceof IOException io) throw io;
                if (next instanceof RuntimeException re) throw re;
                String payload = (String) next;
                return new org.springframework.http.client.ClientHttpResponse() {
                    @Override public org.springframework.http.HttpStatusCode getStatusCode() { return org.springframework.http.HttpStatus.OK; }
                    @Override public String getStatusText() { return "OK"; }
                    @Override public void close() { }
                    @Override public java.io.InputStream getBody() { return new java.io.ByteArrayInputStream(payload.getBytes()); }
                    @Override public org.springframework.http.HttpHeaders getHeaders() {
                        org.springframework.http.HttpHeaders h = new org.springframework.http.HttpHeaders();
                        h.setContentType(MediaType.APPLICATION_JSON);
                        return h;
                    }
                };
            }
        };
        return RestClient.builder().baseUrl("http://test.local").requestFactory(factory).build();
    }

    @Test
    void firstAttemptSucceeds_noRetry() throws Exception {
        String result = invokeHelper(scriptedClient("{\"ok\":true}"));
        assertEquals("{\"ok\":true}", result);
    }

    @Test
    void firstAttemptTcpReset_retrySucceeds() throws Exception {
        // Real batch-9 shape: first POST throws Connection reset (as
        // ResourceAccessException wrapping IOException); FedEx's edge
        // recovers and the second POST succeeds.
        RestClient client = scriptedClient(
                new ResourceAccessException("I/O error on POST", new IOException("Connection reset")),
                "{\"ok\":true,\"attempt\":2}");
        assertEquals("{\"ok\":true,\"attempt\":2}", invokeHelper(client));
    }

    @Test
    void bothAttemptsTcpReset_propagatesOriginalError() {
        // Persistent TCP failure — we retry once, then give up. The
        // ResourceAccessException from the LAST attempt should surface
        // (the caller's error handler already maps it to a typed
        // CarrierException with an actionable message).
        RestClient client = scriptedClient(
                new ResourceAccessException("I/O error on POST", new IOException("Connection reset")),
                new ResourceAccessException("I/O error on POST", new IOException("Connection reset")));
        ResourceAccessException ex = assertThrows(ResourceAccessException.class,
                () -> invokeHelper(client));
        org.junit.jupiter.api.Assertions.assertTrue(
                ex.getMessage().contains("I/O error") || ex.getCause().getMessage().contains("Connection reset"),
                "propagated TCP error must name the failure: " + ex.getMessage());
    }
}
