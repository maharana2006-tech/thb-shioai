package com.multiship.backend.service.carriers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.model.LabelPackage;
import com.multiship.backend.repository.LabelPackageRepository;
import com.multiship.backend.service.carriers.CarrierConnector.LabelReprintResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Focused unit tests for the SERA {@code GET /sera/v1/labels/{label_id}}
 * reprint / retrieve branch. Behaviour under test:
 * <ol>
 *   <li><b>label_id resolution</b> — same lookup as SERA void
 *       (label_package.carrier_label_ref). No id → actionable ERROR
 *       instead of a bogus HTTP call.</li>
 *   <li><b>Response parsing</b> — {@code labels[0].href} split into
 *       URL vs base64 by the {@code http(s)://} prefix. Missing/empty
 *       labels[] → ERROR.</li>
 *   <li><b>Dispatch</b> — SWSIM returns NOT_SUPPORTED with a pointer
 *       to the persisted label_url; SERA branch runs the endpoint.</li>
 *   <li><b>Label size normalisation</b> — SERA rejects sizes outside
 *       its enum; normaliser defaults blank / unknown to 4x6.</li>
 * </ol>
 */
class StampsSeraReprintTest {

    private static final String TRACKING = "9400111899223197428301";
    private static final String LABEL_ID = "abc-uuid-1";

    private StampsConnector connector;
    private LabelPackageRepository labelPackageRepository;

    @BeforeEach
    void setUp() throws Exception {
        CarrierProperties props = new CarrierProperties();
        CarrierProperties.Stamps s = props.getStamps();
        s.setApiFlavor("SERA");
        s.setSeraApiBaseUrl("http://localhost:1/sera");
        s.setSeraSandboxApiBaseUrl("http://localhost:1/sera-sandbox");
        connector = new StampsConnector(props, new ObjectMapper());
        labelPackageRepository = mock(LabelPackageRepository.class);
        Field f = StampsConnector.class.getDeclaredField("labelPackageRepository");
        f.setAccessible(true);
        f.set(connector, labelPackageRepository);
    }

    // ===== label_id resolution =====

    @Test
    void reprintLabelSera_noLabelIdInDB_returnsActionableError() {
        when(labelPackageRepository.findByTrackingNumber(TRACKING)).thenReturn(Optional.empty());
        LabelReprintResult r = connector.reprintLabelSera(TRACKING, "4x6", "pdf", "real-token", "PRODUCTION");
        assertEquals("ERROR", r.status());
        assertTrue(r.message().toLowerCase().contains("label_id"),
                "detail must call out the missing label_id so operator knows why; got: " + r.message());
        assertNull(r.labelUrl());
        assertNull(r.labelBase64());
    }

    @Test
    void reprintLabelSera_localFallbackToken_returnsNotSupported() {
        LabelReprintResult r = connector.reprintLabelSera(TRACKING, "4x6", "pdf",
                "stamps-local-abc", "PRODUCTION");
        assertEquals("NOT_SUPPORTED", r.status());
    }

    // ===== response parsing =====

    @Test
    void parseResponse_base64Bytes_landsInLabelBase64() {
        String json = """
                {
                  "labels": [{"href": "BASE64PDFCONTENT"}],
                  "forms": []
                }
                """;
        LabelReprintResult r = connector.parseSeraReprintResponse(LABEL_ID, json);
        assertEquals("OK", r.status());
        assertNull(r.labelUrl(), "no http:// prefix → treat as base64, not URL");
        assertEquals("BASE64PDFCONTENT", r.labelBase64());
    }

    @Test
    void parseResponse_httpsUrl_landsInLabelUrl() {
        String json = """
                {
                  "labels": [{"href": "https://s3/sera/label.pdf"}]
                }
                """;
        LabelReprintResult r = connector.parseSeraReprintResponse(LABEL_ID, json);
        assertEquals("OK", r.status());
        assertEquals("https://s3/sera/label.pdf", r.labelUrl());
        assertNull(r.labelBase64());
    }

    @Test
    void parseResponse_emptyLabelsArray_surfacesError() {
        LabelReprintResult r = connector.parseSeraReprintResponse(LABEL_ID,
                "{\"labels\": []}");
        assertEquals("ERROR", r.status());
    }

    @Test
    void parseResponse_missingHref_surfacesError() {
        LabelReprintResult r = connector.parseSeraReprintResponse(LABEL_ID,
                "{\"labels\": [{}]}");
        assertEquals("ERROR", r.status());
    }

    @Test
    void parseResponse_emptyBody_surfacesError() {
        LabelReprintResult r = connector.parseSeraReprintResponse(LABEL_ID, "");
        assertEquals("ERROR", r.status());
    }

    // ===== dispatch =====

    @Test
    void reprintLabel_swsimFlavor_returnsNotSupported() {
        CarrierProperties props = new CarrierProperties();
        props.getStamps().setApiFlavor("SWSIM");
        StampsConnector swsimConn = new StampsConnector(props, new ObjectMapper());
        LabelReprintResult r = swsimConn.reprintLabel(TRACKING, "4x6", "pdf", "token", "PRODUCTION");
        assertEquals("NOT_SUPPORTED", r.status());
        assertTrue(r.message().toLowerCase().contains("swsim")
                        || r.message().toLowerCase().contains("label_url")
                        || r.message().toLowerCase().contains("reprint"),
                "SWSIM NOT_SUPPORTED must explain where the persisted label lives; got: " + r.message());
    }

    @Test
    void reprintLabel_defaultConnectorImpl_returnsNotSupported() {
        // Every non-Stamps carrier must inherit the CarrierConnector default
        // NOT_SUPPORTED so no accidental live-call happens on a carrier that
        // doesn't have reprint wired.
        CarrierConnector stub = new CarrierConnector() {
            public String getCarrierCode() { return "STUB"; }
            public String getCarrierName() { return "Stub"; }
            public com.multiship.backend.service.carriers.CarrierConnector.ServiceAvailability
                    listServices(String o, String t, String e) { return null; }
            public com.multiship.backend.service.carriers.CarrierConnector.PackageAvailability
                    listPackages(String o, String t, String e) { return null; }
            public com.multiship.backend.service.carriers.CarrierConnector.CarrierConnectionResult
                    connect(String c, String s, String a) { return null; }
            public String getAccessToken(String c, String s) { return null; }
            public com.multiship.backend.service.carriers.CarrierConnector.ShipmentResult
                    createShipment(com.multiship.backend.dto.ShipmentRequestDTO r, String t, String e) { return null; }
            public boolean validateCredentials(String c, String s) { return false; }
            public com.multiship.backend.service.carriers.CarrierConnector.TrackingResult
                    trackShipment(String t) { return null; }
            public com.multiship.backend.service.carriers.CarrierConnector.CarrierConfiguration
                    getConfiguration() { return null; }
        };
        LabelReprintResult r = stub.reprintLabel(TRACKING, null, null, "t", "PRODUCTION");
        assertEquals("NOT_SUPPORTED", r.status());
        assertEquals("STUB", r.carrierCode());
    }

    // ===== label size / format normalisation =====

    @Test
    void normaliseSeraLabelSize_blankAndUnknownDefaultTo4x6() {
        assertEquals("4x6", StampsConnector.normaliseSeraLabelSize(null));
        assertEquals("4x6", StampsConnector.normaliseSeraLabelSize("  "));
        assertEquals("4x6", StampsConnector.normaliseSeraLabelSize("A4"));
    }

    @Test
    void normaliseSeraLabelSize_knownSizesPassThrough() {
        assertEquals("letter", StampsConnector.normaliseSeraLabelSize("letter"));
        assertEquals("4x6.75-doctab", StampsConnector.normaliseSeraLabelSize("4x6.75-doctab"));
    }

    // ===== end-to-end reprint call with mocked label_id =====

    @Test
    void reprintLabelSera_resolvedLabelId_attemptsRealCallAndSurfacesConnFailure() {
        // Full round-trip: DB returns the id, connector fires HTTP against
        // localhost:1 (refuses), returns ERROR with the underlying reason.
        // Proves that label_id resolution feeds into the URL construction.
        when(labelPackageRepository.findByTrackingNumber(TRACKING)).thenReturn(
                Optional.of(LabelPackage.builder().trackingNumber(TRACKING)
                        .carrierLabelRef(LABEL_ID).build()));
        LabelReprintResult r = connector.reprintLabelSera(TRACKING, "4x6", "pdf",
                "real-token", "PRODUCTION");
        assertEquals("ERROR", r.status());
        // NOT the "no label_id" error — proves resolution succeeded and the
        // call reached the HTTP layer.
        assertTrue(r.message().toLowerCase().contains("failed")
                || r.message().toLowerCase().contains("call")
                || r.message().toLowerCase().contains("rejected"),
                "must surface the underlying HTTP failure; got: " + r.message());
    }
}
