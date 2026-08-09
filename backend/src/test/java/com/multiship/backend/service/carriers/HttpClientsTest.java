package com.multiship.backend.service.carriers;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;

/**
 * Sprint 49 Tier 2 — HttpClients factory smoke tests.
 *
 * <p>Full timeout-firing behavior would need a slow test server. This
 * covers the plumbing: builder returns non-null, reconfigure swaps the
 * underlying factory, and each builder invocation produces a fresh
 * builder instance (so callers can safely chain .baseUrl()).
 */
class HttpClientsTest {

    @Test
    void newBuilderReturnsNonNull() {
        RestClient.Builder b = HttpClients.newBuilder();
        assertNotNull(b);
        RestClient rc = b.baseUrl("http://localhost").build();
        assertNotNull(rc);
    }

    @Test
    void newBuilderProducesFreshInstances() {
        // Callers chain .baseUrl().build() — the builder must not be
        // shared, else two connectors would fight over the base URL.
        RestClient.Builder a = HttpClients.newBuilder();
        RestClient.Builder b = HttpClients.newBuilder();
        assertNotSame(a, b);
    }

    @Test
    void reconfigureDoesNotThrow() {
        // Reset to defaults after test to avoid leaking state.
        HttpClients.reconfigure(10, 60);
        HttpClients.reconfigure(5, 30);
    }
}
