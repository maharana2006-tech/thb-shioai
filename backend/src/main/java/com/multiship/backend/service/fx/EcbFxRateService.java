package com.multiship.backend.service.fx;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ECB daily FX rate feed. The European Central Bank publishes a compact
 * XML with EUR-to-N rates updated once per business day at
 * {@code https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml}.
 * We fetch on-demand (first call after startup), cache the result in
 * memory for 24 hours, and expose {@link #convert} + {@link #rate} on top
 * of the cached snapshot. Cross-currency rates (e.g. USD→GBP) are computed
 * as {@code (1/EURUSD) * EURGBP}.
 *
 * <p>Cache is per-JVM and lock-free (AtomicReference swap); a stale-refresh
 * race just repeats one HTTP call. On network failure we serve the last
 * good snapshot indefinitely rather than throwing — callers should treat
 * FX as best-effort.
 *
 * <p>Currency codes are ISO-4217, case-insensitive.
 */
@Slf4j
@Service
public class EcbFxRateService implements FxRateService {

    private static final String ECB_URL = "https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml";
    private static final Duration CACHE_TTL = Duration.ofHours(24);
    private static final Pattern RATE_PATTERN =
            Pattern.compile("<Cube\\s+currency=\"([A-Z]{3})\"\\s+rate=\"([0-9.]+)\"");

    /** Rates are stored EUR→N (matches the ECB feed). */
    private final AtomicReference<Snapshot> cache = new AtomicReference<>(null);

    @Override
    public Optional<BigDecimal> rate(String from, String to) {
        if (from == null || to == null) return Optional.empty();
        String f = from.trim().toUpperCase();
        String t = to.trim().toUpperCase();
        if (f.equals(t)) return Optional.of(BigDecimal.ONE);

        Snapshot snap = getSnapshot();
        if (snap == null) return Optional.empty();

        Map<String, BigDecimal> rates = snap.rates;
        // ECB's own base is EUR — for one leg we use the rate directly, for
        // the other leg we invert it. Cross rates compose both.
        BigDecimal fRate = "EUR".equals(f) ? BigDecimal.ONE : rates.get(f);
        BigDecimal tRate = "EUR".equals(t) ? BigDecimal.ONE : rates.get(t);
        if (fRate == null || tRate == null) return Optional.empty();

        // rate(from→to) = eurTo / eurFrom
        return Optional.of(tRate.divide(fRate, 8, RoundingMode.HALF_UP));
    }

    @Override
    public Optional<BigDecimal> convert(BigDecimal amount, String from, String to) {
        if (amount == null) return Optional.empty();
        return rate(from, to).map(r -> amount.multiply(r).setScale(2, RoundingMode.HALF_UP));
    }

    @Override
    public boolean supports(String currency) {
        if (currency == null) return false;
        String c = currency.trim().toUpperCase();
        if ("EUR".equals(c)) return true;
        Snapshot snap = getSnapshot();
        return snap != null && snap.rates.containsKey(c);
    }

    private Snapshot getSnapshot() {
        Snapshot current = cache.get();
        if (current != null && !current.isStale()) return current;

        Snapshot fresh = fetchSnapshot();
        if (fresh != null) {
            cache.set(fresh);
            return fresh;
        }
        // Network failure: serve the last good snapshot indefinitely rather
        // than dropping FX entirely — a shipping app that stops rating
        // because ECB is down is worse than one that uses yesterday's rates.
        return current;
    }

    private Snapshot fetchSnapshot() {
        try {
            String body = RestClient.builder().baseUrl(ECB_URL).build()
                    .get()
                    .retrieve()
                    .body(String.class);
            if (body == null || body.isBlank()) return null;

            Map<String, BigDecimal> rates = new HashMap<>();
            Matcher m = RATE_PATTERN.matcher(body);
            while (m.find()) {
                try {
                    rates.put(m.group(1), new BigDecimal(m.group(2)));
                } catch (NumberFormatException ignored) {
                    // one malformed rate shouldn't drop the whole snapshot
                }
            }
            if (rates.isEmpty()) {
                log.warn("ECB FX feed returned no rates; keeping previous snapshot.");
                return null;
            }
            log.info("Loaded {} FX rates from ECB feed.", rates.size());
            return new Snapshot(rates, Instant.now());
        } catch (Exception ex) {
            log.warn("ECB FX fetch failed; keeping previous snapshot. Reason: {}", ex.getMessage());
            return null;
        }
    }

    /** Immutable snapshot with a wall-clock TTL check. */
    private static final class Snapshot {
        final Map<String, BigDecimal> rates;
        final Instant loadedAt;

        Snapshot(Map<String, BigDecimal> rates, Instant loadedAt) {
            this.rates = Map.copyOf(rates);
            this.loadedAt = loadedAt;
        }

        boolean isStale() {
            return Instant.now().isAfter(loadedAt.plus(CACHE_TTL));
        }
    }
}
