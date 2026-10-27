package com.multiship.backend.controller;

import com.multiship.backend.service.label.LabelArtifactService;
import com.multiship.backend.service.label.LabelPrintableService;
import com.multiship.backend.service.label.LabelRenderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BUG-049 — once the carrier has voided a label, every byte-serving route
 * answers 410 with a reason (not a bare 404 the UI can only echo as
 * "unavailable (HTTP 404)"), and nothing is read or rendered.
 */
class LabelControllerVoidedTest {

    private static final int ORDER = 900078;

    private LabelArtifactService artifacts;
    private LabelRenderService render;
    private LabelPrintableService printable;
    private LabelController controller;

    @BeforeEach
    void setUp() {
        artifacts = mock(LabelArtifactService.class);
        render = mock(LabelRenderService.class);
        printable = mock(LabelPrintableService.class);
        controller = new LabelController(artifacts, render, printable);
        when(artifacts.isVoided(ORDER)).thenReturn(true);
    }

    private static void assertGone(ResponseEntity<?> r) {
        assertEquals(HttpStatus.GONE, r.getStatusCode());
        Object body = r.getBody();
        String text = body instanceof byte[] b ? new String(b, StandardCharsets.UTF_8) : String.valueOf(body);
        assertTrue(text.contains("order " + ORDER) && text.contains("voided"), text);
    }

    @Test
    void zpl_routes_answer_410_with_reason() {
        assertGone(controller.allZpl(ORDER));
        assertGone(controller.packageZpl(ORDER, 1));
        verify(artifacts, never()).zplAll(anyInt());
        verify(artifacts, never()).zpl(anyInt(), anyInt());
    }

    @Test
    void pdf_route_answers_410_with_reason_and_never_renders() {
        assertGone(controller.pdf(ORDER, null));
        assertGone(controller.pdf(ORDER, 1));
        verify(printable, never()).pdf(any(), any());
    }

    @Test
    void preview_routes_answer_410() {
        assertGone(controller.previewPng(ORDER, 1));
        assertEquals(HttpStatus.GONE, controller.previewHead(ORDER, 1).getStatusCode());
        verify(render, never()).renderPng(any());
    }

    @Test
    void live_label_is_untouched() {
        when(artifacts.isVoided(ORDER)).thenReturn(false);
        when(artifacts.zplAll(ORDER)).thenReturn(Optional.of("^XA^XZ".getBytes(StandardCharsets.ISO_8859_1)));
        ResponseEntity<String> r = controller.allZpl(ORDER);
        assertEquals(HttpStatus.OK, r.getStatusCode());
        assertEquals("^XA^XZ", r.getBody());
    }
}
