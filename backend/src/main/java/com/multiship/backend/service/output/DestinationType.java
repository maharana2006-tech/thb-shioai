package com.multiship.backend.service.output;

/**
 * Sprint 52 — where to send the bytes for a generated shipment document.
 *
 * <ul>
 *   <li>{@link #LOCAL_FS} — write to a directory on the app server / a
 *       mounted volume. Fan-out to a nearby workflow / third-party watcher.</li>
 *   <li>{@link #SFTP} — upload to a remote SFTP drop; typical for
 *       enterprise customers that want labels rsynced into their WMS.</li>
 *   <li>{@link #PRINTER} — spool to a network printer. Zebra ZPL over
 *       RAW_9100, laser PDF over IPP.</li>
 * </ul>
 *
 * <p>Persisted as a string; the CHECK constraint on
 * {@code client_output_destination.destination_type} guards the DB side.
 */
public enum DestinationType {
    LOCAL_FS,
    SFTP,
    PRINTER
}
