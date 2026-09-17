package com.multiship.backend.service.carriers.usps.queue;

import com.multiship.backend.dto.UspsFallbackAlertDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PR-S4 (S-B2) — pins the String-typed {@code record(...)} overload
 * introduced so non-USPS carriers (Stamps SERA fallback) can post into
 * the same ring buffer without inventing a new SourceType enum entry.
 */
class UspsFallbackAlertServiceStampsTest {

    private UspsFallbackAlertService svc;

    @BeforeEach
    void setUp() {
        svc = new UspsFallbackAlertService(50);
        svc.clearForTest();
    }

    @Test
    void stringOverload_recordsWithGivenSourceLabel() {
        svc.record(100L, "ACME", null,
                "STAMPS_SERA_REFRESH_FAILED",
                "Stamps.com SERA refresh_token grant failed: bad_credentials");

        List<UspsFallbackAlertDTO> recent = svc.recentAlerts();
        assertEquals(1, recent.size());
        UspsFallbackAlertDTO a = recent.get(0);
        assertEquals("STAMPS_SERA_REFRESH_FAILED", a.getSource());
        assertEquals(100L, a.getOrderNo());
        assertEquals("ACME", a.getTenantCode());
        assertNotNull(a.getOccurredAt());
        assertTrue(a.getReason().contains("bad_credentials"));
    }

    @Test
    void stringOverload_nullSourceLabelFallsBackToUnknown() {
        svc.record(null, null, null, (String) null, "something happened");

        List<UspsFallbackAlertDTO> recent = svc.recentAlerts();
        assertEquals(1, recent.size());
        assertEquals("UNKNOWN", recent.get(0).getSource());
    }

    @Test
    void stringOverload_blankReasonNoOps() {
        svc.record(1L, "ACME", null, "STAMPS_TEST", "");
        svc.record(1L, "ACME", null, "STAMPS_TEST", null);

        assertEquals(0, svc.recentAlerts().size(),
                "Blank / null reason must silently no-op — never let alert-recording bugs leak into the carrier path");
    }

    @Test
    void stringOverload_ringBufferEvictsOldest() {
        UspsFallbackAlertService bounded = new UspsFallbackAlertService(3);
        bounded.record(1L, "ACME", null, "STAMPS_TEST", "alert 1");
        bounded.record(2L, "ACME", null, "STAMPS_TEST", "alert 2");
        bounded.record(3L, "ACME", null, "STAMPS_TEST", "alert 3");
        bounded.record(4L, "ACME", null, "STAMPS_TEST", "alert 4");

        List<UspsFallbackAlertDTO> recent = bounded.recentAlerts();
        assertEquals(3, recent.size());
        assertEquals("alert 4", recent.get(0).getReason(), "Most recent first");
        assertEquals("alert 3", recent.get(1).getReason());
        assertEquals("alert 2", recent.get(2).getReason(), "Oldest kept; 'alert 1' evicted");
    }

    @Test
    void stringOverload_coexistsWithEnumOverload() {
        // Both entry points populate the same ring buffer — the dashboard
        // endpoint returns them in one list regardless of surface.
        svc.record(1L, "ACME", null,
                com.multiship.backend.model.UspsLabelQueueItem.SourceType.IMPORT_BACKGROUND,
                "USPS Direct routing SYNC fallback");
        svc.record(2L, "BETA", null,
                "STAMPS_SERA_REFRESH_FAILED",
                "Stamps SERA refresh failed");

        List<UspsFallbackAlertDTO> recent = svc.recentAlerts();
        assertEquals(2, recent.size());
        // Most recent first.
        assertEquals("STAMPS_SERA_REFRESH_FAILED", recent.get(0).getSource());
        assertEquals("IMPORT_BACKGROUND", recent.get(1).getSource());
    }
}
