package com.multiship.backend.service.label;

import com.multiship.backend.model.LabelPackage;
import com.multiship.backend.repository.CarrierAccountRefRepository;
import com.multiship.backend.repository.LabelPackageRepository;
import com.multiship.backend.repository.OrderTrackingRepository;
import com.multiship.backend.service.LabelFormatConverter;
import com.multiship.backend.service.LabelImageZplService;
import com.multiship.backend.service.LabelMainPageCropper;
import com.multiship.backend.service.PdfMerger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BUG-019 / BUG-024 — UPS labels created before the label-package change sit
 * in {@code label_package} with {@code format=UNKNOWN, source=UNKNOWN} while
 * {@code label_file_path} holds base64 of real {@code ^XA…^XZ} ZPL. Those
 * rows must still serve ZPL, preview and print (PDF) — the stored bytes
 * decide, not the stale column.
 */
class LabelLegacyUnknownFormatTest {

    private static final int ORDER = 934453;
    /** A UPS-shaped native label: ^DN is UPS noise the ZPL route strips. */
    private static final String UPS_ZPL = "\n^XA\r\n^LRN^MNY^DN\r\n^FO15,7^A0N,20,24^FVPOB-WEARHOUSE^FS^XZ";
    private static final String STORED_BASE64 = Base64.getEncoder()
            .encodeToString(UPS_ZPL.getBytes(StandardCharsets.ISO_8859_1));

    private LabelPackageRepository pkgRepo;
    private OrderTrackingRepository trackingRepo;
    private LabelImageZplService imageZpl;
    private LabelFormatConverter converter;
    private LabelRenderService render;
    private LabelArtifactService artifacts;
    private LabelPrintableService printable;
    private LabelPackage legacy;

    @BeforeEach
    void setUp() {
        pkgRepo = mock(LabelPackageRepository.class);
        trackingRepo = mock(OrderTrackingRepository.class);
        imageZpl = mock(LabelImageZplService.class);
        converter = mock(LabelFormatConverter.class);
        render = mock(LabelRenderService.class);
        artifacts = new LabelArtifactService(pkgRepo, trackingRepo, mock(CarrierAccountRefRepository.class),
                imageZpl, converter, mock(LabelMainPageCropper.class));
        printable = new LabelPrintableService(artifacts, render, mock(PdfMerger.class));
        legacy = LabelPackage.builder().orderNo(ORDER).sequenceNumber(1)
                .labelFilePath(STORED_BASE64).format("UNKNOWN").source("UNKNOWN")
                .trackingNumber("1ZXXXXXXXXXXXXXXXX").build();
        when(pkgRepo.findByOrderNoAndSequenceNumber(ORDER, 1)).thenReturn(Optional.of(legacy));
        when(pkgRepo.findByOrderNoOrderBySequenceNumberAsc(ORDER)).thenReturn(List.of(legacy));
        when(trackingRepo.findByOrderNo(ORDER)).thenReturn(Optional.empty());
    }

    @Test
    void unknown_row_with_stored_zpl_serves_the_zpl() {
        String zpl = new String(artifacts.zpl(ORDER, 1).orElseThrow(), StandardCharsets.ISO_8859_1);
        assertTrue(zpl.contains("^XA") && zpl.contains("POB-WEARHOUSE"), zpl);
        assertTrue(!zpl.contains("^DN"), "UPS ^DN is stripped like any native label");
        assertEquals(zpl, new String(artifacts.zplAll(ORDER).orElseThrow(), StandardCharsets.ISO_8859_1));
        verify(imageZpl, never()).imageToZpl(any());
        verify(converter, never()).renderAll(any(), any());
    }

    @Test
    void unknown_row_with_stored_zpl_prints_as_pdf_from_the_same_bytes() {
        byte[] page = {'%', 'P', 'D', 'F'};
        when(render.renderPdf(any())).thenReturn(page);

        Optional<byte[]> pdf = printable.pdf(ORDER, null);

        assertArrayEquals(page, pdf.orElseThrow());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<byte[]>> sent = ArgumentCaptor.forClass(List.class);
        verify(render).renderPdf(sent.capture());
        assertArrayEquals(artifacts.zpl(ORDER, 1).orElseThrow(), sent.getValue().get(0),
                "the PDF is a render of exactly what the ZPL route returns");
        verify(render, never()).renderRasterPdf(any(), any());
    }

    @Test
    void unknown_row_with_undecodable_bytes_stays_empty() {
        legacy.setLabelFilePath("https://expired.example/label");
        assertTrue(artifacts.zpl(ORDER, 1).isEmpty());
        assertTrue(printable.pdf(ORDER, null).isEmpty());
    }
}
