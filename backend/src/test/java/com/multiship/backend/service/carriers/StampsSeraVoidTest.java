package com.multiship.backend.service.carriers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.model.LabelPackage;
import com.multiship.backend.repository.LabelPackageRepository;
import com.multiship.backend.service.carriers.CarrierConnector.VoidResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Focused unit tests for the SERA {@code PUT /sera/v1/labels/{label_id}/void}
 * branch. Three shapes need pinning:
 * <ol>
 *   <li><b>label_id resolution</b> — SERA voids by {@code label_id}, not
 *       tracking. The connector must look up
 *       {@code label_package.carrier_label_ref} (V44 column) by tracking
 *       number and short-circuit with an actionable ERROR when it isn't
 *       persisted (e.g. label pre-dates SERA support).</li>
 *   <li><b>Success shape</b> — SERA's void response is minimal
 *       ({@code {"status": "success"}} or empty 200 body); both must
 *       resolve to VOIDED.</li>
 *   <li><b>Idempotency</b> — 404 on the endpoint means the label was
 *       already voided; must surface as ALREADY_VOIDED not ERROR.</li>
 * </ol>
 *
 * <p>The rollback path (used inside createShipmentSera when a later
 * piece fails) is exercised implicitly via
 * {@link StampsSeraCreateLabelTest} — no additional coverage here.
 */
class StampsSeraVoidTest {

    private static final String TRACKING = "9400111899223197428301";
    private static final String LABEL_ID = "abc-123-uuid";

    private StampsConnector connector;
    private LabelPackageRepository labelPackageRepository;

    @BeforeEach
    void setUp() throws Exception {
        CarrierProperties props = new CarrierProperties();
        CarrierProperties.Stamps s = props.getStamps();
        s.setApiFlavor("SERA");
        s.setSeraApiBaseUrl("https://api.stampsendicia.com/sera/v1");
        s.setSeraSandboxApiBaseUrl("https://api.testing.stampsendicia.com/sera/v1");
        // Force the SERA HTTP call to fail fast (localhost:1 refuses) so
        // tests that exercise the rejection paths don't hit real Auctane
        // servers. Success-path unit coverage lives in parseSeraVoidResponse
        // + resolveSeraLabelId which we drive directly.
        s.setSeraApiBaseUrl("http://localhost:1/sera");
        s.setSeraSandboxApiBaseUrl("http://localhost:1/sera-sandbox");
        connector = new StampsConnector(props, new ObjectMapper());
        labelPackageRepository = mock(LabelPackageRepository.class);
        // Inject the mocked repository into the connector's field (annotated
        // @Autowired(required=false), matching the packageCatalogRepository
        // pattern).
        Field f = StampsConnector.class.getDeclaredField("labelPackageRepository");
        f.setAccessible(true);
        f.set(connector, labelPackageRepository);
    }

    // ===== label_id resolution =====

    @Test
    void resolveSeraLabelId_returnsCarrierLabelRefFromDB() {
        LabelPackage pkg = LabelPackage.builder()
                .trackingNumber(TRACKING)
                .carrierLabelRef(LABEL_ID)
                .build();
        when(labelPackageRepository.findByTrackingNumber(TRACKING)).thenReturn(Optional.of(pkg));

        assertEquals(LABEL_ID, connector.resolveSeraLabelId(TRACKING));
    }

    @Test
    void resolveSeraLabelId_nullWhenNoRow() {
        when(labelPackageRepository.findByTrackingNumber(any())).thenReturn(Optional.empty());
        assertEquals(null, connector.resolveSeraLabelId(TRACKING));
    }

    @Test
    void resolveSeraLabelId_nullWhenCarrierLabelRefBlank() {
        // Row exists but carrier_label_ref is null (label pre-dates PR 1's
        // V44 rollout) — connector must treat as "no label_id available".
        LabelPackage pkg = LabelPackage.builder().trackingNumber(TRACKING).build();
        when(labelPackageRepository.findByTrackingNumber(TRACKING)).thenReturn(Optional.of(pkg));
        assertEquals(null, connector.resolveSeraLabelId(TRACKING));
    }

    // ===== void behaviour =====

    @Test
    void voidShipmentSera_localFallbackToken_returnsNotSupported() {
        VoidResult r = connector.voidShipmentSera(TRACKING, "stamps-local-abc", "PRODUCTION");
        assertFalse(r.voided());
        assertEquals("NOT_SUPPORTED", r.status());
        assertTrue(r.message().toLowerCase().contains("fallback"),
                "detail must mention the fallback token so the operator re-verifies");
    }

    @Test
    void voidShipmentSera_noLabelIdInDB_returnsActionableError() {
        // Label wasn't created under SERA (or was created before V44 rolled
        // out) → carrier_label_ref is null. Must fail loud, NOT silently
        // call SERA with a bogus id.
        when(labelPackageRepository.findByTrackingNumber(TRACKING)).thenReturn(Optional.empty());
        VoidResult r = connector.voidShipmentSera(TRACKING, "real-sera-token", "PRODUCTION");
        assertFalse(r.voided());
        assertEquals("ERROR", r.status());
        assertTrue(r.message().toLowerCase().contains("label_id"),
                "detail must mention 'label_id' so operator knows the missing input; got: " + r.message());
    }

    @Test
    void voidShipmentSera_dispatchesThroughVoidShipmentTopLevel() {
        // Sanity check that the top-level voidShipment() actually routes to
        // the SERA branch when the flavor property is SERA.
        when(labelPackageRepository.findByTrackingNumber(TRACKING)).thenReturn(Optional.empty());
        VoidResult r = connector.voidShipment(TRACKING, "real-sera-token", "PRODUCTION", null, null);
        assertFalse(r.voided());
        // SERA-branch's no-label_id error message is what proves the flavor
        // switch dispatched correctly (SWSIM branch never reads the DB).
        assertTrue(r.message().toLowerCase().contains("label_id"));
    }

    // ===== response parsing =====

    @Test
    void parseSeraVoidResponse_emptyBody_isSuccess() {
        // SERA's success response is minimal — often a 200 with no body.
        VoidResult r = connector.parseSeraVoidResponse(TRACKING, LABEL_ID, "");
        assertTrue(r.voided());
        assertEquals("VOIDED", r.status());
    }

    @Test
    void parseSeraVoidResponse_statusSuccessJson_isSuccess() {
        VoidResult r = connector.parseSeraVoidResponse(TRACKING, LABEL_ID,
                "{\"status\": \"success\"}");
        assertTrue(r.voided());
        assertEquals("VOIDED", r.status());
    }

    @Test
    void parseSeraVoidResponse_explicitFailureStatus_isError() {
        VoidResult r = connector.parseSeraVoidResponse(TRACKING, LABEL_ID,
                "{\"status\": \"failed\", \"error\": \"already voided\"}");
        assertFalse(r.voided());
        assertEquals("ERROR", r.status());
        assertTrue(r.message().toLowerCase().contains("failed") || r.message().toLowerCase().contains("already voided"),
                "message must surface SERA's own error text; got: " + r.message());
    }

    @Test
    void parseSeraVoidResponse_nonJsonBody_isSuccess() {
        // A 200 with a non-JSON body — treat as success (SERA never
        // returns 200 on failure, so a weird 200 is still a success).
        VoidResult r = connector.parseSeraVoidResponse(TRACKING, LABEL_ID, "OK");
        assertTrue(r.voided());
    }

    // ===== SWSIM path unaffected =====

    @Test
    void voidShipment_swsimFlavor_doesNotHitSeraBranch() throws Exception {
        // Flip flavor to SWSIM — the DB lookup must NOT fire because SWSIM
        // doesn't care about label_id.
        CarrierProperties props = new CarrierProperties();
        props.getStamps().setApiFlavor("SWSIM");
        props.getStamps().setSandboxUrl("http://localhost:1/swsim");
        props.getStamps().setApiBaseUrl("http://localhost:1/swsim");
        connector = new StampsConnector(props, new ObjectMapper());
        Field f = StampsConnector.class.getDeclaredField("labelPackageRepository");
        f.setAccessible(true);
        f.set(connector, labelPackageRepository);

        VoidResult r = connector.voidShipment(TRACKING, "real-token", "PRODUCTION", null, null);
        // SWSIM branch attempts to reach localhost:1 and fails; the SERA
        // branch would fail earlier (no label_id in DB). Distinguish by
        // status message.
        assertNotNull(r);
        assertFalse(r.voided());
        // SWSIM's error mentions "CancelIndicium"; SERA never does.
        assertTrue(r.message().contains("SWSIM") || r.message().contains("CancelIndicium"),
                "must route to SWSIM branch when flavor=SWSIM; got: " + r.message());
    }
}
