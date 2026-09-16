package com.multiship.backend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * PR-F4 - per-hour retry / failure histogram for the USPS_DIRECT
 * label queue dashboard. Powered by
 * {@code UspsLabelQueueRepository.findRetryCountsByHour} which does
 * a single {@code GROUP BY date_trunc('hour', enqueued_at)} scan
 * over the last N hours.
 *
 * <p>Rendered as a stacked bar chart on the admin dashboard - the
 * height of each bar is {@code attempts} + {@code retries}, tinted
 * by {@code failures}. Operators use it to spot "did we saturate
 * the 55/hr quota" (attempts near cap for consecutive hours) or
 * "is USPS bouncing us" (spikes in retries + failures).
 *
 * <p>Empty {@link #buckets} on a fresh install with no queue activity
 * in the window - the FE renders an empty chart rather than 404.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UspsRetryBucketDTO {

    /** Rolling-window size, in hours, the buckets cover. Default 24. */
    private int hoursLookback;

    /**
     * One entry per hour in the window that had at least one enqueued
     * row; hours with zero activity are omitted (the FE zero-pads the
     * chart X axis). Ordered ASC by {@code hourStart} so the FE can
     * render left-to-right in wall-clock order without re-sorting.
     */
    private List<Bucket> buckets;

    /**
     * One hour's aggregate over the queue's {@code enqueued_at} column.
     *
     * @param hourStart  Truncated-to-hour timestamp (UTC instant) of
     *                   the bucket start. {@code 15:00Z} covers rows
     *                   enqueued from {@code 15:00Z} up to (but not
     *                   including) {@code 16:00Z}.
     * @param attempts   Count of rows enqueued in this hour whose
     *                   {@code retry_count = 0} - i.e. first-attempt
     *                   fresh enqueues. Roughly "how many label
     *                   requests hit us this hour".
     * @param retries    Sum of {@code retry_count} across all rows
     *                   enqueued in this hour - includes rows that
     *                   retried within-hour AND rows still retrying
     *                   in later hours. Signals USPS instability when
     *                   this consistently exceeds attempts.
     * @param failures   Count of rows in this hour currently in
     *                   {@code FAILED} status. A snapshot; a row can
     *                   flip from FAILED to DONE on retry-success in
     *                   a later hour and would then leave this bucket.
     */
    @lombok.Builder
    public record Bucket(Instant hourStart, long attempts, long retries, long failures) {}
}
