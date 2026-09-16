package com.multiship.backend.service.carriers.usps.queue;

import com.multiship.backend.model.UspsLabelQueueItem;
import com.multiship.backend.model.UspsLabelQueueItem.Status;
import com.multiship.backend.repository.UspsLabelQueueRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.ArrayDeque;

/**
 * USPS_DIRECT PR-F - per-tenant fair-share pick strategy the queue
 * processor uses to drain the queue. See
 * {@code docs/usps-direct-integration.md} PR-F.
 *
 * <h3>Algorithm</h3>
 * <ol>
 *   <li>Load all {@code QUEUED} rows ordered by
 *       {@code (priority ASC, enqueued_at ASC)}.</li>
 *   <li>Split them into two buckets: <b>urgent</b> (priority
 *       {@code <= URGENT_THRESHOLD}) and <b>normal</b>.</li>
 *   <li>Fill up to {@link #urgentPerTickCap} slots from the urgent
 *       bucket in raw priority order - urgent tenants jump the line
 *       but the cap prevents monopolisation.</li>
 *   <li>Fill remaining slots by round-robin across tenants: pick 1
 *       from tenant A, 1 from B, 1 from C, then A again, etc. Small
 *       tenants naturally drain first; big tenants backfill as small
 *       ones empty.</li>
 * </ol>
 *
 * <p>The load-all-then-slice approach is fine for the queue sizes we
 * anticipate (thousands, not millions) and keeps the algorithm easy
 * to reason about + test. If depth ever hits 100K+, switch to a
 * per-tenant paginated query.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UspsLabelQueueFairScheduler {

    /** Rows with priority &lt;= this value are treated as urgent and
     *  bypass the round-robin (up to the per-tick cap). Matches the
     *  entity javadoc's "<=10 = urgent" convention. */
    public static final int URGENT_THRESHOLD = 10;

    private final UspsLabelQueueRepository repo;

    /**
     * Cap on how many urgent rows a single tick can fill. Prevents a
     * tenant from spamming {@code priority=1} to monopolise every tick.
     * Configurable so ops can tune during a rush.
     */
    @Value("${usps.direct.queue.urgent-per-tick-cap:1}")
    private int urgentPerTickCap;

    /**
     * Pick up to {@code batchSize} items balanced across tenants.
     * Returns a fresh mutable list in the exact order the processor
     * should dispatch them; empty when the queue has no {@code QUEUED}
     * rows.
     *
     * <p>Contract:
     * <ul>
     *   <li>Never returns more than {@code batchSize} items.</li>
     *   <li>Never returns rows in a status other than {@code QUEUED}.</li>
     *   <li>Never mutates the DB - the caller (processor) marks the
     *       returned rows {@code PROCESSING} atomically after.</li>
     * </ul>
     */
    public List<UspsLabelQueueItem> pickNextBatch(int batchSize) {
        if (batchSize <= 0) return List.of();

        List<UspsLabelQueueItem> all =
                repo.findByStatusOrderByPriorityAscEnqueuedAtAsc(Status.QUEUED);
        if (all.isEmpty()) return List.of();

        List<UspsLabelQueueItem> urgent = new ArrayList<>();
        List<UspsLabelQueueItem> normal = new ArrayList<>();
        for (UspsLabelQueueItem row : all) {
            if (row.getPriority() != null && row.getPriority() <= URGENT_THRESHOLD) {
                urgent.add(row);
            } else {
                normal.add(row);
            }
        }

        List<UspsLabelQueueItem> picked = new ArrayList<>(batchSize);

        // Step 1 - fill urgent slots up to the per-tick cap.
        int urgentTake = Math.min(urgentPerTickCap, Math.min(batchSize, urgent.size()));
        for (int i = 0; i < urgentTake; i++) picked.add(urgent.get(i));
        if (picked.size() >= batchSize) return picked;

        // Step 2 - round-robin the normal bucket across tenants.
        // LinkedHashMap preserves the insertion order (which is the
        // priority/enqueued order from the query) so a tenant's oldest
        // row is always the head of its dequeue.
        Map<String, Queue<UspsLabelQueueItem>> perTenant = new LinkedHashMap<>();
        for (UspsLabelQueueItem row : normal) {
            perTenant.computeIfAbsent(row.getTenantCode(),
                    k -> new ArrayDeque<>()).add(row);
        }

        // Round-robin: iterate the map, pull one from each non-empty
        // dequeue, repeat until batch is full or every dequeue is empty.
        while (picked.size() < batchSize) {
            boolean pickedAnyThisRound = false;
            for (Map.Entry<String, Queue<UspsLabelQueueItem>> e : perTenant.entrySet()) {
                Queue<UspsLabelQueueItem> q = e.getValue();
                if (q.isEmpty()) continue;
                picked.add(q.poll());
                pickedAnyThisRound = true;
                if (picked.size() >= batchSize) break;
            }
            if (!pickedAnyThisRound) break; // all queues drained
        }

        if (log.isDebugEnabled()) {
            log.debug("USPS queue fair-pick: batchSize={} urgent={} normalTenants={} picked={}",
                    batchSize, urgent.size(), perTenant.size(), picked.size());
        }
        return picked;
    }

    /** Test hook - override the urgent-per-tick cap without a
     *  Spring context. */
    void setUrgentPerTickCapForTest(int cap) {
        this.urgentPerTickCap = cap;
    }
}
