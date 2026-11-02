package com.multiship.backend.controller;

import com.multiship.backend.service.ReportService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.server.ResponseStatusException;

import java.io.OutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * BUG-166 / BUG-168 — a report that fails must fail visibly. Before the fix
 * the CSV headers were committed first, so a dead query produced a 200 with
 * an empty file; and a malformed date gave a 500.
 */
class ReportControllerCsvFailureTest {

    @Test
    void aQueryThatThrowsLeavesNoCsvHeadersBehind() {
        ReportService service = mock(ReportService.class);
        doThrow(new IllegalStateException("relation does not exist"))
                .when(service).streamOrdersCsv(any(), any(OutputStream.class));
        ReportController controller = new ReportController(service);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThrows(IllegalStateException.class,
                () -> controller.orders(response, new ReportController.ReportFiltersQuery()));
        assertFalse(response.isCommitted(), "nothing may be committed before the query succeeds");
        assertTrue(response.getContentType() == null || !response.getContentType().contains("csv"),
                "text/csv must not be set when no row was written");
    }

    @Test
    void aSuccessfulStreamSetsTheCsvHeadersOnFirstWrite() throws Exception {
        ReportService service = mock(ReportService.class);
        doAnswer(inv -> {
            OutputStream out = inv.getArgument(1);
            out.write("a,b\n1,2\n".getBytes());
            out.flush();
            return null;
        }).when(service).streamBillingCsv(any(), any(OutputStream.class));
        ReportController controller = new ReportController(service);
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.billing(response, new ReportController.ReportFiltersQuery());

        assertTrue(response.getContentType().startsWith("text/csv"));
        assertTrue(response.getHeader("Content-Disposition").startsWith("attachment; filename=billing-"));
        assertEquals("a,b\n1,2\n", response.getContentAsString());
    }

    @Test
    void badDatesAre400sThatNameTheBox() {
        ReportController.ReportFiltersQuery q = new ReportController.ReportFiltersQuery();
        q.setFrom("abc");
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, q::toFilters);
        assertEquals(400, ex.getStatusCode().value());
        assertTrue(ex.getReason().startsWith("from:"));

        ReportController.ReportFiltersQuery inverted = new ReportController.ReportFiltersQuery();
        inverted.setFrom("2026-10-26");
        inverted.setTo("2026-10-01");
        assertEquals(400, assertThrows(ResponseStatusException.class, inverted::toFilters).getStatusCode().value());
    }
}
