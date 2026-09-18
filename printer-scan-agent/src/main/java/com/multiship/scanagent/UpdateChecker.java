package com.multiship.scanagent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * PR-Printer-P4c — hourly poll of the backend's
 * {@code /api/v1/printer-scan-agents/latest-version} endpoint. On version
 * mismatch, logs + hands off to the caller-supplied exit hook (default:
 * {@code System.exit(0)}). The container's Docker restart mechanism —
 * {@code --restart=always} paired with a Watchtower sidecar, a cron
 * {@code docker pull + docker restart}, or systemd {@code ExecStartPre=docker pull} —
 * then pulls + relaunches the fresh image. See
 * {@code docs/printer-scan-agent-runbook.md} §6.
 *
 * <p>The exit path is a hook (not a hardcoded {@code System.exit}) so
 * unit tests can assert on mismatch without actually killing the JVM.
 * Errors on the poll are swallowed with a warn log — a transient
 * network hiccup must not kill the agent.
 */
public class UpdateChecker {

    private static final Logger log = LoggerFactory.getLogger(UpdateChecker.class);
    /** Poll cadence for the update check. 1h is plenty — a stale agent for
     *  an extra hour is harmless (the scan loop keeps working). */
    static final Duration DEFAULT_INTERVAL = Duration.ofHours(1);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String baseUrl;
    private final String currentVersion;
    private final Runnable exitHook;

    public UpdateChecker(String baseUrl, String currentVersion, Runnable exitHook) {
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.currentVersion = currentVersion;
        this.exitHook = exitHook;
    }

    /** Convenience constructor for prod: default exit hook = {@code System.exit(0)}. */
    public UpdateChecker(String baseUrl, String currentVersion) {
        this(baseUrl, currentVersion, () -> System.exit(0));
    }

    /**
     * Fire one poll. Called on the schedule set up by {@link ScanAgent}.
     * Package-private so tests can drive it deterministically.
     */
    void checkOnce() {
        String latest;
        try {
            latest = fetchLatest();
        } catch (Exception e) {
            log.warn("update-check failed (will retry next tick): {}", e.toString());
            return;
        }
        if (latest == null || latest.isBlank()) {
            log.warn("update-check returned no version — ignoring");
            return;
        }
        if (!latest.equals(currentVersion)) {
            log.info("agent version {} < latest {} — exiting so Docker restart pulls the fresh image",
                    currentVersion, latest);
            exitHook.run();
        } else {
            log.debug("agent version {} matches latest — no update", currentVersion);
        }
    }

    /** GET /api/v1/printer-scan-agents/latest-version. */
    private String fetchLatest() throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/printer-scan-agents/latest-version"))
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IOException("latest-version failed: status=" + resp.statusCode());
        }
        Envelope env = mapper.readValue(resp.body(), Envelope.class);
        return env.data() != null ? env.data().version() : null;
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    /** ApiResponse envelope shape, ignoring unknown fields. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Envelope(String status, int code, Data data) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Data(String version) {}
    }
}
