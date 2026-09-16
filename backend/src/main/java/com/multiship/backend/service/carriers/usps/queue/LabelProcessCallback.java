package com.multiship.backend.service.carriers.usps.queue;

/**
 * PR-F1 Agent-2 stub — the real functional interface lives on Agent 1's
 * branch. Delete this file at merge time.
 *
 * <p>Registered on {@code UspsLabelQueueProcessor} at bean-init time
 * (see {@code UspsLabelQueueWiring}). The processor invokes the
 * callback inside its own worker thread after the rate limiter has
 * gated the call — the callback body may make the USPS API call
 * directly without any additional throttling.
 *
 * <p>Return the tracking number on success; throw on any error and the
 * processor marks the queue row FAILED + increments retry_count.
 */
@FunctionalInterface
public interface LabelProcessCallback {

    String process(UspsLabelQueueItem item) throws Exception;
}
