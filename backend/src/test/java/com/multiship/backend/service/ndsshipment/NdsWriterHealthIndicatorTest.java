package com.multiship.backend.service.ndsshipment;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** D3 — DOWN when the NdsShipmentOracleWriter bean can't be resolved; UP otherwise.
 *  BUG-005 — DOWN when the writer is wired but Oracle DataSource is unreachable. */
class NdsWriterHealthIndicatorTest {

    @SuppressWarnings("unchecked")
    private static ObjectProvider<DataSource> noOracle() {
        ObjectProvider<DataSource> p = (ObjectProvider<DataSource>) mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(null);
        return p;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<DataSource> validOracle() throws SQLException {
        Connection c = mock(Connection.class);
        when(c.isValid(1)).thenReturn(true);
        DataSource ds = mock(DataSource.class);
        when(ds.getConnection()).thenReturn(c);
        ObjectProvider<DataSource> p = (ObjectProvider<DataSource>) mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(ds);
        return p;
    }

    @Test
    void upWhenWriterAvailableAndNoOracleBound() {
        @SuppressWarnings("unchecked")
        ObjectProvider<NdsShipmentOracleWriter> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mock(NdsShipmentOracleWriter.class));

        NdsWriterHealthIndicator indicator = new NdsWriterHealthIndicator(provider, noOracle());
        assertEquals(Status.UP, indicator.health().getStatus());
    }

    @Test
    void upWhenWriterAvailableAndOracleValid() throws SQLException {
        @SuppressWarnings("unchecked")
        ObjectProvider<NdsShipmentOracleWriter> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mock(NdsShipmentOracleWriter.class));

        NdsWriterHealthIndicator indicator = new NdsWriterHealthIndicator(provider, validOracle());
        assertEquals(Status.UP, indicator.health().getStatus());
    }

    @Test
    void downWithReasonWhenWriterMissing() {
        @SuppressWarnings("unchecked")
        ObjectProvider<NdsShipmentOracleWriter> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);

        NdsWriterHealthIndicator indicator = new NdsWriterHealthIndicator(provider, noOracle());
        Health h = indicator.health();
        assertEquals(Status.DOWN, h.getStatus());
        Object reason = h.getDetails().get("reason");
        assertNotNull(reason, "DOWN result must carry a reason for the alert");
        assertTrue(reason.toString().contains("NdsShipmentOracleWriter"),
                "reason must name the bean so ops knows which dependency to fix");
    }

    // BUG-005 — writer wired but Oracle DS probe throws → DOWN with the exception class in the reason.
    @Test
    @SuppressWarnings("unchecked")
    void downWhenOracleProbeThrows() throws SQLException {
        ObjectProvider<NdsShipmentOracleWriter> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mock(NdsShipmentOracleWriter.class));
        DataSource ds = mock(DataSource.class);
        when(ds.getConnection()).thenThrow(new SQLException("ORA-12154: TNS could not resolve"));
        ObjectProvider<DataSource> p = (ObjectProvider<DataSource>) mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(ds);

        NdsWriterHealthIndicator indicator = new NdsWriterHealthIndicator(provider, p);
        Health h = indicator.health();
        assertEquals(Status.DOWN, h.getStatus());
        assertTrue(h.getDetails().get("reason").toString().contains("probe failed"),
                "DOWN carries the probe-failure reason");
    }

    // BUG-005 — writer wired, Oracle DS reports not-valid → DOWN with "not-valid" reason.
    @Test
    @SuppressWarnings("unchecked")
    void downWhenOracleReportsNotValid() throws SQLException {
        ObjectProvider<NdsShipmentOracleWriter> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mock(NdsShipmentOracleWriter.class));
        Connection c = mock(Connection.class);
        when(c.isValid(1)).thenReturn(false);
        DataSource ds = mock(DataSource.class);
        when(ds.getConnection()).thenReturn(c);
        ObjectProvider<DataSource> p = (ObjectProvider<DataSource>) mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(ds);

        NdsWriterHealthIndicator indicator = new NdsWriterHealthIndicator(provider, p);
        Health h = indicator.health();
        assertEquals(Status.DOWN, h.getStatus());
        assertTrue(h.getDetails().get("reason").toString().contains("not-valid"),
                "DOWN carries the not-valid reason");
    }

    @Test
    void bootAlertDoesNotThrowWhenMissing() {
        @SuppressWarnings("unchecked")
        ObjectProvider<NdsShipmentOracleWriter> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        NdsWriterHealthIndicator indicator = new NdsWriterHealthIndicator(provider, noOracle());
        indicator.bootAlert();
    }
}
