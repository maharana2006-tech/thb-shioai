package com.multiship.scanagent.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Wire shape mirrors {@code PrinterScanService.DiscoveredRow} in the
 * backend. Keep field names + order aligned — Jackson serialises by
 * field name so a rename on either side breaks the ingest silently.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record DiscoveredRow(
        String host,
        Integer port,
        String name,
        String location,
        String connectionGuess,
        String formatGuess,
        String paperGuess,
        String queuePath,
        String rawTxt) {
}
