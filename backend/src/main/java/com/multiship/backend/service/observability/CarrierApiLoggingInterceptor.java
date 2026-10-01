package com.multiship.backend.service.observability;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * V114 wire — Spring {@link ClientHttpRequestInterceptor} that calls
 * {@link CarrierApiLogService#record} on every HTTP round-trip made
 * through {@code HttpClients.newBuilder()}. All five currently-wired
 * carriers (FedEx / UPS / USPS / DHL / Stamps / USPS_Direct) and every
 * future connector that uses the shared factory inherit API logging for
 * free — no per-connector change needed.
 *
 * <p>Carrier identity is derived from the request host because the
 * interceptor sits below the connector call-site and has no explicit
 * context. The {@link #carrierFromHost} map covers the five live
 * connectors; unmatched hosts land as {@code UNKNOWN} and still get
 * logged (the URL is persisted, so ops can reclassify).
 *
 * <p>Bodies are clamped by {@link CarrierApiLogService} (60 kB each). A
 * carrier that returns a 2 MB response gets its body truncated, not
 * dropped. Request / response bodies are stored as plaintext — if the
 * carrier ever sends secrets in a response body, redact at this layer.
 *
 * <p>Install at the factory via
 * {@link org.springframework.http.client.BufferingClientHttpRequestFactory}
 * so the body stream is re-readable (the caller still consumes it after
 * the interceptor).
 */
@Slf4j
public class CarrierApiLoggingInterceptor implements ClientHttpRequestInterceptor {

    private final CarrierApiLogService logService;
    private final boolean enabled;

    public CarrierApiLoggingInterceptor(CarrierApiLogService logService, boolean enabled) {
        this.logService = logService;
        this.enabled = enabled;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        if (!enabled || logService == null) {
            return execution.execute(request, body);
        }
        long start = System.currentTimeMillis();
        String carrier = carrierFromHost(request.getURI());
        String method = request.getMethod() == null ? "?" : request.getMethod().name();
        String url = request.getURI().toString();
        String requestBody = body == null || body.length == 0
                ? null : new String(body, StandardCharsets.UTF_8);
        try {
            ClientHttpResponse response = execution.execute(request, body);
            int status = response.getStatusCode().value();
            String responseBody = readBodySafely(response);
            long latency = System.currentTimeMillis() - start;
            logService.record(carrier, method, url, requestBody, responseBody,
                    status, (int) latency, null, null, null, null);
            return response;
        } catch (IOException | RuntimeException ex) {
            long latency = System.currentTimeMillis() - start;
            logService.record(carrier, method, url, requestBody, null,
                    null, (int) latency, ex.getClass().getSimpleName() + ": " + ex.getMessage(),
                    null, null, null);
            throw ex;
        }
    }

    /** Known-host → canonical carrier code map. Add rows as new
     *  connectors land; unknown hosts are logged as UNKNOWN with the
     *  full URL so ops can reclassify after the fact. */
    static String carrierFromHost(URI uri) {
        if (uri == null || uri.getHost() == null) return "UNKNOWN";
        String host = uri.getHost().toLowerCase();
        if (host.contains("fedex.com")) return "FEDEX";
        if (host.contains("ups.com")) return "UPS";
        if (host.contains("usps.com") || host.contains("uspsapis.com")) return "USPS";
        if (host.contains("stamps.com")) return "STAMPS_COM";
        if (host.contains("dhl.com") || host.contains("dhlapi.com")) return "DHL";
        return "UNKNOWN";
    }

    private static String readBodySafely(ClientHttpResponse response) {
        try (var in = response.getBody()) {
            byte[] bytes = in.readAllBytes();
            return bytes.length == 0 ? null : new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception ex) {
            return "[response body read failed: " + ex.getMessage() + "]";
        }
    }
}
