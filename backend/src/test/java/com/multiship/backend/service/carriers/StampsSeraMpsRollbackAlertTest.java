package com.multiship.backend.service.carriers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.service.carriers.CarrierConnector.PackageTracking;
import com.multiship.backend.service.carriers.CarrierConnector.ShipmentResult;
import com.multiship.backend.dto.UspsFallbackAlertDTO;
import com.multiship.backend.service.carriers.usps.queue.UspsFallbackAlertService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** PR-T9-X2 (audit T-MPS5) — pins the MPS partial-rollback alert
 *  contract on the SERA path. Protects:
 *  <ul>
 *    <li>A top-level {@code STAMPS_SERA_MPS_PARTIAL_ROLLBACK} alert fires
 *        on every rollback (ops always sees "MPS flipped partial").</li>
 *    <li>A per-piece {@code STAMPS_SERA_MPS_ROLLBACK_VOID_FAILED} alert
 *        fires when the rollback void itself fails (data-loss signal —
 *        a paid-for label nobody could void).</li>
 *    <li>Alerts fire even when the base URL can't be resolved — ops still
 *        learns rollback was skipped entirely.</li>
 *  </ul> */
class StampsSeraMpsRollbackAlertTest {

    private StampsConnector connector;
    private UspsFallbackAlertService alertSvc;

    @BeforeEach
    void setUp() throws Exception {
        CarrierProperties props = new CarrierProperties();
        props.getStamps().setApiFlavor("SERA");
        // Base URL blank on purpose for one of the tests — override per
        // test when a resolved URL is wanted.
        connector = new StampsConnector(props, new ObjectMapper());
        alertSvc = new UspsFallbackAlertService(50);
        injectAlertService(connector, alertSvc);
    }

    @Test
    void topLevelAlertFiresOnEverySuccessfulRollback() {
        // SERA URL blank → rollback aborts before touching any piece, but
        // the top-level alert + a "void failed" alert for the whole batch
        // still fire. Operator sees two signals.
        connector.rollbackSuccessfulPiecesSera(piecesWithLabelIds("id-1", "id-2"),
                "live-access-token", "SANDBOX");
        List<UspsFallbackAlertDTO> recorded = alertSvc.recentAlerts();
        assertTrue(recorded.stream().anyMatch(
                a -> a.getReason().contains("MPS partial failure")),
                "top-level MPS_PARTIAL_ROLLBACK alert must fire");
    }

    @Test
    void aborted_whenBaseUrlUnresolvable_recordsVoidFailedAlert() {
        // Default props → sera-sandbox-api-base-url is blank → base URL
        // throws. Rollback aborts; we must emit the per-batch failed
        // alert so ops knows paid labels weren't voided.
        connector.rollbackSuccessfulPiecesSera(piecesWithLabelIds("id-a", "id-b"),
                "live-access-token", "SANDBOX");
        List<UspsFallbackAlertDTO> recorded = alertSvc.recentAlerts();
        assertTrue(recorded.stream().anyMatch(
                a -> "STAMPS_SERA_MPS_ROLLBACK_VOID_FAILED".equals(a.getSource())
                        && a.getReason().contains("Rollback aborted")),
                "aborted rollback must emit VOID_FAILED alert so ops reconciles");
    }

    @Test
    void nullAndEmptyPiecesListShortCircuitSilently() {
        // Nothing to roll back → no alert noise.
        connector.rollbackSuccessfulPiecesSera(null, "tok", "SANDBOX");
        connector.rollbackSuccessfulPiecesSera(List.of(), "tok", "SANDBOX");
        assertEquals(0, alertSvc.recentAlerts().size(),
                "no pieces → no alerts (don't flood the dashboard)");
    }

    @Test
    void piecesWithoutLabelIdAreSkippedButBatchAlertStillFires() {
        // Pieces that never got a label_id (e.g. SWSIM pieces leaking
        // into the SERA path, or carrier-level failures) can't be
        // voided — we skip them but still emit the top-level alert.
        ShipmentResult emptyPiece = new ShipmentResult("TRK-1", null, null, null,
                null, null, null, List.of());
        connector.rollbackSuccessfulPiecesSera(List.of(emptyPiece), "tok", "SANDBOX");
        assertTrue(alertSvc.recentAlerts().stream().anyMatch(
                a -> "STAMPS_SERA_MPS_PARTIAL_ROLLBACK".equals(a.getSource())));
    }

    // ===== helpers =====

    private List<ShipmentResult> piecesWithLabelIds(String... labelIds) {
        return java.util.Arrays.stream(labelIds).map(id ->
                new ShipmentResult("TRK-" + id, null, null, null,
                        null, null, null,
                        List.of(new PackageTracking(1, "TRK-" + id, null, null, null, null, id))))
                .toList();
    }

    private void injectAlertService(StampsConnector c, UspsFallbackAlertService svc) throws Exception {
        Field f = StampsConnector.class.getDeclaredField("fallbackAlertService");
        f.setAccessible(true);
        f.set(c, svc);
    }
}
