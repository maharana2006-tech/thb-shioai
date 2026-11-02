package com.multiship.backend.service.observability;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
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
 * dropped. Secrets (client secrets, tokens, passwords) are blanked out
 * by {@link #redact} before anything is stored — any admin can read this log.
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
        String url = redact(request.getURI().toString());
        String requestBody = body == null || body.length == 0
                ? null : redact(new String(body, StandardCharsets.UTF_8));
        // MDC context — requestId is set by MdcCorrelationFilter on every
        // HTTP-triggered flow; orderNo + tracking come from CarrierCallContext
        // when a connector call site tagged itself. All three may be null
        // (background jobs / cron ticks without a request scope).
        String requestId = MDC.get("requestId");
        Long orderNo = parseLong(MDC.get(CarrierCallContext.MDC_CARRIER_ORDER_NO));
        String tracking = MDC.get(CarrierCallContext.MDC_CARRIER_TRACKING);
        try {
            ClientHttpResponse response = execution.execute(request, body);
            int status = response.getStatusCode().value();
            String responseBody = redact(readBodySafely(response));
            long latency = System.currentTimeMillis() - start;
            // BUG-022 — populate errorMessage on non-2xx so the Carrier
            // API Log row's structured error field is searchable and
            // dashboards don't have to pattern-match the response body.
            // The response body is kept whole on responseBody; this is
            // a short summary (CarrierApiLogService clamps to ERR_MAX
            // anyway, so an over-long body gets truncated to a prefix).
            String errorMessage = status >= 400 ? responseBody : null;
            logService.record(carrier, method, url, requestBody, responseBody,
                    status, (int) latency, errorMessage, orderNo, tracking, requestId);
            return response;
        } catch (IOException | RuntimeException ex) {
            long latency = System.currentTimeMillis() - start;
            logService.record(carrier, method, url, requestBody, null,
                    null, (int) latency, ex.getClass().getSimpleName() + ": " + ex.getMessage(),
                    orderNo, tracking, requestId);
            throw ex;
        }
    }

    // BUG-065 — client_id is the public half of an OAuth client credential
    // and treating it as public leaks on carriers that mint long-lived +
    // non-rotatable client_ids (Stamps.com SERA, UPS 3.0). Admin-visible
    // logs persist for weeks; mask it too. If a carrier's client_id ever
    // needs to be read raw, the operator still has the carrier-account
    // row at /settings/carriers.
    private static final String SECRET_KEYS =
            "client_secret|clientSecret|client_id|clientId|refresh_token|refreshToken|access_token|accessToken|id_token"
            + "|code_verifier|password|api_key|apiKey";
    /** "client_secret": "…" in JSON. */
    private static final java.util.regex.Pattern JSON_SECRET = java.util.regex.Pattern.compile(
            "(\"(?:" + SECRET_KEYS + ")\"\\s*:\\s*\")[^\"]*(\")");
    /** client_secret=… in a form body or a query string. */
    private static final java.util.regex.Pattern FORM_SECRET = java.util.regex.Pattern.compile(
            "((?:^|[?&])(?:" + SECRET_KEYS + ")=)[^&\\s]*");
    /** <Password>…</Password> in SOAP (Stamps.com SWSIM). */
    private static final java.util.regex.Pattern XML_SECRET = java.util.regex.Pattern.compile(
            "(<(?:\\w+:)?Password>)[^<]*", java.util.regex.Pattern.CASE_INSENSITIVE);

    /** The text with every secret value replaced by "***". */
    static String redact(String text) {
        if (text == null) return null;
        String out = JSON_SECRET.matcher(text).replaceAll("$1***$2");
        out = XML_SECRET.matcher(out).replaceAll("$1***");
        return FORM_SECRET.matcher(out).replaceAll("$1***");
    }

    private static Long parseLong(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Long.parseLong(s.trim()); } catch (NumberFormatException ex) { return null; }
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
        if (host.contains("stamps.com") || host.contains("stampsendicia.com")) return "STAMPS_COM";
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
