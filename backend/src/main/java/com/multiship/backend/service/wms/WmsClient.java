package com.multiship.backend.service.wms;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.dto.wms.WmsPendingOrderDTO;
import com.multiship.backend.dto.wms.WmsPendingResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * HTTP client for the external WMS's shippable/pending-orders feed.
 *
 * <p>Real contract:
 * {@code GET {base}/api/v1/shipping-label/pending-orders} → an envelope
 * {@code { code, status, message, data:[…] }}. The WMS is open (no API key);
 * {@code WMS_API_KEY} is still honoured as an optional bearer/x-api-key if the
 * deployment fronts it with auth.
 *
 * <p>Config: {@code WMS_BASE_URL} (default {@code http://localhost:8087} for
 * local dev). Set it to the real host in other environments.
 */
@Component
public class WmsClient {

    private static final Logger log = LoggerFactory.getLogger(WmsClient.class);
    private static final String PENDING_PATH = "/api/v1/shipping-label/pending-orders";

    private final String baseUrl;
    private final String apiKey;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public WmsClient(
            @Value("${WMS_BASE_URL:${wms.base-url:http://localhost:8087}}") String baseUrl,
            @Value("${WMS_API_KEY:${wms.api-key:}}") String apiKey) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    /** True when a base URL is present, so a real pull can run. */
    public boolean isConfigured() {
        return StringUtils.hasText(baseUrl);
    }

    public String baseUrl() {
        return baseUrl;
    }

    /**
     * GET the WMS's current pending (shippable) shipments. Returns an empty
     * list when not configured. Throws {@link WmsException} on a transport /
     * HTTP / parse error so the caller can surface a clear message.
     */
    public List<WmsPendingOrderDTO> fetchShippable() {
        if (!isConfigured()) return List.of();
        String url = baseUrl.replaceAll("/+$", "") + PENDING_PATH;
        try {
            HttpRequest.Builder req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("Accept", "*/*")
                    .GET();
            if (StringUtils.hasText(apiKey)) {
                req.header("X-Api-Key", apiKey);
            }
            HttpResponse<String> resp = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new WmsException("WMS returned HTTP " + resp.statusCode()
                        + " for " + url + ": " + truncate(resp.body()));
            }
            WmsPendingResponse parsed = mapper.readValue(resp.body(), WmsPendingResponse.class);
            List<WmsPendingOrderDTO> data = parsed == null ? null : parsed.getData();
            return data == null ? List.of() : data;
        } catch (WmsException e) {
            throw e;
        } catch (Exception e) {
            log.warn("WMS pending-orders fetch failed ({}): {}", url, e.getMessage());
            throw new WmsException("Could not reach the WMS at " + url + ": " + e.getMessage(), e);
        }
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }

    /** Transport / HTTP / parse error talking to the WMS. */
    public static class WmsException extends RuntimeException {
        public WmsException(String message) { super(message); }
        public WmsException(String message, Throwable cause) { super(message, cause); }
    }
}
