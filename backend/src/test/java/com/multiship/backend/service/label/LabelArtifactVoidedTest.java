package com.multiship.backend.service.label;

import com.multiship.backend.dto.LabelArtifactDTO;
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

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BUG-049 (QA 2026-10-23) — pre-fix the Label API reported
 * {@code state: GENERATED} on a voided order and the download endpoints
 * served the stored PDF/ZPL as if the label were live. QA observed:
 * voided FedEx order #934476 → {@code GET /labels/934476/pdf} returned
 * 200 + 23 KB; {@code GET /labels/934476} reported {@code state:
 * GENERATED}; the FE Label / ZPL buttons were enabled on an obviously
 * voided row.
 *
 * <p>Rule (operator decision 2026-10-24): keep the stored bytes for
 * audit, but the Label API reports {@code state: VOIDED}, every
 * download/print endpoint short-circuits to Optional.empty() (controller
 * → 404), and the FE hides the Download / Print buttons. Reissue flips
 * the status back to GENERATED and the artifacts resume.
 */
class LabelArtifactVoidedTest {

    private static final int ORDER = 934476;

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

    private static OrderTracking voided() {
        OrderTracking t = new OrderTracking();
        t.setOrderNo(ORDER);
        t.setStatus("VOIDED");
        t.setTrackingNumber("794896808552");
        return t;
    }

    private static OrderTracking generated() {
        OrderTracking t = new OrderTracking();
        t.setOrderNo(ORDER);
        t.setStatus("GENERATED");
        t.setTrackingNumber("794896808552");
        return t;
    }

    private static LabelPackage pkg(int seq, String rawZpl) {
        return LabelPackage.builder()
                .orderNo(ORDER).sequenceNumber(seq)
                .labelFilePath(rawZpl)
                .format("ZPL").source("CARRIER_NATIVE")
                .trackingNumber("T" + seq).build();
    }

    // ===== state flip =====

    @Test
    void describe_voidedTracking_reportsStateVoided() {
        when(trackingRepo.findByOrderNo(ORDER)).thenReturn(Optional.of(voided()));
        when(pkgRepo.findByOrderNoOrderBySequenceNumberAsc(ORDER))
                .thenReturn(List.of(pkg(1, "^XA^FDraw^FS^XZ")));

        LabelArtifactDTO dto = svc.describe(ORDER).orElseThrow();
        assertEquals("VOIDED", dto.state(),
                "voided tracking must report state=VOIDED so the FE hides Download / Print");
    }

    @Test
    void describe_liveTracking_reportsStateGenerated() {
        when(trackingRepo.findByOrderNo(ORDER)).thenReturn(Optional.of(generated()));
        when(pkgRepo.findByOrderNoOrderBySequenceNumberAsc(ORDER))
                .thenReturn(List.of(pkg(1, "^XA^FDraw^FS^XZ")));

        LabelArtifactDTO dto = svc.describe(ORDER).orElseThrow();
        assertEquals("GENERATED", dto.state(), "unchanged pre-fix behaviour on a live label");
    }

    @Test
    void describe_noTracking_fallsBackToPackagePresence() {
        // No tracking row at all → the state derivation reads from packages
        // only. Keeps the pre-fix PENDING/GENERATED behaviour for API
        // consumers that have never had tracking.
        when(trackingRepo.findByOrderNo(ORDER)).thenReturn(Optional.empty());
        when(pkgRepo.findByOrderNoOrderBySequenceNumberAsc(ORDER))
                .thenReturn(List.of(pkg(1, "^XA^FDraw^FS^XZ")));

        LabelArtifactDTO dto = svc.describe(ORDER).orElseThrow();
        assertEquals("GENERATED", dto.state());
    }

    // ===== isVoided helper =====

    @Test
    void isVoided_trueForVoidedTracking() {
        when(trackingRepo.findByOrderNo(ORDER)).thenReturn(Optional.of(voided()));
        assertTrue(svc.isVoided(ORDER));
    }

    @Test
    void isVoided_falseForLiveTracking() {
        when(trackingRepo.findByOrderNo(ORDER)).thenReturn(Optional.of(generated()));
        assertFalse(svc.isVoided(ORDER));
    }

    @Test
    void isVoided_falseForMissingTracking() {
        when(trackingRepo.findByOrderNo(ORDER)).thenReturn(Optional.empty());
        assertFalse(svc.isVoided(ORDER));
    }

    @Test
    void isVoided_falseForNullInput() {
        assertFalse(svc.isVoided(null));
    }

    // ===== download short-circuits =====

    @Test
    void zpl_shortCircuitsEmptyWhenVoided() {
        when(trackingRepo.findByOrderNo(ORDER)).thenReturn(Optional.of(voided()));

        assertTrue(svc.zpl(ORDER, 1).isEmpty(),
                "voided order's single-package ZPL must not surface — stored bytes are kept for audit, the download is off");
        // No package lookup — we short-circuited before touching the pkg repo.
        verify(pkgRepo, never()).findByOrderNoAndSequenceNumber(eq(ORDER), any());
    }

    @Test
    void zplAll_shortCircuitsEmptyWhenVoided() {
        when(trackingRepo.findByOrderNo(ORDER)).thenReturn(Optional.of(voided()));
        assertTrue(svc.zplAll(ORDER).isEmpty());
        verify(pkgRepo, never()).findByOrderNoOrderBySequenceNumberAsc(ORDER);
    }

    @Test
    void pkg_shortCircuitsEmptyWhenVoided() {
        when(trackingRepo.findByOrderNo(ORDER)).thenReturn(Optional.of(voided()));
        assertTrue(svc.pkg(ORDER, 1).isEmpty());
        verify(pkgRepo, never()).findByOrderNoAndSequenceNumber(eq(ORDER), any());
    }

    @Test
    void packages_shortCircuitsEmptyWhenVoided() {
        when(trackingRepo.findByOrderNo(ORDER)).thenReturn(Optional.of(voided()));
        assertTrue(svc.packages(ORDER).isEmpty());
        verify(pkgRepo, never()).findByOrderNoOrderBySequenceNumberAsc(ORDER);
    }

    // ===== reissue path =====

    @Test
    void reissueFlipsStatus_downloadsResume() {
        // Simulate: void then reissue — reissue flips tracking.status back
        // to GENERATED (CarrierServiceImpl#generateLabel line 3353). The
        // short-circuit must honour the new status immediately.
        when(trackingRepo.findByOrderNo(ORDER)).thenReturn(Optional.of(generated()));
        when(pkgRepo.findByOrderNoOrderBySequenceNumberAsc(ORDER))
                .thenReturn(List.of(pkg(1, "^XA^FDreissued^FS^XZ")));
        when(pkgRepo.findByOrderNoAndSequenceNumber(ORDER, 1))
                .thenReturn(Optional.of(pkg(1, "^XA^FDreissued^FS^XZ")));

        assertFalse(svc.isVoided(ORDER));
        assertTrue(svc.zpl(ORDER, 1).isPresent(), "post-reissue ZPL must flow again");
        LabelArtifactDTO dto = svc.describe(ORDER).orElseThrow();
        assertEquals("GENERATED", dto.state());
    }
}
