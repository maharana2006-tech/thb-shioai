package com.multiship.backend.service.externalsystems.writeback;

import com.multiship.backend.model.WritebackJournalEntity;
import com.multiship.backend.repository.WritebackJournalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** D1b — sweeper reserves + redispatches due rows; skips already-claimed. */
class WritebackJournalSweeperTest {

    private WritebackJournalRepository repo;
    private WritebackJournalService journal;
    private ExternalSystemWritebackDispatcher dispatcher;
    private WritebackJournalSweeper sweeper;

    @BeforeEach
    void setUp() {
        repo = mock(WritebackJournalRepository.class);
        journal = mock(WritebackJournalService.class);
        dispatcher = mock(ExternalSystemWritebackDispatcher.class);
        sweeper = new WritebackJournalSweeper(repo, journal, dispatcher);
    }

    @Test
    void sweepReservesAndRedispatchesEachDueRow() throws Exception {
        WritebackJournalEntity r1 = WritebackJournalEntity.builder().id(1L).mode("GENERATE").build();
        WritebackJournalEntity r2 = WritebackJournalEntity.builder().id(2L).mode("CLEAR").build();
        when(repo.findDueForRetry(any(), any(Pageable.class))).thenReturn(List.of(r1, r2));
        when(journal.reserveForRetry(1L)).thenReturn(true);
        when(journal.reserveForRetry(2L)).thenReturn(true);

        sweeper.sweep();

        verify(dispatcher).redispatch(r1);
        verify(dispatcher).redispatch(r2);
    }

    @Test
    void sweepSkipsRowsAlreadyClaimedByAnotherTick() throws Exception {
        WritebackJournalEntity r1 = WritebackJournalEntity.builder().id(1L).mode("GENERATE").build();
        when(repo.findDueForRetry(any(), any(Pageable.class))).thenReturn(List.of(r1));
        when(journal.reserveForRetry(1L)).thenReturn(false);  // another tick won the race

        sweeper.sweep();

        verify(dispatcher, never()).redispatch(any());
    }

    @Test
    void sweepIsNoOpWhenNothingDue() throws Exception {
        when(repo.findDueForRetry(any(), any(Pageable.class))).thenReturn(List.of());
        sweeper.sweep();
        verify(journal, never()).reserveForRetry(any());
        verify(dispatcher, never()).redispatch(any());
    }

    @Test
    void sweepSwallowsRedispatchFailuresPerRow() throws Exception {
        WritebackJournalEntity r1 = WritebackJournalEntity.builder().id(1L).mode("GENERATE").build();
        WritebackJournalEntity r2 = WritebackJournalEntity.builder().id(2L).mode("GENERATE").build();
        when(repo.findDueForRetry(any(), any(Pageable.class))).thenReturn(List.of(r1, r2));
        when(journal.reserveForRetry(any())).thenReturn(true);
        org.mockito.Mockito.doThrow(new RuntimeException("boom")).when(dispatcher).redispatch(r1);

        sweeper.sweep();  // must not throw

        verify(dispatcher).redispatch(r2);  // row 2 still dispatched
    }
}
