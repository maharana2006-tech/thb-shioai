package com.multiship.backend.service.printing;

import com.multiship.backend.model.DocumentPrintEvent;
import com.multiship.backend.repository.DocumentPrintEventRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentPrintLogTest {

    @Test
    @SuppressWarnings("unchecked")
    void oneRowPerOrderPerPrint() {
        DocumentPrintEventRepository repo = mock(DocumentPrintEventRepository.class);
        new DocumentPrintLog(repo).record(List.of(5001, 5002, 5001), "LABEL", DocumentPrintLog.PRINTER, "SAMSUNG", "malaya");
        ArgumentCaptor<List<DocumentPrintEvent>> saved = ArgumentCaptor.forClass(List.class);
        verify(repo).saveAll(saved.capture());
        assertEquals(2, saved.getValue().size(), "the same order twice in one print is one print");
        DocumentPrintEvent e = saved.getValue().get(0);
        assertEquals("LABEL", e.getDocType());
        assertEquals("PRINTER", e.getChannel());
        assertEquals("SAMSUNG", e.getPrinterName());
        assertEquals("malaya", e.getPrintedBy());
    }

    @Test
    void nothingPrintedNothingRecorded_andAFailedWriteNeverBreaksThePrint() {
        DocumentPrintEventRepository repo = mock(DocumentPrintEventRepository.class);
        DocumentPrintLog log = new DocumentPrintLog(repo);
        log.record(List.of(), "LABEL", DocumentPrintLog.BROWSER, null, "u");
        verify(repo, never()).saveAll(anyList());
        when(repo.saveAll(any())).thenThrow(new IllegalStateException("db down"));
        assertDoesNotThrow(() -> log.record(List.of(1), "LABEL", DocumentPrintLog.BROWSER, null, "u"));
    }

    @Test
    void lastPrintedTimesComeBackPerOrderAndPerLabelBatch() {
        DocumentPrintEventRepository repo = mock(DocumentPrintEventRepository.class);
        LocalDateTime at = LocalDateTime.of(2026, 9, 22, 11, 30);
        when(repo.lastPrintedByOrder(any())).thenReturn(List.<Object[]>of(new Object[] { 5001, at }));
        when(repo.lastPrintedByLabelBatch(any())).thenReturn(List.<Object[]>of(new Object[] { 912L, java.sql.Timestamp.valueOf(at) }));
        DocumentPrintLog log = new DocumentPrintLog(repo);
        assertEquals(at, log.lastPrintedByOrder(List.of(5001, 5002)).get(5001));
        assertEquals(at, log.lastPrintedByLabelBatch(List.of(912)).get(912));
    }
}
