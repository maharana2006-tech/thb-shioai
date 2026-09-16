package com.multiship.backend.service.carriers.usps.queue;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * PR-F1 Agent-2 stub — the real processor lives on Agent 1's branch
 * (thread pool + rate limiter + fair scheduler wired in). Delete this
 * file at merge time; the real class exposes the same
 * {@link #registerCallback} method so Agent-2's wiring survives merge.
 *
 * <p>This stub stores the callback in an AtomicReference so
 * {@code UspsLabelQueueWiringTest} (also under Agent-2 ownership) can
 * verify registration without pulling in the whole real processor.
 */
@Component
public class UspsLabelQueueProcessor {

    private final AtomicReference<LabelProcessCallback> callbackRef = new AtomicReference<>();

    /** Register the callback that {@link #processNext} will invoke on the
     *  next queue item. Called from {@code UspsLabelQueueWiring.wire()}. */
    public void registerCallback(LabelProcessCallback callback) {
        callbackRef.set(callback);
    }

    /** Test-only accessor — real processor exposes this via package-private
     *  hook. Non-null once the wiring has fired. */
    public LabelProcessCallback getRegisteredCallback() {
        return callbackRef.get();
    }
}
