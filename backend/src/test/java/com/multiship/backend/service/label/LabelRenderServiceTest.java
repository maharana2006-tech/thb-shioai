package com.multiship.backend.service.label;

import com.multiship.backend.service.LabelImagePdfService;
import com.multiship.backend.service.LabelMainPageCropper;
import com.multiship.backend.service.LabelaryRenderer;
import com.multiship.backend.service.ZebrashPdfService;
import com.multiship.backend.service.ZebrashRenderer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 1/3 gate — the one render pipeline. Locks the engine order, the
 * production invariant (no off-host fallback when Labelary is disabled),
 * the raster printable routes (PDF cropped to its main label), and the
 * ETag contract.
 */
class LabelRenderServiceTest {

    private ZebrashRenderer zebrash;
    private LabelaryRenderer labelary;
    private ZebrashPdfService zebrashPdf;
    private LabelImagePdfService imagePdf;
    private LabelMainPageCropper cropper;
    private LabelRenderService svc;

    private static final byte[] ZPL = "^XA^FO0,0^A0N,30,30^FDhi^FS^XZ".getBytes();
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G'};

    @BeforeEach
    void setUp() {
        zebrash = mock(ZebrashRenderer.class);
        labelary = mock(LabelaryRenderer.class);
        zebrashPdf = mock(ZebrashPdfService.class);
        imagePdf = mock(LabelImagePdfService.class);
        cropper = mock(LabelMainPageCropper.class);
        svc = new LabelRenderService(zebrash, labelary, zebrashPdf, imagePdf, cropper);
    }

    @Test
    void png_uses_zebrash_and_never_labelary_when_binary_present() {
        when(zebrash.renderPng(ZPL)).thenReturn(PNG);
        assertSame(PNG, svc.renderPng(ZPL));
        verify(labelary, never()).renderPng(any());
    }

    @Test
    void png_falls_to_labelary_only_when_binary_absent_and_labelary_enabled() {
        when(zebrash.renderPng(ZPL)).thenThrow(new ZebrashRenderer.RendererUnavailableException("no binary"));
        when(labelary.isAvailable()).thenReturn(true);
        when(labelary.renderPng(ZPL)).thenReturn(PNG);
        assertSame(PNG, svc.renderPng(ZPL));
    }

    @Test
    void prod_invariant_rethrows_when_binary_absent_and_labelary_disabled() {
        when(zebrash.renderPng(ZPL)).thenThrow(new ZebrashRenderer.RendererUnavailableException("no binary"));
        when(labelary.isAvailable()).thenReturn(false);
        assertThrows(ZebrashRenderer.RendererUnavailableException.class, () -> svc.renderPng(ZPL));
        verify(labelary, never()).renderPng(any()); // label content never leaves the host
    }

    @Test
    void parse_failures_propagate_rather_than_routing_to_labelary() {
        when(zebrash.renderPng(ZPL)).thenThrow(new ZebrashRenderer.ZplParseException("bad zpl"));
        when(labelary.isAvailable()).thenReturn(true);
        assertThrows(ZebrashRenderer.ZplParseException.class, () -> svc.renderPng(ZPL));
        verify(labelary, never()).renderPng(any());
    }

    @Test
    void pdf_renders_all_labels_through_zebrash_in_one_batch() {
        byte[] pdf = "%PDF-1.7".getBytes();
        List<byte[]> zpls = List.of(ZPL, ZPL);
        when(zebrashPdf.renderZplsToPdf(zpls)).thenReturn(pdf);
        assertSame(pdf, svc.renderPdf(zpls));
    }

    @Test
    void pdf_rejects_empty_input() {
        assertThrows(IllegalArgumentException.class, () -> svc.renderPdf(List.of()));
    }

    @Test
    void raster_pdf_is_cropped_to_its_main_label_for_the_printable() {
        byte[] letter = "%PDF-1.4 letter-size".getBytes();
        byte[] cropped = "%PDF-1.4 4x6".getBytes();
        when(cropper.mainLabelOnly(letter)).thenReturn(cropped);

        assertSame(cropped, svc.renderRasterPdf(letter, "PDF"), "printable is the cropped 4x6");
        verify(imagePdf, never()).imagesToPdf(any());
    }

    @Test
    void raster_image_goes_through_imagesToPdf_so_ups_landscape_is_turned_upright() {
        byte[] gif = "GIF89a".getBytes();
        byte[] wrapped = "%PDF-from-gif".getBytes();
        when(imagePdf.imagesToPdf(List.of(gif))).thenReturn(wrapped);

        assertSame(wrapped, svc.renderRasterPdf(gif, "GIF"));
        verify(cropper, never()).mainLabelOnly(any());
    }

    @Test
    void etag_is_a_quoted_sha256_of_the_exact_bytes() {
        String a = LabelRenderService.etag(ZPL);
        assertTrue(a.startsWith("\"") && a.endsWith("\""), "strong ETag is quoted");
        assertEquals(66, a.length(), "64 hex chars + 2 quotes");
        assertEquals(a, LabelRenderService.etag(ZPL.clone()), "deterministic");
        assertNotEquals(a, LabelRenderService.etag("^XA^XZ".getBytes()), "different bytes, different tag");
    }
}
