package com.multiship.backend.service.carriers.usps.queue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.dto.OrderImportRowDTO;
import com.multiship.backend.model.ImportBatch;
import com.multiship.backend.model.UspsLabelQueueItem;
import com.multiship.backend.repository.ImportBatchRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * PR-G3b - bridge USPS Direct queue-terminal events (DONE / FAILED)
 * back to the originating import batch row. Closes audit finding U5:
 * "Retry-exhausted queue FAILED items don't bridge back to import row's
 * generatedStatus; operators see row stuck at QUEUED_USPS even after
 * queue gives up."
 *
 * <p>Flow:
 * <ol>
 *   <li>{@link UspsLabelQueueProcessor} finishes a queue row and
 *       publishes {@link UspsLabelQueueTerminalEvent}.</li>
 *   <li>This listener picks it up, filters out events without an
 *       {@code importBatchId} (manual / bulk paths that never bridge),
 *       loads the {@link ImportBatch} and its {@code rowsJson} payload.</li>
 *   <li>Finds every row whose {@code generatedOrderNo} matches the
 *       event's {@code orderNo}. On {@link UspsLabelQueueItem.Status#DONE}
 *       it flips {@code QUEUED_USPS} -&gt; {@code GENERATED} + stamps the
 *       tracking number; on {@link UspsLabelQueueItem.Status#FAILED}
 *       it flips to {@code FAILED} + stamps the queue's last-error
 *       string as the row's message.</li>
 *   <li>Serialises the updated rows back onto the batch and saves.</li>
 * </ol>
 *
 * <p><b>Batch-status recompute intentionally deferred.</b> The row-level
 * flip is enough to unstick the UI. Rolling the batch's own status
 * (COMPLETE / PARTIAL_COMPLETE / FAILED) after a queue drain would
 * duplicate logic that already lives in
 * {@code OrderImportServiceImpl.runGeneration}; instead the next
 * generation attempt reads the correct row states and derives the batch
 * status from that. Ops don't lose anything - the FE polls both the
 * per-row states and the batch status; the per-row updates surface
 * immediately and the batch header refreshes on the next generation
 * run or the next Retry click.
 *
 * <p><b>Idempotency.</b> Re-emission of the same event (duplicate on
 * the app-event bus in some contrived failure mode) is a no-op:
 * matching rows that are already GENERATED / FAILED stay put; only
 * rows still in {@code QUEUED_USPS} flip.
 *
 * <p><b>MPS aggregation.</b> An MPS parent order has one queue row per
 * piece; the terminal event fires once per piece. The reconciler
 * updates the SAME import row on every piece event - the row's tracking
 * number tracks the FIRST completed piece's tracking (MPS parents show
 * a representative label; the full piece breakdown lives on the MPS
 * progress endpoint the FE already consumes). A single FAILED piece
 * does NOT flip the row from GENERATED back to FAILED; a piece can
 * come back FAILED after siblings already DONE and we don't want to
 * regress the row's happy state. Ops see the piece failure via the
 * MPS progress card the FE already renders.
 */
@Slf4j
@Service
public class UspsQueueImportRowReconciler {

    private final ImportBatchRepository importBatchRepository;
    private final ObjectMapper objectMapper;
    /** Every import's rows (V87). Optional for hand-built tests. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.multiship.backend.service.ImportBatchRowStore rowStore;

    /**
     * Spring resolves {@link ObjectMapper} to the Boot-configured
     * primary bean (Jackson auto-config sees to it that one exists in
     * every runtime). Unit tests can pass their own mapper via the
     * ctor - keeping the field non-null is a hard requirement because
     * the reconciler has to serialise the updated rowsJson back.
     */
    public UspsQueueImportRowReconciler(
            ImportBatchRepository importBatchRepository,
            ObjectMapper objectMapper) {
        this.importBatchRepository = importBatchRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * PR-G3b - handle a terminal-status event. Runs synchronously on
     * the publisher's thread (the queue processor). No @Async needed:
     * the queue is capped at 55/hr platform-wide so this listener fires
     * at ~1/min, and the parse-update-save cycle is a single JPA save
     * on the ImportBatch aggregate.
     *
     * <p>Fail-open: any exception during reconciliation is logged at
     * WARN and swallowed so a broken listener never breaks the queue
     * drain (the ONLY thing worse than a stuck QUEUED_USPS row is a
     * stuck QUEUED_USPS row PLUS a wedged queue behind it).
     */
    @EventListener
    @Transactional
    public void onTerminal(UspsLabelQueueTerminalEvent event) {
        if (event == null) return;
        if (event.importBatchId() == null) {
            // BULK_OPERATOR / MANUAL / standalone MPS - no import batch
            // to reconcile. Common path; log at DEBUG so we don't spam.
            log.debug("USPS queue reconciler: skip terminal event queue={} - no importBatchId",
                    event.queueItemId());
            return;
        }
        if (event.terminalStatus() != UspsLabelQueueItem.Status.DONE
                && event.terminalStatus() != UspsLabelQueueItem.Status.FAILED) {
            log.debug("USPS queue reconciler: skip non-terminal status {} for queue={}",
                    event.terminalStatus(), event.queueItemId());
            return;
        }
        try {
            reconcile(event);
        } catch (RuntimeException ex) {
            // WARN not ERROR - the queue drain is fine, the import row
            // just won't unstick until the next generation attempt.
            log.warn("USPS queue reconciler failed for queue={} import={}: {}",
                    event.queueItemId(), event.importBatchId(), ex.getMessage());
        }
    }

    private void reconcile(UspsLabelQueueTerminalEvent event) {
        Optional<ImportBatch> maybeBatch = importBatchRepository.findById(event.importBatchId());
        if (maybeBatch.isEmpty()) {
            log.warn("USPS queue reconciler: import batch {} not found for queue={}",
                    event.importBatchId(), event.queueItemId());
            return;
        }
        ImportBatch batch = maybeBatch.get();
        boolean inRowsTable = batch.getRowsJson() == null || batch.getRowsJson().isBlank();
        if (inRowsTable && rowStore == null) {
            log.warn("USPS queue reconciler: import batch {} has no rowsJson - nothing to reconcile",
                    event.importBatchId());
            return;
        }
        if (objectMapper == null) {
            log.warn("USPS queue reconciler: objectMapper is null - cannot parse batch {}",
                    event.importBatchId());
            return;
        }

        List<OrderImportRowDTO> rows;
        try {
            rows = inRowsTable ? new ArrayList<>(rowStore.load(batch.getId()))
                    : objectMapper.readValue(batch.getRowsJson(), new TypeReference<List<OrderImportRowDTO>>() {});
        } catch (Exception parseFail) {
            log.warn("USPS queue reconciler: batch {} rows could not be read: {}",
                    event.importBatchId(), parseFail.getMessage());
            return;
        }
        if (rows == null || rows.isEmpty()) return;

        // Only mutate rows that (a) match this queue's order + (b) are
        // still parked in QUEUED_USPS. This makes the listener idempotent
        // + prevents regressing a row that already resolved via another
        // code path (e.g. an operator manually flipped it).
        List<OrderImportRowDTO> flipped = new ArrayList<>();
        for (OrderImportRowDTO row : rows) {
            if (row == null) continue;
            Integer rowOrderNo = row.getGeneratedOrderNo();
            if (rowOrderNo == null || event.orderNo() == null) continue;
            if (rowOrderNo.longValue() != event.orderNo().longValue()) continue;
            String current = row.getGeneratedStatus();
            if (!"QUEUED_USPS".equalsIgnoreCase(current)) continue;

            if (event.terminalStatus() == UspsLabelQueueItem.Status.DONE) {
                row.setGeneratedStatus("GENERATED");
                if (event.trackingNumber() != null && !event.trackingNumber().isBlank()) {
                    row.setGeneratedTrackingNumber(event.trackingNumber());
                }
                row.setGeneratedMessage("USPS Direct label generated via queue.");
            } else { // FAILED
                row.setGeneratedStatus("FAILED");
                row.setGeneratedMessage(reasonFromError(event.errorMessage()));
            }
            flipped.add(row);
        }
        if (flipped.isEmpty()) {
            log.debug("USPS queue reconciler: no QUEUED_USPS rows matched order={} in batch={}",
                    event.orderNo(), event.importBatchId());
            return;
        }

        try {
            if (rowStore != null) {
                rowStore.store(batch.getId(), rows);   // the flipped row(s) only
                batch.setRowsJson(null);
            } else {
                batch.setRowsJson(objectMapper.writeValueAsString(rows));
            }
            com.multiship.backend.service.OrderImportServiceImpl.stampLabelCounts(batch, rows, objectMapper);
            importBatchRepository.save(batch);
            log.info("USPS queue reconciler: import batch {} - flipped {} row(s) for order={} -> {}",
                    event.importBatchId(), flipped.size(), event.orderNo(), event.terminalStatus());
        } catch (Exception serFail) {
            log.warn("USPS queue reconciler: batch {} rowsJson serialisation failed: {}",
                    event.importBatchId(), serFail.getMessage());
        }
    }

    private static String reasonFromError(String rawError) {
        if (rawError == null || rawError.isBlank()) {
            return "USPS Direct label failed via queue.";
        }
        // Keep the queue error short - the FE column has limited width.
        String trimmed = rawError.trim();
        int cap = 220;
        if (trimmed.length() > cap) {
            trimmed = trimmed.substring(0, cap) + "…";
        }
        return "USPS Direct queue exhausted retries: " + trimmed;
    }
}
