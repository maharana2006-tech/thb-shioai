package com.multiship.backend.service.dtc;

import com.multiship.backend.model.DtcGenerationJob;
import com.multiship.backend.model.DtcOrder;
import com.multiship.backend.repository.DtcGenerationJobRepository;
import com.multiship.backend.repository.DtcOrderRepository;
import com.multiship.backend.service.CarrierService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A backend that dies while the carrier is answering leaves the line IN_FLIGHT and
 * nothing else (the purchase is one transaction). When the requeued run reaches that
 * line it must not buy again — the carrier may already have charged — and must tell
 * the operator what to check instead.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DtcInterruptedPurchaseTest {

    @Mock DtcGenerationJobRepository jobRepository;
    @Mock DtcOrderRepository dtcOrderRepository;
    @Mock CarrierService carrierService;

    @InjectMocks DtcLabelGenerationService service;

    private static final BigDecimal BATCH = new BigDecimal("245");

    @Test
    void aLineLeftMidPurchaseIsHandedToTheOperatorNotBoughtAgain() {
        DtcGenerationJob job = new DtcGenerationJob();
        job.setId(7L);
        job.setTenantId("ARHDEV");
        job.setBatchId(BATCH);

        DtcOrder line = new DtcOrder();
        line.setId(11L);
        line.setTenantId("ARHDEV");
        line.setBatchId(BATCH);
        line.setToteNumber("T-004");
        line.setGeneratedStatus(DtcLabelGenerationService.STATUS_IN_FLIGHT);

        when(jobRepository.findById(7L)).thenReturn(Optional.of(job));
        when(dtcOrderRepository.findByTenantIdAndBatchIdOrderByIdAsc("ARHDEV", BATCH)).thenReturn(List.of(line));
        when(dtcOrderRepository.findById(11L)).thenReturn(Optional.of(line));

        service.executeJob(7L);

        verifyNoInteractions(carrierService);
        ArgumentCaptor<DtcOrder> saved = ArgumentCaptor.forClass(DtcOrder.class);
        verify(dtcOrderRepository, atLeastOnce()).save(saved.capture());
        DtcOrder stamped = saved.getValue();
        assertEquals("FAILED", stamped.getGeneratedStatus());
        assertTrue(stamped.getGeneratedMessage().contains("carrier may have charged"), stamped.getGeneratedMessage());
        assertTrue(stamped.getGeneratedMessage().contains("batch 245 / tote T-004"), stamped.getGeneratedMessage());
        assertTrue(stamped.getGeneratedMessage().length() <= 255);
        assertEquals(1, job.getFailedCount());
        assertEquals(DtcGenerationJob.DONE, job.getStatus());
    }
}
