package com.multiship.backend.integration;

import com.multiship.backend.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Sprint 49 Tier 5 Fix 1 — exemplar integration test.
 *
 * <p>Proves the scaffold works end-to-end:
 * <ul>
 *   <li>Postgres container up + reachable</li>
 *   <li>Spring Boot context loads with test JWT + encryption key</li>
 *   <li>Real repositories injectable and callable against real Postgres</li>
 * </ul>
 *
 * <p>New integration tests copy this file's shape: extend
 * {@link AbstractIntegrationTest} and {@code @Autowired} whatever
 * you need. Only runs when {@code INTEGRATION_TESTS=1} is set.
 */
class SmokeIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private OrderRepository orderRepository;

    @Test
    void contextAndRepositoryReachable() {
        // Real DB round-trip via Hibernate — count on an empty table
        // returns 0 without throwing. Proves schema creation ran and
        // the connection pool works.
        assertNotNull(orderRepository);
        long count = orderRepository.count();
        // count is >= 0 by definition; assertion is just to prevent
        // the compiler warning about an ignored return.
        org.junit.jupiter.api.Assertions.assertTrue(count >= 0);
    }
}
