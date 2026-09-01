package com.multiship.backend.service;

import com.multiship.backend.model.OrderTracking;
import com.multiship.backend.repository.OrderTrackingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Sprint 52 PR B — LabelArtifactResolver unit tests. The resolver has
 * to make three decisions:
 *   1. Is there a stored artifact at all?
 *   2. In what format? (sniff magic bytes)
 *   3. Does that format match what the caller asked for?
 *
 * The URL-fetch branch isn't covered here — that path hits an HTTP
 * client and wants an integration test with a stubbed server. Base64
 * + raw-ZPL branches cover the two ways connectors stash bytes today.
 */
class LabelArtifactResolverTest {

    private OrderTrackingRepository orderTrackingRepository;
    /** PR #544 — new required constructor arg (per-package label lookup). */
    private com.multiship.backend.repository.LabelPackageRepository labelPackageRepository;
    private LabelArtifactResolver resolver;

    @BeforeEach
    void setUp() {
        orderTrackingRepository = mock(OrderTrackingRepository.class);
        labelPackageRepository = mock(com.multiship.backend.repository.LabelPackageRepository.class);
        resolver = new LabelArtifactResolver(orderTrackingRepository, labelPackageRepository);
    }

    // ─── No-stored-artifact branches (fall to facsimile) ───────────────

    @Test
    void nullOrderNo_returnsEmpty() {
        assertTrue(resolver.resolveAsBytes(null, "ZPL").isEmpty());
    }

    @Test
    void blankDesiredFormat_returnsEmpty() {
        assertTrue(resolver.resolveAsBytes(900007, "").isEmpty());
        assertTrue(resolver.resolveAsBytes(900007, null).isEmpty());
    }

    @Test
    void trackingRowMissing_returnsEmpty() {
        when(orderTrackingRepository.findByOrderNo(900007)).thenReturn(Optional.empty());
        assertTrue(resolver.resolveAsBytes(900007, "ZPL").isEmpty());
    }

    @Test
    void blankLabelFilePath_returnsEmpty() {
        OrderTracking t = new OrderTracking();
        t.setLabelFilePath("");
        when(orderTrackingRepository.findByOrderNo(900007)).thenReturn(Optional.of(t));
        assertTrue(resolver.resolveAsBytes(900007, "ZPL").isEmpty());
    }

    // ─── Raw ZPL branch (some carriers persist ^XA...^XZ verbatim) ─────

    @Test
    void rawZplStored_zplRequested_returnsBytesVerbatim() {
        // Order 900007-shaped scenario: carrier's real ZPL block is
        // sitting in label_file_path as plain text starting with ^XA.
        String rawZpl = "^XA^FO50,50^A0N,40,40^FDTest Label^FS^XZ";
        OrderTracking t = new OrderTracking();
        t.setLabelFilePath(rawZpl);
        when(orderTrackingRepository.findByOrderNo(900007)).thenReturn(Optional.of(t));

        Optional<byte[]> out = resolver.resolveAsBytes(900007, "ZPL");

        assertTrue(out.isPresent(), "raw ZPL must be returned when caller asks for ZPL");
        assertArrayEquals(rawZpl.getBytes(), out.get());
    }

    @Test
    void rawZplStored_pdfRequested_returnsEmpty_fallsToFacsimile() {
        // Caller asked for PDF but the stored artifact is ZPL — facsimile
        // fallback (not a wrong-format error).
        OrderTracking t = new OrderTracking();
        t.setLabelFilePath("^XA^FDsomething^XZ");
        when(orderTrackingRepository.findByOrderNo(900007)).thenReturn(Optional.of(t));

        assertTrue(resolver.resolveAsBytes(900007, "PDF").isEmpty());
    }

    // ─── Base64 branch (FedEx encodedLabel, UPS GraphicImage) ──────────

    @Test
    void base64EncodedPdf_pdfRequested_returnsDecodedBytes() {
        // FedEx returned a PDF in packageDocuments[0].encodedLabel; we
        // stashed the base64 string. Requesting PDF should decode +
        // return the raw PDF bytes so browsers render inline.
        byte[] pdfBytes = new byte[]{'%', 'P', 'D', 'F', '-', '1', '.', '4', ' ', ' ', ' ', ' '};
        String stored = Base64.getEncoder().encodeToString(pdfBytes);
        OrderTracking t = new OrderTracking();
        t.setLabelFilePath(stored);
        when(orderTrackingRepository.findByOrderNo(900007)).thenReturn(Optional.of(t));

        Optional<byte[]> out = resolver.resolveAsBytes(900007, "PDF");

        assertTrue(out.isPresent(), "base64 PDF must decode + return when caller asks for PDF");
        assertEquals('%', (char) out.get()[0]);
        assertEquals('P', (char) out.get()[1]);
    }

    @Test
    void base64EncodedZpl_zplRequested_returnsDecodedBytes() {
        // Some carriers return ZPL as base64 (label_image_format=ZPL on
        // UPS). Base64-decoded bytes start with ^XA → detected as ZPL.
        byte[] zplBytes = "^XA^FDsomething^XZ".getBytes();
        String stored = Base64.getEncoder().encodeToString(zplBytes);
        OrderTracking t = new OrderTracking();
        t.setLabelFilePath(stored);
        when(orderTrackingRepository.findByOrderNo(900007)).thenReturn(Optional.of(t));

        Optional<byte[]> out = resolver.resolveAsBytes(900007, "ZPL");

        assertTrue(out.isPresent(), "base64 ZPL must decode + return when caller asks for ZPL");
        assertEquals('^', (char) out.get()[0]);
    }

    @Test
    void base64EncodedPdf_zplRequested_returnsEmpty_mismatchFallsToFacsimile() {
        byte[] pdfBytes = "%PDF-1.4 stuff".getBytes();
        String stored = Base64.getEncoder().encodeToString(pdfBytes);
        OrderTracking t = new OrderTracking();
        t.setLabelFilePath(stored);
        when(orderTrackingRepository.findByOrderNo(900007)).thenReturn(Optional.of(t));

        assertTrue(resolver.resolveAsBytes(900007, "ZPL").isEmpty(),
                "PDF artifact must not be returned when caller asks for ZPL");
    }

    // ─── Format-detection failure (unknown format) ─────────────────────

    @Test
    void base64EncodedUnknownFormat_returnsEmpty() {
        // Neither ZPL nor PDF magic — could be PNG, GIF, HTMLPLU, ...
        // We don't serve those as passthrough today; fall to facsimile.
        byte[] pngBytes = new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n'};
        String stored = Base64.getEncoder().encodeToString(pngBytes);
        OrderTracking t = new OrderTracking();
        t.setLabelFilePath(stored);
        when(orderTrackingRepository.findByOrderNo(900007)).thenReturn(Optional.of(t));

        assertTrue(resolver.resolveAsBytes(900007, "ZPL").isEmpty());
        assertTrue(resolver.resolveAsBytes(900007, "PDF").isEmpty());
    }

    // ─── Case-insensitivity on the desired-format arg ──────────────────

    @Test
    void desiredFormat_caseInsensitive() {
        String rawZpl = "^XA^XZ";
        OrderTracking t = new OrderTracking();
        t.setLabelFilePath(rawZpl);
        when(orderTrackingRepository.findByOrderNo(900007)).thenReturn(Optional.of(t));

        assertTrue(resolver.resolveAsBytes(900007, "zpl").isPresent());
        assertTrue(resolver.resolveAsBytes(900007, "  ZPL  ").isPresent());
        assertTrue(resolver.resolveAsBytes(900007, "Zpl").isPresent());
    }
}
