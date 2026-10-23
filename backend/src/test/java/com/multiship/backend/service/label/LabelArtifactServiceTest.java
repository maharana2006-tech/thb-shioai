package com.multiship.backend.service.label;

import com.multiship.backend.dto.LabelArtifactDTO;
import com.multiship.backend.model.CarrierAccountRef;
import com.multiship.backend.model.LabelPackage;
import com.multiship.backend.model.OrderTracking;
import com.multiship.backend.repository.CarrierAccountRefRepository;
import com.multiship.backend.repository.LabelPackageRepository;
import com.multiship.backend.repository.OrderTrackingRepository;
import com.multiship.backend.service.LabelFormatConverter;
import com.multiship.backend.service.LabelImageZplService;
import com.multiship.backend.service.LabelMainPageCropper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 1 gate — the read side decides by the stored {@code format} column,
 * never by sniffing, and never substitutes a facsimile.
 */
class LabelArtifactServiceTest {

    private static final int ORDER = 900058;
    private static final String RAW_ZPL = "^XA^FO0,0^FDnative^FS^XZ";

    private LabelPackageRepository pkgRepo;
    private OrderTrackingRepository trackingRepo;
    private CarrierAccountRefRepository accountRepo;
    private LabelImageZplService imageZpl;
    private LabelFormatConverter converter;
    private LabelMainPageCropper cropper;
    private LabelArtifactService svc;

    @BeforeEach
    void setUp() {
        pkgRepo = mock(LabelPackageRepository.class);
        trackingRepo = mock(OrderTrackingRepository.class);
        accountRepo = mock(CarrierAccountRefRepository.class);
        imageZpl = mock(LabelImageZplService.class);
        converter = mock(LabelFormatConverter.class);
        cropper = mock(LabelMainPageCropper.class);
        svc = new LabelArtifactService(pkgRepo, trackingRepo, accountRepo, imageZpl, converter, cropper);
    }

    private static LabelPackage pkg(int seq, String stored, String format, String source) {
        return LabelPackage.builder().orderNo(ORDER).sequenceNumber(seq)
                .labelFilePath(stored).format(format).source(source).trackingNumber("T" + seq).build();
    }

    // ── zpl(): format column decides, no sniffing ───────────────────────────

    @Test
    void native_zpl_is_served_verbatim_and_never_rasterised() {
        when(pkgRepo.findByOrderNoAndSequenceNumber(ORDER, 1))
                .thenReturn(Optional.of(pkg(1, RAW_ZPL, "ZPL", "CARRIER_NATIVE")));

        byte[] out = svc.zpl(ORDER, 1).orElseThrow();

        assertEquals(RAW_ZPL, new String(out, StandardCharsets.ISO_8859_1));
        verify(imageZpl, never()).imageToZpl(any());
        verify(converter, never()).renderAll(any(), anyString());
    }

    @Test
    void raster_gif_is_wrapped_into_gfa_from_the_stored_image() {
        byte[] gif = "GIF89a-ups".getBytes();
        String b64 = Base64.getEncoder().encodeToString(gif);
        when(pkgRepo.findByOrderNoAndSequenceNumber(ORDER, 1))
                .thenReturn(Optional.of(pkg(1, b64, "GIF", "CARRIER_RASTER")));
        when(imageZpl.imageToZpl(gif)).thenReturn("^XA^GFA,1,1,1,F0^FS^XZ");

        String out = new String(svc.zpl(ORDER, 1).orElseThrow(), StandardCharsets.ISO_8859_1);

        assertTrue(out.contains("^GFA"));
        assertTrue(LabelArtifactService.isRaster(pkg(1, b64, "GIF", "CARRIER_RASTER")));
    }

    /** FedEx hands back a Letter page with the 4×6 label in one quadrant;
     *  the ZPL must be the cropped label, never the whole page shrunk. */
    @Test
    void raster_pdf_is_cropped_to_its_main_label_before_being_wrapped() {
        byte[] letter = "%PDF-1.4 letter".getBytes();
        byte[] cropped = "%PDF-1.4 4x6".getBytes();
        String b64 = Base64.getEncoder().encodeToString(letter);
        when(pkgRepo.findByOrderNoAndSequenceNumber(ORDER, 1))
                .thenReturn(Optional.of(pkg(1, b64, "PDF", "CARRIER_RASTER")));
        when(cropper.mainLabelOnly(letter)).thenReturn(cropped);
        when(converter.renderAll(cropped, "PDF"))
                .thenReturn(new LabelFormatConverter.RenderedFormats("^XA^GFA,...^XZ", null, null));

        assertTrue(new String(svc.zpl(ORDER, 1).orElseThrow()).contains("^GFA"));
        verify(converter, never()).renderAll(letter, "PDF");
    }

    @Test
    void unknown_format_yields_empty_never_a_facsimile() {
        when(pkgRepo.findByOrderNoAndSequenceNumber(ORDER, 1))
                .thenReturn(Optional.of(pkg(1, "https://expired.example/label", "UNKNOWN", "UNKNOWN")));
        assertTrue(svc.zpl(ORDER, 1).isEmpty());
        verify(imageZpl, never()).imageToZpl(any());
    }

    @Test
    void missing_package_is_empty() {
        when(pkgRepo.findByOrderNoAndSequenceNumber(ORDER, 3)).thenReturn(Optional.empty());
        assertTrue(svc.zpl(ORDER, 3).isEmpty());
        assertTrue(svc.zpl(ORDER, 0).isEmpty());
    }

    // ── zplAll(): spooling order ───────────────────────────────────────────

    @Test
    void all_packages_concatenate_in_sequence_with_a_newline() {
        when(pkgRepo.findByOrderNoOrderBySequenceNumberAsc(ORDER)).thenReturn(List.of(
                pkg(1, "^XA^FD1^FS^XZ", "ZPL", "CARRIER_NATIVE"),
                pkg(2, "^XA^FD2^FS^XZ", "ZPL", "CARRIER_NATIVE")));

        String out = new String(svc.zplAll(ORDER).orElseThrow(), StandardCharsets.ISO_8859_1);

        assertEquals("^XA^FD1^FS^XZ\n^XA^FD2^FS^XZ", out);
    }

    // ── describe(): everything from columns ────────────────────────────────

    @Test
    void describe_reads_state_format_source_and_shipped_account() {
        when(pkgRepo.findByOrderNoOrderBySequenceNumberAsc(ORDER)).thenReturn(List.of(
                pkg(1, RAW_ZPL, "ZPL", "CARRIER_NATIVE")));
        OrderTracking t = new OrderTracking();
        t.setAccountNumber("ACC-1");
        when(trackingRepo.findByOrderNo(ORDER)).thenReturn(Optional.of(t));
        CarrierAccountRef ref = new CarrierAccountRef();
        ref.setCarrierCode("UPS");
        ref.setEnvironment("SANDBOX");
        when(accountRepo.findFirstByAccountNumberIgnoreCaseOrderByUpdatedAtDesc("ACC-1")).thenReturn(Optional.of(ref));

        LabelArtifactDTO dto = svc.describe(ORDER).orElseThrow();

        assertEquals("GENERATED", dto.state());
        assertEquals("UPS", dto.carrier());
        assertEquals("SANDBOX", dto.environment());
        assertEquals(1, dto.packages().size());
        assertEquals("ZPL", dto.packages().get(0).format());
        assertEquals("CARRIER_NATIVE", dto.packages().get(0).source());
        assertEquals("T1", dto.packages().get(0).tracking());
    }

    @Test
    void describe_is_pending_with_tracking_but_no_packages_and_empty_with_neither() {
        when(pkgRepo.findByOrderNoOrderBySequenceNumberAsc(ORDER)).thenReturn(List.of());
        when(trackingRepo.findByOrderNo(ORDER)).thenReturn(Optional.of(new OrderTracking()));
        assertEquals("PENDING", svc.describe(ORDER).orElseThrow().state());

        when(trackingRepo.findByOrderNo(ORDER)).thenReturn(Optional.empty());
        assertFalse(svc.describe(ORDER).isPresent());
    }

    // ── decodeStored(): the only byte-level logic, and it is not a sniff ──

    @Test
    void decode_handles_raw_zpl_base64_and_rejects_urls() {
        assertArrayEquals(RAW_ZPL.getBytes(StandardCharsets.ISO_8859_1), LabelArtifactService.decodeStored(RAW_ZPL));
        // Stamps' native ZPL opens with a ^FX comment, not ^XA — still raw ZPL.
        String stamps = "^FX  ZPL generated by V1.0.0.0  ^FS\r\n^XA\r\n^PW812\r\n^XZ";
        assertArrayEquals(stamps.getBytes(StandardCharsets.ISO_8859_1), LabelArtifactService.decodeStored(stamps));
        byte[] pdf = "%PDF-x".getBytes();
        assertArrayEquals(pdf, LabelArtifactService.decodeStored(Base64.getEncoder().encodeToString(pdf)));
        assertNull(LabelArtifactService.decodeStored("https://carrier.example/label.pdf"));
        assertNull(LabelArtifactService.decodeStored("not base64!!"));
        assertNull(LabelArtifactService.decodeStored("  "));
    }
}
