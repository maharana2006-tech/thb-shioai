package com.multiship.backend.service.shipment;

import com.multiship.backend.model.OrderTracking;
import com.multiship.backend.model.ShipmentAttempt;
import com.multiship.backend.repository.OrderTrackingRepository;
import com.multiship.backend.repository.ShipmentAttemptRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Phase 2 — a purchase that never recorded an outcome (process died between
 * the carrier answering and tx B) is a known state with a known repair, not
 * a mystery row. Every minute: attempts still PURCHASING past the grace
 * period are settled from the order's tracking row when it has one, marked
 * UNKNOWN for an operator when it has not, and ABANDONED when no order was
 * ever allocated.
 * ponytail: no per-carrier "find label by reference" lookup yet; UNKNOWN is
 * the honest state until one exists. Replaces the regenerate-only
 * InFlightTrackingSweeper for the manual path.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AttemptSweeper {

    public static final Duration GRACE = Duration.ofMinutes(10);

    private final ShipmentAttemptRepository attempts;
    private final OrderTrackingRepository tracking;

    @Scheduled(fixedDelayString = "${shipment.attempt-sweeper.delay-ms:60000}", initialDelayString = "60000")
    public void sweep() {
        sweep(LocalDateTime.now());
    }

    /** @return how many attempts were settled. */
    public int sweep(LocalDateTime now) {
        List<ShipmentAttempt> stale = attempts.findByStateAndUpdatedAtBefore(ShipmentAttempt.PURCHASING, now.minus(GRACE));
        int settled = 0;
        for (ShipmentAttempt a : stale) {
            String state;
            String note;
            if (a.getOrderNo() == null) {
                state = ShipmentAttempt.ABANDONED;
                note = "No order was allocated before the process stopped; nothing was bought.";
            } else {
                OrderTracking t = tracking.findByOrderNo(a.getOrderNo()).orElse(null);
                String status = t != null && t.getStatus() != null ? t.getStatus().toUpperCase() : null;
                if ("GENERATED".equals(status)) {
                    state = ShipmentAttempt.DONE;
                    note = "Settled by the sweeper: order " + a.getOrderNo() + " is GENERATED (" + t.getTrackingNumber() + ").";
                } else if ("ERROR".equals(status) || "FAILED".equals(status)) {
                    state = ShipmentAttempt.FAILED;
                    note = "Settled by the sweeper: order " + a.getOrderNo() + " failed: " + t.getErrorMessage();
                } else {
                    state = ShipmentAttempt.UNKNOWN;
                    note = "No outcome recorded for order " + a.getOrderNo()
                            + "; check the carrier for a label with reference " + a.getOrderNo() + " before retrying.";
                }
            }
            attempts.finish(a.getAttemptKey(), state, a.getOrderNo(), a.getResponseJson(), a.getErrorCode(), note, now);
            log.warn("Attempt {} swept {} → {}: {}", a.getAttemptKey(), ShipmentAttempt.PURCHASING, state, note);
            settled++;
        }
        return settled;
    }
}
