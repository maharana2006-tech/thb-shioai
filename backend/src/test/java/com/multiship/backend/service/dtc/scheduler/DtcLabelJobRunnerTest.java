package com.multiship.backend.service.dtc.scheduler;

import com.multiship.backend.dto.DtcBatchKey;
import com.multiship.backend.model.DtcGenerationJob;
import com.multiship.backend.repository.DtcOrderRepository;
import com.multiship.backend.service.dtc.DtcLabelGenerationService;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DtcLabelJobRunnerTest {

    private final DtcOrderRepository repo = mock(DtcOrderRepository.class);
    private final DtcLabelGenerationService labels = mock(DtcLabelGenerationService.class);
    private final DtcLabelJobRunner runner = new DtcLabelJobRunner(repo, labels);

    private static DtcBatchKey batch(String tenant, int id) {
        return new DtcBatchKey(tenant, BigDecimal.valueOf(id));
    }

    @Test
    void queuesEachBatchAndCountsAlreadyActive() {
        when(repo.findBatchesAwaitingLabels(any(), anyBoolean(), any(), any()))
                .thenReturn(List.of(batch("ACME", 1), batch("ACME", 2)));
        when(labels.enqueue("ACME", BigDecimal.valueOf(1), DtcLabelJobRunner.REQUESTED_BY))
                .thenReturn(Optional.of(new DtcGenerationJob()));
        when(labels.enqueue("ACME", BigDecimal.valueOf(2), DtcLabelJobRunner.REQUESTED_BY))
                .thenReturn(Optional.empty());

        DtcSchedulerJobRunner.Outcome out = runner.run(Map.of());

        assertEquals(DtcSchedulerEngine.SUCCESS, out.status());
        assertTrue(out.message().startsWith("Queued 1 batch(es); 1 already queued or running"), out.message());
    }

    @Test
    @SuppressWarnings("unchecked")
    void allTenantsWhenListEmptyElseFiltersByTenant() {
        when(repo.findBatchesAwaitingLabels(any(), anyBoolean(), any(), any())).thenReturn(List.of());

        runner.run(Map.of("tenants", List.of()));
        verify(repo).findBatchesAwaitingLabels(any(), eq(true), (Collection<String>) argThat(c -> !((Collection<?>) c).isEmpty()), any());

        runner.run(Map.of("tenants", List.of("ACME", " ", "ACME")));
        verify(repo).findBatchesAwaitingLabels(any(), eq(false), eq(List.of("ACME")), any());
    }

    @Test
    void capsBatchesAndSaysSo() {
        when(repo.findBatchesAwaitingLabels(any(), anyBoolean(), any(), any()))
                .thenReturn(List.of(batch("A", 1), batch("A", 2)));
        when(labels.enqueue(any(), any(), any())).thenReturn(Optional.of(new DtcGenerationJob()));

        DtcSchedulerJobRunner.Outcome out = runner.run(Map.of("maxBatchesPerRun", 2));

        verify(repo).findBatchesAwaitingLabels(any(), anyBoolean(), any(), argThat((Pageable p) -> p.getPageSize() == 2));
        assertTrue(out.message().contains("2-batch cap"), out.message());
    }

    @Test
    void nothingWaiting() {
        when(repo.findBatchesAwaitingLabels(any(), anyBoolean(), any(), any())).thenReturn(List.of());
        DtcSchedulerJobRunner.Outcome out = runner.run(Map.of("lookbackHours", 6));
        assertEquals(DtcSchedulerEngine.SUCCESS, out.status());
        assertTrue(out.message().contains("last 6 h"), out.message());
        verifyNoInteractions(labels);
    }
}
