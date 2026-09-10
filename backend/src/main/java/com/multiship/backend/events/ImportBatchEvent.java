package com.multiship.backend.events;

/**
 * State change on an {@code import_batch} row. Published by
 * {@link com.multiship.backend.service.OrderImportServiceImpl} on:
 * <ul>
 *   <li>Batch persisted (upload → INITIATE) — {@code eventType="batch-created"}</li>
 *   <li>Generate CAS gate flipped INITIATE→IN_PROGRESS — {@code eventType="batch-updated"}</li>
 *   <li>Terminal state reached (COMPLETE / PARTIAL_COMPLETE / FAILED / CANCELLED) — {@code eventType="batch-updated"}</li>
 *   <li>Cancellation requested — {@code eventType="batch-cancel-requested"}</li>
 *   <li>Startup housekeeper reaped a stale row — {@code eventType="batch-reaped"}</li>
 * </ul>
 *
 * <p>Counters ({@code successful} / {@code failed} / {@code invalid})
 * are approximations at publish time — the FE's DataHistoryPage should
 * treat these as advisory (patch the row optimistically) and rely on
 * the next scheduled refresh for authoritative counts.
 */
public record ImportBatchEvent(
        String eventType,
        String tenant,
        Long batchId,
        String status,
        int totalRows,
        int savedRows,
        int invalidRows
) implements AppEvent {

    /** All import-batch events live on this Redis channel. */
    public static final String TOPIC = "import-batches";

    @Override
    public String topic() {
        return TOPIC;
    }
}
