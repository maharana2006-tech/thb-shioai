package com.multiship.backend.service.wms;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.dto.wms.WmsShippableOrderDTO;
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
import java.util.Arrays;
import java.util.List;

/**
 * Thin, config-gated HTTP client for an external WMS.
 *
 * <p>Set {@code WMS_BASE_URL} and {@code WMS_API_KEY} (env / properties) to
 * enable a real pull. Without them the client reports "not configured" and
 * returns nothing rather than failing — mirroring the OpenAI/AI-assist gating
 * so the app boots and runs fine before the WMS credentials exist.
 *
 * <p>The endpoint + auth header here are placeholders based on a common WMS
 * shape (GET {base}/shippable-orders, X-Api-Key). Adjust once the real WMS
 * contract is known — only this class changes.
 */
@Component
public class WmsClient {

    private static final Logger log = LoggerFactory.getLogger(WmsClient.class);

    private final String baseUrl;
    private final String apiKey;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public WmsClient(
            @Value("${WMS_BASE_URL:${wms.base-url:}}") String baseUrl,
            @Value("${WMS_API_KEY:${wms.api-key:}}") String apiKey) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    /** True when a base URL + API key are present, so a real pull can run. */
    public boolean isConfigured() {
        return StringUtils.hasText(baseUrl) && StringUtils.hasText(apiKey);
    }

    /**
     * GET the WMS's current shippable orders. Returns an empty list when not
     * configured. Throws {@link WmsException} on a transport / HTTP error so
     * the caller can surface a clear message.
     */
    public List<WmsShippableOrderDTO> fetchShippable() {
        if (!isConfigured()) return List.of();
        String url = baseUrl.replaceAll("/+$", "") + "/shippable-orders";
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("Accept", "application/json")
                    .header("X-Api-Key", apiKey)
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new WmsException("WMS returned HTTP " + resp.statusCode()
                        + " for " + url + ": " + truncate(resp.body()));
            }
            WmsShippableOrderDTO[] rows = mapper.readValue(resp.body(), WmsShippableOrderDTO[].class);
            return Arrays.asList(rows);
        } catch (WmsException e) {
            throw e;
        } catch (Exception e) {
            log.warn("WMS shippable-orders fetch failed ({}): {}", url, e.getMessage());
            throw new WmsException("Could not reach the WMS at " + url + ": " + e.getMessage(), e);
        }
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }

    /** Transport / HTTP error talking to the WMS. */
    public static class WmsException extends RuntimeException {
        public WmsException(String message) { super(message); }
        public WmsException(String message, Throwable cause) { super(message, cause); }
    }
}
