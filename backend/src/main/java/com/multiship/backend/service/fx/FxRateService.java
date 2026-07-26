package com.multiship.backend.service.fx;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * FX conversion for customs values, duties reporting, and IOSS threshold
 * checks. Implementations pull daily rates from a public source (ECB by
 * default) and cache them per-JVM to keep the hot path off the network.
 *
 * <p>All conversions are ISO-4217 code-to-code and return
 * {@link Optional#empty()} rather than throwing when a rate is unavailable
 * (unsupported currency, rate service outage) — callers decide whether to
 * fall back to a fixed threshold, block the flow, or ignore.
 *
 * <p>Not injected into carrier connectors yet — the IOSS threshold in
 * {@code FedExConnector} still uses the local fixed table added in Sprint 3.
 * Wiring the service in is a follow-up so a rate-service outage never blocks
 * a shipment.
 */
public interface FxRateService {

    /**
     * Return the rate for {@code 1 unit of from} → {@code to}, or empty when
     * the pair isn't supported / the rate feed is unavailable.
     */
    Optional<BigDecimal> rate(String fromCurrency, String toCurrency);

    /**
     * Convert an amount from {@code from} to {@code to}. Returns
     * {@link Optional#empty()} when {@link #rate(String, String)} returns
     * empty. Rounded to 2 decimal places HALF_UP.
     */
    Optional<BigDecimal> convert(BigDecimal amount, String fromCurrency, String toCurrency);

    /**
     * True when the rate feed considers the two codes supported. Used by the
     * IOSS pre-check to decide whether to attempt a live conversion vs. fall
     * back to the fixed threshold table.
     */
    boolean supports(String currency);
}
