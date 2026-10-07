package com.multiship.backend.service.observability;

import com.multiship.backend.model.OrderTracking;
import com.multiship.backend.repository.OrderTrackingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Perf P3 phase 5 — sweeper now resolves stuck rows (marks ERROR + nulls in_flight_since). */
class InFlightTrackingSweeperTest {

    private OrderTrackingRepository repo;
    private InFlightTrackingSweeper sweeper;

    @BeforeEach
    void setUp() {
        repo = mock(OrderTrackingRepository.class);
        when(repo.tryAcquireInFlightSweeperLock()).thenReturn(true);
        sweeper = new InFlightTrackingSweeper(repo);
        ReflectionTestUtils.setField(sweeper, "inFlightTimeout", Duration.ofMinutes(2));
    }

    @Test
    void sweepSkipsScanWhenAnotherNodeHoldsTheClusterLock() {
        when(repo.tryAcquireInFlightSweeperLock()).thenReturn(false);

        sweeper.sweep();

        verify(repo, never()).findStuckInFlight(any(), any(Pageable.class));
        verify(repo, never()).save(any());
    }

    @Test
    void sweepIsNoOpWhenNoStuckRows() {
        when(repo.findStuckInFlight(any(), any(Pageable.class))).thenReturn(List.of());
        sweeper.sweep();  // must not throw
        verify(repo, never()).save(any());
    }

    @Test
    void sweepResolvesStuckRow_marksErrorAndNullsInFlightSince() {
        OrderTracking stuck = new OrderTracking();
        stuck.setOrderNo(900003);
        stuck.setInFlightSince(Instant.now().minus(Duration.ofMinutes(5)));
        stuck.setStatus("PENDING");
        stuck.setIsLabelGenerated(true);  // whatever it was pre-stuck
        when(repo.findStuckInFlight(any(), any(Pageable.class))).thenReturn(List.of(stuck));

        sweeper.sweep();

        verify(repo).save(stuck);
        assertEquals("ERROR", stuck.getStatus());
        assertNull(stuck.getInFlightSince(), "resolved row must have in_flight_since nulled");
        assertEquals(false, stuck.getIsLabelGenerated());
        assertNotNull(stuck.getErrorMessage());
        assertEquals(true, stuck.getErrorMessage().contains("in-flight timeout"));
    }

    @Test
    void sweepSkipsRowRaceSettledBetweenSelectAndUpdate() {
        // Defensive: a parallel phase C commit nulled in_flight_since between
        // the sweeper's SELECT and its UPDATE. Don't overwrite the settled row.
        OrderTracking raceSettled = new OrderTracking();
        raceSettled.setOrderNo(900004);
        raceSettled.setInFlightSince(null);  // settled between SELECT and here
        raceSettled.setStatus("GENERATED");
        when(repo.findStuckInFlight(any(), any(Pageable.class))).thenReturn(List.of(raceSettled));

        sweeper.sweep();

        verify(repo, never()).save(any());
        assertEquals("GENERATED", raceSettled.getStatus());
    }
}
