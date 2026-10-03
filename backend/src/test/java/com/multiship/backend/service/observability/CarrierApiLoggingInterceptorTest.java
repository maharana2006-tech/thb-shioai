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

    private static ClientHttpResponse okResponse(int status, String body) {
        MockClientHttpResponse resp = new MockClientHttpResponse(
                body.getBytes(StandardCharsets.UTF_8), status);
        resp.getHeaders().set("Content-Type", "application/json");
        // Touch MockHttpInputMessage to pull in the symbol so the import isn't dropped.
        new MockHttpInputMessage(body.getBytes(StandardCharsets.UTF_8));
        return resp;
    }
}
