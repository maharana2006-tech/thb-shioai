package com.multiship.scanagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.scanagent.model.DiscoveredRow;
import com.multiship.scanagent.model.PollEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Long-poll + discovered-post client for the multiship API. Auth is a
 * single header ({@code X-Printer-Scan-Key}); no JWT — the backend
 * verifies the raw key against a SHA-256 hex hash. See
 * {@code PrinterScanController} on the backend side.
 */
public class ApiClient {

    private static final Logger log = LoggerFactory.getLogger(ApiClient.class);
    private static final String KEY_HEADER = "X-Printer-Scan-Key";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String baseUrl;
    private final String agentKey;

    public ApiClient(String baseUrl, String agentKey) {
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.agentKey = agentKey;
    }

    /** @return {@code true} when the admin has requested a scan since the previous poll. */
    public boolean pollScanRequest() throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/printer-scan-agents/poll"))
                .header(KEY_HEADER, agentKey)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IOException("poll failed: status=" + resp.statusCode() + " body=" + truncate(resp.body()));
        }
        PollEnvelope env = mapper.readValue(resp.body(), PollEnvelope.class);
        boolean requested = env.data() != null && env.data().scanRequested();
        log.debug("poll ok scanRequested={}", requested);
        return requested;
    }

    /** POST discovered printers back to the ingest endpoint. */
    public int postDiscovered(List<DiscoveredRow> rows) throws IOException, InterruptedException {
        String json = mapper.writeValueAsString(rows);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/printers/discovered"))
                .header(KEY_HEADER, agentKey)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IOException("post-discovered failed: status=" + resp.statusCode() + " body=" + truncate(resp.body()));
        }
        return rows.size();
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 500 ? s.substring(0, 500) + "…" : s;
    }
}
