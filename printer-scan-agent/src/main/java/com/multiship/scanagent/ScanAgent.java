package com.multiship.scanagent;

import com.multiship.scanagent.model.DiscoveredRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;

/**
 * Entrypoint. Runs a long-poll loop: every {@code MULTISHIP_POLL_SECS}
 * seconds, calls {@link ApiClient#pollScanRequest()}; on a hit runs
 * {@link PrinterScanner#scan(Duration)} and posts the results back.
 *
 * <p>Errors in the loop are logged and swallowed so a transient API
 * hiccup or unreachable multicast interface does not kill the container
 * (Docker would restart it anyway, but resilience keeps the operator
 * noise low).
 */
public class ScanAgent {

    private static final Logger log = LoggerFactory.getLogger(ScanAgent.class);

    public static void main(String[] args) {
        String base = requireEnv("MULTISHIP_API_BASE");
        String key = requireEnv("MULTISHIP_AGENT_KEY");
        int pollSecs = intEnv("MULTISHIP_POLL_SECS", 5);
        int scanTimeoutSecs = intEnv("MULTISHIP_SCAN_TIMEOUT_SECS", 15);

        ApiClient api = new ApiClient(base, key);
        PrinterScanner scanner = new PrinterScanner();

        log.info("multiship-lan-scanner v0.1.0 starting: base={} poll={}s scanTimeout={}s",
                base, pollSecs, scanTimeoutSecs);

        runLoop(api, scanner, Duration.ofSeconds(pollSecs), Duration.ofSeconds(scanTimeoutSecs));
    }

    static void runLoop(ApiClient api, PrinterScanner scanner, Duration pollInterval, Duration scanTimeout) {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                if (api.pollScanRequest()) {
                    log.info("scan requested — starting mDNS discovery (timeout={}s)", scanTimeout.toSeconds());
                    List<DiscoveredRow> rows = scanner.scan(scanTimeout);
                    int posted = api.postDiscovered(rows);
                    log.info("posted {} discovered printers", posted);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.info("interrupted, exiting");
                return;
            } catch (Exception e) {
                log.warn("poll loop error (will retry in {}s): {}", pollInterval.toSeconds(), e.toString());
            }
            try {
                Thread.sleep(pollInterval.toMillis());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static String requireEnv(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("Missing required env var: " + name);
        }
        return v.trim();
    }

    private static int intEnv(String name, int fallback) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) return fallback;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            log.warn("env {}='{}' is not an integer; using default {}", name, v, fallback);
            return fallback;
        }
    }
}
