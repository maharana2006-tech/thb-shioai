package com.multiship.backend.util;

import com.multiship.backend.model.OrderTracking;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JSON helpers for {@link OrderTracking#getLabelHistory()} — superseded labels, newest last. */
public final class LabelHistory {

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private LabelHistory() {}

    public static List<Map<String, Object>> parse(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return MAPPER.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    /** Append one entry; idempotent for the same (event, trackingNumber). */
    public static void append(OrderTracking tracking, String event, String trackingNumber, String replacedBy, LocalDateTime at) {
        if (tracking == null || trackingNumber == null || trackingNumber.isBlank()) return;
        List<Map<String, Object>> entries = new ArrayList<>(parse(tracking.getLabelHistory()));
        for (Map<String, Object> e : entries) {
            if (event.equals(e.get("event")) && trackingNumber.equals(e.get("trackingNumber"))) return;
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("event", event);
        entry.put("trackingNumber", trackingNumber);
        if (replacedBy != null && !replacedBy.isBlank()) entry.put("replacedBy", replacedBy);
        entry.put("at", (at == null ? LocalDateTime.now() : at).toString());
        entries.add(entry);
        try {
            tracking.setLabelHistory(MAPPER.writeValueAsString(entries));
        } catch (Exception ignore) {
            // history is best-effort; never block the void / regenerate
        }
    }
}
