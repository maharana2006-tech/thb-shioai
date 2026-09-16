package com.multiship.backend.service.carriers.usps;

import com.multiship.backend.dto.UspsReconciliationRollupDTO;
import com.multiship.backend.repository.OrderTrackingRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Deque;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PR-F4 - pure-Mockito tests for
 * {@link UspsDirectVoidReconciliationService#getRollup(Duration)}.
 *
 * <p>Query dispatch is stubbed on the mocked {@link EntityManager};
 * we route each JPQL by target-type overload
 * ({@code Long.class} / {@code BigDecimal.class} /
 * {@code LocalDateTime.class}) plus call-ordering. The service issues
 * exactly 6 queries in a fixed order:
 * <ol>
 *   <li>{@code COUNT(t)} - voided total (Long)</li>
 *   <li>{@code COUNT(t)} - reconciled approved (Long)</li>
 *   <li>{@code COUNT(t)} - reconciled denied (Long)</li>
 *   <li>{@code COUNT(t)} - not yet reconciled (Long)</li>
 *   <li>{@code MAX(voidReconciliationCheckedAt)} (LocalDateTime)</li>
 *   <li>{@code SUM(carrierAmount)} (BigDecimal)</li>
 * </ol>
 * We queue the 4 Long results in order and pin the other two by their
 * distinct return type.
 */
class UspsDirectVoidReconciliationServiceRollupTest {

    private OrderTrackingRepository repo;
    private EntityManager em;
    private UspsDirectVoidReconciliationService service;

    /** In-order Long results the 4 COUNT queries pop from. */
    private Deque<Long> longResults;
    private LocalDateTime lastCheckedResult;
    private BigDecimal sumResult;

    @BeforeEach
    void setUp() {
        repo = mock(OrderTrackingRepository.class);
        em = mock(EntityManager.class);
        service = new UspsDirectVoidReconciliationService(repo);
        service.setEntityManagerForTest(em);

        longResults = new ArrayDeque<>();
        lastCheckedResult = null;
        sumResult = BigDecimal.ZERO;

        wireEntityManager();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void wireEntityManager() {
        // Long overload - COUNT queries. Returns a fresh TypedQuery
        // per call whose getSingleResult() pops the head of longResults.
        when(em.createQuery(anyString(), eq(Long.class))).thenAnswer(inv -> {
            TypedQuery q = mock(TypedQuery.class);
            when(q.setParameter(anyString(), any())).thenReturn(q);
            when(q.getSingleResult()).thenAnswer(inv2 ->
                    longResults.isEmpty() ? 0L : longResults.poll());
            return q;
        });
        // BigDecimal overload - SUM query.
        when(em.createQuery(anyString(), eq(BigDecimal.class))).thenAnswer(inv -> {
            TypedQuery q = mock(TypedQuery.class);
            when(q.setParameter(anyString(), any())).thenReturn(q);
            when(q.getSingleResult()).thenAnswer(inv2 -> sumResult);
            return q;
        });
        // LocalDateTime overload - MAX(voidReconciliationCheckedAt).
        when(em.createQuery(anyString(), eq(LocalDateTime.class))).thenAnswer(inv -> {
            TypedQuery q = mock(TypedQuery.class);
            when(q.setParameter(anyString(), any())).thenReturn(q);
            when(q.getSingleResult()).thenAnswer(inv2 -> lastCheckedResult);
            return q;
        });
    }

    // ================================================================
    // Empty results -> zeros
    // ================================================================

    @Test
    void rollup_emptyDb_returnsZeros() {
        longResults.add(0L); // voidedTotal
        longResults.add(0L); // approvedCount
        longResults.add(0L); // deniedCount
        longResults.add(0L); // pendingCount
        lastCheckedResult = null;
        sumResult = BigDecimal.ZERO;

        UspsReconciliationRollupDTO out = service.getRollup(Duration.ofDays(30));

        assertNotNull(out);
        assertEquals(30, out.getLookbackDays());
        assertEquals(0L, out.getVoidedShipmentsInWindow());
        assertEquals(0L, out.getReconciledApproved());
        assertEquals(0L, out.getReconciledDenied());
        assertEquals(0L, out.getNotYetReconciled());
        assertNull(out.getLastReconciliationAt());
        assertEquals(BigDecimal.ZERO, out.getPendingRefundValue());
        assertEquals("USD", out.getCurrency());
    }

    // ================================================================
    // Mixed statuses -> correct rollup
    // ================================================================

    @Test
    void rollup_mixedStatuses_populatesEveryCounter() {
        LocalDateTime lastChecked = LocalDateTime.parse("2026-09-16T02:00:00");
        // Order matches the service's fixed dispatch:
        //   voidedTotal, approvedCount, deniedCount, pendingCount
        longResults.add(250L);
        longResults.add(240L);
        longResults.add(3L);
        longResults.add(7L);
        lastCheckedResult = lastChecked;
        sumResult = new BigDecimal("42.50");

        UspsReconciliationRollupDTO out = service.getRollup(Duration.ofDays(30));

        assertEquals(250L, out.getVoidedShipmentsInWindow());
        assertEquals(240L, out.getReconciledApproved());
        assertEquals(3L, out.getReconciledDenied());
        assertEquals(7L, out.getNotYetReconciled());
        assertEquals(lastChecked, out.getLastReconciliationAt());
        assertEquals(new BigDecimal("42.50"), out.getPendingRefundValue());
        assertEquals("USD", out.getCurrency());
    }

    @Test
    void rollup_nullEntityManager_returnsEmptyRollup() {
        UspsDirectVoidReconciliationService s = new UspsDirectVoidReconciliationService(repo);
        // Explicitly do NOT inject an EM.

        UspsReconciliationRollupDTO out = s.getRollup(Duration.ofDays(30));

        assertEquals(30, out.getLookbackDays());
        assertEquals(0L, out.getVoidedShipmentsInWindow());
        assertNull(out.getLastReconciliationAt());
        assertEquals(BigDecimal.ZERO, out.getPendingRefundValue());
    }

    @Test
    void rollup_nullLookback_defaultsTo30Days() {
        longResults.add(0L);
        longResults.add(0L);
        longResults.add(0L);
        longResults.add(0L);

        UspsReconciliationRollupDTO out = service.getRollup(null);

        assertEquals(30, out.getLookbackDays());
    }

    @Test
    void rollup_zeroLookback_defaultsTo30Days() {
        longResults.add(0L);
        longResults.add(0L);
        longResults.add(0L);
        longResults.add(0L);

        UspsReconciliationRollupDTO out = service.getRollup(Duration.ZERO);

        assertEquals(30, out.getLookbackDays());
    }

    @Test
    void rollup_nullReturnFromSumQuery_collapsesToZero() {
        longResults.add(1L);
        longResults.add(0L);
        longResults.add(0L);
        longResults.add(1L);
        sumResult = null; // simulate all-NULL SUM

        UspsReconciliationRollupDTO out = service.getRollup(Duration.ofDays(30));

        assertEquals(BigDecimal.ZERO, out.getPendingRefundValue());
    }

    @Test
    void rollup_shortLookback_reflectsInDto() {
        longResults.add(0L);
        longResults.add(0L);
        longResults.add(0L);
        longResults.add(0L);

        UspsReconciliationRollupDTO out = service.getRollup(Duration.ofDays(7));

        assertEquals(7, out.getLookbackDays());
    }
}
