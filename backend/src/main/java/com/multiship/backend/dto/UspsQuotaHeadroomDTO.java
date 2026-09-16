package com.multiship.backend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * PR-F4 - USPS Direct hourly quota headroom snapshot for the admin
 * dashboard. Non-mutating peek at the platform-wide
 * {@code TokenBucket} that gates {@code UspsLabelQueueProcessor} label
 * writes (55/hr default, ceilinged under USPS' documented 60/hr limit).
 *
 * <p>Rendered as "42 / 55 label calls left this hour" plus a
 * utilisation-percent gauge on the dashboard. Cheap enough that the FE
 * polls the dedicated {@code /dashboard/quota-headroom} endpoint every
 * 15-30s for a live indicator.
 *
 * <p>Field semantics:
 * <ul>
 *   <li>{@link #hourlyCap} - the configured platform ceiling
 *       ({@code usps.direct.queue.hourly-cap}, default 55).</li>
 *   <li>{@link #remainingTokens} - tokens left in the bucket right now,
 *       peeked via {@code TokenBucket.availableTokens()} (no side
 *       effect).</li>
 *   <li>{@link #utilizationPercent} - {@code (hourlyCap - remainingTokens) /
 *       hourlyCap * 100} rounded to one decimal. 0.0 on a fresh bucket,
 *       100.0 when the bucket is fully drained.</li>
 *   <li>{@link #lastReplenishAt} - server clock at the moment the
 *       snapshot was taken (renamed from "last replenish" for clarity -
 *       the bucket refills continuously, so we surface the query
 *       timestamp).</li>
 *   <li>{@link #nextReplenishInSeconds} - estimated seconds until the
 *       next full token accrues when the bucket is empty. Zero when
 *       at least one token is already available.</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UspsQuotaHeadroomDTO {

    /** Platform ceiling for label writes per rolling hour. */
    private long hourlyCap;

    /** Tokens available right now (peeked, not consumed). */
    private int remainingTokens;

    /**
     * Percent of the hourly cap currently consumed, one-decimal precision.
     * 0.0 = full bucket, 100.0 = fully drained.
     */
    private BigDecimal utilizationPercent;

    /**
     * Server clock at snapshot time - the TokenBucket refills
     * continuously so we report "as of now" rather than a discrete
     * replenish event.
     */
    private Instant lastReplenishAt;

    /**
     * Seconds until at least one token becomes available. Zero when a
     * token is already available. Rendered as "next slot in 47s" when
     * the bucket is drained.
     */
    private long nextReplenishInSeconds;
}
