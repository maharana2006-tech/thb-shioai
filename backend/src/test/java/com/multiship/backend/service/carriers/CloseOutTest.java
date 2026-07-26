package com.multiship.backend.service.carriers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.service.carriers.CarrierConnector.AddressToValidate;
import com.multiship.backend.service.carriers.CarrierConnector.CloseOutRequest;
import com.multiship.backend.service.carriers.CarrierConnector.CloseOutResult;
import com.multiship.backend.service.fx.FxRateService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 34 — end-of-day close-out parsing across UPS, FedEx, USPS, plus
 * DHL's inherited NOT_SUPPORTED default.
 */
class CloseOutTest {

    private static FxRateService noFx() {
        return new FxRateService() {
            @Override public Optional<BigDecimal> rate(String f, String t) { return Optional.empty(); }
            @Override public Optional<BigDecimal> convert(BigDecimal a, String f, String t) { return Optional.empty(); }
            @Override public boolean supports(String c) { return false; }
        };
    }

    private static CloseOutRequest baseRequest() {
        return new CloseOutRequest(
                List.of("1Z999AA10123456784", "1Z999AA10123456785", "1Z999AA10123456786"),
                LocalDate.of(2026, 8, 1),
                new AddressToValidate("Acme Warehouse", null,
                        "1 Warehouse Way", null, null,
                        "Louisville", "KY", "40209", "US"));
    }

    private static CloseOutRequest emptyTrackingRequest() {
        return new CloseOutRequest(List.of(), LocalDate.of(2026, 8, 1), null);
    }

    /* -------------------------- Auth + input guardrails -------------------------- */

    @Test
    void everyCarrierReturnsNotSupportedForLocalFallbackToken() {
        CloseOutRequest r = baseRequest();
        assertEquals("NOT_SUPPORTED",
                new UpsConnector(new CarrierProperties(), new ObjectMapper())
                        .closeOutDay(r, "ups-local-abc").status());
        assertEquals("NOT_SUPPORTED",
                new FedExConnector(new CarrierProperties(), new ObjectMapper(), noFx())
                        .closeOutDay(r, "fedex-local-abc").status());
        assertEquals("NOT_SUPPORTED",
                new StampsConnector(new CarrierProperties(), new ObjectMapper())
                        .closeOutDay(r, "stamps-local-abc").status());
    }

    @Test
    void dhlAlwaysReturnsNotSupported() {
        // DHL doesn't have an explicit close-out — manifest is implicit
        // via the pickup call from Sprint 33. Inherits the interface
        // default even with real tokens.
        CloseOutResult r = new DhlConnector(new CarrierProperties(), new ObjectMapper())
                .closeOutDay(baseRequest(), "REAL-TOKEN");
        assertEquals("NOT_SUPPORTED", r.status());
        assertEquals("DHL", r.carrierCode());
    }

    @Test
    void emptyTrackingListReturnsErrorEverywhereWithRealToken() {
        // Real token, no tracking → validation error surfaces from the
        // connector, not from the service layer.
        String tok = "REAL-TOKEN";
        assertEquals("ERROR",
                new UpsConnector(new CarrierProperties(), new ObjectMapper())
                        .closeOutDay(emptyTrackingRequest(), tok).status());
        assertEquals("ERROR",
                new FedExConnector(new CarrierProperties(), new ObjectMapper(), noFx())
                        .closeOutDay(emptyTrackingRequest(), tok).status());
        assertEquals("ERROR",
                new StampsConnector(new CarrierProperties(), new ObjectMapper())
                        .closeOutDay(emptyTrackingRequest(), tok).status());
    }

    /* -------------------------- UPS parsing -------------------------- */

    @Test
    void upsSuccessResponseCarriesBolNumberAndOptionalPdf() {
        UpsConnector c = new UpsConnector(new CarrierProperties(), new ObjectMapper());
        String canned = """
                {
                  "EndOfDayResponse": {
                    "BOLNumber": "BOL-987654321",
                    "Manifest": {
                      "ManifestImage": {"GraphicImage": "JVBERi0xLj..."}
                    }
                  }
                }""";
        CloseOutResult r = c.parseUpsCloseOutResponse(baseRequest(), canned);
        assertEquals("MANIFESTED", r.status());
        assertEquals("BOL-987654321", r.manifestId());
        assertEquals(3, r.trackingCount());
        assertNotNull(r.manifestPdfBase64());
        assertTrue(r.manifestPdfBase64().startsWith("JVBERi"),
                "PDF passthrough should preserve the base64 prefix from GraphicImage");
    }

    @Test
    void upsMissingBolNumberReturnsError() {
        UpsConnector c = new UpsConnector(new CarrierProperties(), new ObjectMapper());
        CloseOutResult r = c.parseUpsCloseOutResponse(baseRequest(), "{\"EndOfDayResponse\":{}}");
        assertEquals("ERROR", r.status());
    }

    /* -------------------------- FedEx parsing -------------------------- */

    @Test
    void fedexSuccessResponseCarriesGroupId() {
        FedExConnector c = new FedExConnector(new CarrierProperties(), new ObjectMapper(), noFx());
        String canned = """
                {
                  "output": {
                    "transactionShipments": [{
                      "groupID": "FDX-987654",
                      "pieceResponses": []
                    }]
                  }
                }""";
        CloseOutResult r = c.parseFedExCloseOutResponse(baseRequest(), canned);
        assertEquals("MANIFESTED", r.status());
        assertEquals("FDX-987654", r.manifestId());
        assertEquals(3, r.trackingCount());
    }

    @Test
    void fedexGroupIdFromManifestDataFallback() {
        FedExConnector c = new FedExConnector(new CarrierProperties(), new ObjectMapper(), noFx());
        // Some FedEx sandboxes put groupID under output.manifestData.
        String canned = """
                {
                  "output": {
                    "transactionShipments": [{}],
                    "manifestData": {
                      "groupID": "ALT-135790",
                      "manifestImage": "PDF-BASE64-TEXT"
                    }
                  }
                }""";
        CloseOutResult r = c.parseFedExCloseOutResponse(baseRequest(), canned);
        assertEquals("MANIFESTED", r.status());
        assertEquals("ALT-135790", r.manifestId());
        assertEquals("PDF-BASE64-TEXT", r.manifestPdfBase64());
    }

    @Test
    void fedexMissingGroupIdEverywhereReturnsError() {
        FedExConnector c = new FedExConnector(new CarrierProperties(), new ObjectMapper(), noFx());
        CloseOutResult r = c.parseFedExCloseOutResponse(baseRequest(),
                "{\"output\":{\"transactionShipments\":[{}]}}");
        assertEquals("ERROR", r.status());
    }

    /* -------------------------- SWSIM CreateScanForm -------------------------- */

    @Test
    void stampsCreateScanFormEnvelopeIncludesEveryTrackingNumberAndFromAddress() {
        StampsConnector c = new StampsConnector(new CarrierProperties(), new ObjectMapper());
        String xml = c.buildCreateScanFormEnvelope(baseRequest(), "AUTH-XYZ");
        assertTrue(xml.contains("<CreateScanForm xmlns=\""), xml);
        assertTrue(xml.contains("<Authenticator>AUTH-XYZ</Authenticator>"));
        assertTrue(xml.contains("<StampsTxIDs>"));
        assertTrue(xml.contains("<string>1Z999AA10123456784</string>"));
        assertTrue(xml.contains("<string>1Z999AA10123456785</string>"));
        assertTrue(xml.contains("<string>1Z999AA10123456786</string>"));
        assertTrue(xml.contains("<City>Louisville</City>"));
        assertTrue(xml.contains("<ImageType>Pdf</ImageType>"));
    }

    @Test
    void stampsSuccessResponseCarriesSubmissionIdAndScanFormUrl() {
        StampsConnector c = new StampsConnector(new CarrierProperties(), new ObjectMapper());
        String canned = """
                <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
                  <soap:Body>
                    <CreateScanFormResponse>
                      <Authenticator>NEW-AUTH-456</Authenticator>
                      <SubmissionID>SCAN-12345</SubmissionID>
                      <ScanFormUrl>https://static.stamps.com/scan-forms/SCAN-12345.pdf</ScanFormUrl>
                    </CreateScanFormResponse>
                  </soap:Body>
                </soap:Envelope>""";
        CloseOutResult r = c.parseCreateScanFormResponse(baseRequest(), canned);
        assertEquals("MANIFESTED", r.status());
        assertEquals("SCAN-12345", r.manifestId());
        assertEquals("https://static.stamps.com/scan-forms/SCAN-12345.pdf", r.manifestPdfUrl());
        assertEquals(3, r.trackingCount());
    }

    @Test
    void stampsFaultstringReturnsError() {
        StampsConnector c = new StampsConnector(new CarrierProperties(), new ObjectMapper());
        String canned = """
                <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
                  <soap:Body>
                    <soap:Fault>
                      <faultstring>No qualifying labels for SCAN Form</faultstring>
                    </soap:Fault>
                  </soap:Body>
                </soap:Envelope>""";
        CloseOutResult r = c.parseCreateScanFormResponse(baseRequest(), canned);
        assertEquals("ERROR", r.status());
        assertTrue(r.message().contains("No qualifying labels"));
    }

    @Test
    void stampsMissingSubmissionIdIsError() {
        StampsConnector c = new StampsConnector(new CarrierProperties(), new ObjectMapper());
        String canned = """
                <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
                  <soap:Body>
                    <CreateScanFormResponse>
                      <Authenticator>NEW-AUTH-456</Authenticator>
                    </CreateScanFormResponse>
                  </soap:Body>
                </soap:Envelope>""";
        CloseOutResult r = c.parseCreateScanFormResponse(baseRequest(), canned);
        assertEquals("ERROR", r.status());
    }
}
