package com.multiship.backend.service.carriers.usps.queue;

import com.multiship.backend.model.UspsLabelQueueItem;

/**
 * USPS_DIRECT PR-F - Agent-2 contract for the actual USPS label
 * write. {@link UspsLabelQueueProcessor} marks a row {@code PROCESSING}
 * then invokes this callback; the callback returns the assigned USPS
 * tracking number on success or throws to signal a failure.
 *
 * <p>The processor is oblivious to the concrete label-generation
 * pipeline - Agent 2's wiring class registers a callback via
 * {@link UspsLabelQueueProcessor#registerCallback(LabelProcessCallback)}
 * (typically from a {@code @PostConstruct}) so the queue-core slice
 * can ship + be tested without any dependency on the wiring slice.
 */
@FunctionalInterface
public interface LabelProcessCallback {

    /**
     * Perform the USPS label write for {@code item}. Return the USPS-
     * assigned tracking number on success. Throw any exception to
     * signal failure - the processor records the message as
     * {@code lastError}, increments {@code retryCount}, and marks the
     * row {@code FAILED}.
     *
     * @param item the queue row being processed - carries
     *             {@code tenantCode}, {@code shipmentId}, {@code priority}.
     * @return the USPS tracking number assigned to this shipment.
     * @throws Exception when USPS refuses the label OR the wiring
     *                   layer throws for any other reason.
     */
    String process(UspsLabelQueueItem item) throws Exception;
}
