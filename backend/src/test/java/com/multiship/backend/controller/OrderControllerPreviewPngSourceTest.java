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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Label-preview orientation/engine fix — preview PNG must come from the
 * SAME engine+geometry as /label/pdf (zebrash pngData column, else zebrash
 * render), with Labelary only as an un-rotated fallback. Locks the
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

    private Optional<byte[]> resolvePreview(Integer pkg) {
        //noinspection unchecked
        return (Optional<byte[]>) ReflectionTestUtils.invokeMethod(
                controller, "resolvePreviewPng", 42, pkg);
    }

    private byte[] renderPreferZebrash() {
        return ReflectionTestUtils.invokeMethod(controller, "renderPngPreferZebrash", (Object) ZPL);
    }

    @Test
    void pngColumn_served_verbatim_without_any_render() {
        byte[] col = {(byte) 0x89, 'P', 'N', 'G'};
        when(resolver.resolveAsBytes(42, "PNG", 2)).thenReturn(Optional.of(col));

        Optional<byte[]> out = resolvePreview(2);

        assertArrayEquals(col, out.orElseThrow());
        verify(resolver, never()).resolveAsBytes(eq(42), eq("ZPL"), any());
        verify(zebrash, never()).renderPng(any());
        verify(labelary, never()).renderPng(any());
    }

    @Test
    void noPngColumn_renders_zpl_through_zebrash() {
        byte[] rendered = {(byte) 0x89, 'P', 'N', 'G', 'Z'};
        when(resolver.resolveAsBytes(42, "PNG", 2)).thenReturn(Optional.empty());
        when(resolver.resolveAsBytes(42, "ZPL", 2)).thenReturn(Optional.of(ZPL));
        when(zebrash.renderPng(ZPL)).thenReturn(rendered);

        assertArrayEquals(rendered, resolvePreview(2).orElseThrow());
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
