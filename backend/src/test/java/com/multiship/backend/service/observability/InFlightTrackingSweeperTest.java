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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Perf P3 phase 0 — sweeper is log-only; proves advisory-lock gating + scan. */
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
    }

    @Test
    void sweepIsNoOpWhenNoStuckRows() {
        when(repo.findStuckInFlight(any(), any(Pageable.class))).thenReturn(List.of());
        sweeper.sweep();  // must not throw
    }

    @Test
    void sweepScansWhenLockAcquiredAndSurfacesStuckRows() {
        OrderTracking stuck = new OrderTracking();
        stuck.setOrderNo(900003);
        stuck.setInFlightSince(Instant.now().minus(Duration.ofMinutes(5)));
        when(repo.findStuckInFlight(any(), any(Pageable.class))).thenReturn(List.of(stuck));

        sweeper.sweep();  // must not throw — P0 is log-only

        verify(repo).findStuckInFlight(any(), any(Pageable.class));
    }
}
