package com.multiship.backend.events;

import java.time.LocalDateTime;

/**
 * PR-D follow-up — USPS_DIRECT reconciliation flipped an optimistic
 * void from {@code VOIDED} to {@code VOID_FAILED} because USPS
 * refused the refund on their eVS Refund report. Published by
 * {@link com.multiship.backend.service.carriers.usps.UspsDirectVoidReconciliationService}
 * on the DENIED branch so an operator sees a real-time toast the
 * instant the platform admin uploads the report — no need to spot
 * the WARN log or wait until the daily rollup surfaces the flip.
 *
 * <p>Common cause: the label was scanned in transit before the void
 * request arrived at USPS, so the postage is now committed (see the
 * documented gotcha in {@code docs/usps-direct-integration.md}). The
 * operator's next action is manual — contact the recipient / carrier
 * to arrange a return or write off the shipment — so surfacing the
 * flip in real time is worth the SSE channel.
 *
 * <p>Envelope contract mirrors {@link BulkLabelJobEvent} /
 * {@link ImportBatchEvent}: {@link #topic()} pins the event to the
 * {@code "void-failed"} SSE channel; {@link #tenant()} is the
 * per-order tenant scope so a scoped USER only sees their own
 * failures ({@link com.multiship.backend.controller.SseController}
 * greps the serialized {@code "tenant":"..."} field for filtering,
 * so the record component name must stay {@code tenant} - do not
 * rename to {@code tenantCode} or the tenant filter silently
 * degrades to "everyone sees everything"); {@link #eventType()} is
 * the SSE {@code event:} line the FE handler switches on.
 *
 * <p>Payload fields:
 * <ul>
 *   <li>{@code orderNo} — the order the failed void belongs to; the
 *       toast body renders it verbatim so ops can click straight
 *       through to the order in another tab.</li>
 *   <li>{@code trackingNumber} — the carrier tracking number that
 *       USPS refused to refund; needed for the ops follow-up call
 *       to the carrier support line.</li>
 *   <li>{@code tenant} — used for SSE tenant filtering AND surfaced
 *       in the payload for FE dashboards that may want to group
 *       toasts by client on multi-tenant admin sessions. Named to
 *       match the SseController wire contract; the FE hook aliases
 *       it back to {@code tenantCode} for consumer readability.</li>
 *   <li>{@code uspsReason} — free-text reason from the eVS Refund
 *       report (may be null / blank when USPS didn't populate it).
 *       Truncated by the FE if long; passed through verbatim on
 *       the wire.</li>
 *   <li>{@code reconciledAt} — UTC timestamp of the reconciliation
 *       run; the FE renders it relative to now.</li>
 * </ul>
 */
public record VoidFailedEvent(
        String eventType,
        String tenant,
        Long orderNo,
        String trackingNumber,
        String uspsReason,
        LocalDateTime reconciledAt
) implements AppEvent {

    /** All void-failed events live on this Redis channel. Kept as a
     *  public constant so {@link com.multiship.backend.controller.SseController}
     *  and FE {@code useVoidFailedToast} subscribe to the same string. */
    public static final String TOPIC = "void-failed";

    /** Convenience constant for the primary {@link #eventType()} —
     *  matches the topic string today, kept separate so a future
     *  {@code "void-failed-retry-scheduled"} kind can share the
     *  channel without churn on the topic constant. */
    public static final String EVENT_TYPE = "void-failed";

    @Override
    public String topic() {
        return TOPIC;
    }
}
