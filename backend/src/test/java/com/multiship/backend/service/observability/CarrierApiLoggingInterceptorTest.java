package com.multiship.backend.service.observability;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** V114 wire — host-based carrier detection + record() around success / failure. */
class CarrierApiLoggingInterceptorTest {

    @Test
    void hostLookupMapsKnownCarriers() {
        assertEquals("FEDEX", CarrierApiLoggingInterceptor.carrierFromHost(URI.create("https://apis.fedex.com/ship/v1")));
        assertEquals("UPS",   CarrierApiLoggingInterceptor.carrierFromHost(URI.create("https://onlinetools.ups.com/api")));
        assertEquals("USPS",  CarrierApiLoggingInterceptor.carrierFromHost(URI.create("https://api.usps.com/label/v1")));
        assertEquals("STAMPS_COM", CarrierApiLoggingInterceptor.carrierFromHost(URI.create("https://swsim.stamps.com/swsim/SwsimV139.asmx")));
        assertEquals("DHL",   CarrierApiLoggingInterceptor.carrierFromHost(URI.create("https://express.api.dhl.com/mydhlapi")));
        assertEquals("UNKNOWN", CarrierApiLoggingInterceptor.carrierFromHost(URI.create("https://api.example.com")));
    }

    @Test
    void secretsAreBlankedBeforeTheyReachTheLog() {
        // BUG-065 — client_id is the public half of an OAuth client
        // credential, but it's long-lived and non-rotatable on several
        // carriers (SERA / UPS 3.0). Admin-visible logs persist for
        // weeks, so mask it alongside client_secret / refresh_token /
        // access_token.
        String sera = "{\"grant_type\":\"refresh_token\",\"client_id\":\"cid\",\"client_secret\":\"S3cr3t\",\"refresh_token\" : \"RT-1\"}";
        assertEquals("{\"grant_type\":\"refresh_token\",\"client_id\":\"***\",\"client_secret\":\"***\",\"refresh_token\" : \"***\"}",
                CarrierApiLoggingInterceptor.redact(sera));
        assertEquals("{\"access_token\":\"***\",\"expires_in\":3600}",
                CarrierApiLoggingInterceptor.redact("{\"access_token\":\"eyJabc\",\"expires_in\":3600}"));
        assertEquals("grant_type=client_credentials&client_id=***&client_secret=***",
                CarrierApiLoggingInterceptor.redact("grant_type=client_credentials&client_id=cid&client_secret=S3cr3t"));
        assertEquals("<Username>u</Username><Password>***</Password>",
                CarrierApiLoggingInterceptor.redact("<Username>u</Username><Password>pw</Password>"));
        assertEquals("{\"code\":\"E100\",\"message\":\"Bad ZIP\"}",
                CarrierApiLoggingInterceptor.redact("{\"code\":\"E100\",\"message\":\"Bad ZIP\"}"), "carrier error codes stay readable");
        assertEquals("STAMPS_COM", CarrierApiLoggingInterceptor.carrierFromHost(URI.create("https://signin.testing.stampsendicia.com/oauth/token")));
    }

    @Test
    void successRecordsRowWithStatusAndBodies() throws IOException {
        CarrierApiLogService svc = mock(CarrierApiLogService.class);
        CarrierApiLoggingInterceptor interceptor = new CarrierApiLoggingInterceptor(svc, true);

        MockClientHttpRequest req = new MockClientHttpRequest(HttpMethod.POST,
                URI.create("https://apis.fedex.com/ship/v1/shipments"));
        byte[] reqBody = "{\"orderNo\":42}".getBytes(StandardCharsets.UTF_8);
        ClientHttpRequestExecution exec = (r, b) -> okResponse(200, "{\"tracking\":\"1Z\"}");

        ClientHttpResponse resp = interceptor.intercept(req, reqBody, exec);
        assertEquals(200, resp.getStatusCode().value());

        verify(svc).record(eq("FEDEX"), eq("POST"),
                eq("https://apis.fedex.com/ship/v1/shipments"),
                eq("{\"orderNo\":42}"), eq("{\"tracking\":\"1Z\"}"),
                eq(200), any(Integer.class), eq(null), eq(null), eq(null), eq(null));
    }

    @Test
    void exceptionIsRecordedAndRethrown() {
        CarrierApiLogService svc = mock(CarrierApiLogService.class);
        CarrierApiLoggingInterceptor interceptor = new CarrierApiLoggingInterceptor(svc, true);

        MockClientHttpRequest req = new MockClientHttpRequest(HttpMethod.POST,
                URI.create("https://onlinetools.ups.com/api/shipments"));
        ClientHttpRequestExecution exec = (r, b) -> { throw new java.net.ConnectException("refused"); };

        assertThrows(IOException.class, () -> interceptor.intercept(req, new byte[0], exec));

        ArgumentCaptor<String> errCaptor = ArgumentCaptor.forClass(String.class);
        verify(svc).record(eq("UPS"), eq("POST"), any(), any(), any(),
                eq(null), any(Integer.class), errCaptor.capture(),
                eq(null), eq(null), eq(null));
        assertTrue(errCaptor.getValue().contains("ConnectException"));
        assertTrue(errCaptor.getValue().contains("refused"));
    }

    @Test
    void disabledInterceptorSkipsPersist() throws IOException {
        CarrierApiLogService svc = mock(CarrierApiLogService.class);
        CarrierApiLoggingInterceptor interceptor = new CarrierApiLoggingInterceptor(svc, false);

        MockClientHttpRequest req = new MockClientHttpRequest(HttpMethod.GET,
                URI.create("https://apis.fedex.com/ping"));
        ClientHttpRequestExecution exec = (r, b) -> okResponse(200, "ok");

        interceptor.intercept(req, new byte[0], exec);
        verify(svc, never()).record(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    /**
     * BUG-022 (QA 2026-10-23) — pre-fix, the Carrier API Log row's
     * {@code errorMessage} column was NULL for every failed call
     * (164/164 in the latest 1,200-row sample). Operators had to open
     * each row to read the response body for the carrier's reason. On
     * a non-2xx response, errorMessage is now populated with the
     * response body so support can filter / dashboard on it; the full
     * body still lives on {@code responseBody}.
     */
    @Test
    void nonTwoHundredResponseRecordsErrorMessage() throws IOException {
        CarrierApiLogService svc = mock(CarrierApiLogService.class);
        CarrierApiLoggingInterceptor interceptor = new CarrierApiLoggingInterceptor(svc, true);

        MockClientHttpRequest req = new MockClientHttpRequest(HttpMethod.POST,
                URI.create("https://onlinetools.ups.com/api/pickupcreation/v1/pickup"));
        String errBody = "{\"response\":{\"errors\":[{\"code\":\"250002\",\"message\":\"Invalid Authentication Information.\"}]}}";
        ClientHttpRequestExecution exec = (r, b) -> okResponse(401, errBody);

        ClientHttpResponse resp = interceptor.intercept(req, new byte[0], exec);
        assertEquals(401, resp.getStatusCode().value());

        ArgumentCaptor<String> errCaptor = ArgumentCaptor.forClass(String.class);
        verify(svc).record(eq("UPS"), eq("POST"), any(), any(),
                eq(errBody),           // responseBody carries the full payload
                eq(401), any(Integer.class),
                errCaptor.capture(),    // errorMessage now populated
                eq(null), eq(null), eq(null));
        assertEquals(errBody, errCaptor.getValue(),
                "non-2xx responses must populate errorMessage with the response body");
    }

    @Test
    void twoHundredResponseLeavesErrorMessageNull() throws IOException {
        // BUG-022 — defence against regressing success calls into
        // false-positive errors. 2xx stays null; only >= 400 populates.
        CarrierApiLogService svc = mock(CarrierApiLogService.class);
        CarrierApiLoggingInterceptor interceptor = new CarrierApiLoggingInterceptor(svc, true);
        MockClientHttpRequest req = new MockClientHttpRequest(HttpMethod.POST,
                URI.create("https://apis.fedex.com/pickup/v1/pickups"));
        ClientHttpRequestExecution exec = (r, b) -> okResponse(201, "{\"ok\":true}");

        interceptor.intercept(req, new byte[0], exec);
        verify(svc).record(eq("FEDEX"), eq("POST"), any(), any(),
                eq("{\"ok\":true}"), eq(201), any(Integer.class),
                eq(null), eq(null), eq(null), eq(null));
    }

    private static ClientHttpResponse okResponse(int status, String body) {
        MockClientHttpResponse resp = new MockClientHttpResponse(
                body.getBytes(StandardCharsets.UTF_8), status);
        resp.getHeaders().set("Content-Type", "application/json");
        // Touch MockHttpInputMessage to pull in the symbol so the import isn't dropped.
        new MockHttpInputMessage(body.getBytes(StandardCharsets.UTF_8));
        return resp;
    }
}
