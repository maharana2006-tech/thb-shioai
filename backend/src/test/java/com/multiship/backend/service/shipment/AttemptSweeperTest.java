package com.multiship.backend.service.shipment;

import com.multiship.backend.model.OrderTracking;
import com.multiship.backend.model.ShipmentAttempt;
import com.multiship.backend.repository.OrderTrackingRepository;
import com.multiship.backend.repository.ShipmentAttemptRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A crash between the carrier answering and tx B becomes a settled state, never a stuck row. */
class AttemptSweeperTest {

    private static ShipmentAttempt stale(Integer orderNo) {
        return ShipmentAttempt.builder().attemptKey(UUID.randomUUID()).orderNo(orderNo)
                .state(ShipmentAttempt.PURCHASING).fingerprint("f").requestJson("{}")
                .createdAt(LocalDateTime.now().minusHours(1)).updatedAt(LocalDateTime.now().minusHours(1)).build();
    }

    private static OrderTracking tracking(String status, String track, String err) {
        OrderTracking t = new OrderTracking();
        t.setStatus(status); t.setTrackingNumber(track); t.setErrorMessage(err);
        return t;
    }

    @Test
    void settles_generated_failed_unknown_and_abandoned() {
        ShipmentAttemptRepository repo = mock(ShipmentAttemptRepository.class);
        OrderTrackingRepository tracking = mock(OrderTrackingRepository.class);
        ShipmentAttempt done = stale(1), failed = stale(2), unknown = stale(3), abandoned = stale(null);
        LocalDateTime now = LocalDateTime.now();
        when(repo.findByStateAndUpdatedAtBefore(eq(ShipmentAttempt.PURCHASING), any()))
                .thenReturn(List.of(done, failed, unknown, abandoned));
        when(tracking.findByOrderNo(1)).thenReturn(Optional.of(tracking("GENERATED", "1Z1", null)));
        when(tracking.findByOrderNo(2)).thenReturn(Optional.of(tracking("ERROR", null, "carrier said no")));
        when(tracking.findByOrderNo(3)).thenReturn(Optional.empty());

        int settled = new AttemptSweeper(repo, tracking).sweep(now);

        assertEquals(4, settled);
        verify(repo).finish(eq(done.getAttemptKey()), eq(ShipmentAttempt.DONE), eq(1), any(), any(), anyString(), eq(now));
        verify(repo).finish(eq(failed.getAttemptKey()), eq(ShipmentAttempt.FAILED), eq(2), any(), any(), anyString(), eq(now));
        verify(repo).finish(eq(unknown.getAttemptKey()), eq(ShipmentAttempt.UNKNOWN), eq(3), any(), any(), anyString(), eq(now));
        verify(repo).finish(eq(abandoned.getAttemptKey()), eq(ShipmentAttempt.ABANDONED), any(), any(), any(), anyString(), eq(now));
    }

    @Test
    void a_fresh_purchasing_attempt_is_left_alone() {
        ShipmentAttemptRepository repo = mock(ShipmentAttemptRepository.class);
        when(repo.findByStateAndUpdatedAtBefore(anyString(), any())).thenReturn(List.of());
        assertEquals(0, new AttemptSweeper(repo, mock(OrderTrackingRepository.class)).sweep(LocalDateTime.now()));
    }
}
