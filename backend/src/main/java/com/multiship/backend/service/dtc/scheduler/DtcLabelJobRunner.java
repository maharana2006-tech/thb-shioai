package com.multiship.backend.service.dtc.scheduler;

import com.multiship.backend.dto.DtcBatchKey;
import com.multiship.backend.repository.DtcOrderRepository;
import com.multiship.backend.service.dtc.DtcLabelGenerationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * DTC_LABELS (V134) — queues label generation for synced DTC batches that
 * still have never-attempted lines. Queues through
 * {@link DtcLabelGenerationService#enqueue}, the D2C Generate button's path,
 * so the existing worker buys the labels with the same guards: one active
 * job per batch, labelled lines skipped, interrupted lines never re-bought.
 *
 * <p>FAILED lines are not picked (only never-attempted ones), so a batch that
 * keeps failing is not retried every tick; retry stays on the D2C screen.
 *
 * <p>Params: {@code tenants} ([] = all), {@code lookbackHours},
 * {@code maxBatchesPerRun}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DtcLabelJobRunner implements DtcSchedulerJobRunner {

    public static final String KEY = "DTC_LABELS";
    static final String REQUESTED_BY = "system:dtc-scheduler";

    static final int DEFAULT_LOOKBACK_HOURS = 24;
    static final int DEFAULT_MAX_BATCHES = 20;

    private final DtcOrderRepository dtcOrderRepository;
    private final DtcLabelGenerationService labelGenerationService;

    @Override
    public String jobKey() {
        return KEY;
    }

    @Override
    public Outcome run(Map<String, Object> params) {
        List<String> tenants = tenants(params.get("tenants"));
        int lookbackHours = positive(params.get("lookbackHours"), DEFAULT_LOOKBACK_HOURS);
        int maxBatches = positive(params.get("maxBatchesPerRun"), DEFAULT_MAX_BATCHES);

        boolean allTenants = tenants.isEmpty();
        List<DtcBatchKey> batches = dtcOrderRepository.findBatchesAwaitingLabels(
                LocalDateTime.now().minusHours(lookbackHours),
                allTenants,
                allTenants ? List.of("") : tenants,
                PageRequest.of(0, maxBatches));
        if (batches.isEmpty()) {
            return Outcome.success("No batches waiting for labels (last " + lookbackHours + " h).");
        }

        int queued = 0, alreadyActive = 0, failed = 0;
        for (DtcBatchKey b : batches) {
            try {
                if (labelGenerationService.enqueue(b.tenantId(), b.batchId(), REQUESTED_BY).isPresent()) {
                    queued++;
                } else {
                    alreadyActive++;
                }
            } catch (Exception e) {
                failed++;
                log.warn("[DTC Labels] could not queue tenant={} batch={}: {}", b.tenantId(), b.batchId(), e.toString());
            }
        }
        String msg = String.format("Queued %d batch(es); %d already queued or running%s.",
                queued, alreadyActive, failed > 0 ? "; " + failed + " could not be queued (see log)" : "");
        if (batches.size() == maxBatches) {
            msg += " Hit the " + maxBatches + "-batch cap; the rest go next run.";
        }
        if (failed > 0 && queued == 0 && alreadyActive == 0) {
            return new Outcome(DtcSchedulerEngine.FAILED, msg);
        }
        return Outcome.success(msg);
    }

    private static List<String> tenants(Object v) {
        if (!(v instanceof Collection<?> c)) return List.of();
        return c.stream()
                .filter(x -> x != null && !x.toString().isBlank())
                .map(x -> x.toString().trim())
                .distinct()
                .toList();
    }

    private static int positive(Object v, int fallback) {
        return v instanceof Number n && n.intValue() > 0 ? n.intValue() : fallback;
    }
}
