package com.multiship.backend.service.carriers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.model.LabelPackage;
import com.multiship.backend.repository.LabelPackageRepository;
import com.multiship.backend.service.carriers.CarrierConnector.AddressToValidate;
import com.multiship.backend.service.carriers.CarrierConnector.CloseOutRequest;
import com.multiship.backend.service.carriers.CarrierConnector.CloseOutResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Focused unit tests for the SERA {@code POST /sera/v1/manifests}
 * branch. Behaviour under test:
 * <ol>
 *   <li><b>By-label_ids path</b> — when every tracking has a persisted
 *       {@code carrier_label_ref} we send an exact-target manifest
 *       with the {@code label_ids[]} array (preferred).</li>
 *   <li><b>Fallback to by-carrier+ship_date</b> — if any tracking is
 *       missing its label_id (legacy pre-V44 label), fall back to
 *       SERA's broad by-carrier+ship_date sweep so those labels still
 *       manifest instead of getting stranded.</li>
 *   <li><b>Response parsing</b> — {@code manifest_id} + {@code labels[0].href}
 *       land in {@link CloseOutResult}; missing manifest_id → ERROR.</li>
 * </ol>
 */
class StampsSeraManifestTest {

    private StampsConnector connector;
    private LabelPackageRepository labelPackageRepository;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper();
        CarrierProperties props = new CarrierProperties();
        CarrierProperties.Stamps s = props.getStamps();
        s.setApiFlavor("SERA");
        // Unreachable host — tests that would fire real HTTP fail fast.
        s.setSeraApiBaseUrl("http://localhost:1/sera");
        s.setSeraSandboxApiBaseUrl("http://localhost:1/sera-sandbox");
        connector = new StampsConnector(props, objectMapper);
        labelPackageRepository = mock(LabelPackageRepository.class);
        Field f = StampsConnector.class.getDeclaredField("labelPackageRepository");
        f.setAccessible(true);
        f.set(connector, labelPackageRepository);
    }

    // ===== body building =====

    @Test
    void buildBody_allTrackingHaveLabelId_usesByLabelIdsPath() {
        when(labelPackageRepository.findByTrackingNumber("T1")).thenReturn(
                Optional.of(LabelPackage.builder().trackingNumber("T1").carrierLabelRef("L1").build()));
        when(labelPackageRepository.findByTrackingNumber("T2")).thenReturn(
                Optional.of(LabelPackage.builder().trackingNumber("T2").carrierLabelRef("L2").build()));

        CloseOutRequest req = new CloseOutRequest(List.of("T1", "T2"), LocalDate.now(), null);
        Map<String, Object> body = connector.buildSeraManifestBody(req);

        // Exact-target manifest — label_ids array, no carrier/ship_date fields.
        assertEquals(List.of("L1", "L2"), body.get("label_ids"));
        assertFalse(body.containsKey("carrier"),
                "label_ids path must NOT include carrier/ship_date fallback fields");
        assertFalse(body.containsKey("ship_date"));
    }

    @Test
    void buildBody_someTrackingMissingLabelId_fallsBackToByCarrierShipDate() {
        // T1 has a label_id; T2 is legacy pre-V44 (null). Must fall back to
        // the by-carrier+ship_date shape so the legacy label still manifests.
        when(labelPackageRepository.findByTrackingNumber("T1")).thenReturn(
                Optional.of(LabelPackage.builder().trackingNumber("T1").carrierLabelRef("L1").build()));
        when(labelPackageRepository.findByTrackingNumber("T2")).thenReturn(Optional.empty());

        CloseOutRequest req = new CloseOutRequest(List.of("T1", "T2"),
                LocalDate.of(2026, 9, 8),
                new AddressToValidate("Warehouse", null, "1 Warehouse Way", null, null,
                        "Louisville", "KY", "40209", "US"));
        Map<String, Object> body = connector.buildSeraManifestBody(req);

        assertFalse(body.containsKey("label_ids"),
                "must not send incomplete label_ids array — SERA would only manifest a subset");
        assertEquals("usps", body.get("carrier"));
        assertEquals("2026-09-08", body.get("ship_date"));
        assertTrue(body.containsKey("from_address"),
                "fallback path must include shipper address so SERA scopes the manifest correctly");
    }

    @Test
    void buildBody_noRepositoryWired_fallsBackGracefully() throws Exception {
        // Unit tests that don't wire the repository must still produce a
        // valid body (the by-carrier+ship_date fallback). Ensures nothing
        // NPEs when running the connector in a leaner test setup.
        Field f = StampsConnector.class.getDeclaredField("labelPackageRepository");
        f.setAccessible(true);
        f.set(connector, null);

        CloseOutRequest req = new CloseOutRequest(List.of("T1"), LocalDate.now(), null);
        Map<String, Object> body = connector.buildSeraManifestBody(req);
        assertEquals("usps", body.get("carrier"));
        assertFalse(body.containsKey("label_ids"));
    }

    // ===== response parsing =====

    @Test
    void parseResponse_manifestId_populatedFromLabelsHref() {
        String json = """
                {
                  "manifest_id": "manifest-uuid-1",
                  "carrier": "usps",
                  "ship_date": "2026-09-08",
                  "number_of_items_in_manifest": 12,
                  "labels": [{"href": "https://s3/manifest.pdf"}]
                }
                """;
        CloseOutRequest req = new CloseOutRequest(List.of("T1", "T2"), LocalDate.now(), null);
        CloseOutResult r = connector.parseSeraManifestResponse(req, json);
        assertEquals("MANIFESTED", r.status());
        assertEquals("manifest-uuid-1", r.manifestId());
        assertEquals("https://s3/manifest.pdf", r.manifestPdfUrl());
        assertEquals(12, r.trackingCount());
    }

    @Test
    void parseResponse_missingManifestId_surfacesError() {
        // A response without a manifest_id can't drive downstream —
        // fail loud (mirrors parseSeraCreateLabelResponse's fault-first
        // contract).
        CloseOutRequest req = new CloseOutRequest(List.of("T1"), LocalDate.now(), null);
        CloseOutResult r = connector.parseSeraManifestResponse(req,
                "{\"error\": \"no tracking numbers for this account today\"}");
        assertEquals("ERROR", r.status());
        assertNull(r.manifestId());
    }

    @Test
    void parseResponse_emptyBody_surfacesError() {
        CloseOutResult r = connector.parseSeraManifestResponse(
                new CloseOutRequest(List.of("T1"), LocalDate.now(), null), "");
        assertEquals("ERROR", r.status());
    }

    // ===== dispatch + guards =====

    @Test
    void closeOutDaySera_localFallbackToken_returnsNotSupported() {
        CloseOutRequest req = new CloseOutRequest(List.of("T1"), LocalDate.now(), null);
        CloseOutResult r = connector.closeOutDaySera(req, "stamps-local-abc", "PRODUCTION");
        assertEquals("NOT_SUPPORTED", r.status());
    }

    @Test
    void closeOutDaySera_emptyTrackingList_returnsError() {
        CloseOutRequest req = new CloseOutRequest(List.of(), LocalDate.now(), null);
        CloseOutResult r = connector.closeOutDaySera(req, "real-token", "PRODUCTION");
        assertEquals("ERROR", r.status());
    }

    @Test
    void closeOutDay_swsimFlavor_routesToSwsimBranch() throws Exception {
        // Flip flavor to SWSIM — the top-level closeOutDay must NOT reach
        // the SERA endpoint (which would 404 anyway at localhost:1). We
        // detect the route by checking the error prefix: SWSIM's path
        // surfaces "SWSIM CreateScanForm" in its error messages.
        CarrierProperties props = new CarrierProperties();
        props.getStamps().setApiFlavor("SWSIM");
        props.getStamps().setSandboxUrl("http://localhost:1/swsim");
        props.getStamps().setApiBaseUrl("http://localhost:1/swsim");
        StampsConnector swsimConn = new StampsConnector(props, new ObjectMapper());

        CloseOutRequest req = new CloseOutRequest(List.of("T1"), LocalDate.now(), null);
        CloseOutResult r = swsimConn.closeOutDay(req, "real-token", "PRODUCTION");
        assertTrue(r.message().contains("SWSIM") || r.message().contains("CreateScanForm"),
                "must route to SWSIM branch when flavor=SWSIM; got: " + r.message());
    }

    // ===== JSON serialisation (integration-ish, just confirms the body
    // Jackson emits matches what we assert on) =====

    @Test
    void buildBody_serialisesToValidJson() throws Exception {
        when(labelPackageRepository.findByTrackingNumber("T1")).thenReturn(
                Optional.of(LabelPackage.builder().trackingNumber("T1").carrierLabelRef("L1").build()));
        CloseOutRequest req = new CloseOutRequest(List.of("T1"), LocalDate.now(), null);
        Map<String, Object> body = connector.buildSeraManifestBody(req);
        String json = objectMapper.writeValueAsString(body);
        JsonNode root = objectMapper.readTree(json);
        assertTrue(root.path("label_ids").isArray());
        assertEquals("L1", root.path("label_ids").get(0).asText());
        // label_options must be a nested object with the SERA-expected fields.
        assertEquals("pdf", root.path("label_options").path("label_format").asText());
    }
}
