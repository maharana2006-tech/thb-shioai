package com.multiship.backend.service.output;

import com.multiship.backend.model.ClientOutputDestination;

/**
 * Sprint 52 — pluggable transport for a generated document. One
 * implementation per {@link DestinationType}. Selected at dispatch time
 * by matching {@link #supports()} against the destination row's
 * {@code destinationType}.
 *
 * <p>Contract:
 * <ul>
 *   <li>{@link #dispatch(ClientOutputDestination, DocType, byte[], DispatchContext)}
 *       MUST throw on any failure (network timeout, auth failure, IPP
 *       reject, etc.) — the service wraps every call in a try/catch and
 *       aggregates failures so one bad destination doesn't kill others.</li>
 *   <li>Drivers MUST honour a bounded timeout (5-10s network, ~1s for
 *       local FS). Fail loud rather than hang the fan-out pool.</li>
 *   <li>Drivers MUST NOT leak the config's secret material into log
 *       lines. Log destination id + host + doc type, never a password.</li>
 * </ul>
 */
public interface OutputDriver {

    /** Which destination type this driver handles. */
    DestinationType supports();

    /**
     * Send the bytes.
     *
     * @param destination the routing config row (id, client_code, config JSON)
     * @param docType     what kind of document — some drivers pick a
     *                    filename extension or IPP flavor from this
     * @param payload     the bytes to send (never null, never empty —
     *                    caller guards)
     * @param ctx         per-dispatch context for logging + filename hints
     * @throws OutputDeliveryException on any failure
     */
    void dispatch(ClientOutputDestination destination,
                  DocType docType,
                  byte[] payload,
                  DispatchContext ctx);
}
