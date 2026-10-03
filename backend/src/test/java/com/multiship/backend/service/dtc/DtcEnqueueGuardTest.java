package com.multiship.backend.service.dtc;

import com.multiship.backend.model.DtcGenerationJob;
import com.multiship.backend.repository.DtcGenerationJobRepository;
import com.multiship.backend.repository.DtcOrderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Automatic label must never queue two runs for one batch — two runs label every
 * new line twice and the carrier bills both. The exists-check is the fast path;
 * the V128 unique index is the guard when two clicks race past it.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DtcEnqueueGuardTest {

    @Mock DtcGenerationJobRepository jobRepository;
    @Mock DtcOrderRepository dtcOrderRepository;

    @InjectMocks DtcLabelGenerationService service;

    private static final BigDecimal BATCH = new BigDecimal("245");

    @Test
    void queuesAJobWhenNoneIsActive() {
        when(jobRepository.existsByTenantIdAndBatchIdAndStatusIn(eq("ARHDEV"), eq(BATCH), anyList())).thenReturn(false);
        when(dtcOrderRepository.findByTenantIdAndBatchId(eq("ARHDEV"), eq(BATCH), any())).thenReturn(Page.empty());
        when(jobRepository.saveAndFlush(any(DtcGenerationJob.class))).thenAnswer(inv -> inv.getArgument(0));

        var job = service.enqueue("ARHDEV", BATCH, "alice");

        assertTrue(job.isPresent());
        assertEquals(DtcGenerationJob.QUEUED, job.get().getStatus());
    }

    @Test
    void refusesWhenAJobIsAlreadyActive() {
        when(jobRepository.existsByTenantIdAndBatchIdAndStatusIn(eq("ARHDEV"), eq(BATCH), anyList())).thenReturn(true);

        assertTrue(service.enqueue("ARHDEV", BATCH, "alice").isEmpty());
        verify(jobRepository, never()).saveAndFlush(any());
    }

    @Test
    void aSecondClickThatRacesPastTheCheckIsRefusedByTheIndex() {
        // Both requests saw "nothing active"; the database keeps only the first.
        when(jobRepository.existsByTenantIdAndBatchIdAndStatusIn(eq("ARHDEV"), eq(BATCH), anyList())).thenReturn(false);
        when(dtcOrderRepository.findByTenantIdAndBatchId(eq("ARHDEV"), eq(BATCH), any())).thenReturn(Page.empty());
        when(jobRepository.saveAndFlush(any(DtcGenerationJob.class)))
                .thenThrow(new DataIntegrityViolationException("uq_dtc_generation_job_active_batch"));

        assertTrue(service.enqueue("ARHDEV", BATCH, "bob").isEmpty());
    }
}
