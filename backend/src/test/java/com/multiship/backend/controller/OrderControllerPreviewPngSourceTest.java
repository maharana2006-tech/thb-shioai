package com.multiship.backend.controller;

import com.multiship.backend.service.LabelArtifactResolver;
import com.multiship.backend.service.LabelaryRenderer;
import com.multiship.backend.service.ZebrashRenderer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Label-preview orientation/engine fix — a ZPL carrier's preview PNG must
 * come from the SAME engine+geometry as /label/pdf (zebrash, un-rotated),
 * with Labelary only as an un-rotated fallback. Locks the
 * prod-critical invariant: when the zebrash binary is unavailable AND
 * Labelary is disabled (require-zebrash=true, labelary.enabled=false),
 * the preview path must RETHROW rather than route label content to
 * Labelary.
 */
class OrderControllerPreviewPngSourceTest {

    private LabelArtifactResolver resolver;
    private ZebrashRenderer zebrash;
    private LabelaryRenderer labelary;
    private OrderController controller;

    private static final byte[] ZPL = "^XA^FO0,0^A0N,30,30^FDhi^FS^XZ".getBytes();

    @BeforeEach
    void setUp() {
        resolver = mock(LabelArtifactResolver.class);
        zebrash = mock(ZebrashRenderer.class);
        labelary = mock(LabelaryRenderer.class);
        controller = new OrderController();
        ReflectionTestUtils.setField(controller, "labelArtifactResolver", resolver);
        ReflectionTestUtils.setField(controller, "zebrashRenderer", zebrash);
        ReflectionTestUtils.setField(controller, "labelaryRenderer", labelary);
    }

    private Optional<byte[]> realLabelPng(Integer pkg) {
        //noinspection unchecked
        return (Optional<byte[]>) ReflectionTestUtils.invokeMethod(
                controller, "realLabelPng", 42, pkg);
    }

    private byte[] renderPreferZebrash() {
        return ReflectionTestUtils.invokeMethod(controller, "renderPngPreferZebrash", (Object) ZPL);
    }

    @Test
    void zplCarrier_preview_renders_through_zebrash_not_labelary() {
        // A ZPL carrier's preview must go through zebrash (same un-rotated
        // engine+geometry as /label/pdf), never Labelary with its old 180° flip.
        byte[] rendered = {(byte) 0x89, 'P', 'N', 'G', 'Z'};
        when(resolver.resolveAsBytes(42, "ZPL", 2)).thenReturn(Optional.of(ZPL));
        when(zebrash.renderPng(ZPL)).thenReturn(rendered);

        assertArrayEquals(rendered, realLabelPng(2).orElseThrow());
        verify(labelary, never()).renderPng(any());
    }

    @Test
    void preferZebrash_uses_zebrash_when_available() {
        byte[] rendered = {1, 2, 3};
        when(zebrash.renderPng(ZPL)).thenReturn(rendered);
        assertSame(rendered, renderPreferZebrash());
        verify(labelary, never()).renderPng(any());
    }

    @Test
    void prod_invariant_rethrows_when_zebrash_down_and_labelary_disabled() {
        when(zebrash.renderPng(ZPL)).thenThrow(
                new ZebrashRenderer.RendererUnavailableException("binary missing"));
        when(labelary.isAvailable()).thenReturn(false); // prod: labelary.enabled=false

        assertThrows(ZebrashRenderer.RendererUnavailableException.class, this::renderPreferZebrash);
        verify(labelary, never()).renderPng(any()); // label content never leaves for Labelary
    }

    @Test
    void falls_back_to_labelary_only_when_zebrash_binary_absent() {
        byte[] fallback = {9, 9, 9};
        when(zebrash.renderPng(ZPL)).thenThrow(
                new ZebrashRenderer.RendererUnavailableException("binary missing"));
        when(labelary.isAvailable()).thenReturn(true);
        when(labelary.renderPng(ZPL)).thenReturn(fallback);

        assertArrayEquals(fallback, renderPreferZebrash());
    }
}
