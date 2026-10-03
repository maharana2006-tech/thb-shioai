package com.multiship.backend.service.externalsystems.writeback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.model.WritebackJournalEntity;
import com.multiship.backend.repository.WritebackJournalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** D1 — insert PENDING, update on ack, update on failure. */
class WritebackJournalServiceTest {

    private WritebackJournalRepository repo;
    private WritebackJournalService service;

    @BeforeEach
    void setUp() {
        repo = mock(WritebackJournalRepository.class);
        // Save echoes back the entity so we can capture the id-less shape.
        when(repo.save(any(WritebackJournalEntity.class))).thenAnswer(inv -> {
            WritebackJournalEntity e = inv.getArgument(0);
            if (e.getId() == null) e.setId(99L);
            return e;
        });
        service = new WritebackJournalService(repo, new ObjectMapper());
    }

    @Test
    void recordPendingGeneratesRowAndSerializesPayload() {
        WritebackPayload p = WritebackPayload.builder()
                .clientCode("ACME")
                .orderNo(1234)
                .trackingNumber("1Z999")
                .source("MANUAL")
                .channel("D2C")
                .build();
        WritebackJournalEntity row = service.recordPending("nds-default", "NDS_ORACLE",
                WritebackJournalService.MODE_GENERATE, p);
        assertNotNull(row.getId());
        ArgumentCaptor<WritebackJournalEntity> saved = ArgumentCaptor.forClass(WritebackJournalEntity.class);
        verify(repo).save(saved.capture());
        WritebackJournalEntity persisted = saved.getValue();
        assertEquals("PENDING", persisted.getStatus());
        assertEquals("nds-default", persisted.getConnectionName());
        assertEquals("NDS_ORACLE", persisted.getSystemType());
        assertEquals("GENERATE", persisted.getMode());
        assertEquals("ACME", persisted.getClientCode());
        assertEquals(Integer.valueOf(1234), persisted.getOrderNo());
        assertEquals("1Z999", persisted.getTrackingNumber());
        assertTrue(persisted.getPayloadJson().contains("1Z999"));
        assertEquals(Integer.valueOf(1), persisted.getAttemptNumber());
    }

    @Test
    void recordAckMapsWritebackAckStatusToJournalStatus() {
        WritebackJournalEntity existing = WritebackJournalEntity.builder()
                .id(1L).status("PENDING").build();
        when(repo.findById(1L)).thenReturn(Optional.of(existing));

        service.recordAck(1L, WritebackAck.ok("wrote 3 rows"), 42);

        assertEquals("OK", existing.getStatus());
        assertEquals("OK", existing.getAckStatus());
        assertEquals("wrote 3 rows", existing.getAckDetail());
        assertEquals(Integer.valueOf(42), existing.getLatencyMs());
    }

    @Test
    void recordAckSkippedMapsToSkipped() {
        WritebackJournalEntity existing = WritebackJournalEntity.builder().id(1L).build();
        when(repo.findById(1L)).thenReturn(Optional.of(existing));
        service.recordAck(1L, WritebackAck.skipped("no flags enabled"), 5);
        assertEquals("SKIPPED", existing.getStatus());
    }

    @Test
    void recordFailureMarksFailedWithMessage() {
        WritebackJournalEntity existing = WritebackJournalEntity.builder().id(1L).build();
        when(repo.findById(1L)).thenReturn(Optional.of(existing));

        service.recordFailure(1L, "SQL error: table CLIPPER not found", 1234);

        assertEquals("FAILED", existing.getStatus());
        assertEquals("SQL error: table CLIPPER not found", existing.getErrorMessage());
        assertEquals(Integer.valueOf(1234), existing.getLatencyMs());
    }

    @Test
    void recordFailureIsNoOpWhenJournalIdIsNull() {
        service.recordFailure(null, "won't matter", 0);
        verify(repo, org.mockito.Mockito.never()).findById(any());
    }

    @Test
    void recordFailureSchedulesNextRetryWhenUnderAttemptCap() {
        WritebackJournalEntity existing = WritebackJournalEntity.builder()
                .id(1L).attemptNumber(1).build();
        when(repo.findById(1L)).thenReturn(Optional.of(existing));
        service.recordFailure(1L, "transient oracle hiccup", 500);
        assertEquals("FAILED", existing.getStatus());
        assertNotNull(existing.getNextRetryAt(), "nextRetryAt must be set for sweeper pickup");
        assertTrue(existing.getNextRetryAt().isAfter(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusSeconds(1)),
                "nextRetryAt should be in the future");
    }

    @Test
    void recordFailureLeavesNextRetryNullAtAttemptCap() {
        WritebackJournalEntity existing = WritebackJournalEntity.builder()
                .id(1L).attemptNumber(WritebackJournalService.MAX_ATTEMPTS).build();
        when(repo.findById(1L)).thenReturn(Optional.of(existing));
        service.recordFailure(1L, "giving up", 1);
        assertEquals("FAILED", existing.getStatus());
        assertEquals(null, existing.getNextRetryAt(),
                "terminal attempt must stay next_retry_at=null so sweeper ignores it");
    }

    @Test
    void recordPendingChainsAttemptNumberWhenRetryOfIdSupplied() {
        WritebackJournalEntity previous = WritebackJournalEntity.builder()
                .id(42L).attemptNumber(2).build();
        when(repo.findById(42L)).thenReturn(Optional.of(previous));
        WritebackPayload p = WritebackPayload.builder().orderNo(7).build();
        WritebackJournalEntity row = service.recordPending(
                "nds-default", "NDS_ORACLE",
                WritebackJournalService.MODE_GENERATE, p, 42L);
        assertEquals(Integer.valueOf(3), row.getAttemptNumber());
        assertEquals(Long.valueOf(42L), row.getRetryOfId());
    }

    @Test
    void reserveForRetryReturnsTrueWhenUpdateHitsOneRow() {
        when(repo.clearNextRetryAt(5L)).thenReturn(1);
        assertTrue(service.reserveForRetry(5L));
    }

    @Test
    void reserveForRetryReturnsFalseWhenAlreadyClaimed() {
        when(repo.clearNextRetryAt(5L)).thenReturn(0);
        assertEquals(false, service.reserveForRetry(5L));
    }
}
