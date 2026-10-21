package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.OrderWithLinesDTO;
import com.multiship.backend.service.LabelArtifactResolver;
import com.multiship.backend.service.LabelArtifactResolver.ImageArtifact;
import com.multiship.backend.service.LabelFormatConverter;
import com.multiship.backend.service.LabelFormatConverter.RenderedFormats;
import com.multiship.backend.service.LabelImagePdfService;
import com.multiship.backend.service.LabelImageZplService;
import com.multiship.backend.service.LabelaryRenderer;
import com.multiship.backend.service.OrderService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The real client fix: /label/zpl and /label/preview.png must serve the
 * carrier's REAL label for PDF carriers (USPS/Stamps) and GIF carriers (UPS),
 * not a synthesized FedEx-layout "DO NOT SHIP" facsimile — and an ungenerated
 * order gets an honest 404, not a fake. Focused controller test; every
 * collaborator is mocked (no zebrash binary, no DB).
 */
class OrderControllerLabelArtifactTest {

    private static final int ORDER = 900060;

    private OrderController controller;
    private LabelArtifactResolver resolver;
    private LabelImageZplService imageZpl;
    private LabelFormatConverter formatConverter;
    private LabelaryRenderer labelary;
    private LabelImagePdfService imagePdf;
    private OrderService orderService;

    @BeforeEach
    void setUp() {
        controller = new OrderController();
        resolver = mock(LabelArtifactResolver.class);
        imageZpl = mock(LabelImageZplService.class);
        formatConverter = mock(LabelFormatConverter.class);
        labelary = mock(LabelaryRenderer.class);
        imagePdf = mock(LabelImagePdfService.class);
        orderService = mock(OrderService.class);
        ReflectionTestUtils.setField(controller, "labelArtifactResolver", resolver);
        ReflectionTestUtils.setField(controller, "labelImageZplService", imageZpl);
        ReflectionTestUtils.setField(controller, "labelFormatConverter", formatConverter);
        ReflectionTestUtils.setField(controller, "labelaryRenderer", labelary);
        ReflectionTestUtils.setField(controller, "labelImagePdfService", imagePdf);
        ReflectionTestUtils.setField(controller, "orderService", orderService);
        ReflectionTestUtils.setField(controller, "renderCarrierZplEnabled", true);
        // Single-package order shape for every peek.
        ApiResponse<OrderWithLinesDTO> resp = ApiResponse.<OrderWithLinesDTO>builder()
                .status("SUCCESS").code(200)
                .data(OrderWithLinesDTO.builder().packageCount(1).build())
                .build();
        when(orderService.getOrderWithLines(ORDER)).thenReturn(resp);
    }

    private void noRawZpl() {
        when(resolver.resolveAsBytes(eq(ORDER), eq("ZPL"), nullable(Integer.class)))
                .thenReturn(Optional.empty());
    }

    private void noImage() {
        when(resolver.resolveImage(eq(ORDER), nullable(Integer.class))).thenReturn(Optional.empty());
    }

    private void noPdf() {
        when(resolver.resolveAsBytes(eq(ORDER), eq("PDF"), nullable(Integer.class)))
                .thenReturn(Optional.empty());
    }

    // ── PRIMARY: PDF carrier /label/zpl is the real label, not a facsimile ──

    @Test
    void labelZpl_pdfCarrier_returnsGfaOfRealLabel_notFacsimile() {
        noRawZpl();
        noImage();
        byte[] pdf = "%PDF-1.4 real stamps label".getBytes();
        when(resolver.resolveAsBytes(eq(ORDER), eq("PDF"), nullable(Integer.class)))
                .thenReturn(Optional.of(pdf));
        when(formatConverter.renderAll(pdf, "PDF"))
                .thenReturn(new RenderedFormats("^XA^GFA,100,100,10,:real-label^FS^XZ", null, null));

        ResponseEntity<String> resp = controller.getLabelZpl(ORDER, null);

        assertEquals(200, resp.getStatusCode().value());
        assertTrue(resp.getBody().contains("^GFA"), "serves the real label as ^GFA ZPL");
        assertFalse(resp.getBody().contains("DO NOT SHIP"), "must NOT be the facsimile");
    }

    // ── UPS (GIF) path unchanged: image → ^GFA ──

    @Test
    void labelZpl_gifCarrier_stillReturnsImageZpl() {
        noRawZpl();
        noPdf();
        byte[] gif = "GIF89a-ups-label".getBytes();
        when(resolver.resolveImage(eq(ORDER), nullable(Integer.class)))
                .thenReturn(Optional.of(new ImageArtifact(gif, "image/gif")));
        when(imageZpl.imageToZpl(gif)).thenReturn("^XA^GFA,ups^FS^XZ");

        ResponseEntity<String> resp = controller.getLabelZpl(ORDER, null);

        assertEquals(200, resp.getStatusCode().value());
        assertTrue(resp.getBody().contains("^GFA"));
    }

    // ── Ungenerated order: honest 404, never a fake ──

    @Test
    void labelZpl_ungenerated_returns404_notFacsimile() {
        noRawZpl();
        noImage();
        noPdf();

        ResponseEntity<String> resp = controller.getLabelZpl(ORDER, null);

        assertEquals(404, resp.getStatusCode().value());
        assertTrue(resp.getBody().contains("No label has been generated"));
    }

    @Test
    void labelPdf_ungenerated_returns404_notFacsimile() {
        noRawZpl();
        noImage();
        noPdf();

        ResponseEntity<byte[]> resp = controller.getLabelPdf(ORDER, null, false);

        assertEquals(404, resp.getStatusCode().value());
    }

    // ── TERTIARY: preview.png serves a real PNG for PDF/GIF carriers ──

    @Test
    void previewPng_pdfCarrier_returnsRealPng() {
        noRawZpl();
        noImage();
        byte[] pdf = "%PDF-1.4 real".getBytes();
        byte[] png = pngBytes();
        when(resolver.resolveAsBytes(eq(ORDER), eq("PDF"), nullable(Integer.class)))
                .thenReturn(Optional.of(pdf));
        when(formatConverter.pdfFirstPageToPng(pdf)).thenReturn(png);

        ResponseEntity<byte[]> resp = controller.getLabelPreviewPng(ORDER, null, getReq());

        assertEquals(200, resp.getStatusCode().value());
        assertEquals(png, resp.getBody());
    }

    @Test
    void previewPng_gifCarrier_returnsRealPng() {
        noRawZpl();
        noPdf();
        byte[] gif = "GIF89a-ups".getBytes();
        byte[] wrappedPdf = "%PDF-from-gif".getBytes();
        byte[] png = pngBytes();
        when(resolver.resolveImage(eq(ORDER), nullable(Integer.class)))
                .thenReturn(Optional.of(new ImageArtifact(gif, "image/gif")));
        when(imagePdf.imagesToPdf(any())).thenReturn(wrappedPdf);
        when(formatConverter.pdfFirstPageToPng(wrappedPdf)).thenReturn(png);

        ResponseEntity<byte[]> resp = controller.getLabelPreviewPng(ORDER, null, getReq());

        assertEquals(200, resp.getStatusCode().value());
        assertEquals(png, resp.getBody());
    }

    @Test
    void previewPng_ungenerated_returns404() {
        noRawZpl();
        noImage();
        noPdf();

        ResponseEntity<byte[]> resp = controller.getLabelPreviewPng(ORDER, null, getReq());

        assertEquals(404, resp.getStatusCode().value());
    }

    private static HttpServletRequest getReq() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getMethod()).thenReturn("GET");
        return req;
    }

    /** Minimal valid-magic PNG stand-in (content irrelevant to the routing). */
    private static byte[] pngBytes() {
        return new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
    }
}
