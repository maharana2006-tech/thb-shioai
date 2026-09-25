package com.multiship.backend.events;

/**
 * An {@link AppEvent} handed to this JVM's own listeners when Redis is not
 * configured: the SSE stream relays it, so live updates work on a single
 * server (a dev machine) without Redis. With Redis the Stream carries events
 * between servers and this is not used.
 *
 * @param seq this JVM's sequence number — the SSE event id
 */
public record LocalAppEvent(String topic, String payload, long seq) { }
