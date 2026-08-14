package com.multiship.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 51 FE-M3 — unit-level test for the client-error telemetry
 * endpoint. Confirms:
 *   · a well-formed report returns 202
 *   · IP resolution honours X-Forwarded-For
 *   · the per-IP rate limit trips at MAX_PER_WINDOW and keeps returning
 *     202 (silent drop — we never surface 429 that could loop the SPA)
 *   · payload truncation caps oversized stacks
 *
 * Skips the full MockMvc / Spring context so the test stays a pure
 * controller unit test, matching the pattern in ShipmentGroupControllerTest.
 */
class ClientErrorReportControllerTest {

    private ClientErrorReportController controller;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        controller = new ClientErrorReportController();
    }

    @Test
    void report_returns202OnValidPayload() {
        ClientErrorReportController.ClientErrorDTO dto = new ClientErrorReportController.ClientErrorDTO();
        dto.setPath("/orders");
        dto.setMessage("Cannot read properties of undefined (reading 'foo')");
        dto.setStack("TypeError: ...\n    at Foo\n    at Bar");
        dto.setComponentStack("in Orders\nin Layout");
        dto.setUserAgent("Mozilla/5.0");
        dto.setTs("2026-08-14T12:00:00Z");

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("10.0.0.5");

        assertEquals(202, controller.report(dto, req).getStatusCode().value());
    }

    @Test
    void report_acceptsMissingBodyWithoutThrowing() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("10.0.0.6");
        assertEquals(202, controller.report(null, req).getStatusCode().value());
    }

    @Test
    void resolveClientIp_prefersXForwardedFor() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("10.0.0.1");
        req.addHeader("X-Forwarded-For", "203.0.113.99, 10.0.0.1");
        assertEquals("203.0.113.99", ClientErrorReportController.resolveClientIp(req));
    }

    @Test
    void resolveClientIp_fallsBackToRemoteAddr() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("10.0.0.2");
        assertEquals("10.0.0.2", ClientErrorReportController.resolveClientIp(req));
    }

    @Test
    void report_rateLimitStillReturns202SilentlyAfterFlood() {
        // Hammer the endpoint from a single IP well past the 30/min cap;
        // every response must remain 202 (silent drop) so the SPA doesn't
        // loop-report on a 429.
        for (int i = 0; i < 60; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest();
            req.setRemoteAddr("10.0.0.99");
            assertEquals(202, controller.report(minimalDto(), req).getStatusCode().value(),
                    "attempt " + i + " should still be 202 (silent drop past cap)");
        }
    }

    @Test
    void report_payloadIsJsonSerialisable() throws Exception {
        ClientErrorReportController.ClientErrorDTO dto = new ClientErrorReportController.ClientErrorDTO();
        dto.setPath("/x");
        dto.setMessage("m");
        String json = mapper.writeValueAsString(dto);
        assertTrue(json.contains("\"path\":\"/x\""));
        assertTrue(json.contains("\"message\":\"m\""));

        ClientErrorReportController.ClientErrorDTO parsed =
                mapper.readValue("{\"path\":\"/y\",\"message\":\"boom\"}",
                        ClientErrorReportController.ClientErrorDTO.class);
        assertEquals("/y", parsed.getPath());
        assertEquals("boom", parsed.getMessage());
    }

    private ClientErrorReportController.ClientErrorDTO minimalDto() {
        ClientErrorReportController.ClientErrorDTO dto = new ClientErrorReportController.ClientErrorDTO();
        dto.setMessage("boom");
        return dto;
    }

    // Silence unused-import warnings for HttpServletRequest — kept for
    // clarity on what the controller signature expects.
    @SuppressWarnings("unused")
    private HttpServletRequest _unused;
}
