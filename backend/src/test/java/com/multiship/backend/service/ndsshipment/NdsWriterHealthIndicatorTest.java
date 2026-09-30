package com.multiship.backend.service.ndsshipment;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** D3 — DOWN when the NdsShipmentOracleWriter bean can't be resolved; UP otherwise. */
class NdsWriterHealthIndicatorTest {

    @Test
    void upWhenWriterAvailable() {
        @SuppressWarnings("unchecked")
        ObjectProvider<NdsShipmentOracleWriter> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mock(NdsShipmentOracleWriter.class));

        NdsWriterHealthIndicator indicator = new NdsWriterHealthIndicator(provider);
        Health h = indicator.health();
        assertEquals(Status.UP, h.getStatus());
    }

    @Test
    void downWithReasonWhenWriterMissing() {
        @SuppressWarnings("unchecked")
        ObjectProvider<NdsShipmentOracleWriter> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);

        NdsWriterHealthIndicator indicator = new NdsWriterHealthIndicator(provider);
        Health h = indicator.health();
        assertEquals(Status.DOWN, h.getStatus());
        Object reason = h.getDetails().get("reason");
        assertNotNull(reason, "DOWN result must carry a reason for the alert");
        assertTrue(reason.toString().contains("NdsShipmentOracleWriter"),
                "reason must name the bean so ops knows which dependency to fix");
    }

    @Test
    void bootAlertDoesNotThrowWhenMissing() {
        @SuppressWarnings("unchecked")
        ObjectProvider<NdsShipmentOracleWriter> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        // @PostConstruct wouldn't normally fire from `new`; we call it
        // directly to prove the log-and-continue behavior — it must not
        // block the app from booting.
        NdsWriterHealthIndicator indicator = new NdsWriterHealthIndicator(provider);
        indicator.bootAlert();
    }
}
