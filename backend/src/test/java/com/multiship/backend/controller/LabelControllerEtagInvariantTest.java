package com.multiship.backend.controller;

import com.multiship.backend.model.LabelPackage;
import com.multiship.backend.service.label.LabelArtifactService;
import com.multiship.backend.service.label.LabelPrintableService;
import com.multiship.backend.service.label.LabelRenderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * THE invariant of the re-architecture: the preview's ETag equals the
 * SHA-256 of the exact bytes the ZPL endpoint returns, and the preview was
 * rendered from those same bytes. A client holding a preview holds a render
 * of what it will copy and print. No carrier, no DB, no renderer binary
 * needed to prove it.
 */
class LabelControllerEtagInvariantTest {

    private static final int ORDER = 900058;
    private static final byte[] ZPL = "^XA^FO10,10^BCN,80,Y^FD1Z999^FS^XZ".getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};

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
    }

    private static LabelPackage nativePkg() {
        return LabelPackage.builder().orderNo(ORDER).sequenceNumber(1)
                .format("ZPL").source("CARRIER_NATIVE").build();
    }

    @Test
    void preview_etag_equals_sha256_of_the_zpl_the_zpl_endpoint_returns() {
        when(artifacts.pkg(ORDER, 1)).thenReturn(Optional.of(nativePkg()));
        when(artifacts.zpl(ORDER, 1)).thenReturn(Optional.of(ZPL));
        when(render.renderPng(ZPL)).thenReturn(PNG);

        ResponseEntity<byte[]> preview = controller.previewPng(ORDER, 1);
        ResponseEntity<String> zpl = controller.packageZpl(ORDER, 1);

        String previewTag = preview.getHeaders().getFirst(HttpHeaders.ETAG);
        String zplTag = zpl.getHeaders().getFirst(HttpHeaders.ETAG);

        assertEquals(200, preview.getStatusCode().value());
        assertEquals(200, zpl.getStatusCode().value());
        assertEquals(LabelRenderService.etag(ZPL), previewTag, "preview ETag is the hash of the ZPL bytes");
        assertEquals(previewTag, zplTag, "ZPL endpoint carries the identical tag");
        assertEquals(new String(ZPL, StandardCharsets.ISO_8859_1), zpl.getBody(), "ZPL body is those exact bytes");
        assertSame(PNG, preview.getBody());
        verify(render).renderPng(ZPL); // rendered from the same bytes, not a synthetic label
    }

    @Test
    void native_and_raster_are_declared_in_a_header_not_inferred_by_the_client() {
        when(artifacts.pkg(ORDER, 1)).thenReturn(Optional.of(nativePkg()));
        when(artifacts.zpl(ORDER, 1)).thenReturn(Optional.of(ZPL));
        assertEquals("native", controller.packageZpl(ORDER, 1).getHeaders().getFirst("X-Label-Source"));

        LabelPackage raster = LabelPackage.builder().orderNo(ORDER).sequenceNumber(2)
                .format("GIF").source("CARRIER_RASTER").build();
        byte[] gfa = "^XA^GFA,1,1,1,F0^FS^XZ".getBytes(StandardCharsets.ISO_8859_1);
        when(artifacts.pkg(ORDER, 2)).thenReturn(Optional.of(raster));
        when(artifacts.zpl(ORDER, 2)).thenReturn(Optional.of(gfa));
        assertEquals("raster", controller.packageZpl(ORDER, 2).getHeaders().getFirst("X-Label-Source"));
    }

    /** A raster package's preview is a render of its ^GFA too — never of the
     *  stored image by a second path that could disagree with Copy ZPL. */
    @Test
    void raster_preview_is_rendered_from_the_gfa_not_from_the_stored_image() {
        LabelPackage raster = LabelPackage.builder().orderNo(ORDER).sequenceNumber(1)
                .format("PDF").source("CARRIER_RASTER").build();
        byte[] gfa = "^XA^GFA,1,1,1,F0^FS^XZ".getBytes(StandardCharsets.ISO_8859_1);
        when(artifacts.pkg(ORDER, 1)).thenReturn(Optional.of(raster));
        when(artifacts.zpl(ORDER, 1)).thenReturn(Optional.of(gfa));
        when(render.renderPng(gfa)).thenReturn(PNG);

        ResponseEntity<byte[]> preview = controller.previewPng(ORDER, 1);

        assertSame(PNG, preview.getBody());
        assertEquals(LabelRenderService.etag(gfa), preview.getHeaders().getFirst(HttpHeaders.ETAG));
        assertEquals("raster", preview.getHeaders().getFirst("X-Label-Source"));
        verify(artifacts, never()).storedBytes(any());
    }

    @Test
    void pdf_is_the_printable_from_the_one_composition_used_by_print_too() {
        byte[] pdf = "%PDF-1.7 printable".getBytes();
        when(printable.pdf(ORDER, null)).thenReturn(Optional.of(pdf));

        ResponseEntity<byte[]> resp = controller.pdf(ORDER, null);

        assertEquals(200, resp.getStatusCode().value());
        assertSame(pdf, resp.getBody());
    }

    @Test
    void missing_label_is_an_honest_404_on_every_surface() {
        when(artifacts.pkg(ORDER, 9)).thenReturn(Optional.empty());
        when(artifacts.zpl(ORDER, 9)).thenReturn(Optional.empty());
        when(printable.pdf(ORDER, null)).thenReturn(Optional.empty());
        assertEquals(404, controller.previewPng(ORDER, 9).getStatusCode().value());
        assertEquals(404, controller.packageZpl(ORDER, 9).getStatusCode().value());
        assertEquals(404, controller.previewHead(ORDER, 9).getStatusCode().value());
        assertEquals(404, controller.pdf(ORDER, null).getStatusCode().value());
        verify(render, never()).renderPng(any());
    }
}
