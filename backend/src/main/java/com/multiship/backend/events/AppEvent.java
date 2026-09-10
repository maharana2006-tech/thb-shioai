package com.multiship.backend.events;

/**
 * Common contract for all events published on the app event bus.
 *
 * <p>Every event carries three envelope fields the transport
 * (Redis pub/sub in Phase 1, Redis Streams in Phase 4) uses for
 * routing and durability, and the SSE controller uses for tenant
 * filtering:
 *
 * <ul>
 *   <li>{@link #topic()} — the coarse channel the event lives on
 *       (e.g. {@code "bulk-labels"}, {@code "import-batches"}).
 *       SSE clients subscribe to one or more topics; the publisher
 *       decides which topic an event belongs on.</li>
 *   <li>{@link #tenant()} — the tenant/client scope. Server-side
 *       filtering drops events for OTHER tenants before they hit
 *       the wire so a scoped USER never sees another tenant's
 *       activity. May be null for platform-level events.</li>
 *   <li>{@link #eventType()} — the specific event kind within the
 *       topic (e.g. {@code "job-updated"}, {@code "status-changed"}).
 *       Included as the SSE {@code event:} line so FE handlers can
 *       switch on it without parsing the JSON body.</li>
 * </ul>
 *
 * <p>Sealed to force every event through {@link AppEventBus}'s
 * type-checked publish overloads — a stray {@code publish(Object)}
 * with a random POJO would silently escape the tenant filter.
 */
public sealed interface AppEvent permits BulkLabelJobEvent, ImportBatchEvent {
    String topic();
    String tenant();
    String eventType();
}
